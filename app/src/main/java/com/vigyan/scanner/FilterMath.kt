package com.vigyan.scanner

import kotlin.math.floor

/** Pure pixel maths behind the page filters (unit-tested). Pixels are ARGB ints. */
object FilterMath {

    /** Brightness at which "Black & white" splits ink from paper, after shadows are removed. */
    const val BW_THRESHOLD = 170

    /** A smooth estimate of the paper's colour across the page (a small grid of [w]×[h]). */
    class Background(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        /** Paper colour at ([fx], [fy]) given as fractions (0..1) of the page, into [out] (r, g, b). */
        fun at(fx: Float, fy: Float, out: FloatArray) {
            val gx = (fx * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val gy = (fy * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = floor(gx).toInt()
            val y0 = floor(gy).toInt()
            val x1 = minOf(x0 + 1, w - 1)
            val y1 = minOf(y0 + 1, h - 1)
            val tx = gx - x0
            val ty = gy - y0
            fun mix(a: FloatArray): Float {
                val top = a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx
                val bottom = a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx
                return top * (1 - ty) + bottom * ty
            }
            out[0] = mix(r)
            out[1] = mix(g)
            out[2] = mix(b)
        }
    }

    /**
     * The paper's colour from a small copy of the page. Text is dark and thin, so the brightest
     * pixel nearby is the paper (a "max" step removes the text); then it is smoothed.
     */
    fun background(small: IntArray, w: Int, h: Int): Background {
        val ch = (0 until 3).map { c ->
            val shift = 16 - 8 * c
            val a = FloatArray(w * h) { i -> ((small[i] shr shift) and 0xFF).toFloat() }
            blur(blur(dilate(dilate(a, w, h), w, h), w, h), w, h)
        }
        return Background(w, h, ch[0], ch[1], ch[2])
    }

    private fun dilate(a: FloatArray, w: Int, h: Int) = FloatArray(a.size) { i ->
        val x = i % w
        val y = i / w
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1)
            val yy = (y + dy).coerceIn(0, h - 1)
            m = maxOf(m, a[yy * w + xx])
        }
        m
    }

    private fun blur(a: FloatArray, w: Int, h: Int) = FloatArray(a.size) { i ->
        val x = i % w
        val y = i / w
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1)
            val yy = (y + dy).coerceIn(0, h - 1)
            s += a[yy * w + xx]
        }
        s / 9f
    }

    /**
     * Divides a band of [rows] rows (starting at row [y0] of a [fullH]-row page) by the paper's
     * colour, so shadows and uneven light turn white while ink stays dark. With [bw] the result
     * is pure black and white. Works in place on [px].
     */
    fun flatten(px: IntArray, width: Int, rows: Int, y0: Int, fullH: Int, bg: Background, bw: Boolean) {
        val c = FloatArray(3)
        for (y in 0 until rows) {
            val fy = (y0 + y + 0.5f) / fullH
            for (x in 0 until width) {
                bg.at((x + 0.5f) / width, fy, c)
                val i = y * width + x
                val p = px[i]
                val r = scale((p shr 16) and 0xFF, c[0])
                val g = scale((p shr 8) and 0xFF, c[1])
                val b = scale(p and 0xFF, c[2])
                px[i] = if (bw) {
                    val v = if ((r * 299 + g * 587 + b * 114) / 1000 < BW_THRESHOLD) 0 else 255
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                } else {
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    /**
     * "Ink only": the darkness below which a pixel counts as ink, from the page's own paper
     * brightness (a sample of brightness values), so a dim photo works too.
     */
    fun inkThreshold(lum: IntArray): Int {
        if (lum.isEmpty()) return 110
        val sorted = lum.sorted()
        val paper = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        return (paper * 0.48f).toInt().coerceIn(70, 140)
    }

    /** Colours above this saturation (purple security pattern, blue seals…) are wiped out. */
    const val INK_SATURATION = 60

    /**
     * Keeps only dark, uncoloured ink (printed text): the coloured or light background pattern of
     * certificates and ID cards turns white. Works in place on [px].
     */
    fun inkOnly(px: IntArray, threshold: Int) {
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val sat = maxOf(r, g, b) - minOf(r, g, b)
            val v = if (sat > INK_SATURATION || lum >= threshold) 255 else lum * 255 / (2 * threshold)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun scale(v: Int, paper: Float) = (v * 255f / paper.coerceAtLeast(1f)).toInt().coerceIn(0, 255)

    // ---- "Clear & bright": dull, faded scans made clear ----

    /**
     * Black and white points per channel from a small copy of the page: [rBlack, rWhite, gBlack,
     * gWhite, bBlack, bWhite]. The white point is the paper (grey or yellowish paper becomes
     * white); a slight colour cast is corrected, but a truly coloured paper (pink certificate)
     * keeps its colour. The black point never goes so high that dark photos are crushed.
     */
    fun clearLevels(px: IntArray): IntArray {
        val hist = Array(3) { IntArray(256) }
        for (p in px) {
            hist[0][(p shr 16) and 0xFF]++
            hist[1][(p shr 8) and 0xFF]++
            hist[2][p and 0xFF]++
        }
        fun pct(h: IntArray, q: Double): Int {
            var c = 0
            for (i in 0..255) { c += h[i]; if (c >= px.size * q) return i }
            return 255
        }
        val whites = IntArray(3) { pct(hist[it], 0.94) }
        val top = maxOf(whites[0], whites[1], whites[2])
        val out = IntArray(6)
        for (c in 0..2) {
            // Correct a small tint only (at most 25 levels apart from the brightest channel).
            val white = maxOf(whites[c], top - 25).coerceIn(90, 255)
            val black = pct(hist[c], 0.01).coerceAtMost(55).coerceAtMost(white - 60)
            out[2 * c] = black
            out[2 * c + 1] = white
        }
        return out
    }

    /** Tone curves per channel: stretch black..white to 0..255, then a gentle curve that deepens faded text. */
    fun clearLuts(levels: IntArray, gamma: Double = 1.25): Array<IntArray> = Array(3) { c ->
        val black = levels[2 * c]
        val white = levels[2 * c + 1]
        IntArray(256) { v ->
            val t = ((v - black).toDouble() / (white - black)).coerceIn(0.0, 1.0)
            (Math.pow(t, gamma) * 255 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    /**
     * Sharpens, applies [luts] and livens colours by [saturation]. [px] holds [n] rows of [w]
     * pixels; rows [first] until [first] + [rows] are returned (the others are only neighbours).
     */
    fun clarify(px: IntArray, w: Int, n: Int, first: Int, rows: Int, luts: Array<IntArray>, sharpen: Float = 0.35f, saturation: Float = 1.2f): IntArray {
        val out = IntArray(w * rows)
        val ch = IntArray(3)
        for (y in first until first + rows) {
            val up = maxOf(0, y - 1) * w
            val down = minOf(n - 1, y + 1) * w
            val row = y * w
            for (x in 0 until w) {
                val left = row + maxOf(0, x - 1)
                val right = row + minOf(w - 1, x + 1)
                val p = px[row + x]
                for (c in 0..2) {
                    val s = 16 - 8 * c
                    val v = (p shr s) and 0xFF
                    val around = ((px[up + x] shr s) and 0xFF) + ((px[down + x] shr s) and 0xFF) +
                        ((px[left] shr s) and 0xFF) + ((px[right] shr s) and 0xFF)
                    val sharp = (v + sharpen * (4 * v - around)).toInt().coerceIn(0, 255)
                    ch[c] = luts[c][sharp]
                }
                val grey = (ch[0] * 299 + ch[1] * 587 + ch[2] * 114) / 1000f
                val r = (grey + (ch[0] - grey) * saturation).toInt().coerceIn(0, 255)
                val g = (grey + (ch[1] - grey) * saturation).toInt().coerceIn(0, 255)
                val b = (grey + (ch[2] - grey) * saturation).toInt().coerceIn(0, 255)
                out[(y - first) * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
