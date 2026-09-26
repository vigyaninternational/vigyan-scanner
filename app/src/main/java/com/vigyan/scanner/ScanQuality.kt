package com.vigyan.scanner

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Checks a photographed page (as brightness values of a small copy, about 640 px long) for the
 * usual problems: blur, darkness, glare, or nothing on it. Pure logic, unit-tested.
 */
object ScanQuality {

    enum class Issue(val label: String) {
        BLURRY("blurry"),
        DARK("too dark"),
        GLARE("has a glare"),
        BLANK("blank"),
    }

    class Result(
        /** Higher is sharper (variance of the Laplacian). Compares two shots of the same page. */
        val sharpness: Double,
        val issues: List<Issue>,
    ) {
        /** Problems worth warning about (a blank page is not a problem in itself). */
        val warnings get() = issues - Issue.BLANK
    }

    private const val BLUR_LIMIT = 40.0
    private const val DARK_MEAN = 70.0
    private const val GLARE_LEVEL = 250
    private const val GLARE_SHARE = 0.06
    private const val BLANK_EDGES = 0.002
    private const val BLANK_STD = 25.0

    fun check(lum: IntArray, w: Int, h: Int): Result {
        val mean = lum.average()
        var v = 0.0
        lum.forEach { v += (it - mean) * (it - mean) }
        val std = sqrt(v / lum.size)

        var sum = 0.0
        var sq = 0.0
        var edges = 0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]
            sum += l
            sq += l.toDouble() * l
            if (abs(l) > 30) edges++
            n++
        }
        val sharpness = if (n == 0) 0.0 else sq / n - (sum / n) * (sum / n)
        val edgeShare = if (n == 0) 0.0 else edges.toDouble() / n

        val issues = mutableListOf<Issue>()
        val blank = edgeShare < BLANK_EDGES && std < BLANK_STD
        if (blank) issues += Issue.BLANK
        else if (sharpness < BLUR_LIMIT) issues += Issue.BLURRY
        if (mean < DARK_MEAN) issues += Issue.DARK
        val glare = lum.count { it >= GLARE_LEVEL }.toDouble() / lum.size
        if (!blank && glare > GLARE_SHARE && mean < 200) issues += Issue.GLARE
        return Result(sharpness, issues)
    }

    /** "blurry and too dark" */
    fun describe(issues: List<Issue>) = issues.joinToString(" and ") { it.label }
}
