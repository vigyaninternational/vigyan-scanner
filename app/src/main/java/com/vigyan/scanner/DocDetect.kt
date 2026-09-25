package com.vigyan.scanner

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Finds the four corners of a sheet of paper in a small picture (about 256 px), so the page
 * can be straightened. Paper is bright and colourless: each pixel gets a "paper score"
 * (brightness minus colour), Otsu splits paper from table, small gaps (text) are filled, and
 * the paper-shaped blob nearest the middle is taken. Its corners are the points furthest
 * towards each corner of the picture. Pure Kotlin, unit-tested.
 */
object DocDetect {

    /**
     * [lum] brightness 0..255, [sat] colourfulness 0..255 (max − min of R, G, B) or null.
     * Returns [tlx, tly, trx, try, brx, bry, blx, bly] in the small picture's pixels, or null
     * when no clear page is found (or the page already fills the picture).
     */
    fun findQuad(lum: IntArray, sat: IntArray?, w: Int, h: Int): FloatArray? {
        val n = w * h
        var score = IntArray(n) { i -> (lum[i] - (sat?.get(i) ?: 0) / 2).coerceIn(0, 255) }
        score = blur(score, w, h)
        score = blur(score, w, h)
        val t = QuickScanLogic.otsu(score)
        var mask = BooleanArray(n) { score[it] > t }
        // Close (fill text inside the page), then open (cut thin links to other bright things).
        mask = dilate(dilate(mask, w, h), w, h)
        mask = erode(erode(mask, w, h), w, h)
        mask = dilate(erode(mask, w, h), w, h)

        // Connected blobs; prefer a big one that covers the middle of the picture.
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        var best = -1
        var bestScore = -1.0
        var comp = 0
        for (start in 0 until n) {
            if (!mask[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = comp
            var size = 0
            var central = 0
            while (head < tail) {
                val p = queue[head++]
                size++
                val x = p % w
                val y = p / w
                if (x >= w / 4 && x < 3 * w / 4 && y >= h / 4 && y < 3 * h / 4) central++
                if (x > 0 && mask[p - 1] && label[p - 1] < 0) { label[p - 1] = comp; queue[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && label[p + 1] < 0) { label[p + 1] = comp; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && label[p - w] < 0) { label[p - w] = comp; queue[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && label[p + w] < 0) { label[p + w] = comp; queue[tail++] = p + w }
            }
            val s = size + central * 2.0
            if (s > bestScore) { bestScore = s; best = comp }
            comp++
        }
        if (best < 0) return null

        // Corners: extreme points along the two diagonals.
        var tl = -1; var tr = -1; var br = -1; var bl = -1
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        var count = 0
        for (p in 0 until n) {
            if (label[p] != best) continue
            count++
            val x = p % w
            val y = p / w
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
            if (d < minDiff) { minDiff = d; bl = p }
        }
        val q = FloatArray(8)
        listOf(tl, tr, br, bl).forEachIndexed { i, p ->
            q[i * 2] = (p % w).toFloat()
            q[i * 2 + 1] = (p / w).toFloat()
        }
        return q.takeIf { plausible(it, count, w, h) }
    }

    /** A real page: a sensible size, convex, not a thin sliver, and mostly filled by the blob. */
    private fun plausible(q: FloatArray, count: Int, w: Int, h: Int): Boolean {
        val area = area(q)
        val frac = area / (w.toDouble() * h)
        if (frac < 0.15 || frac > 0.95) return false
        if (count / area < 0.75) return false
        val minSide = 0.2 * minOf(w, h)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (hypot((q[j * 2] - q[i * 2]).toDouble(), (q[j * 2 + 1] - q[i * 2 + 1]).toDouble()) < minSide) return false
        }
        // Convex: every turn goes the same way.
        var sign = 0
        for (i in 0 until 4) {
            val a = i; val b = (i + 1) % 4; val c = (i + 2) % 4
            val cross = (q[b * 2] - q[a * 2]) * (q[c * 2 + 1] - q[b * 2 + 1]) - (q[b * 2 + 1] - q[a * 2 + 1]) * (q[c * 2] - q[b * 2])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Shoelace area of the 4-point outline. */
    fun area(q: FloatArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1]
        }
        return abs(s) / 2
    }

    /** Width and height of the straightened page: the longer of each pair of opposite sides. */
    fun outputSize(q: FloatArray): Pair<Float, Float> {
        fun d(a: Int, b: Int) = hypot((q[b * 2] - q[a * 2]).toDouble(), (q[b * 2 + 1] - q[a * 2 + 1]).toDouble()).toFloat()
        return maxOf(d(0, 1), d(3, 2)) to maxOf(d(0, 3), d(1, 2))
    }

    private fun blur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx]; c++ }
            }
            out[y * w + x] = s / c
        }
        return out
    }

    private fun erode(m: BooleanArray, w: Int, h: Int) = BooleanArray(m.size) { p ->
        val x = p % w
        val y = p / w
        m[p] && (x == 0 || m[p - 1]) && (x == w - 1 || m[p + 1]) && (y == 0 || m[p - w]) && (y == h - 1 || m[p + w])
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int) = BooleanArray(m.size) { p ->
        val x = p % w
        val y = p / w
        m[p] || (x > 0 && m[p - 1]) || (x < w - 1 && m[p + 1]) || (y > 0 && m[p - w]) || (y < h - 1 && m[p + w])
    }
}
