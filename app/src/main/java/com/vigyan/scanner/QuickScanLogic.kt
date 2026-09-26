package com.vigyan.scanner

import kotlin.math.abs

/**
 * Pure logic behind Quick scan (unit-tested): when to take a picture automatically, where the
 * paper is in a photo, and how to brighten a page.
 */
object QuickScanLogic {

    /**
     * Auto-capture: takes a picture once the view has been still for [stillMs], then waits for
     * a big change (the page being turned) before it can take the next one.
     */
    class AutoShutter(
        private val stillMs: Long = 800,
        private val stillDiff: Double = 4.0,
        private val changeDiff: Double = 16.0,
    ) {
        private var armed = true
        private var stillSince = -1L

        /** [diff]: average brightness change from the last frame (0..255); [brightness]: average brightness. */
        fun onFrame(diff: Double, brightness: Double, timeMs: Long): Boolean {
            if (diff > changeDiff) {
                armed = true
                stillSince = -1
                return false
            }
            if (!armed || brightness < 40) return false // too dark: probably covered / not pointed at paper
            if (diff < stillDiff) {
                if (stillSince < 0) stillSince = timeMs
                if (timeMs - stillSince >= stillMs) {
                    armed = false
                    stillSince = -1
                    return true
                }
            } else {
                stillSince = -1
            }
            return false
        }

        /** After a manual capture: wait for the page to change before snapping again. */
        fun captured() {
            armed = false
            stillSince = -1
        }

        val waitingForNewPage get() = !armed
    }

    fun meanDiff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    /**
     * Finds the paper: rows and columns that are mostly brighter than the page's own threshold
     * (Otsu). Works when the paper lies on a darker table or bed. Returns [left, top, right,
     * bottom] in the small image's pixels, or null when the paper fills the frame or isn't clear.
     */
    fun paperBox(lum: IntArray, w: Int, h: Int): IntArray? {
        val t = otsu(lum)
        val bright = BooleanArray(lum.size) { lum[it] > t }
        fun rowFrac(y: Int) = (0 until w).count { bright[y * w + it] }.toDouble() / w
        fun colFrac(x: Int) = (0 until h).count { bright[it * w + x] }.toDouble() / h
        val top = (0 until h).firstOrNull { rowFrac(it) > 0.5 } ?: return null
        val bottom = (h - 1 downTo 0).firstOrNull { rowFrac(it) > 0.5 } ?: return null
        val left = (0 until w).firstOrNull { colFrac(it) > 0.5 } ?: return null
        val right = (w - 1 downTo 0).firstOrNull { colFrac(it) > 0.5 } ?: return null
        val area = (right - left + 1).toDouble() * (bottom - top + 1) / (w * h)
        // Nothing to trim, or an unlikely shape: keep the whole photo.
        if (area > 0.93 || area < 0.25 || right <= left || bottom <= top) return null
        return intArrayOf(left, top, right, bottom)
    }

    /** Otsu's threshold for a brightness histogram (0..255). */
    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        lum.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = lum.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = i }
        }
        return threshold
    }

    /**
     * Bulk scanning: [count] pages split into documents where each index in [breaks] starts a new
     * one. Returns the page ranges, leaving out empty ones.
     */
    fun groups(count: Int, breaks: Collection<Int>): List<IntRange> {
        val cuts = listOf(0) + breaks.filter { it in 1 until count }.distinct().sorted() + count
        return cuts.zipWithNext().map { (a, b) -> a until b }.filter { !it.isEmpty() }
    }

    /** Levels for "document look": the 3rd and 97th brightness percentiles become black and white. */
    fun levels(lum: IntArray): Pair<Int, Int> {
        val hist = IntArray(256)
        lum.forEach { hist[it.coerceIn(0, 255)]++ }
        fun pct(p: Double): Int {
            var c = 0
            for (i in 0..255) { c += hist[i]; if (c >= lum.size * p) return i }
            return 255
        }
        val black = pct(0.03)
        val white = pct(0.97).coerceAtLeast(black + 30)
        return black to white.coerceAtMost(255)
    }
}
