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

    /** Upright and, with [enhance], brightened. The whole photo is kept (corners can be set by hand with ⛶). */
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

        if (enhance) {
            // Levels from the page itself.
            val (black, white) = QuickScanLogic.levels(Perspective.sample(bmp, 200).lum)
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
