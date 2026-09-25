package com.vigyan.scanner

import kotlin.math.roundToInt

data class SubjectMark(val subject: String, val max: Int, val obtained: Int)

/** A marksheet as read (and corrected by the user). */
data class MarksRecord(
    val name: String = "",
    val roll: String = "",
    /** Gen / SC / ST / OBC / SEBC, or "". */
    val category: String = "",
    val subjects: List<SubjectMark> = emptyList(),
    /** The grand total printed on the marksheet, for cross-checking the sum (0 = none found). */
    val printedTotal: Int = 0,
    val printedMax: Int = 0,
) {
    val total get() = subjects.sumOf { it.obtained }
    val maxTotal get() = subjects.sumOf { it.max }
    val percent get() = if (maxTotal > 0) total * 100.0 / maxTotal else 0.0
}

/**
 * Reads subject marks from OCR row text (label and numbers on one row, see OcrLayout.rows):
 * "MATHEMATICS   100   30   88" → Mathematics, out of 100, 88 secured. Pure Kotlin, unit-tested.
 */
object MarksheetExtractor {

    private val SKIP = Regex(
        """total|grand|aggregate|percent|result|division|grade\s*point|roll|regd|registration|reg\.?\s*no|year|date|serial|\bsl\b|page|\bpin\b|birth|\bdob\b|centre|center|code|session|mobile|phone|school\s+name|examination""",
        RegexOption.IGNORE_CASE,
    )
    private val SUBJECT_ROW = Regex("""^(?:\d{1,2}[.)]?\s+)?([A-Za-z][A-Za-z .&()'/,\-]{1,48}?)\s*[:\-]?\s+((?:\d{1,3}\s*){1,6})(?:\s+.*)?$""")
    private val TOTAL_ROW = Regex("""\b(?:grand\s+total|total\s+marks|aggregate|total)\b""", RegexOption.IGNORE_CASE)
    private val FULL_MARKS = setOf(100, 50, 200, 150, 80, 75, 70, 60, 40, 30, 25, 20)

    fun extract(rows: String): MarksRecord {
        val subjects = mutableListOf<SubjectMark>()
        var printedTotal = 0
        var printedMax = 0
        for (raw in rows.lines()) {
            val line = raw.replace(Regex("""\s{2,}"""), " ").trim()
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
        return MarksRecord(subjects = subjects, printedTotal = printedTotal, printedMax = printedMax)
    }

    /** "FIRST LANGUAGE (ODIA)" → "First Language (Odia)". */
    private fun tidy(s: String) = s.lowercase().split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
        w.replaceFirstChar { it.uppercase() }.replace(Regex("""\((\w)""")) { "(" + it.groupValues[1].uppercase() }
    }
}

/** Ranks students for a merit list. */
object Merit {

    data class Entry(val id: String, val name: String, val roll: String, val category: String, val total: Int, val max: Int) {
        val percent get() = if (max > 0) total * 100.0 / max else 0.0
    }

    data class Ranked(val rank: Int, val entry: Entry)

    /**
     * Highest percentage first, then higher total, then name. Equal percentage and total share
     * a rank (1, 2, 2, 4).
     */
    fun rank(entries: List<Entry>): List<Ranked> {
        val sorted = entries.sortedWith(
            compareByDescending<Entry> { (it.percent * 100).roundToInt() }.thenByDescending { it.total }.thenBy { it.name.lowercase() },
        )
        val out = mutableListOf<Ranked>()
        sorted.forEachIndexed { i, e ->
            val prev = out.lastOrNull()
            val tied = prev != null && (prev.entry.percent * 100).roundToInt() == (e.percent * 100).roundToInt() && prev.entry.total == e.total
            out += Ranked(if (tied) prev!!.rank else i + 1, e)
        }
        return out
    }

    fun pct(p: Double) = String.format(java.util.Locale.US, "%.2f", p)
}
