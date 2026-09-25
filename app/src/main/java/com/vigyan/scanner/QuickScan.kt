package com.vigyan.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File

/** Turns a Quick scan camera photo into a clean page image. */
object QuickScan {

    /** Upright, trimmed to the paper (when it's on a darker surface) and, with [enhance], brightened. */
    fun process(raw: File, dest: File, enhance: Boolean) {
        var bmp = Images.decode(raw, 2400)
        // The camera saves the picture sideways plus a "turn me" note (EXIF); apply it.
        val degrees = when (ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }

        // Small copy for finding the paper and the levels.
        val sw = 200
        val sh = (bmp.height * sw / bmp.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val lum = IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }

        val box = QuickScanLogic.paperBox(lum, sw, sh)
        // Brightness levels from the paper only (not the table around it).
        val paperLum = box?.let { b ->
            IntArray((b[2] - b[0] + 1) * (b[3] - b[1] + 1)) { i ->
                val w = b[2] - b[0] + 1
                lum[(b[1] + i / w) * sw + b[0] + i % w]
            }
        } ?: lum
        box?.let { b ->
            val k = bmp.width.toFloat() / sw
            val left = (b[0] * k).toInt().coerceIn(0, bmp.width - 1)
            val top = (b[1] * k).toInt().coerceIn(0, bmp.height - 1)
            val right = ((b[2] + 1) * k).toInt().coerceIn(left + 1, bmp.width)
            val bottom = ((b[3] + 1) * k).toInt().coerceIn(top + 1, bmp.height)
            val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }

        if (enhance) {
            val (black, white) = QuickScanLogic.levels(paperLum)
            val scale = 255f / (white - black)
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, -black * scale,
                    0f, scale, 0f, 0f, -black * scale,
                    0f, 0f, scale, 0f, -black * scale,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            bmp.recycle()
            bmp = out
        }
        Images.save(bmp, dest, 90)
        bmp.recycle()
    }
}
