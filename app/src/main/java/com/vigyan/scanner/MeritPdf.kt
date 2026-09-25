package com.vigyan.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Printable merit list (A4): the overall list, then one list per category. */
object MeritPdf {

    private const val W = 595
    private const val H = 842
    private const val MARGIN = 36f

    private class Col(val title: String, val width: Float, val right: Boolean = false)

    private val COLS = listOf(
        Col("Rank", 40f), Col("Name", 190f), Col("Roll no.", 80f), Col("Category", 60f),
        Col("Total", 50f, true), Col("Out of", 50f, true), Col("%", 53f, true),
    )

    fun write(context: Context, title: String, ranked: List<Merit.Ranked>, file: File) {
        val college = Branding(context).collegeName
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 9.5f; color = Color.BLACK }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.BLACK }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page!!.canvas
            val c = canvas!!
            val center = Paint(bold).apply { textAlign = Paint.Align.CENTER; textSize = 13f }
            c.drawText(college, W / 2f, MARGIN + 6, center)
            center.textSize = 11f
            c.drawText(title, W / 2f, MARGIN + 22, center)
            val small = Paint(normal).apply { textSize = 8f; textAlign = Paint.Align.RIGHT }
            c.drawText("Page $pageNo · " + SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()), W - MARGIN, MARGIN + 36, small)
            y = MARGIN + 50
        }

        fun row(values: List<String>, paint: Paint) {
            if (canvas == null || y > H - MARGIN) {
                newPage()
                row(COLS.map { it.title }, bold)
            }
            val c = canvas!!
            var x = MARGIN
            COLS.forEachIndexed { i, col ->
                var v = values.getOrElse(i) { "" }
                while (v.length > 1 && paint.measureText(v) > col.width - 4) v = v.dropLast(1)
                if (col.right) {
                    paint.textAlign = Paint.Align.RIGHT
                    c.drawText(v, x + col.width - 4, y, paint)
                } else {
                    paint.textAlign = Paint.Align.LEFT
                    c.drawText(v, x, y, paint)
                }
                x += col.width
            }
            paint.textAlign = Paint.Align.LEFT
            c.drawLine(MARGIN, y + 4, W - MARGIN, y + 4, line)
            y += 15f
        }

        fun section(heading: String, list: List<Merit.Ranked>) {
            if (canvas == null || y > H - MARGIN - 60) newPage()
            y += 6f
            canvas!!.drawText(heading, MARGIN, y, Paint(bold).apply { textSize = 11f })
            y += 16f
            row(COLS.map { it.title }, bold)
            list.forEach { r ->
                val e = r.entry
                row(listOf(r.rank.toString(), e.name, e.roll, e.category, e.total.toString(), e.max.toString(), Merit.pct(e.percent)), normal)
            }
            y += 10f
        }

        section("Overall merit list (${ranked.size} students)", ranked)
        ranked.map { it.entry }.groupBy { it.category }.filterKeys { it.isNotBlank() }.toSortedMap().forEach { (cat, entries) ->
            section("Category: $cat (${entries.size})", Merit.rank(entries))
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
