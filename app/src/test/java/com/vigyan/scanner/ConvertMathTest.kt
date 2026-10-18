package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ConvertMathTest {

    @Test
    fun parsesPageLists() {
        assertEquals(listOf(0, 1, 2, 4), ConvertMath.parsePages("1-3, 5", 10))
        assertEquals(listOf(0, 1, 2, 4), ConvertMath.parsePages(" 1-3 ,5 ", 10))
        assertEquals(listOf(4, 3, 2), ConvertMath.parsePages("5-3", 10))
        assertEquals(listOf(7, 8, 9), ConvertMath.parsePages("8-", 10))
        assertEquals(listOf(1, 0), ConvertMath.parsePages("2,1,2", 10))
        assertEquals(listOf(0, 1, 2), ConvertMath.parsePages("all", 3))
        assertEquals(listOf(0, 1), ConvertMath.parsePages("1–2", 3)) // en dash typed by some keyboards
        assertEquals(listOf(2, 5), ConvertMath.parsePages("3 6", 6))
        // Spaces around the dash are one range, not "1", "-", "3".
        assertEquals(listOf(0, 1, 2), ConvertMath.parsePages("1 - 3", 10))
        assertEquals(listOf(1, 2, 3, 5), ConvertMath.parsePages("2 -4, 6", 10))
        assertEquals(listOf(1, 2, 3, 6), ConvertMath.parsePages("2- 4 7", 10))
        assertEquals(listOf(0, 1), ConvertMath.parsePages("1 – 2", 3))
        // "-3" = from the first page.
        assertEquals(listOf(0, 1, 2), ConvertMath.parsePages("-3", 10))
    }

    @Test
    fun rejectsBadPageLists() {
        assertNull(ConvertMath.parsePages("", 5))
        assertNull(ConvertMath.parsePages("0", 5))
        assertNull(ConvertMath.parsePages("6", 5))
        assertNull(ConvertMath.parsePages("2-9", 5))
        assertNull(ConvertMath.parsePages("abc", 5))
        assertNull(ConvertMath.parsePages("1-x", 5))
        assertNull(ConvertMath.parsePages("1", 0))
    }

    @Test
    fun formatsPageLists() {
        assertEquals("1-3, 5", ConvertMath.formatPages(listOf(0, 1, 2, 4)))
        assertEquals("1, 2", ConvertMath.formatPages(listOf(1, 0)))
        assertEquals("4", ConvertMath.formatPages(listOf(3)))
        assertEquals("", ConvertMath.formatPages(emptyList()))
        // What is shown can be typed back.
        val pages = listOf(0, 2, 3, 4, 7, 8)
        assertEquals(pages, ConvertMath.parsePages(ConvertMath.formatPages(pages), 10))
    }

    @Test
    fun sharesTheKbBudgetBetweenPages() {
        val max = 200 * 1024
        val first = ConvertMath.pageBudget(max, 0, 4, 4)
        val overhead = ConvertMath.PDF_OVERHEAD + 4 * ConvertMath.PDF_PAGE_OVERHEAD
        assertEquals((max - overhead) / 4, first)
        // A page that came out small leaves more for the rest.
        val second = ConvertMath.pageBudget(max, first / 2, 3, 4)
        assertTrue(second > first)
        // Never asks for less than a tiny readable page.
        assertEquals(ConvertMath.MIN_PAGE_BYTES, ConvertMath.pageBudget(10 * 1024, 0, 50, 50))
        assertEquals(0, ConvertMath.pageBudget(max, 0, 0, 4))
    }

    @Test
    fun budgetedPagesFitInARealPdf() {
        // Pages made exactly to the budget, written by PdfWriter, stay under the limit.
        val max = 60 * 1024
        val n = 5
        var used = 0
        val pages = (0 until n).map { i ->
            val budget = ConvertMath.pageBudget(max, used, n - i, n)
            val jpeg = ByteArray(budget) { 0x55 }
            used += jpeg.size
            val plan = ConvertMath.placePage(1000, 1400, a4 = true)
            PdfWriter.Page(plan.pageW, plan.pageH, listOf(PdfWriter.Placement(PdfWriter.Image(jpeg, 1000, 1400), plan.x, plan.y, plan.w, plan.h)))
        }
        val out = ByteArrayOutputStream()
        PdfWriter().write(pages, out)
        assertTrue("PDF is ${out.size()} bytes", out.size() <= max)
    }

    @Test
    fun placesPhotosOnPages() {
        // Same shape: a portrait photo fills an A4-sized page of its own shape.
        val p = ConvertMath.placePage(1000, 2000, a4 = false)
        assertEquals(421f, p.pageW, 0.5f)
        assertEquals(842f, p.pageH, 0.5f)
        assertEquals(0f, p.x, 0.01f)
        assertEquals(p.pageW, p.w, 0.01f)
        // A4: white edges, centred.
        val a = ConvertMath.placePage(1000, 1000, a4 = true)
        assertEquals(ConvertMath.A4_W, a.pageW, 0.01f)
        assertEquals(ConvertMath.A4_H, a.pageH, 0.01f)
        assertEquals(ConvertMath.A4_W - 2 * ConvertMath.A4_MARGIN, a.w, 0.01f)
        assertEquals((a.pageH - a.h) / 2, a.y, 0.01f)
        // A wide photo gets a sideways A4 page.
        val w = ConvertMath.placePage(3000, 2000, a4 = true)
        assertEquals(ConvertMath.A4_H, w.pageW, 0.01f)
        assertEquals(ConvertMath.A4_W, w.pageH, 0.01f)
        assertTrue(w.x >= ConvertMath.A4_MARGIN - 0.01f && w.y >= ConvertMath.A4_MARGIN - 0.01f)
    }

    @Test
    fun rendersPagesAtTheChosenDpiWithinTheCap() {
        // A4 at 150 dpi: 1240 × 1754.
        assertEquals(1240 to 1754, ConvertMath.renderSize(595, 842, 150))
        // 200 dpi would be 2339 px tall: capped at 2000.
        val (w, h) = ConvertMath.renderSize(595, 842, 200)
        assertEquals(2000, h)
        assertEquals(1413, w)
        // 300 dpi is allowed up to 3600.
        assertEquals(2479 to 3508, ConvertMath.renderSize(595, 842, 300))
        assertEquals(3600, ConvertMath.renderSize(1190, 1684, 300).second)
    }

    @Test
    fun turnsAndMoves() {
        assertEquals(1, ConvertMath.turn(0, 1))
        assertEquals(3, ConvertMath.turn(0, -1))
        assertEquals(0, ConvertMath.turn(3, 1))
        assertEquals(200f to 100f, ConvertMath.turned(100f, 200f, 1))
        assertEquals(100f to 200f, ConvertMath.turned(100f, 200f, 2))
        assertEquals(200f to 100f, ConvertMath.turned(100f, 200f, -1))
        assertEquals(listOf("b", "a", "c"), ConvertMath.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "c", "b"), ConvertMath.move(listOf("a", "b", "c"), 2, -1))
        assertEquals(listOf("a", "b", "c"), ConvertMath.move(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("a", "b", "c"), ConvertMath.move(listOf("a", "b", "c"), 2, 1))
    }

    @Test
    fun namesFiles() {
        assertEquals("Marksheet 10th", ConvertMath.baseName("Marksheet 10th.pdf"))
        assertEquals("IMG_2026", ConvertMath.baseName("IMG_2026.jpeg"))
        assertEquals("a_b", ConvertMath.baseName("a/b"))
        assertEquals("file", ConvertMath.baseName("  "))
        assertEquals("v1.2 notes", ConvertMath.baseName("v1.2 notes")) // a dot in the name is not an extension
        // A typed name with a number after the dot keeps it.
        assertEquals("Fees.2026", ConvertMath.baseName("Fees.2026"))
        assertEquals("Report v1.2", ConvertMath.baseName("Report v1.2"))
        assertEquals("scan", ConvertMath.baseName("scan.mp4"))
        assertEquals("photo", ConvertMath.baseName("photo.HEIC"))
        assertEquals("doc.jpg", ConvertMath.pageFileName("doc", 0, 1, "jpg"))
        assertEquals("doc_page3.png", ConvertMath.pageFileName("doc", 2, 5, "png"))
        assertEquals("a.jpg", ConvertMath.uniqueFileName("a.jpg", setOf("b.jpg")))
        assertEquals("a (2).jpg", ConvertMath.uniqueFileName("a.jpg", setOf("a.jpg")))
        assertEquals("a (3).jpg", ConvertMath.uniqueFileName("a.jpg", setOf("a.jpg", "a (2).jpg")))
    }

    @Test
    fun aSmallPhotoIsNeverMadeBigger() {
        val max = 200 * 1024
        // 30 KB photo, "under 200 KB": it may not come out bigger than 30 KB.
        assertEquals(30 * 1024, ConvertMath.shrinkTarget(30L * 1024, max, 0))
        // Bigger than the limit: the limit.
        assertEquals(max, ConvertMath.shrinkTarget(900L * 1024, max, 0))
        // Under a portal's minimum: it has to grow, so the limit is used.
        assertEquals(max, ConvertMath.shrinkTarget(10L * 1024, max, 20 * 1024))
        // Size not known.
        assertEquals(max, ConvertMath.shrinkTarget(-1, max, 0))
    }

    @Test
    fun showsSizes() {
        assertEquals("1 KB", ConvertMath.sizeText(10))
        assertEquals("200 KB", ConvertMath.sizeText(200 * 1024))
        assertEquals("2.5 MB", ConvertMath.sizeText(2_621_440))
        assertEquals(200 * 1024, ConvertMath.kbToBytes("200"))
        assertNull(ConvertMath.kbToBytes(""))
        assertNull(ConvertMath.kbToBytes("2"))
    }
}
