package com.vigyan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Image quality for PDFs and JPGs: longest side in pixels and JPEG quality. */
enum class Quality(val label: String, val hint: String, val maxSide: Int, val jpeg: Int) {
    SMALL("Small", "WhatsApp / email", 1300, 55),
    NORMAL("Normal", "good for most", 2000, 75),
    HIGH("High", "best for printing", 4000, 92),
}

/** Bitmap helpers: resizing, rotating and importing photos/PDFs as page images. */
object Images {

    /** Decodes [file] no larger than [maxSide] and returns it as JPEG bytes with its size. */
    fun jpeg(file: File, quality: Quality): PdfWriter.Image {
        val bitmap = decode(file, quality.maxSide)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, out)
            return PdfWriter.Image(out.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }


    fun encode(bitmap: Bitmap, quality: Int): PdfWriter.Image {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return PdfWriter.Image(out.toByteArray(), bitmap.width, bitmap.height)
    }

    /** Black & white (grey) copy: smaller files for documents. */
    fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Centre-crops to the shape of [w]x[h] and scales to exactly that many pixels. */
    fun cropResize(src: Bitmap, w: Int, h: Int): Bitmap {
        val c = PassportMath.centerCrop(src.width, src.height, w, h)
        val cropped = Bitmap.createBitmap(src, c[0], c[1], c[2].coerceAtMost(src.width - c[0]), c[3].coerceAtMost(src.height - c[1]))
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /** Turned by [degrees] (any angle), with white filling the new corners. Returns [src] for 0. */
    fun rotateOnWhite(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        val r = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        val out = Bitmap.createBitmap(r.width().roundToInt().coerceAtLeast(1), r.height().roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** A see-through picture on white (for JPG, which has no transparency). */
    fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    /** [src] fitted (not cropped) and centred in exactly [w]×[h] pixels of white. */
    fun fitOnWhite(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val s = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s
        val dh = src.height * s
        android.graphics.Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    fun toCutout(bitmap: Bitmap): Cutout.Image {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Cutout.Image(px, bitmap.width, bitmap.height)
    }

    fun fromCutout(image: Cutout.Image): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

    /** Width and height of the image in [file], without loading it. */
    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    fun decode(file: File, maxSide: Int): Bitmap {
        val (w, h) = size(file)
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not open ${file.name}")
        return fit(bitmap, maxSide)
    }

    private fun fit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun save(bitmap: Bitmap, dest: File, quality: Int = 92) {
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** Turns the page image a quarter turn clockwise, in place. */
    fun rotate(file: File) {
        val bitmap = decode(file, 5000)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
        save(rotated, file)
        bitmap.recycle()
        rotated.recycle()
    }

    /**
     * Photos and PDFs shared from WhatsApp, the gallery or Files → JPEG page images in [dir].
     * Each PDF page becomes one image.
     */
    fun import(context: Context, uris: List<Uri>, dir: File): List<File> {
        dir.mkdirs()
        val out = mutableListOf<File>()
        var n = 0
        for (uri in uris) {
            val type = context.contentResolver.getType(uri).orEmpty()
            val isPdf = type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")
            if (isPdf) {
                val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the PDF")
                try {
                    val pdf = PdfRenderer(fd)
                    try {
                        for (i in 0 until pdf.pageCount) {
                            val page = pdf.openPage(i)
                            try {
                                // About 180 dpi, capped so big pages don't run out of memory.
                                val scale = minOf(2.5f, 2200f / max(page.width, page.height))
                                val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt(), (page.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                out += File(dir, "import_${n++}.jpg").also { save(bitmap, it) }
                                bitmap.recycle()
                            } finally {
                                page.close()
                            }
                        }
                    } finally {
                        pdf.close()
                    }
                } catch (e: SecurityException) {
                    error("This PDF is password-protected. Open it in a PDF app first.")
                } finally {
                    fd.close()
                }
            } else {
                val bitmap = decodeUri(context, uri, 3000)
                out += File(dir, "import_${n++}.jpg").also { save(bitmap, it) }
                bitmap.recycle()
            }
        }
        return out
    }

    fun decodeUri(context: Context, uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's rotation (EXIF).
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val s = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not open the image")
        return fit(bitmap, maxSide)
    }
}
