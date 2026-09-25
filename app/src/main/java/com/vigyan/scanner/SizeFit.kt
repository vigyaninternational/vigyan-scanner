package com.vigyan.scanner

/**
 * Finds the best-looking file that fits a size limit ("photo under 50 KB", "PDF under 200 KB"),
 * as government portals require. It tries the highest JPEG quality that fits. If even low
 * quality is too big and [allowShrink] is on, it makes the image smaller and tries again.
 *
 * [encode] turns (scale 0..1, JPEG quality) into file bytes. Pure Kotlin, unit-tested.
 */
object SizeFit {

    class Result(val bytes: ByteArray, val scale: Float, val quality: Int, val fits: Boolean)

    private const val MIN_Q = 12
    private const val MAX_Q = 95

    fun fit(maxBytes: Int, minBytes: Int = 0, allowShrink: Boolean = true, encode: (Float, Int) -> ByteArray): Result {
        var scale = 1f
        var last: Result? = null
        repeat(if (allowShrink) 14 else 1) {
            var lo = MIN_Q
            var hi = MAX_Q
            var best: Result? = null
            while (lo <= hi) {
                val q = (lo + hi) / 2
                val bytes = encode(scale, q)
                if (bytes.size <= maxBytes) {
                    best = Result(bytes, scale, q, true)
                    lo = q + 1
                } else {
                    last = Result(bytes, scale, q, false)
                    hi = q - 1
                }
            }
            best?.let { found ->
                // Too small for the portal's minimum? Try the very best quality.
                if (found.bytes.size < minBytes && scale == 1f) {
                    val top = encode(1f, 100)
                    if (top.size <= maxBytes) return Result(top, 1f, 100, true)
                }
                return found
            }
            scale *= 0.8f
        }
        return last ?: encode(scale, MIN_Q).let { Result(it, scale, MIN_Q, it.size <= maxBytes) }
    }
}
