package com.vigyan.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Turns a Quick scan camera photo into a clean page image. */
object QuickScan {

    /**
     * Upright and, with [enhance], brightened. The whole photo is kept (corners can be set by hand
     * with ⛶). Returns the photo's quality (checked before brightening).
     */
    fun process(raw: File, dest: File, enhance: Boolean): ScanQuality.Result {
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

        val sample = Perspective.sample(bmp, 640)
        val quality = ScanQuality.check(sample.lum, sample.w, sample.h)
        if (enhance) {
            val out = Filters.enhance(bmp)
            bmp.recycle()
            bmp = out
        }
        Images.save(bmp, dest, 90)
        bmp.recycle()
        return quality
    }
}
