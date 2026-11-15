package com.vigyan.scanner

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vigyan.scanner.DewarpMath.Pt
import com.vigyan.scanner.DewarpMath.Region

/** Curved page correction on the phone: finds text lines with ML Kit and flattens with [DewarpMath]. */
object Dewarp {

    /** Flattens [r] out of [src] into a new upright picture. */
    fun flatten(src: Bitmap, r: Region, maxSide: Int = 3000): Bitmap {
        val (w, h) = DewarpMath.outputSize(r, maxSide)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = DewarpMath.remap(px, src.width, src.height, r, w, h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The lines of text on [bmp]: each line is the centres of its words, left to right. Read from
     * a copy at most 1600 px long (fast enough, and plenty to see a curve), scaled back to [bmp]'s pixels.
     */
    suspend fun textLines(bmp: Bitmap): List<List<Pt>> {
        val k = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(InputImage.fromBitmap(small, 0)).await()
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val pts = line.elements.mapNotNull { e -> e.boundingBox?.let { b -> Pt(b.exactCenterX() / k, b.exactCenterY() / k) } }
                    .sortedBy { it.x }
                pts.takeIf { it.size >= 3 }
            }
        } finally {
            recognizer.close()
            if (small !== bmp) small.recycle()
        }
    }

    /** [r] with its curve found from the text inside it; [r] unchanged (and false) when there is too little text. */
    fun curveFromText(r: Region, lines: List<List<Pt>>): Pair<Region, Boolean> {
        // Only lines that sit inside this region (Book mode: one page at a time).
        val minX = minOf(r.tl.x, r.bl.x); val maxX = maxOf(r.tr.x, r.br.x)
        val minY = minOf(r.tl.y, r.tr.y); val maxY = maxOf(r.bl.y, r.br.y)
        val inside = lines.mapNotNull { l -> l.filter { it.x in minX..maxX && it.y in minY..maxY }.takeIf { it.size >= 3 } }
        val bows = DewarpMath.bowsFromText(r, inside) ?: return r to false
        return DewarpMath.withBows(r, bows.first, bows.second) to true
    }

    /** The spine of an open book between [quad]'s left and right sides, as an x in [bmp]'s pixels. */
    fun spineX(bmp: Bitmap, quad: FloatArray): Float {
        val s = Perspective.sample(bmp, 400)
        val left = (minOf(quad[0], quad[6]) / s.toFull).toInt().coerceIn(0, s.w - 1)
        val right = (maxOf(quad[2], quad[4]) / s.toFull).toInt().coerceIn(left + 1, s.w)
        val top = (minOf(quad[1], quad[3]) / s.toFull).toInt().coerceIn(0, s.h - 1)
        val bottom = (maxOf(quad[5], quad[7]) / s.toFull).toInt().coerceIn(top + 1, s.h)
        val w = right - left; val h = bottom - top
        val sub = IntArray(w * h) { i -> s.lum[(top + i / w) * s.w + left + i % w] }
        return (left + DewarpMath.spineColumn(sub, w, h) * (w - 1)) * s.toFull
    }
}
