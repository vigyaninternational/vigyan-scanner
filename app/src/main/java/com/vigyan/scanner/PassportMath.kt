package com.vigyan.scanner

import kotlin.math.floor
import kotlin.math.roundToInt

/** A photo size in millimetres; [headShare] = how much of the photo's height the head fills. */
data class PhotoSize(val label: String, val widthMm: Float, val heightMm: Float, val headShare: Float = 0.72f) {
    val widthPx get() = PassportMath.mmToPx(widthMm)
    val heightPx get() = PassportMath.mmToPx(heightMm)

    companion object {
        val PRESETS = listOf(
            PhotoSize("35×45 mm · passport, forms", 35f, 45f),
            PhotoSize("51×51 mm · 2×2 inch (US visa)", 51f, 51f, 0.6f),
            PhotoSize("30×40 mm", 30f, 40f),
            PhotoSize("25×35 mm · stamp size", 25f, 35f),
            PhotoSize("20×25 mm · small", 20f, 25f),
        )
    }
}

/**
 * Where to crop a passport photo around a detected face. The face detector's box runs roughly
 * from the eyebrows to the chin; the head (crown to chin) is about 1.35 times that. A passport
 * photo wants the head at about 72% of the photo height, with a little space above the hair.
 * Pure Kotlin, unit-tested.
 */
object PassportMath {

    /** Passport photo at 300 dpi: 35 x 45 mm. */
    const val WIDTH_PX = 413
    const val HEIGHT_PX = 531

    fun mmToPx(mm: Float, dpi: Int = 300) = (mm / 25.4f * dpi).roundToInt()

    /**
     * Returns [left, top, width, height] in image pixels. [zoom] > 1 makes the face bigger;
     * [aspect] = photo width / height; [shiftX], [shiftY] move the frame by that share of its size.
     */
    fun crop(
        faceLeft: Int, faceTop: Int, faceRight: Int, faceBottom: Int, imageWidth: Int, imageHeight: Int,
        zoom: Float = 1f, aspect: Float = 35f / 45f, headShare: Float = 0.72f, shiftX: Float = 0f, shiftY: Float = 0f,
    ): IntArray {
        val faceH = (faceBottom - faceTop).toFloat()
        val centerX = (faceLeft + faceRight) / 2f
        val crown = faceTop - 0.35f * faceH
        var h = 1.35f * faceH / headShare / zoom
        var w = h * aspect
        // Bigger than the photo itself: shrink, keeping the photo's shape.
        if (w > imageWidth) { w = imageWidth.toFloat(); h = w / aspect }
        if (h > imageHeight) { h = imageHeight.toFloat(); w = h * aspect }
        var left = centerX - w / 2 + shiftX * w
        var top = crown - 0.12f * h + shiftY * h
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

/** How many photos fit on a sheet of paper, and where (all in millimetres). Pure Kotlin, unit-tested. */
object PhotoSheet {

    class Layout(val cols: Int, val rows: Int, val photoW: Float, val photoH: Float, val positions: List<Pair<Float, Float>>) {
        val count get() = positions.size
    }

    /**
     * Photos of [photoW]×[photoH] on a [pageW]×[pageH] page with [margin] around the edge and
     * [gap] between photos, centred. [maxCols] / [maxRows] (0 = as many as fit) limit the grid.
     */
    fun layout(pageW: Float, pageH: Float, photoW: Float, photoH: Float, margin: Float, gap: Float, maxCols: Int = 0, maxRows: Int = 0): Layout {
        var cols = floor((pageW - 2 * margin + gap) / (photoW + gap)).toInt().coerceAtLeast(0)
        var rows = floor((pageH - 2 * margin + gap) / (photoH + gap)).toInt().coerceAtLeast(0)
        if (maxCols > 0) cols = minOf(cols, maxCols)
        if (maxRows > 0) rows = minOf(rows, maxRows)
        val blockW = cols * photoW + (cols - 1).coerceAtLeast(0) * gap
        val blockH = rows * photoH + (rows - 1).coerceAtLeast(0) * gap
        val x0 = (pageW - blockW) / 2
        val y0 = (pageH - blockH) / 2
        val positions = (0 until rows).flatMap { r -> (0 until cols).map { c -> (x0 + c * (photoW + gap)) to (y0 + r * (photoH + gap)) } }
        return Layout(cols, rows, photoW, photoH, positions)
    }

    const val A4_W = 210f
    const val A4_H = 297f
    const val SIX_W = 152.4f // 6 x 4 inch photo paper, landscape
    const val FOUR_H = 101.6f
}
