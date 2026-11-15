package com.vigyan.scanner

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Curved page correction (a book page bending towards the spine). Pure Kotlin, unit-tested.
 *
 * A page is a [Region]: four corners with straight left and right sides, and a curved top and
 * bottom edge. Each curved edge is a quadratic curve through its two corners whose middle point
 * sits [Region.topBow] / [Region.bottomBow] away from the straight line between the corners.
 * Flattening maps the region onto a rectangle: across the page (u) along both curves, and down the
 * page (v) straight from the top curve to the bottom curve.
 */
object DewarpMath {

    data class Pt(val x: Float, val y: Float) {
        operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
        operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
        operator fun times(k: Float) = Pt(x * k, y * k)
    }

    /** Corners TL, TR, BR, BL and how far each curved edge's middle bows out from straight. */
    data class Region(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt, val topBow: Pt = Pt(0f, 0f), val bottomBow: Pt = Pt(0f, 0f)) {
        val topMid get() = mid(tl, tr) + topBow
        val bottomMid get() = mid(bl, br) + bottomBow
        /** The same region with its curved edges' middles moved to these points. */
        fun withTopMid(p: Pt) = copy(topBow = p - mid(tl, tr))
        fun withBottomMid(p: Pt) = copy(bottomBow = p - mid(bl, br))
    }

    fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    /** A point on the quadratic edge from [a] to [b] whose middle (t = 0.5) is [m]. */
    fun edge(a: Pt, m: Pt, b: Pt, t: Float): Pt {
        // Control point so that the curve passes through m at t = 0.5.
        val c = m * 2f - mid(a, b)
        val s = 1f - t
        return Pt(s * s * a.x + 2f * s * t * c.x + t * t * b.x, s * s * a.y + 2f * s * t * c.y + t * t * b.y)
    }

    /** The source point for (u, v) in 0..1 across and down the flattened page. */
    fun sourceAt(r: Region, u: Float, v: Float): Pt {
        val top = edge(r.tl, r.topMid, r.tr, u)
        val bot = edge(r.bl, r.bottomMid, r.br, u)
        return Pt(top.x + (bot.x - top.x) * v, top.y + (bot.y - top.y) * v)
    }

    private fun length(a: Pt, m: Pt, b: Pt): Float {
        var len = 0f
        var prev = a
        for (i in 1..32) {
            val p = edge(a, m, b, i / 32f)
            len += hypot(p.x - prev.x, p.y - prev.y)
            prev = p
        }
        return len
    }

    /** Width (average length of the two curved edges) and height (average of the sides), capped at [maxSide]. */
    fun outputSize(r: Region, maxSide: Int = 3000): Pair<Int, Int> {
        var w = (length(r.tl, r.topMid, r.tr) + length(r.bl, r.bottomMid, r.br)) / 2f
        var h = (hypot(r.bl.x - r.tl.x, r.bl.y - r.tl.y) + hypot(r.br.x - r.tr.x, r.br.y - r.tr.y)) / 2f
        val k = min(1f, maxSide / max(w, h).coerceAtLeast(1f))
        w *= k; h *= k
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /**
     * Flattens [r] out of the picture [src] (ARGB pixels, [sw] × [sh]) into an [outW] × [outH]
     * picture. Each output pixel is read from the source with bilinear smoothing; outside the
     * picture it is white.
     */
    fun remap(src: IntArray, sw: Int, sh: Int, r: Region, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        // The two edges at every output column, worked out once.
        val tx = FloatArray(outW); val ty = FloatArray(outW); val bx = FloatArray(outW); val by = FloatArray(outW)
        val tm = r.topMid; val bm = r.bottomMid
        for (x in 0 until outW) {
            val u = if (outW == 1) 0.5f else x / (outW - 1f)
            val t = edge(r.tl, tm, r.tr, u); val b = edge(r.bl, bm, r.br, u)
            tx[x] = t.x; ty[x] = t.y; bx[x] = b.x; by[x] = b.y
        }
        for (y in 0 until outH) {
            val v = if (outH == 1) 0.5f else y / (outH - 1f)
            val row = y * outW
            for (x in 0 until outW) {
                out[row + x] = sample(src, sw, sh, tx[x] + (bx[x] - tx[x]) * v, ty[x] + (by[x] - ty[x]) * v)
            }
        }
        return out
    }

    private const val WHITE = -0x1

    private fun sample(src: IntArray, sw: Int, sh: Int, fx: Float, fy: Float): Int {
        if (fx < 0f || fy < 0f || fx > sw - 1f || fy > sh - 1f) return WHITE
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
        val ax = fx - x0; val ay = fy - y0
        val p00 = src[y0 * sw + x0]; val p10 = src[y0 * sw + x1]
        val p01 = src[y1 * sw + x0]; val p11 = src[y1 * sw + x1]
        fun ch(shift: Int): Int {
            val a = (p00 shr shift) and 0xFF; val b = (p10 shr shift) and 0xFF
            val c = (p01 shr shift) and 0xFF; val d = (p11 shr shift) and 0xFF
            val top = a + (b - a) * ax; val bot = c + (d - c) * ax
            return (top + (bot - top) * ay).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------------- Automatic: the curve from the lines of text ----------------

    /**
     * Works out the top and bottom bows of [r] from lines of text: each line is the centres of its
     * words, left to right, in picture pixels. A line's bow is how far its middle sits from the
     * straight line joining its two ends (measured across the full page width at that height).
     * The bows are spread down the page with a straight-line fit, giving the bow at the top and
     * the bottom edge. Null when there are too few usable lines (fewer than 3 long ones).
     */
    fun bowsFromText(r: Region, lines: List<List<Pt>>): Pair<Float, Float>? {
        val pageW = (hypot(r.tr.x - r.tl.x, r.tr.y - r.tl.y) + hypot(r.br.x - r.bl.x, r.br.y - r.bl.y)) / 2f
        val pageTop = (r.tl.y + r.tr.y) / 2f
        val pageH = ((r.bl.y + r.br.y) / 2f - pageTop).coerceAtLeast(1f)
        val vs = ArrayList<Float>(); val bows = ArrayList<Float>(); val weights = ArrayList<Float>()
        for (line in lines) {
            if (line.size < 3) continue
            val xs = line.map { it.x }; val minX = xs.min(); val maxX = xs.max()
            val span = maxX - minX
            if (span < pageW * 0.3f) continue
            val fit = quadFit(line) ?: continue
            val (a, b, c) = fit
            // Left and right page edge at this line's height (straight sides).
            val yMid = line.map { it.y }.average().toFloat()
            val v = ((yMid - pageTop) / pageH).coerceIn(0f, 1f)
            val xl = r.tl.x + (r.bl.x - r.tl.x) * v
            val xr = r.tr.x + (r.br.x - r.tr.x) * v
            fun f(x: Float) = a * x * x + b * x + c
            val bow = f((xl + xr) / 2f) - (f(xl) + f(xr)) / 2f
            if (abs(bow) > pageH * 0.25f) continue // a wild fit (e.g. two columns read as one line)
            vs += v; bows += bow; weights += span / pageW
        }
        if (bows.size < 3) return null
        // Weighted straight line bow = p + q·v.
        var sw = 0f; var sv = 0f; var sb = 0f; var svv = 0f; var svb = 0f
        for (i in bows.indices) {
            val w = weights[i]; sw += w; sv += w * vs[i]; sb += w * bows[i]; svv += w * vs[i] * vs[i]; svb += w * vs[i] * bows[i]
        }
        val den = sw * svv - sv * sv
        val q = if (abs(den) < 1e-6f) 0f else (sw * svb - sv * sb) / den
        val p = (sb - q * sv) / sw
        val cap = pageH * 0.2f
        return (p).coerceIn(-cap, cap) to (p + q).coerceIn(-cap, cap)
    }

    /** Least-squares y = a·x² + b·x + c, or null when the points can't fix one. */
    fun quadFit(pts: List<Pt>): Triple<Float, Float, Float>? {
        if (pts.size < 3) return null
        // Centre and scale x for a stable fit.
        val mx = pts.map { it.x.toDouble() }.average()
        val sx = (pts.maxOf { it.x } - pts.minOf { it.x }).toDouble().coerceAtLeast(1.0)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val x = (p.x - mx) / sx; val y = p.y.toDouble()
            s0 += 1; s1 += x; s2 += x * x; s3 += x * x * x; s4 += x * x * x * x
            t0 += y; t1 += x * y; t2 += x * x * y
        }
        // Solve [s4 s3 s2; s3 s2 s1; s2 s1 s0] [A B C] = [t2 t1 t0].
        val m = arrayOf(doubleArrayOf(s4, s3, s2, t2), doubleArrayOf(s3, s2, s1, t1), doubleArrayOf(s2, s1, s0, t0))
        for (col in 0 until 3) {
            val piv = (col until 3).maxBy { abs(m[it][col]) }
            if (abs(m[piv][col]) < 1e-9) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (row in 0 until 3) if (row != col) {
                val k = m[row][col] / m[col][col]
                for (j in col until 4) m[row][j] -= k * m[col][j]
            }
        }
        val A = m[0][3] / m[0][0]; val B = m[1][3] / m[1][1]; val C = m[2][3] / m[2][2]
        // Back to picture x: y = A·((x-mx)/sx)² + B·((x-mx)/sx) + C.
        val a = A / (sx * sx)
        val b = -2 * A * mx / (sx * sx) + B / sx
        val c = A * mx * mx / (sx * sx) - B * mx / sx + C
        return Triple(a.toFloat(), b.toFloat(), c.toFloat())
    }

    /** [r] with its bows set straight down (the page held upright): the usual result of [bowsFromText]. */
    fun withBows(r: Region, top: Float, bottom: Float) = r.copy(topBow = Pt(0f, top), bottomBow = Pt(0f, bottom))

    // ---------------- Book mode: the spine between two pages ----------------

    /**
     * The spine of an open book: the darkest column (its shadow) in the middle 40 % of [lum], a
     * small brightness picture ([w] × [h], 0–255) of the book, averaged over its middle rows.
     * Returns the column as a fraction 0..1 of the width (0.5 when nothing stands out).
     */
    fun spineColumn(lum: IntArray, w: Int, h: Int): Float {
        if (w < 5 || h < 3) return 0.5f
        val y0 = h / 5; val y1 = h - h / 5
        val col = FloatArray(w)
        for (x in 0 until w) {
            var s = 0L
            for (y in y0 until y1) s += lum[y * w + x]
            col[x] = s.toFloat() / (y1 - y0)
        }
        // Smooth a little so one dark letter doesn't win.
        val k = max(1, w / 60)
        val sm = FloatArray(w) { x -> var s = 0f; var n = 0; for (j in max(0, x - k)..min(w - 1, x + k)) { s += col[j]; n++ }; s / n }
        val from = (w * 0.3f).toInt(); val to = (w * 0.7f).toInt()
        var best = (from + to) / 2
        for (x in from..to) if (sm[x] < sm[best]) best = x
        val avg = sm.slice(from..to).average().toFloat()
        // Not clearly darker than its surroundings: the middle.
        return if (avg - sm[best] < 6f) 0.5f else best / (w - 1f)
    }

    /** The two pages of an open book: left (TL, spine top, spine bottom, BL) and right (spine top, TR, BR, spine bottom). */
    fun splitBook(tl: Pt, tr: Pt, br: Pt, bl: Pt, spineTop: Pt, spineBottom: Pt): Pair<Region, Region> =
        Region(tl, spineTop, spineBottom, bl) to Region(spineTop, tr, br, spineBottom)
}
