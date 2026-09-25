package com.vigyan.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

/** Straightens a photographed page: its four corners become a flat rectangle. */
object Perspective {

    /** [quad] = [tlx, tly, trx, try, brx, bry, blx, bly] in [src]'s pixels. */
    fun warp(src: Bitmap, quad: FloatArray, maxSide: Int = 3000): Bitmap {
        var (w, h) = DocDetect.outputSize(quad)
        val k = minOf(1f, maxSide / maxOf(w, h))
        w *= k
        h *= k
        val out = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(quad, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** Brightness and colourfulness of a small copy of [bmp], for DocDetect. Returns (lum, sat, w, h). */
    fun sample(bmp: Bitmap, longSide: Int = 256): Sample {
        val scale = longSide.toFloat() / maxOf(bmp.width, bmp.height)
        val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bmp) small.recycle()
        val lum = IntArray(px.size)
        val sat = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (r * 299 + g * 587 + b * 114) / 1000
            sat[i] = maxOf(r, g, b) - minOf(r, g, b)
        }
        return Sample(lum, sat, sw, sh, bmp.width.toFloat() / sw)
    }

    class Sample(val lum: IntArray, val sat: IntArray, val w: Int, val h: Int, val toFull: Float) {
        /** The page's corners in the full bitmap's pixels, or null. */
        fun quad(): FloatArray? = DocDetect.findQuad(lum, sat, w, h)?.let { q -> FloatArray(8) { q[it] * toFull } }
    }
}
