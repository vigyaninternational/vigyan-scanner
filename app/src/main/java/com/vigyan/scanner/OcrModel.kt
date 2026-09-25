package com.vigyan.scanner

import kotlin.math.abs
import kotlin.math.min

/** One line of OCR text and its box, in the page image's pixels (origin top-left). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** OCR result for one page image of [width]×[height] pixels. */
class PageOcr(val width: Int, val height: Int, val text: String, val lines: List<OcrLine>)

object OcrLayout {

    /**
     * Rebuilds the page as visual rows: lines whose vertical centres line up are joined left to
     * right, so a form's label and its value land on the same line. Three spaces mark the gap
     * between separate pieces of text on one row (FormExtractor relies on that).
     */
    fun rows(lines: List<OcrLine>): String {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { (it.top + it.bottom) / 2 }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { (it.top + it.bottom) / 2.0 }.average()
                val rowHeight = row.map { (it.bottom - it.top).toDouble() }.average()
                val center = (line.top + line.bottom) / 2.0
                if (abs(center - rowCenter) < 0.5 * min(rowHeight, (line.bottom - line.top).toDouble())) {
                    row += line
                    continue
                }
            }
            rows += mutableListOf(line)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("   ") { it.text } }
    }

    /** Whole-document text and row text from per-page results (page headers when there are several pages). */
    fun combine(pages: List<PageOcr>): Pair<String, String> {
        val text = pages.mapIndexed { i, p ->
            (if (pages.size > 1) "--- Page ${i + 1} ---\n" else "") + p.text.trim()
        }.joinToString("\n\n").trim()
        val rows = pages.joinToString("\n") { rows(it.lines) }
        return text to rows
    }
}
