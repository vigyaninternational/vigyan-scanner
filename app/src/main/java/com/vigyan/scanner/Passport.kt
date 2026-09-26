package com.vigyan.scanner

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
 * Passport photo studio: finds the face, crops a photo of the chosen size around it
 * (PassportMath), finds the person (for a new background colour) and adjusts brightness and
 * contrast. Runs on the phone through Google Play services.
 */
object Passport {

    class Result(val photo: Bitmap, val faceFound: Boolean)

    /** The cropped photo plus how sure the model is that each pixel is the person (null if unknown). */
    class Cut(val photo: Bitmap, val mask: FloatArray?, val faceFound: Boolean)

    /** Backgrounds on offer; null colour = keep the real background. */
    val BACKGROUNDS = listOf<Pair<String, Int?>>(
        "Original" to null,
        "White" to Color.WHITE,
        "Light blue" to Color.rgb(170, 205, 240),
        "Blue" to Color.rgb(40, 110, 200),
        "Red" to Color.rgb(200, 30, 40),
        "Light grey" to Color.rgb(225, 225, 225),
    )

    /** Share of the photo's height used by the name and date strip. */
    const val STRIP = 0.18f

    /**
     * [manual] = a crop drawn by hand: left, top and width as fractions of [source] (the height
     * follows from the photo's shape). Otherwise the crop is placed around the face.
     */
    suspend fun cut(source: Bitmap, size: PhotoSize, zoom: Float, shiftX: Float, shiftY: Float, manual: FloatArray? = null): Cut {
        val aspectHand = size.widthMm / size.heightMm
        if (manual != null) {
            val w = (manual[2] * source.width).coerceIn(10f, source.width.toFloat())
            val h = (w / aspectHand).coerceAtMost(source.height.toFloat())
            val ww = h * aspectHand
            val left = (manual[0] * source.width).coerceIn(0f, source.width - ww).toInt()
            val top = (manual[1] * source.height).coerceIn(0f, source.height - h).toInt()
            val cropped = Bitmap.createBitmap(source, left, top, ww.toInt().coerceIn(1, source.width - left), h.toInt().coerceIn(1, source.height - top))
            val photo = Bitmap.createScaledBitmap(cropped, size.widthPx, size.heightPx, true)
            if (cropped !== source && cropped !== photo) cropped.recycle()
            return Cut(photo, runCatching { personMask(photo) }.getOrNull(), true)
        }
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
        val aspect = size.widthMm / size.heightMm
        val c = if (face != null) {
            val b = face.boundingBox
            PassportMath.crop(b.left, b.top, b.right, b.bottom, source.width, source.height, zoom, aspect, size.headShare, shiftX, shiftY)
        } else {
            PassportMath.centerCrop(source.width, source.height, (size.widthMm * 10).toInt(), (size.heightMm * 10).toInt())
        }
        val left = c[0].coerceIn(0, source.width - 1)
        val top = c[1].coerceIn(0, source.height - 1)
        val cropped = Bitmap.createBitmap(source, left, top, c[2].coerceIn(1, source.width - left), c[3].coerceIn(1, source.height - top))
        val photo = Bitmap.createScaledBitmap(cropped, size.widthPx, size.heightPx, true)
        if (cropped !== source && cropped !== photo) cropped.recycle()
        return Cut(photo, runCatching { personMask(photo) }.getOrNull(), face != null)
    }

    private suspend fun personMask(photo: Bitmap): FloatArray? {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        val result = try {
            segmenter.process(InputImage.fromBitmap(photo, 0)).await()
        } finally {
            segmenter.close()
        }
        val buf = result.foregroundConfidenceMask ?: return null
        buf.rewind()
        val n = photo.width * photo.height
        if (buf.remaining() < n) return null
        return FloatArray(n) { buf.get().coerceIn(0f, 1f) }
    }

    /**
     * The final photo: [brightness] (-0.5..0.5) and [contrast] (0.5..1.5) applied to the person,
     * on the [background] colour (null = the real background, adjusted too).
     */
    fun render(cut: Cut, background: Int?, brightness: Float, contrast: Float): Bitmap {
        val w = cut.photo.width
        val h = cut.photo.height
        val px = IntArray(w * h)
        cut.photo.getPixels(px, 0, w, 0, 0, w, h)
        val add = brightness * 255f
        fun adj(v: Int) = ((v - 128) * contrast + 128 + add).coerceIn(0f, 255f)
        val mask = if (background != null) cut.mask else null
        val br = background?.let { Color.red(it).toFloat() } ?: 0f
        val bg = background?.let { Color.green(it).toFloat() } ?: 0f
        val bb = background?.let { Color.blue(it).toFloat() } ?: 0f
        for (i in px.indices) {
            val p = px[i]
            var r = adj((p shr 16) and 0xFF)
            var g = adj((p shr 8) and 0xFF)
            var b = adj(p and 0xFF)
            if (mask != null) {
                val a = mask[i]
                r = r * a + br * (1 - a)
                g = g * a + bg * (1 - a)
                b = b * a + bb * (1 - a)
            }
            px[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The photo on top and a white strip below with the name (bold) and the date, black text
     * shrunk to fit, the whole thing exactly [width]×[height] pixels.
     */
    fun withLabel(photo: Bitmap, name: String, date: String, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val photoH = (height * (1 - STRIP)).toInt()
        c.drawBitmap(photo, null, RectF(0f, 0f, width.toFloat(), photoH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val lines = listOf(name.trim().uppercase(), date.trim()).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return out
        val stripH = height - photoH
        val pad = width * 0.04f
        val paints = lines.mapIndexed { i, line ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                isFakeBoldText = i == 0
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, if (i == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                // As big as the strip allows, then smaller until the line fits the width.
                textSize = stripH * (if (lines.size == 2) 0.36f else 0.5f)
                while (textSize > 8f && measureText(line) > width - 2 * pad) textSize *= 0.92f
            }
        }
        val total = paints.sumOf { (it.textSize * 1.15f).toDouble() }.toFloat()
        var y = photoH + (stripH - total) / 2
        lines.forEachIndexed { i, line ->
            val p = paints[i]
            y += p.textSize * 1.15f
            c.drawText(line, width / 2f, y - p.textSize * 0.2f, p)
        }
        return out
    }

    /** 6 x 4 inch photo paper at 300 dpi, as many copies as fit, with thin cutting lines. */
    fun sheet4x6(photo: Bitmap, size: PhotoSize, marginMm: Float = 3f, gapMm: Float = 2f): Bitmap {
        val layout = PhotoSheet.layout(PhotoSheet.SIX_W, PhotoSheet.FOUR_H, size.widthMm, size.heightMm, marginMm, gapMm)
        val sheet = Bitmap.createBitmap(PassportMath.mmToPx(PhotoSheet.SIX_W), PassportMath.mmToPx(PhotoSheet.FOUR_H), Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        val pw = PassportMath.mmToPx(size.widthMm).toFloat()
        val ph = PassportMath.mmToPx(size.heightMm).toFloat()
        for ((x, y) in layout.positions) {
            val rect = RectF(PassportMath.mmToPx(x).toFloat(), PassportMath.mmToPx(y).toFloat(), 0f, 0f).apply { right = left + pw; bottom = top + ph }
            c.drawBitmap(photo, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(rect, border)
        }
        return sheet
    }

    /** A4 PDF with the photo at its real size, laid out by [layout] (millimetres). */
    fun a4Sheet(photo: Bitmap, layout: PhotoSheet.Layout): ByteArray {
        val image = Images.encode(withBorder(photo), 92)
        val k = 72f / 25.4f // points per mm
        val placements = layout.positions.map { (x, y) ->
            PdfWriter.Placement(image, x * k, y * k, layout.photoW * k, layout.photoH * k)
        }
        val out = ByteArrayOutputStream()
        PdfWriter().write(listOf(PdfWriter.Page(PhotoSheet.A4_W * k, PhotoSheet.A4_H * k, placements)), out)
        return out.toByteArray()
    }

    private fun withBorder(photo: Bitmap): Bitmap {
        val b = photo.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(b).drawRect(RectF(0f, 0f, b.width - 1f, b.height - 1f), Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f })
        return b
    }
}
