package com.vigyan.scanner

import com.vigyan.scanner.DewarpMath.Pt
import com.vigyan.scanner.DewarpMath.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DewarpMathTest {
    private val black = 0xFF000000.toInt()
    private val white = -1

    @Test
    fun edgePassesThroughCornersAndMiddle() {
        val a = Pt(0f, 100f); val b = Pt(200f, 100f); val m = Pt(100f, 130f)
        assertEquals(a, DewarpMath.edge(a, m, b, 0f))
        assertEquals(b, DewarpMath.edge(a, m, b, 1f))
        val half = DewarpMath.edge(a, m, b, 0.5f)
        assertEquals(100f, half.x, 0.01f); assertEquals(130f, half.y, 0.01f)
    }

    @Test
    fun flatRegionIsAPlainCrop() {
        // A 10 × 10 picture with a black pixel at (6, 3); region = the box (2,1)–(8,7).
        val src = IntArray(100) { white }.also { it[3 * 10 + 6] = black }
        val r = Region(Pt(2f, 1f), Pt(8f, 1f), Pt(8f, 7f), Pt(2f, 7f))
        val out = DewarpMath.remap(src, 10, 10, r, 7, 7)
        assertEquals(black, out[2 * 7 + 4])
        assertEquals(1, out.count { it == black })
    }

    /** A page with straight text lines, bent like a book page; flattening must make them straight again. */
    @Test
    fun bentLinesComeOutStraight() {
        val w = 400; val h = 500
        val flat = Region(Pt(0f, 0f), Pt(w - 1f, 0f), Pt(w - 1f, h - 1f), Pt(0f, h - 1f))
        val bent = DewarpMath.withBows(flat, 40f, 25f)
        // Draw: each straight line at v = 0.2, 0.4, 0.6, 0.8 of the flat page, placed on the bent page.
        val src = IntArray(w * h) { white }
        for (v in listOf(0.2f, 0.4f, 0.6f, 0.8f)) for (i in 0..2000) {
            val p = DewarpMath.sourceAt(bent, i / 2000f, v)
            val x = p.x.toInt(); val y = p.y.toInt()
            for (dy in -1..1) if (y + dy in 0 until h && x in 0 until w) src[(y + dy) * w + x] = black
        }
        val out = DewarpMath.remap(src, w, h, bent, w, h)
        // In every column, the dark rows sit at the same height (within 3 px) as in the first column.
        fun darkRows(x: Int) = (0 until h).filter { y -> (out[y * w + x] and 0xFF) < 128 }
        val first = darkRows(20).groupRuns()
        for (x in listOf(100, 200, 300, 380)) {
            val rows = darkRows(x).groupRuns()
            assertEquals(first.size, rows.size)
            rows.zip(first).forEach { (a, b) -> assertTrue("column $x: $a vs $b", abs(a - b) <= 3f) }
        }
    }

    private fun List<Int>.groupRuns(): List<Float> {
        val runs = ArrayList<MutableList<Int>>()
        for (y in this) if (runs.isNotEmpty() && y - runs.last().last() <= 2) runs.last() += y else runs += mutableListOf(y)
        return runs.map { it.average().toFloat() }
    }

    @Test
    fun bowsFoundFromWordCentres() {
        val w = 1000f; val h = 1400f
        val flat = Region(Pt(0f, 0f), Pt(w, 0f), Pt(w, h), Pt(0f, h))
        val bent = DewarpMath.withBows(flat, 60f, 30f)
        // 10 lines of 8 words each, as the OCR would see them on the bent page.
        val lines = (1..10).map { li ->
            val v = li / 11f
            (0 until 8).map { k -> DewarpMath.sourceAt(bent, 0.08f + k * 0.12f, v) }
        }
        val bows = DewarpMath.bowsFromText(flat, lines)
        assertNotNull(bows)
        assertEquals(60f, bows!!.first, 8f)
        assertEquals(30f, bows.second, 8f)
    }

    @Test
    fun tooFewLinesGiveNoGuess() {
        val flat = Region(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertNull(DewarpMath.bowsFromText(flat, listOf(listOf(Pt(10f, 10f), Pt(50f, 12f), Pt(90f, 10f)))))
    }

    @Test
    fun quadFitIsExact() {
        val pts = (0..5).map { x -> Pt(x * 100f + 300f, 0.001f * (x * 100f + 300f) * (x * 100f + 300f) - 2f * (x * 100f + 300f) + 7f) }
        val (a, b, c) = DewarpMath.quadFit(pts)!!
        assertEquals(0.001f, a, 1e-5f); assertEquals(-2f, b, 1e-2f); assertEquals(7f, c, 2f)
    }

    @Test
    fun spineIsTheDarkColumn() {
        val w = 200; val h = 100
        val lum = IntArray(w * h) { i -> val x = i % w; if (x in 118..122) 60 else 220 }
        assertEquals(120f / 199f, DewarpMath.spineColumn(lum, w, h), 0.02f)
        assertEquals(0.5f, DewarpMath.spineColumn(IntArray(w * h) { 200 }, w, h), 0.001f)
    }

    @Test
    fun outputSizeFollowsTheCurve() {
        val r = Region(Pt(0f, 0f), Pt(1000f, 0f), Pt(1000f, 1400f), Pt(0f, 1400f))
        val (w, h) = DewarpMath.outputSize(r)
        assertEquals(1000, w); assertEquals(1400, h)
        val (bw, _) = DewarpMath.outputSize(DewarpMath.withBows(r, 80f, 80f))
        assertTrue(bw > 1000)
    }
}
