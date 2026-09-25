package com.vigyan.scanner

import kotlin.math.roundToInt

/**
 * Where to crop a 35 x 45 mm passport photo around a detected face. The face detector's box
 * runs roughly from the eyebrows to the chin; the head (crown to chin) is about 1.35 times
 * that. A passport photo wants the head at about 72% of the photo height, with a little space
 * above the hair. Pure Kotlin, unit-tested.
 */
object PassportMath {

    /** Passport photo at 300 dpi: 35 x 45 mm. */
    const val WIDTH_PX = 413
    const val HEIGHT_PX = 531

    /** Returns [left, top, width, height] in image pixels. [zoom] > 1 makes the face bigger. */
    fun crop(faceLeft: Int, faceTop: Int, faceRight: Int, faceBottom: Int, imageWidth: Int, imageHeight: Int, zoom: Float = 1f): IntArray {
        val faceH = (faceBottom - faceTop).toFloat()
        val centerX = (faceLeft + faceRight) / 2f
        val crown = faceTop - 0.35f * faceH
        var h = 1.35f * faceH / 0.72f / zoom
        var w = h * 35f / 45f
        // Bigger than the photo itself: shrink, keeping the passport shape.
        if (w > imageWidth) { w = imageWidth.toFloat(); h = w * 45f / 35f }
        if (h > imageHeight) { h = imageHeight.toFloat(); w = h * 35f / 45f }
        var left = centerX - w / 2
        var top = crown - 0.12f * h
        left = left.coerceIn(0f, imageWidth - w)
        top = top.coerceIn(0f, imageHeight - h)
        return intArrayOf(left.roundToInt(), top.roundToInt(), w.roundToInt(), h.roundToInt())
    }

    /** A centred crop of the given shape (for when no face is found). */
    fun centerCrop(imageWidth: Int, imageHeight: Int, aspectW: Int = 35, aspectH: Int = 45): IntArray {
        var w = imageWidth.toFloat()
        var h = w * aspectH / aspectW
        if (h > imageHeight) { h = imageHeight.toFloat(); w = h * aspectW / aspectH }
        return intArrayOf(((imageWidth - w) / 2).roundToInt(), ((imageHeight - h) / 2).roundToInt(), w.roundToInt(), h.roundToInt())
    }
}
