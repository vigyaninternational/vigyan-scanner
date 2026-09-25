package com.vigyan.scanner

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfWriterTest {

    private fun jpeg(w: Int, h: Int): PdfWriter.Image {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, if ((x / 20 + y / 20) % 2 == 0) 0xFFFFFF else 0x3366AA)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return PdfWriter.Image(out.toByteArray(), w, h)
    }

    private fun pdf(password: String? = null): ByteArray {
        val image = jpeg(400, 560)
        // OCR boxes from a 800x1120 original: the PDF image is a smaller copy.
        val lines = listOf(
            OcrLine("Vigyan International", 80, 100, 700, 160),
            OcrLine("Name: Ravi (Sahu)", 80, 300, 600, 350),
        )
        val pages = listOf(
            PdfWriter.Page(595f, 833f, listOf(PdfWriter.Placement(image, 0f, 0f, 595f, 833f, lines, 800, 1120))),
            PdfWriter.Page(595f, 842f, listOf(
                PdfWriter.Placement(image, 100f, 60f, 153f, 214f),
                PdfWriter.Placement(image, 100f, 320f, 153f, 214f, listOf(OcrLine("Back side", 10, 10, 300, 60))),
            )),
        )
        val out = ByteArrayOutputStream()
        PdfWriter().write(pages, out, password)
        return out.toByteArray()
    }

    @Test
    fun searchableText() {
        PDDocument.load(pdf()).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text, text.contains("Vigyan International"))
            assertTrue(text, text.contains("Name: Ravi (Sahu)"))
            assertTrue(text, text.contains("Back side"))
        }
    }

    @Test
    fun passwordProtected() {
        val bytes = pdf("vigyan123")
        try {
            PDDocument.load(bytes).close()
            fail("Opened without the password")
        } catch (_: InvalidPasswordException) {
        }
        PDDocument.load(bytes, "vigyan123").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(2, doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("Vigyan International"))
        }
    }
}
