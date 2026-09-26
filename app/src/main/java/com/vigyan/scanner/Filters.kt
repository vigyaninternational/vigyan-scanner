package com.vigyan.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File

/** Page looks, like the filters in a scanner app. */
enum class PageFilter(val label: String, val hint: String) {
    ENHANCE("✨ Brighten", "Whiter paper, darker text, colours kept"),
    SHADOW("🌗 Remove shadows", "Evens out shadows and uneven light, colours kept"),
    GRAY("⚪ Grayscale", "Grey, smaller file"),
    BW("⚫ Black & white", "Crisp text, smallest file; best for printed pages"),
}

object Filters {

    fun apply(src: Bitmap, filter: PageFilter): Bitmap = when (filter) {
        PageFilter.ENHANCE -> enhance(src)
        PageFilter.GRAY -> Images.grayscale(src)
        PageFilter.SHADOW -> flatten(src, bw = false)
        PageFilter.BW -> flatten(src, bw = true)
    }

    /** Levels taken from the page itself: its paper becomes white and its ink black. */
    fun enhance(src: Bitmap): Bitmap {
        val (black, white) = QuickScanLogic.levels(Perspective.sample(src, 200).lum)
        val scale = 255f / (white - black)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, -black * scale,
                0f, scale, 0f, 0f, -black * scale,
                0f, 0f, scale, 0f, -black * scale,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Divides the page by its paper colour (removes shadows); [bw] then makes it black and white. */
    private fun flatten(src: Bitmap, bw: Boolean): Bitmap {
        val gw = 96
        val gh = (gw * src.height / src.width).coerceIn(8, 400)
        val small = Bitmap.createScaledBitmap(src, gw, gh, true)
        val smallPx = IntArray(gw * gh)
        small.getPixels(smallPx, 0, gw, 0, 0, gw, gh)
        if (small !== src) small.recycle()
        val bg = FilterMath.background(smallPx, gw, gh)

        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // A band of rows at a time, so a big page doesn't need two full pixel copies in memory.
        val band = 64
        val px = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            src.getPixels(px, 0, w, 0, y, w, rows)
            FilterMath.flatten(px, w, rows, y, h, bg, bw)
            out.setPixels(px, 0, w, 0, y, w, rows)
            y += rows
        }
        return out
    }

    /** Quality of a saved page (for warnings after scanning). */
    fun quality(page: File): ScanQuality.Result {
        val bmp = Images.decode(page, 640)
        val s = Perspective.sample(bmp, 640)
        bmp.recycle()
        return ScanQuality.check(s.lum, s.w, s.h)
    }
}
