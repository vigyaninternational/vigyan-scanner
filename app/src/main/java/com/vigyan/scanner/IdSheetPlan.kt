package com.vigyan.scanner

/**
 * Where each ID card side goes on A4 sheets (all in millimetres). Every side keeps its own shape:
 * a landscape card (Aadhaar, PAN) is 85.6 × 54 mm, a portrait card (voter ID) 54 × 85.6 mm, times
 * [scale]. With [pairs], two sides per row so a card's front and back sit side by side; without,
 * as many as fit across. Rows go down the sheet from the top; a new sheet starts when one is full.
 * Pure Kotlin, unit-tested.
 */
object IdSheetPlan {

    const val CARD_LONG = 85.6f
    const val CARD_SHORT = 54f

    /** One card side: on sheet [sheet] (0, 1, …), top-left at ([x], [y]), [w] × [h]. */
    data class Box(val sheet: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    fun plan(
        portrait: List<Boolean>, pageW: Float, pageH: Float, scale: Float = 1f, pairs: Boolean = true,
        margin: Float = 10f, gap: Float = 8f,
    ): List<Box> {
        val sizes = portrait.map { p -> if (p) (CARD_SHORT * scale) to (CARD_LONG * scale) else (CARD_LONG * scale) to (CARD_SHORT * scale) }
        val usableW = pageW - 2 * margin
        // Rows of card indexes.
        val rows = mutableListOf<MutableList<Int>>()
        for (i in sizes.indices) {
            val row = rows.lastOrNull()
            val fits = row != null && if (pairs) row.size < 2 else row.sumOf { sizes[it].first.toDouble() } + gap * row.size + sizes[i].first <= usableW
            if (fits) row!!.add(i) else rows.add(mutableListOf(i))
        }
        val out = arrayOfNulls<Box>(sizes.size)
        var sheet = 0
        var y = margin
        var rowsOnSheet = 0
        for (row in rows) {
            val rowH = row.maxOf { sizes[it].second }
            if (rowsOnSheet > 0 && y + rowH > pageH - margin) { sheet++; y = margin; rowsOnSheet = 0 }
            // With pairs, columns are as wide as a card's long side, so fronts line up down the sheet.
            val cellW = if (pairs) row.map { CARD_LONG * scale } else row.map { sizes[it].first }
            val rowW = cellW.sum() + gap * (row.size - 1)
            var x = (pageW - rowW) / 2
            row.forEachIndexed { k, i ->
                val (w, h) = sizes[i]
                out[i] = Box(sheet, x + (cellW[k] - w) / 2, y + (rowH - h) / 2, w, h)
                x += cellW[k] + gap
            }
            y += rowH + gap
            rowsOnSheet++
        }
        return out.map { it!! }
    }

    /** How many A4 sheets the plan needs. */
    fun sheets(plan: List<Box>) = (plan.maxOfOrNull { it.sheet } ?: -1) + 1
}
