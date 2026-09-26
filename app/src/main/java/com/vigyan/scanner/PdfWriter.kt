package com.vigyan.scanner

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.Deflater

/**
 * A small PDF writer made for scans. Pages hold JPEG images (stored as they are, so the file
 * size is whatever JPEG quality was chosen) with an invisible OCR text layer on top. That makes
 * the PDF searchable and lets you copy text from it in any viewer, Google Drive included. It can
 * also lock the PDF with an open password (standard 128-bit encryption).
 *
 * Plain Kotlin/JVM, so it is unit-tested (PdfWriterTest opens the output with PDFBox).
 */
class PdfWriter {

    class Image(val jpeg: ByteArray, val pixelWidth: Int, val pixelHeight: Int)

    /**
     * [image] drawn at [x],[y] (points, from the page's top-left) with size [w]×[h]. [lines] are
     * OCR boxes in a source image of [srcWidth]×[srcHeight] pixels (the original page, which may
     * be larger than the re-compressed [image]).
     */
    class Placement(
        val image: Image,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val lines: List<OcrLine> = emptyList(),
        val srcWidth: Int = image.pixelWidth,
        val srcHeight: Int = image.pixelHeight,
    )

    /** [footer]: visible text centred at the bottom (e.g. "Page 2 of 5"), on a small white box. */
    class Page(val width: Float, val height: Float, val placements: List<Placement>, val footer: String? = null)

    fun write(pages: List<Page>, out: OutputStream, password: String? = null) {
        require(pages.isNotEmpty()) { "No pages" }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val security = password?.takeIf { it.isNotEmpty() }?.let { Security(it, id) }

        var next = 1
        val bodies = sortedMapOf<Int, ByteArray>()
        val catalog = next++
        val pagesObj = next++
        val font = next++
        val pageNums = mutableListOf<Int>()

        for (page in pages) {
            val pageNum = next++
            val contentNum = next++
            val imageNums = page.placements.map { next++ }
            pageNums += pageNum

            page.placements.forEachIndexed { i, p ->
                bodies[imageNums[i]] = stream(
                    imageNums[i],
                    "/Type /XObject /Subtype /Image /Width ${p.image.pixelWidth} /Height ${p.image.pixelHeight} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode",
                    p.image.jpeg,
                    security,
                )
            }
            bodies[contentNum] = stream(contentNum, "/Filter /FlateDecode", deflate(content(page)), security)
            val xobjects = imageNums.mapIndexed { i, n -> "/Im$i $n 0 R" }.joinToString(" ")
            bodies[pageNum] = latin1(
                "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${num(page.width)} ${num(page.height)}] " +
                    "/Resources << /Font << /F1 $font 0 R >> /XObject << $xobjects >> >> /Contents $contentNum 0 R >>",
            )
        }
        bodies[font] = latin1("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        bodies[pagesObj] = latin1("<< /Type /Pages /Kids [${pageNums.joinToString(" ") { "$it 0 R" }}] /Count ${pageNums.size} >>")
        bodies[catalog] = latin1("<< /Type /Catalog /Pages $pagesObj 0 R >>")
        val encryptNum = security?.let { s -> (next++).also { bodies[it] = latin1(s.dictionary()) } }

        val buf = ByteArrayOutputStream()
        buf.write(latin1("%PDF-1.4\n"))
        buf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val offsets = IntArray(next)
        for ((n, body) in bodies) {
            offsets[n] = buf.size()
            buf.write(latin1("$n 0 obj\n"))
            buf.write(body)
            buf.write(latin1("\nendobj\n"))
        }
        val xref = buf.size()
        val sb = StringBuilder("xref\n0 $next\n0000000000 65535 f \n")
        for (n in 1 until next) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
        val hexId = id.joinToString("") { "%02X".format(it) }
        sb.append("trailer\n<< /Size $next /Root $catalog 0 R /ID [<$hexId> <$hexId>]")
        if (encryptNum != null) sb.append(" /Encrypt $encryptNum 0 R")
        sb.append(" >>\nstartxref\n$xref\n%%EOF\n")
        buf.write(latin1(sb.toString()))
        buf.writeTo(out)
    }

    private fun content(page: Page): ByteArray {
        val sb = StringBuilder()
        page.placements.forEachIndexed { i, p ->
            val bottom = page.height - p.y - p.h
            sb.append("q ${num(p.w)} 0 0 ${num(p.h)} ${num(p.x)} ${num(bottom)} cm /Im$i Do Q\n")
            if (p.lines.isEmpty() || p.srcWidth <= 0 || p.srcHeight <= 0) return@forEachIndexed
            val sx = p.w / p.srcWidth
            val sy = p.h / p.srcHeight
            // Text render mode 3 = invisible: it is there for search / copy, the image shows the page.
            sb.append("BT 3 Tr\n")
            for (line in p.lines) {
                val text = winAnsi(line.text)
                if (text.isBlank()) continue
                val lw = (line.right - line.left) * sx
                val lh = (line.bottom - line.top) * sy
                if (lw < 1f || lh < 1f) continue
                val size = lh * 0.8f
                val natural = textWidth(text) * size / 1000f
                val scale = if (natural > 0f) 100f * lw / natural else 100f
                val x = p.x + line.left * sx
                val baseline = page.height - (p.y + line.bottom * sy) + lh * 0.2f
                sb.append("/F1 ${num(size)} Tf ${num(scale)} Tz 1 0 0 1 ${num(x)} ${num(baseline)} Tm (${escape(text)}) Tj\n")
            }
            sb.append("ET\n")
        }
        page.footer?.let { f ->
            val text = winAnsi(f)
            val size = 9f
            val w = textWidth(text) * size / 1000f
            val x = (page.width - w) / 2
            sb.append("q 1 1 1 rg ${num(x - 5)} 9 ${num(w + 10)} ${num(size + 6)} re f Q\n")
            // Text state (render mode, scaling) carries over from the invisible layer: reset it.
            sb.append("q BT 0 Tr 100 Tz 0 0 0 rg /F1 ${num(size)} Tf 1 0 0 1 ${num(x)} 13 Tm (${escape(text)}) Tj ET Q\n")
        }
        return latin1(sb.toString())
    }

    private fun stream(objNum: Int, dict: String, data: ByteArray, security: Security?): ByteArray {
        val bytes = security?.encrypt(objNum, data) ?: data
        val out = ByteArrayOutputStream()
        out.write(latin1("<< $dict /Length ${bytes.size} >>\nstream\n"))
        out.write(bytes)
        out.write(latin1("\nendstream"))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private companion object {
        fun num(v: Float) = String.format(Locale.US, "%.2f", v)
        fun latin1(s: String) = s.toByteArray(Charsets.ISO_8859_1)

        /** Keeps characters the standard Helvetica font can show (Latin-1); others become spaces. */
        fun winAnsi(s: String) = s.map { c -> if (c.code in 32..126 || c.code in 160..255) c else ' ' }.joinToString("")

        fun escape(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

        /** Helvetica advance widths (per 1000 em) for ASCII 32..126. */
        private val WIDTHS = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
        )

        fun textWidth(s: String) = s.sumOf { c -> if (c.code in 32..126) WIDTHS[c.code - 32] else 556 }.toFloat()
    }

    /** PDF standard security handler, revision 3 (RC4, 128-bit key): an open password. */
    private class Security(password: String, private val id: ByteArray) {
        private val o: ByteArray
        private val u: ByteArray
        private val key: ByteArray
        private val permissions = -4 // everything allowed once opened

        init {
            val pw = pad(password)
            // Algorithm 3: O entry (owner password = the same password).
            var h = md5(pw)
            repeat(50) { h = md5(h) }
            o = rc4Rounds(h.copyOf(16), pw)
            // Algorithm 2: the file key.
            val p = byteArrayOf(permissions.toByte(), (permissions shr 8).toByte(), (permissions shr 16).toByte(), (permissions shr 24).toByte())
            var k = md5(pw + o + p + id)
            repeat(50) { k = md5(k.copyOf(16)) }
            key = k.copyOf(16)
            // Algorithm 5: U entry.
            u = rc4Rounds(key, md5(PADDING + id)) + ByteArray(16)
        }

        fun dictionary() = "<< /Filter /Standard /V 2 /R 3 /Length 128 /P $permissions /O <${hex(o)}> /U <${hex(u)}> >>"

        fun encrypt(objNum: Int, data: ByteArray): ByteArray {
            val objKey = md5(key + byteArrayOf(objNum.toByte(), (objNum shr 8).toByte(), (objNum shr 16).toByte(), 0, 0)).copyOf(16)
            return rc4(objKey, data)
        }

        private fun rc4Rounds(baseKey: ByteArray, data: ByteArray): ByteArray {
            var out = rc4(baseKey, data)
            for (i in 1..19) out = rc4(ByteArray(baseKey.size) { j -> (baseKey[j].toInt() xor i).toByte() }, out)
            return out
        }

        private companion object {
            val PADDING = intArrayOf(
                0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
                0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
            ).map { it.toByte() }.toByteArray()

            fun pad(password: String): ByteArray {
                val bytes = password.toByteArray(Charsets.ISO_8859_1).take(32).toByteArray()
                return bytes + PADDING.copyOf(32 - bytes.size)
            }

            fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)

            fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

            fun rc4(key: ByteArray, data: ByteArray): ByteArray {
                val s = IntArray(256) { it }
                var j = 0
                for (i in 0 until 256) {
                    j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                    val t = s[i]; s[i] = s[j]; s[j] = t
                }
                var i = 0
                j = 0
                return ByteArray(data.size) { n ->
                    i = (i + 1) and 0xFF
                    j = (j + s[i]) and 0xFF
                    val t = s[i]; s[i] = s[j]; s[j] = t
                    (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
                }
            }
        }
    }
}
