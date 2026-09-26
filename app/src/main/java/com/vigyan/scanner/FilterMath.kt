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

    private fun scale(v: Int, paper: Float) = (v * 255f / paper.coerceAtLeast(1f)).toInt().coerceIn(0, 255)
}
