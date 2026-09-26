package com.vigyan.scanner

import java.util.Locale

data class SubjectMark(val subject: String, val max: Int, val obtained: Int)

/** Subject marks read from a marksheet or pass certificate. */
class MarksResult(val subjects: List<SubjectMark>, val printedTotal: Int, val printedMax: Int) {
    /** The printed total when there is one (it is what the board certifies), else the sum. */
    val total get() = if (printedTotal > 0) printedTotal else subjects.sumOf { it.obtained }
    val max get() = if (printedMax > 0) printedMax else subjects.sumOf { it.max }
    val percent get() = if (max > 0) total * 100.0 / max else 0.0

    fun percentText() = String.format(Locale.US, "%.2f", percent)
}

/**
 * Reads subject marks from OCR row text (separate pieces of a row are 3+ spaces apart, see
 * OcrLayout.rows): "MTH   MATHEMATICS   100   46" → Mathematics, 46 out of 100. A short subject
 * code in its own column is left out. Pure Kotlin, unit-tested.
 */
object MarksheetExtractor {

    private val SKIP = Regex(
        """total|grand|aggregate|percent|result|division|grade\s*point|roll|regd|registration|reg\.?\s*no|year|date|serial|\bsl\b|page|\bpin\b|birth|\bdob\b|centre|center|code|session|mobile|phone|school\s+name|examination""",
        RegexOption.IGNORE_CASE,
    )
    private val SUBJECT_ROW = Regex("""^(?:\d{1,2}[.)]?\s+)?([A-Za-z][A-Za-z .&()'/,\-]{1,48}?)\s*[:\-]?\s+((?:\d{1,3}\s*){1,6})(?:\s+.*)?$""")
    private val TOTAL_ROW = Regex("""\b(?:grand\s+total|total\s+marks|aggregate|total)\b""", RegexOption.IGNORE_CASE)
    private val FULL_MARKS = setOf(100, 50, 200, 150, 80, 75, 70, 60, 40, 30, 25, 20)
    private val CODE_CELL = Regex("""^[A-Z]{2,4}\d{0,3}$""")
    private val GAP = Regex("""\s{3,}""")

    fun extract(rows: String): MarksResult {
        val subjects = mutableListOf<SubjectMark>()
        var printedTotal = 0
        var printedMax = 0
        for (raw in rows.lines()) {
            // A subject code in its own column ("FLO", "MTH") before the subject name.
            val cells = raw.trim().split(GAP).filter { it.isNotBlank() }
            val cleaned = if (cells.size >= 3 && CODE_CELL.matches(cells[0].trim()) && cells[1].any(Char::isLetter)) cells.drop(1) else cells
            val line = cleaned.joinToString(" ").replace(Regex("""\s{2,}"""), " ").trim()
            if (line.isEmpty()) continue
            if (TOTAL_ROW.containsMatchIn(line) && !Regex("""percent""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                val nums = Regex("""(?<![\d.])\d{2,4}(?![\d.])""").findAll(line).map { it.value.toInt() }.toList()
                if (nums.size >= 2) {
                    printedMax = nums.max()
                    printedTotal = nums.filter { it < printedMax }.maxOrNull() ?: 0
                } else if (nums.size == 1) {
                    printedTotal = nums[0]
                }
                continue
            }
            val m = SUBJECT_ROW.find(line) ?: continue
            val label = m.groupValues[1].trim(' ', '-', ':', ',', '.')
            if (label.count(Char::isLetter) < 3 || SKIP.containsMatchIn(label)) continue
            val nums = m.groupValues[2].trim().split(Regex("""\s+""")).mapNotNull { it.toIntOrNull() }
            if (nums.isEmpty() || nums.any { it > 200 }) continue
            val max = nums.filter { it in FULL_MARKS }.maxOrNull()?.takeIf { nums.size > 1 } ?: 100
            val rest = nums.toMutableList().apply { if (nums.size > 1) remove(max) }
            val obtained = rest.lastOrNull() ?: continue
            if (obtained > max) continue
            subjects += SubjectMark(tidy(label), max, obtained)
        }
        // One number on the total row that equals the full marks is the maximum, not the score.
        if (printedMax == 0 && printedTotal > 0 && printedTotal == subjects.sumOf { it.max }) {
            printedMax = printedTotal
            printedTotal = 0
        }
        return MarksResult(subjects, printedTotal, printedMax)
    }

    /** "FIRST LANGUAGE (ODIA)" → "First Language (Odia)". */
    private fun tidy(s: String) = s.lowercase().split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
        w.replaceFirstChar { it.uppercase() }.replace(Regex("""\((\w)""")) { "(" + it.groupValues[1].uppercase() }
    }
}
