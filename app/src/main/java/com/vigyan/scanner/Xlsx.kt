package com.vigyan.scanner

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal Excel (.xlsx) writer: plain text and number cells, with a bold first row. Enough for
 * tables read from scans and filled forms. Pure JVM, unit-tested.
 */
object Xlsx {

    class Sheet(val name: String, val rows: List<List<String>>, val boldHeader: Boolean = true)

    fun write(sheets: List<Sheet>, out: OutputStream) {
        require(sheets.isNotEmpty()) { "No sheets" }
        val names = mutableListOf<String>()
        sheets.forEach { names += uniqueSheetName(it.name, names) }
        ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                    sheets.indices.joinToString("") {
                        """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                    } +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                    "</Relationships>",
            )
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    names.mapIndexed { i, n -> """<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    sheets.indices.joinToString("") {
                        """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>"""
                    } +
                    """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
                    "</Relationships>",
            )
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        val widest = sheet.rows.maxOfOrNull { it.size } ?: 0
        if (widest > 0) {
            // Columns wide enough for their longest text (up to a limit).
            sb.append("<cols>")
            for (c in 0 until widest) {
                val len = sheet.rows.maxOf { it.getOrNull(c)?.length ?: 0 }
                sb.append("""<col min="${c + 1}" max="${c + 1}" width="${(len + 2).coerceIn(8, 60)}" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                if (value.isEmpty()) return@forEachIndexed
                val ref = column(c) + (r + 1)
                val style = if (sheet.boldHeader && r == 0) """ s="1"""" else ""
                if (isNumber(value) && !(sheet.boldHeader && r == 0)) {
                    sb.append("""<c r="$ref"$style><v>$value</v></c>""")
                } else {
                    sb.append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(value)}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, ('A' + m))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /**
     * Only plain numbers become number cells. Phone, Aadhaar and roll numbers (long, or with a
     * leading zero) stay text, so Excel doesn't turn them into 9.87E+09 or drop the zero.
     */
    fun isNumber(v: String) = Regex("""-?(0|[1-9]\d{0,8})(\.\d{1,6})?""").matches(v)

    private fun uniqueSheetName(name: String, taken: List<String>): String {
        val base = name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().ifBlank { "Sheet" }.take(28)
        var n = base
        var k = 2
        while (taken.any { it.equals(n, ignoreCase = true) }) n = "$base ${k++}"
        return n
    }

    /** Splits rows read from a scan ("Name   Ravi   Class   XI") into cells. */
    fun cells(rowsText: String): List<List<String>> =
        rowsText.lines().map { line -> line.split(Regex("""\t|\s{3,}""")).map { it.trim() } }
            .filter { row -> row.any { it.isNotEmpty() } }

    private fun esc(s: String) = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 32 && ch != '\t' && ch != '\n' && ch != '\r' -> append(' ') // not allowed in XML
                else -> append(ch)
            }
        }
    }

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            "</styleSheet>"
}
