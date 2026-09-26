package com.vigyan.scanner

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class PdfWriterTest {

    // A 40x56 checkerboard JPEG (made once with ImageIO; java.awt is not available in Android unit tests).
    private val jpegBytes = Base64.getDecoder().decode(JPEG_BASE64)

    companion object {
        const val JPEG_BASE64 = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAA4ACgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+vl+ivqCvT/3L+9zfLb7+5w/7z5WCvl+ivqCj/cv73N8tvv7h/vPlYKK+X6KP7O/vfh/wQ+uf3fxPqCvl+ivqCj/AHL+9zfLb7+4f7z5WCvl+ivqCj/cv73N8tvv7h/vPlYKK+X6KP7O/vfh/wAEPrn938T6gr5for6go/3L+9zfLb7+4f7z5WCvl+ivqCj/AHL+9zfLb7+4f7z5WCivl+ij+zv734f8EPrn938T6gr5foooy77Xy/UMZ9n5n1BXy/RRRl32vl+oYz7PzPqCiiivMO4//9k="
    }

    private fun jpeg(): PdfWriter.Image = PdfWriter.Image(jpegBytes, 40, 56)

    private fun pdf(password: String? = null): ByteArray {
        val image = jpeg()
        // OCR boxes from a 800x1120 original: the PDF image is a smaller copy.
        val lines = listOf(
            OcrLine("Vigyan International", 80, 100, 700, 160),
            OcrLine("Name: Ravi (Sahu)", 80, 300, 600, 350),
        )
        val pages = listOf(
            PdfWriter.Page(595f, 833f, listOf(PdfWriter.Placement(image, 0f, 0f, 595f, 833f, lines, 800, 1120))),
            PdfWriter.Page(595f, 842f, listOf(
                PdfWriter.Placement(image, 100f, 60f, 153f, 214f),
                PdfWriter.Placement(image, 100f, 320f, 153f, 214f, listOf(OcrLine("Back side", 2, 2, 38, 10))),
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
