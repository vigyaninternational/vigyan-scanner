package com.vigyan.scanner

/**
 * Signature / stamp cut-out: the paper becomes transparent, the ink stays, and the image is
 * cropped to the ink with a small margin. Works on plain ARGB pixel arrays (pure Kotlin, unit-tested).
 */
object Cutout {

    enum class Ink(val label: String) { ORIGINAL("Original colour"), BLACK("Black"), BLUE("Blue") }

    class Image(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * [strength] 0..1: higher keeps fainter strokes (and more paper noise). Returns null when no
     * ink is found.
     */
    fun cut(src: Image, strength: Float = 0.5f, ink: Ink = Ink.ORIGINAL): Image? {
        val n = src.width * src.height
        val lum = IntArray(n) { i ->
            val p = src.pixels[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        // Paper brightness = the 85th percentile of brightness (most of the picture is paper).
        val hist = IntArray(256)
        lum.forEach { hist[it]++ }
        var paper = 255
        var count = 0
        for (v in 0..255) {
            count += hist[v]
            if (count >= n * 0.85) { paper = v; break }
        }
        val s = strength.coerceIn(0f, 1f)
        val threshold = paper - (70 - 55 * s).toInt() // strength 0: only dark ink; 1: faint ink too
        val alpha = IntArray(n) { i ->
            val d = threshold - lum[i]
            if (d <= 0) 0 else minOf(255, d * 10)
        }

        var minX = src.width
        var minY = src.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (alpha[y * src.width + x] > 60) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val pad = maxOf(2, ((maxX - minX + maxY - minY) * 0.03).toInt())
        minX = maxOf(0, minX - pad)
        minY = maxOf(0, minY - pad)
        maxX = minOf(src.width - 1, maxX + pad)
        maxY = minOf(src.height - 1, maxY + pad)

        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val out = IntArray(w * h) { k ->
            val i = (minY + k / w) * src.width + (minX + k % w)
            val a = alpha[i]
            val rgb = when (ink) {
                Ink.ORIGINAL -> src.pixels[i] and 0xFFFFFF
                Ink.BLACK -> 0x1A1A1A
                Ink.BLUE -> 0x0D2A8A
            }
            (a shl 24) or rgb
        }
        return Image(out, w, h)
    }
}
