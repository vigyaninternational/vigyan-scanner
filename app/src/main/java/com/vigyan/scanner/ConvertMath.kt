package com.vigyan.scanner

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The sums behind the "Convert files" tool: page lists like "1-3, 5", sharing a KB limit
 * between pages, page sizes in a PDF, render sizes and file names. Plain Kotlin, unit-tested.
 */
object ConvertMath {

    /** A4 in points (1/72 inch). */
    const val A4_W = 595f
    const val A4_H = 842f

    /** White edge around a photo on an A4 page, in points (about 10 mm). */
    const val A4_MARGIN = 28f

    /** Rough bytes PdfWriter adds: once per file, and for every page. */
    const val PDF_OVERHEAD = 1200
    const val PDF_PAGE_OVERHEAD = 700

    /** Photos → PDF quality: longest side in pixels and JPEG quality. */
    enum class PdfQuality(val label: String, val maxSide: Int, val jpeg: Int) {
        SMALL("Small", 1400, 60),
        NORMAL("Normal", 2000, 80),
        HIGH("High", 3000, 92),
    }

    /** Picture formats the tool can write. */
    enum class PicFormat(val label: String, val ext: String) {
        JPG("JPG", "jpg"),
        PNG("PNG", "png"),
        WEBP("WEBP", "webp"),
    }

    /**
     * "1-3, 5" → page indices 0, 1, 2, 4 (in the order typed, no repeats). "all" or "*" = every
     * page. "5-3" counts down. Returns null when the text is not a page list, or names a page
     * that is not in a PDF of [count] pages.
     */
    fun parsePages(text: String, count: Int): List<Int>? {
        val t = text.trim().lowercase(Locale.US)
        if (count <= 0) return null
        if (t == "all" || t == "*") return (0 until count).toList()
        if (t.isEmpty()) return null
        val out = LinkedHashSet<Int>()
        for (raw in t.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val part = raw.replace('–', '-').replace('—', '-')
            val dash = part.indexOf('-')
            if (dash < 0) {
                val n = part.toIntOrNull() ?: return null
                if (n !in 1..count) return null
                out += n - 1
            } else {
                val a = part.substring(0, dash).trim().toIntOrNull() ?: return null
                // "4-" means from page 4 to the end.
                val bText = part.substring(dash + 1).trim()
                val b = if (bText.isEmpty()) count else bText.toIntOrNull() ?: return null
                if (a !in 1..count || b !in 1..count) return null
                val range = if (a <= b) a..b else a downTo b
                for (n in range) out += n - 1
            }
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    /** Page indices → the short text a person would type: [0, 1, 2, 4] → "1-3, 5". */
    fun formatPages(indices: Collection<Int>): String {
        val sorted = indices.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        fun flush() {
            parts += when {
                start == prev -> "${start + 1}"
                prev == start + 1 -> "${start + 1}, ${prev + 1}"
                else -> "${start + 1}-${prev + 1}"
            }
        }
        for (n in sorted.drop(1)) {
            if (n == prev + 1) {
                prev = n
            } else {
                flush()
                start = n
                prev = n
            }
        }
        flush()
        return parts.joinToString(", ")
    }

    /**
     * Bytes the next page's picture may use so a [maxBytes] PDF fits: what is left after the
     * pages already made ([usedBytes]) and the PDF's own overhead, shared by the [pagesLeft]
     * pages still to make. Pages that come out smaller leave more for the later ones.
     */
    fun pageBudget(maxBytes: Int, usedBytes: Int, pagesLeft: Int, totalPages: Int): Int {
        if (pagesLeft <= 0) return 0
        val overhead = PDF_OVERHEAD + PDF_PAGE_OVERHEAD * totalPages
        val left = maxBytes - overhead - usedBytes
        return (left / pagesLeft).coerceAtLeast(MIN_PAGE_BYTES)
    }

    /** The smallest picture budget tried for one page (a tiny, but readable-ish, JPEG). */
    const val MIN_PAGE_BYTES = 3000

    /** Where a picture goes on a PDF page: page size, and the picture's box from the top-left (points). */
    data class PagePlan(val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * A page for a [imgW]×[imgH] picture. [a4] = an A4 sheet (turned sideways for a wide
     * picture) with white edges; otherwise the page has the picture's own shape, fitted into
     * A4 size so it prints well.
     */
    fun placePage(imgW: Int, imgH: Int, a4: Boolean): PagePlan {
        val w = max(1, imgW).toFloat()
        val h = max(1, imgH).toFloat()
        val landscape = w > h
        val boxW = if (landscape) A4_H else A4_W
        val boxH = if (landscape) A4_W else A4_H
        if (!a4) {
            val s = minOf(boxW / w, boxH / h)
            return PagePlan(w * s, h * s, 0f, 0f, w * s, h * s)
        }
        val s = minOf((boxW - 2 * A4_MARGIN) / w, (boxH - 2 * A4_MARGIN) / h)
        val dw = w * s
        val dh = h * s
        return PagePlan(boxW, boxH, (boxW - dw) / 2, (boxH - dh) / 2, dw, dh)
    }

    /** Longest side allowed when turning a PDF page into a picture (keeps memory low). */
    fun renderCap(dpi: Int) = if (dpi >= 300) 3600 else 2000

    /**
     * Pixel size for a PDF page of [wPt]×[hPt] points at [dpi], kept under [renderCap].
     */
    fun renderSize(wPt: Int, hPt: Int, dpi: Int): Pair<Int, Int> {
        val w = max(1, wPt)
        val h = max(1, hPt)
        var s = dpi / 72f
        val cap = renderCap(dpi)
        if (max(w, h) * s > cap) s = cap.toFloat() / max(w, h)
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /** Page size after turning it [turns] quarter turns. */
    fun turned(w: Float, h: Float, turns: Int): Pair<Float, Float> = if (Math.floorMod(turns, 2) == 1) h to w else w to h

    /** Quarter turns kept in 0..3 (⟳ = +1, ⟲ = -1). */
    fun turn(turns: Int, delta: Int) = Math.floorMod(turns + delta, 4)

    /** [list] with the item at [from] moved one place up (-1) or down (+1); unchanged at the ends. */
    fun <T> move(list: List<T>, from: Int, delta: Int): List<T> {
        val to = from + delta
        if (from !in list.indices || to !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }

    /** A file name without its extension and without characters phones / portals dislike. */
    fun baseName(displayName: String, fallback: String = "file"): String {
        val noExt = displayName.trim().let { n ->
            val dot = n.lastIndexOf('.')
            if (dot > 0 && n.length - dot <= 5) n.substring(0, dot) else n
        }
        return noExt.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim().take(80).ifBlank { fallback }
    }

    /** "name.jpg" for a single file, "name_page3.jpg" when there are several. */
    fun pageFileName(base: String, pageIndex: Int, count: Int, ext: String): String =
        if (count <= 1) "$base.$ext" else "${base}_page${pageIndex + 1}.$ext"

    /** [name], or "name (2)", "name (3)"… so no two files in one ZIP / folder share a name. */
    fun uniqueFileName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while ("$stem ($n)$ext" in taken) n++
        return "$stem ($n)$ext"
    }

    /** File size as people read it: "850 KB", "2.4 MB". */
    fun sizeText(bytes: Long): String {
        val kb = (bytes + 1023) / 1024
        return if (kb < 1024) "$kb KB" else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** A typed "under ___ KB" → bytes, or null when empty / not a sensible number. */
    fun kbToBytes(text: String): Int? {
        val kb = text.trim().toIntOrNull() ?: return null
        if (kb < 5 || kb > 50_000) return null
        return kb * 1024
    }
}
