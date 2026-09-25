package com.vigyan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream

/**
 * Passport photo maker: finds the face, crops a 35 x 45 mm photo around it (PassportMath) and
 * can turn the background white. Runs on the phone through Google Play services.
 */
object Passport {

    class Result(val photo: Bitmap, val faceFound: Boolean)

    suspend fun make(source: Bitmap, whiteBackground: Boolean, zoom: Float): Result {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build(),
        )
        val faces = try {
            detector.process(InputImage.fromBitmap(source, 0)).await()
        } finally {
            detector.close()
        }
        // The biggest face is the student.
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val c = if (face != null) {
            val b = face.boundingBox
            PassportMath.crop(b.left, b.top, b.right, b.bottom, source.width, source.height, zoom)
        } else {
            PassportMath.centerCrop(source.width, source.height)
        }
        val left = c[0].coerceIn(0, source.width - 1)
        val top = c[1].coerceIn(0, source.height - 1)
        val cropped = Bitmap.createBitmap(source, left, top, c[2].coerceAtMost(source.width - left), c[3].coerceAtMost(source.height - top))
        var photo = Bitmap.createScaledBitmap(cropped, PassportMath.WIDTH_PX, PassportMath.HEIGHT_PX, true)
        if (cropped !== source && cropped !== photo) cropped.recycle()
        if (whiteBackground) photo = whiten(photo)
        return Result(photo, face != null)
    }

    /** Replaces everything that isn't the person with white (soft edges from the confidence mask). */
    private suspend fun whiten(photo: Bitmap): Bitmap {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        val result = try {
            segmenter.process(InputImage.fromBitmap(photo, 0)).await()
        } finally {
            segmenter.close()
        }
        val mask = result.foregroundConfidenceMask ?: return photo
        val w = photo.width
        val h = photo.height
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        mask.rewind()
        for (i in px.indices) {
            if (!mask.hasRemaining()) break
            val a = mask.get().coerceIn(0f, 1f)
            val p = px[i]
            val r = ((p shr 16) and 0xFF) * a + 255 * (1 - a)
            val g = ((p shr 8) and 0xFF) * a + 255 * (1 - a)
            val b = (p and 0xFF) * a + 255 * (1 - a)
            px[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        photo.recycle()
        return out
    }

    /** 6 x 4 inch photo paper at 300 dpi with 8 copies (4 x 2) and thin cutting lines. */
    fun sheet4x6(photo: Bitmap): Bitmap {
        val sheet = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.WHITE)
        val gapX = (1800 - 4 * PassportMath.WIDTH_PX) / 5f
        val gapY = (1200 - 2 * PassportMath.HEIGHT_PX) / 3f
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        for (row in 0 until 2) for (col in 0 until 4) {
            val x = gapX + col * (PassportMath.WIDTH_PX + gapX)
            val y = gapY + row * (PassportMath.HEIGHT_PX + gapY)
            c.drawBitmap(photo, x, y, null)
            c.drawRect(RectF(x, y, x + PassportMath.WIDTH_PX, y + PassportMath.HEIGHT_PX), border)
        }
        return sheet
    }

    /** A4 PDF with 30 copies (5 x 6) at the real 35 x 45 mm size. */
    fun a4Sheet(photo: Bitmap): ByteArray {
        val image = Images.encode(withBorder(photo), 92)
        val w = 35f / 25.4f * 72f // 35 mm in points
        val h = 45f / 25.4f * 72f
        val gapX = (595f - 5 * w) / 6
        val gapY = (842f - 6 * h) / 7
        val placements = (0 until 30).map { i ->
            PdfWriter.Placement(image, gapX + (i % 5) * (w + gapX), gapY + (i / 5) * (h + gapY), w, h)
        }
        val out = ByteArrayOutputStream()
        PdfWriter().write(listOf(PdfWriter.Page(595f, 842f, placements)), out)
        return out.toByteArray()
    }

    private fun withBorder(photo: Bitmap): Bitmap {
        val b = photo.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(b).drawRect(RectF(0f, 0f, b.width - 1f, b.height - 1f), Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f })
        return b
    }
}
