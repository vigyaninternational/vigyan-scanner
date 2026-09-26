package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.ZipInputStream

class FilterMathTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun lumOf(p: Int) = p and 0xFF

    /** A page lit from one side (paper 250 on the left, 130 on the right) with a line of ink. */
    private fun shadowedPage(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val paper = 250 - 120 * x / (w - 1)
        if (y in 48..51 && x in 10 until w - 10) gray(paper / 5) else gray(paper)
    }

    private fun shrink(px: IntArray, w: Int, h: Int, sw: Int, sh: Int) = IntArray(sw * sh) { i ->
        px[(i / sw) * h / sh * w + (i % sw) * w / sw]
    }

    @Test
    fun removesShadowsAndKeepsInk() {
        val w = 200
        val h = 100
        val px = shadowedPage(w, h)
        val bg = FilterMath.background(shrink(px, w, h, 40, 20), 40, 20)
        FilterMath.flatten(px, w, h, 0, h, bg, bw = false)
        // Paper on both the bright and the shadowed side comes out (nearly) white.
        assertTrue(lumOf(px[20 * w + 5]) > 235)
        assertTrue(lumOf(px[20 * w + w - 5]) > 235)
        // Ink stays dark.
        assertTrue(lumOf(px[50 * w + w / 2]) < 90)
    }

    @Test
    fun blackAndWhiteIsOnlyBlackOrWhite() {
        val w = 200
        val h = 100
        val px = shadowedPage(w, h)
        val bg = FilterMath.background(shrink(px, w, h, 40, 20), 40, 20)
        FilterMath.flatten(px, w, h, 0, h, bg, bw = true)
        assertTrue(px.all { lumOf(it) == 0 || lumOf(it) == 255 })
        assertEquals(255, lumOf(px[10 * w + w - 5])) // shadowed paper → white
        assertEquals(0, lumOf(px[50 * w + 100])) // ink → black
    }
}

class ScanQualityTest {
    /** Black blocks (like words) on light paper. */
    private fun page(w: Int, h: Int, paper: Int = 220, ink: Int = 20) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if ((x / 20) % 2 == 0 && (y / 12) % 3 == 0 && x in 10 until w - 10) ink else paper
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int) = IntArray(a.size) { i ->
        val x = i % w
        val y = i / w
        var s = 0
        var n = 0
        for (dy in -r..r) for (dx in -r..r) {
            val xx = (x + dx).coerceIn(0, w - 1)
            val yy = (y + dy).coerceIn(0, h - 1)
            s += a[yy * w + xx]
            n++
        }
        s / n
    }

    @Test
    fun sharpPageIsFine() {
        val r = ScanQuality.check(page(200, 200), 200, 200)
        assertTrue(r.issues.toString(), r.issues.isEmpty())
    }

    @Test
    fun blurredPageIsBlurryAndLessSharp() {
        val sharp = page(200, 200)
        var blurred = sharp
        repeat(3) { blurred = boxBlur(blurred, 200, 200, 3) }
        val a = ScanQuality.check(sharp, 200, 200)
        val b = ScanQuality.check(blurred, 200, 200)
        assertTrue(b.sharpness < a.sharpness / 5)
        assertTrue(b.issues.toString(), ScanQuality.Issue.BLURRY in b.issues)
    }

    @Test
    fun darkAndBlankPages() {
        val dark = ScanQuality.check(page(200, 200, paper = 45, ink = 5), 200, 200)
        assertTrue(ScanQuality.Issue.DARK in dark.issues)
        // Plain paper with a gentle light gradient: blank, which is not a warning.
        val blank = ScanQuality.check(IntArray(200 * 200) { 200 + (it % 200) / 20 }, 200, 200)
        assertTrue(ScanQuality.Issue.BLANK in blank.issues)
        assertTrue(blank.warnings.isEmpty())
    }
}

class XlsxTest {
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun writesAWorkbook() {
        val buf = ByteArrayOutputStream()
        Xlsx.write(listOf(Xlsx.Sheet("Forms", listOf(listOf("Name", "Marks", "Mobile"), listOf("Ravi & Co", "87", "0987654321")))), buf)
        val files = unzip(buf.toByteArray())
        assertTrue("[Content_Types].xml" in files)
        assertTrue("xl/workbook.xml" in files)
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("Ravi &amp; Co"))
        assertTrue(sheet.contains("""<c r="B2"><v>87</v></c>""")) // a number
        assertTrue(sheet.contains("0987654321</t>")) // a phone number stays text
    }

    @Test
    fun columnsAndNumbers() {
        assertEquals("A", Xlsx.column(0))
        assertEquals("Z", Xlsx.column(25))
        assertEquals("AA", Xlsx.column(26))
        assertEquals("BA", Xlsx.column(52))
        assertTrue(Xlsx.isNumber("87"))
        assertTrue(Xlsx.isNumber("-3.5"))
        assertFalse(Xlsx.isNumber("0987654321"))
        assertFalse(Xlsx.isNumber("1234567890123"))
        assertFalse(Xlsx.isNumber("12A"))
    }

    @Test
    fun splitsRowsIntoCells() {
        val cells = Xlsx.cells("Name   Ravi Kumar   Class   XI\n\nRoll\t12")
        assertEquals(listOf(listOf("Name", "Ravi Kumar", "Class", "XI"), listOf("Roll", "12")), cells)
    }
}

class NamingTest {
    private val date = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 5, 10, 0) }.timeInMillis

    @Test
    fun templates() {
        assertEquals("Ravi", Naming.apply("{name}", "Ravi", created = date))
        assertEquals("Ravi_05-03-2026", Naming.apply("{name}_{date}", "Ravi", created = date))
        // An empty part goes with its separator.
        assertEquals("Ravi", Naming.apply("{ref}_{name}", "Ravi", ref = "", created = date))
        assertEquals("R12_Ravi", Naming.apply("{ref}_{name}", "Ravi", ref = "R12", created = date))
        assertEquals("Admissions_Ravi", Naming.apply("{folder}_{name}", "Ravi", folder = "Admissions", created = date))
        // The name itself keeps its own dashes and spaces.
        assertEquals("Aadhaar card - Ravi", Naming.apply("{folder}_{name}", "Aadhaar card - Ravi", created = date))
    }

    @Test
    fun personFileNames() {
        assertEquals("RAHUL_KUMAR", Naming.personFile("Rahul kumar"))
        assertEquals("RAHUL_KUMAR", Naming.personFile("  Rahul  Kumar. "))
        assertEquals("RAHUL_KUMAR_A-12", Naming.personFile("Rahul Kumar", "A-12"))
    }

    @Test
    fun noDuplicates() {
        assertEquals("Ravi", Naming.unique("Ravi", listOf("Sita")))
        assertEquals("Ravi (2)", Naming.unique("Ravi", listOf("ravi")))
        assertEquals("Ravi (3)", Naming.unique("Ravi", listOf("Ravi", "Ravi (2)")))
    }

    @Test
    fun dateRanges() {
        val day = 86_400_000L
        val now = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 15, 12, 0) }.timeInMillis
        assertTrue(DateRange.TODAY.matches(now - 3600_000L, now))
        assertFalse(DateRange.TODAY.matches(now - 2 * day, now))
        assertTrue(DateRange.WEEK.matches(now - 6 * day, now))
        assertFalse(DateRange.WEEK.matches(now - 8 * day, now))
        assertTrue(DateRange.YEAR.matches(now - 100 * day, now))
        assertTrue(DateRange.OLDER.matches(now - 200 * day, now))
        assertFalse(DateRange.OLDER.matches(now - 10 * day, now))
    }
}

class BulkGroupsTest {
    @Test
    fun splitsIntoDocuments() {
        assertEquals(listOf(0 until 5), QuickScanLogic.groups(5, emptyList()))
        assertEquals(listOf(0 until 2, 2 until 5), QuickScanLogic.groups(5, listOf(2)))
        // Breaks at the start, the end, repeated or out of order are ignored.
        assertEquals(listOf(0 until 1, 1 until 3, 3 until 4), QuickScanLogic.groups(4, listOf(3, 0, 1, 4, 3)))
        assertEquals(emptyList<IntRange>(), QuickScanLogic.groups(0, listOf(1)))
    }
}

class PdfFooterTest {
    @Test
    fun pageNumbersAreVisibleText() {
        val jpeg = java.util.Base64.getDecoder().decode(PdfWriterTest.JPEG_BASE64)
        val image = PdfWriter.Image(jpeg, 40, 56)
        val pages = (1..2).map { n -> PdfWriter.Page(595f, 842f, listOf(PdfWriter.Placement(image, 0f, 0f, 595f, 842f)), "Page $n of 2") }
        val buf = ByteArrayOutputStream()
        PdfWriter().write(pages, buf)
        val doc = org.apache.pdfbox.pdmodel.PDDocument.load(buf.toByteArray())
        doc.use {
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(it)
            assertTrue(text, text.contains("Page 1 of 2"))
            assertTrue(text, text.contains("Page 2 of 2"))
        }
    }
}

class PhotoSheetTest {
    @Test
    fun thirtyPassportPhotosOnA4() {
        val l = PhotoSheet.layout(PhotoSheet.A4_W, PhotoSheet.A4_H, 35f, 45f, 5f, 2f)
        assertEquals(5, l.cols)
        assertEquals(6, l.rows)
        assertEquals(30, l.count)
        // Everything stays inside the margins and nothing overlaps.
        assertTrue(l.positions.all { (x, y) -> x >= 5f && y >= 5f && x + 35f <= 205f && y + 45f <= 292f })
        assertTrue(l.positions.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun eightOnSixByFourAndLimits() {
        assertEquals(8, PhotoSheet.layout(PhotoSheet.SIX_W, PhotoSheet.FOUR_H, 35f, 45f, 3f, 2f).count)
        assertEquals(6, PhotoSheet.layout(PhotoSheet.A4_W, PhotoSheet.A4_H, 35f, 45f, 5f, 2f, maxCols = 3, maxRows = 2).count)
        // 2x2 inch photos: fewer fit.
        assertEquals(15, PhotoSheet.layout(PhotoSheet.A4_W, PhotoSheet.A4_H, 51f, 51f, 5f, 2f).count)
        assertEquals(0, PhotoSheet.layout(PhotoSheet.A4_W, PhotoSheet.A4_H, 35f, 45f, 120f, 2f).count)
    }

    @Test
    fun squareCropAndShift() {
        val c = PassportMath.crop(400, 300, 600, 550, 1000, 1400, aspect = 1f, headShare = 0.6f)
        assertEquals(c[2], c[3]) // square
        val moved = PassportMath.crop(400, 300, 600, 550, 1000, 1400, shiftX = 0.1f)
        val plain = PassportMath.crop(400, 300, 600, 550, 1000, 1400)
        assertTrue(moved[0] > plain[0])
        assertEquals(413, PassportMath.mmToPx(35f))
    }
}

class FormFillTest {
    @Test
    fun findsBlankFields() {
        assertEquals("name", FormExtractor.blankLabel("Name : ____________"))
        assertEquals("father", FormExtractor.blankLabel("Father's Name ........"))
        assertEquals("dob", FormExtractor.blankLabel("Date of Birth:"))
        assertEquals("mobile1", FormExtractor.blankLabel("Mobile No. ________"))
        assertEquals(null, FormExtractor.blankLabel("Name : Ravi Kumar"))
        assertEquals(null, FormExtractor.blankLabel("Instructions for filling the form"))
        val (key, end) = FormExtractor.blankLabelEnd("Name : ______")!!
        assertEquals("name", key)
        assertEquals(7, end) // after "Name : "
    }
}

class PortalPresetTest {
    @Test
    fun savesAndLoadsPresets() {
        val list = listOf(
            PortalSpec("OJEE photo", pdf = false, width = 200, height = 230, minKb = 10, maxKb = 50),
            PortalSpec("Marksheet PDF", pdf = true, maxKb = 300, gray = true),
            PortalSpec("Sign PNG", pdf = false, width = 140, height = 60, maxKb = 20, png = true),
        )
        assertEquals(list, PortalSpec.fromJson(PortalSpec.toJson(list)))
        assertEquals(emptyList<PortalSpec>(), PortalSpec.fromJson("not json"))
    }
}

class CutoutDarknessTest {
    @Test
    fun darkerInkIsMoreOpaqueAndDarker() {
        val w = 40
        val h = 20
        // Paper 230, a grey stroke of 150 across the middle.
        val px = IntArray(w * h) { i -> val v = if ((i / w) in 8..11) 150 else 230; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val plain = Cutout.cut(Cutout.Image(px, w, h), 0.5f)!!
        val dark = Cutout.cut(Cutout.Image(px, w, h), 0.5f, darkness = 1f)!!
        val i = plain.pixels.indices.maxBy { plain.pixels[it] ushr 24 }
        assertTrue((dark.pixels[i] ushr 24) >= (plain.pixels[i] ushr 24))
        assertTrue((dark.pixels[i] and 0xFF) < (plain.pixels[i] and 0xFF))
    }
}
