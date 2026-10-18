package com.vigyan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A message for the person (not an error report): shown as it is, without "Failed:". */
class Notice(message: String) : Exception(message)

/**
 * The "Convert files" tool: photos ↔ PDF, file size, merge, pick pages, picture format.
 * PDF pages are read with Android's PdfRenderer and written with [PdfWriter] (pages become
 * pictures). Pages are made one at a time and every bitmap is recycled straight away.
 */
object Convert {

    const val LOCKED = "This PDF is locked with a password. Open it in a PDF app, save a copy without the password, and try again."

    /** Working folder (shared through the FileProvider as "convert"). */
    fun dir(context: Context) = File(context.cacheDir, "convert").apply { mkdirs() }

    /** A fresh, empty folder for the files of one save / share. */
    fun outDir(context: Context) = File(dir(context), "out").apply {
        deleteRecursively()
        mkdirs()
    }

    // ---------------- Reading ----------------

    /** Name and size of a picked file. */
    fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                }
            }
        }
        return name to size
    }

    fun isPdf(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri).orEmpty()
        return type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")
    }

    /** Copies a picked file into the working folder (it stays readable after the picker closes). */
    fun copyIn(context: Context, uri: Uri, name: String): File {
        val dest = File(dir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: error("Could not open the file")
        return dest
    }

    /** Opens [file] with PdfRenderer, runs [block], and closes it. A password-locked PDF gives a [Notice]. */
    fun <T> withPdf(file: File, block: (PdfRenderer) -> T): T {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close()
            throw Notice(LOCKED)
        } catch (e: Exception) {
            fd.close()
            throw Notice("This file could not be opened as a PDF. It may be damaged.")
        }
        try {
            return block(renderer)
        } finally {
            renderer.close() // also closes fd
        }
    }

    /** Size of every page in points. */
    fun pageSizes(file: File): List<Pair<Int, Int>> = withPdf(file) { r ->
        (0 until r.pageCount).map { i -> r.openPage(i).useAndClose { it.width to it.height } }
    }

    /** Page [index] as a picture on white, [w]×[h] pixels. */
    fun renderPage(renderer: PdfRenderer, index: Int, w: Int, h: Int): Bitmap {
        renderer.openPage(index).useAndClose { page ->
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /** Page [index] at [dpi] (kept under ConvertMath.renderCap), plus its size in points. */
    fun renderAtDpi(renderer: PdfRenderer, index: Int, dpi: Int): Pair<Bitmap, Pair<Int, Int>> {
        val pt = renderer.openPage(index).useAndClose { it.width to it.height }
        val (w, h) = ConvertMath.renderSize(pt.first, pt.second, dpi)
        return renderPage(renderer, index, w, h) to pt
    }

    /** Small JPEG pictures of every page (for ticking pages), written to files. */
    fun thumbnails(file: File, tag: String, progress: (String) -> Unit): List<File> {
        val thumbs = File(file.parentFile, "thumbs_$tag").apply { deleteRecursively(); mkdirs() }
        return withPdf(file) { r ->
            (0 until r.pageCount).map { i ->
                if (r.pageCount > 4) progress("Reading page ${i + 1} of ${r.pageCount}…")
                val pt = r.openPage(i).useAndClose { it.width to it.height }
                val s = 240f / max(1, max(pt.first, pt.second))
                val bmp = renderPage(r, i, max(1, (pt.first * s).roundToInt()), max(1, (pt.second * s).roundToInt()))
                File(thumbs, "p$i.jpg").also { Images.save(bmp, it, 80); bmp.recycle() }
            }
        }
    }

    /** A picked photo, upright, no larger than [maxSide], turned [turns] quarter turns, see-through parts on white. */
    fun loadPhoto(context: Context, uri: Uri, maxSide: Int, turns: Int = 0, white: Boolean = true): Bitmap {
        var bmp = Images.decodeUri(context, uri, maxSide)
        if (white && bmp.hasAlpha()) bmp = Images.onWhite(bmp).also { bmp.recycle() }
        return turn(bmp, turns)
    }

    /** [src] turned [turns] quarter turns clockwise (recycles [src] when a new bitmap is made). */
    fun turn(src: Bitmap, turns: Int): Bitmap {
        val t = Math.floorMod(turns, 4)
        if (t == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f * t) }, true)
        if (out !== src) src.recycle()
        return out
    }

    // ---------------- Writing ----------------

    @Suppress("DEPRECATION")
    fun encode(bitmap: Bitmap, format: ConvertMath.PicFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val fmt = when (format) {
            ConvertMath.PicFormat.JPG -> Bitmap.CompressFormat.JPEG
            ConvertMath.PicFormat.PNG -> Bitmap.CompressFormat.PNG
            ConvertMath.PicFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(fmt, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /** [bitmap] scaled by [scale] (a new bitmap; the caller recycles it unless it is [bitmap]). */
    private fun scaled(bitmap: Bitmap, scale: Float): Bitmap =
        if (scale >= 1f) bitmap
        else Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)

    /** The best JPEG of [bitmap] under [maxBytes] (smaller quality first, then fewer pixels). */
    fun fitJpeg(bitmap: Bitmap, maxBytes: Int, minBytes: Int = 0): Pair<PdfWriter.Image, Boolean> {
        val r = SizeFit.fit(maxBytes, minBytes) { scale, q ->
            val s = scaled(bitmap, scale)
            Images.encode(s, q).jpeg.also { if (s !== bitmap) s.recycle() }
        }
        val w = max(1, (bitmap.width * r.scale).toInt())
        val h = max(1, (bitmap.height * r.scale).toInt())
        return PdfWriter.Image(r.bytes, w, h) to r.fits
    }

    fun writePdf(pages: List<PdfWriter.Page>, dest: File): File {
        dest.outputStream().use { PdfWriter().write(pages, it) }
        return dest
    }

    fun zip(files: List<File>, dest: File): File {
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            val taken = mutableSetOf<String>()
            for (f in files) {
                val name = ConvertMath.uniqueFileName(f.name, taken).also { taken += it }
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return dest
    }

    // ---------------- 1. Photos → PDF ----------------

    class PhotoIn(val uri: Uri, val turns: Int)

    /** Returns the PDF and, when a KB limit could not be met, a note. */
    fun photosToPdf(
        context: Context,
        photos: List<PhotoIn>,
        a4: Boolean,
        quality: ConvertMath.PdfQuality,
        maxBytes: Int?,
        dest: File,
        progress: (String) -> Unit,
    ): Pair<File, String?> {
        val pages = mutableListOf<PdfWriter.Page>()
        var used = 0
        var fits = true
        photos.forEachIndexed { i, p ->
            progress("Adding photo ${i + 1} of ${photos.size}…")
            val bmp = loadPhoto(context, p.uri, quality.maxSide, p.turns)
            val image = try {
                if (maxBytes != null) {
                    val budget = ConvertMath.pageBudget(maxBytes, used, photos.size - i, photos.size)
                    fitJpeg(bmp, budget).also { if (!it.second) fits = false }.first
                } else {
                    Images.encode(bmp, quality.jpeg)
                }
            } finally {
                bmp.recycle()
            }
            used += image.jpeg.size
            val plan = ConvertMath.placePage(image.pixelWidth, image.pixelHeight, a4)
            pages += PdfWriter.Page(plan.pageW, plan.pageH, listOf(PdfWriter.Placement(image, plan.x, plan.y, plan.w, plan.h)))
        }
        writePdf(pages, dest)
        val note = if (maxBytes != null && (!fits || dest.length() > maxBytes)) {
            "Could not get it under ${maxBytes / 1024} KB (it is ${ConvertMath.sizeText(dest.length())}). Try fewer photos."
        } else null
        return dest to note
    }

    // ---------------- 2. PDF → photos ----------------

    fun pdfToPhotos(
        pdf: File,
        base: String,
        pages: List<Int>,
        format: ConvertMath.PicFormat,
        dpi: Int,
        maxBytesEach: Int?,
        outDir: File,
        progress: (String) -> Unit,
    ): Pair<List<File>, String?> {
        var missed = 0
        val files = withPdf(pdf) { r ->
            pages.mapIndexed { k, i ->
                progress("Page ${k + 1} of ${pages.size}…")
                val (bmp, _) = renderAtDpi(r, i, dpi)
                try {
                    val bytes = if (format == ConvertMath.PicFormat.JPG && maxBytesEach != null) {
                        fitJpeg(bmp, maxBytesEach).also { if (!it.second) missed++ }.first.jpeg
                    } else {
                        encode(bmp, format, 90)
                    }
                    File(outDir, ConvertMath.pageFileName(base, i, r.pageCount, format.ext)).also { it.writeBytes(bytes) }
                } finally {
                    bmp.recycle()
                }
            }
        }
        val note = if (missed > 0) "$missed picture(s) could not get under ${maxBytesEach!! / 1024} KB." else null
        return files to note
    }

    // ---------------- 3. Change size (KB) ----------------

    class SizeResult(val file: File, val before: Long, val after: Long, val note: String?)

    fun shrinkPhoto(context: Context, uri: Uri, base: String, before: Long, maxBytes: Int, minBytes: Int, outDir: File): SizeResult {
        val bmp = loadPhoto(context, uri, 3000)
        val (image, fits) = try {
            fitJpeg(bmp, maxBytes, minBytes)
        } finally {
            bmp.recycle()
        }
        val dest = File(outDir, "$base.jpg").also { it.writeBytes(image.jpeg) }
        val note = when {
            !fits -> "Could not get it under ${maxBytes / 1024} KB. This is the smallest it can be."
            minBytes > 0 && dest.length() < minBytes -> "It is under the ${minBytes / 1024} KB minimum even at the best quality."
            else -> null
        }
        return SizeResult(dest, before, dest.length(), note)
    }

    fun shrinkPdf(pdf: File, base: String, maxBytes: Int, outDir: File, progress: (String) -> Unit): SizeResult {
        val before = pdf.length()
        val dest = File(outDir, "$base.pdf")
        if (before in 1..maxBytes.toLong()) {
            pdf.copyTo(dest, overwrite = true)
            return SizeResult(dest, before, before, "It was already under ${maxBytes / 1024} KB, so nothing was changed.")
        }
        val pages = mutableListOf<PdfWriter.Page>()
        withPdf(pdf) { r ->
            var used = 0
            for (i in 0 until r.pageCount) {
                progress("Making page ${i + 1} of ${r.pageCount} smaller…")
                val (bmp, pt) = renderAtDpi(r, i, 150)
                val image = try {
                    fitJpeg(bmp, ConvertMath.pageBudget(maxBytes, used, r.pageCount - i, r.pageCount)).first
                } finally {
                    bmp.recycle()
                }
                used += image.jpeg.size
                val w = pt.first.toFloat()
                val h = pt.second.toFloat()
                pages += PdfWriter.Page(w, h, listOf(PdfWriter.Placement(image, 0f, 0f, w, h)))
            }
        }
        writePdf(pages, dest)
        val note = if (dest.length() > maxBytes) {
            "Could not get it under ${maxBytes / 1024} KB. This is the smallest it can be (${ConvertMath.sizeText(dest.length())}). Try fewer pages."
        } else null
        return SizeResult(dest, before, dest.length(), note)
    }

    // ---------------- 4. Merge ----------------

    class MergeIn(val uri: Uri?, val pdf: File?, val turns: Int = 0)

    fun merge(context: Context, items: List<MergeIn>, dest: File, progress: (String) -> Unit): File {
        val pages = mutableListOf<PdfWriter.Page>()
        items.forEachIndexed { n, item ->
            if (item.pdf != null) {
                withPdf(item.pdf) { r ->
                    for (i in 0 until r.pageCount) {
                        progress("File ${n + 1} of ${items.size}, page ${i + 1} of ${r.pageCount}…")
                        pages += pdfPage(r, i, 0)
                    }
                }
            } else if (item.uri != null) {
                progress("File ${n + 1} of ${items.size}…")
                val bmp = loadPhoto(context, item.uri, 2000, item.turns)
                val image = Images.encode(bmp, 82).also { bmp.recycle() }
                val plan = ConvertMath.placePage(image.pixelWidth, image.pixelHeight, a4 = false)
                pages += PdfWriter.Page(plan.pageW, plan.pageH, listOf(PdfWriter.Placement(image, plan.x, plan.y, plan.w, plan.h)))
            }
        }
        return writePdf(pages, dest)
    }

    /** One page of an open PDF as a picture page of the same size, turned [turns] quarter turns. */
    private fun pdfPage(r: PdfRenderer, index: Int, turns: Int): PdfWriter.Page {
        val (bmp0, pt) = renderAtDpi(r, index, 200)
        val bmp = turn(bmp0, turns)
        val image = Images.encode(bmp, 82).also { bmp.recycle() }
        val (w, h) = ConvertMath.turned(pt.first.toFloat(), pt.second.toFloat(), turns)
        return PdfWriter.Page(w, h, listOf(PdfWriter.Placement(image, 0f, 0f, w, h)))
    }

    // ---------------- 5. Pick / split pages ----------------

    fun pickPages(
        pdf: File,
        base: String,
        pages: List<Int>,
        turns: Map<Int, Int>,
        separate: Boolean,
        outDir: File,
        progress: (String) -> Unit,
    ): List<File> = withPdf(pdf) { r ->
        if (!separate) {
            val made = pages.mapIndexed { k, i ->
                progress("Page ${k + 1} of ${pages.size}…")
                pdfPage(r, i, turns[i] ?: 0)
            }
            listOf(writePdf(made, File(outDir, "${base}_pages.pdf")))
        } else {
            val one = File(outDir, "pages").apply { mkdirs() }
            val files = pages.mapIndexed { k, i ->
                progress("Page ${k + 1} of ${pages.size}…")
                writePdf(listOf(pdfPage(r, i, turns[i] ?: 0)), File(one, ConvertMath.pageFileName(base, i, r.pageCount, "pdf")))
            }
            if (files.size == 1) files else listOf(zip(files, File(outDir, "${base}_pages.zip")))
        }
    }

    // ---------------- 6. Change photo format ----------------

    fun changeFormat(
        context: Context,
        photos: List<Pair<Uri, String>>,
        format: ConvertMath.PicFormat,
        quality: Int,
        outDir: File,
        progress: (String) -> Unit,
    ): List<File> {
        val taken = mutableSetOf<String>()
        return photos.mapIndexed { i, (uri, base) ->
            if (photos.size > 1) progress("Photo ${i + 1} of ${photos.size}…")
            // Only JPG has no see-through parts: those become white.
            val bmp = loadPhoto(context, uri, 4000, white = format == ConvertMath.PicFormat.JPG)
            val bytes = try {
                encode(bmp, format, if (format == ConvertMath.PicFormat.PNG) 100 else quality)
            } finally {
                bmp.recycle()
            }
            val name = ConvertMath.uniqueFileName("$base.${format.ext}", taken).also { taken += it }
            File(outDir, name).also { it.writeBytes(bytes) }
        }
    }
}

/** PdfRenderer.Page is AutoCloseable only on newer Android: close it by hand. */
private inline fun <T> PdfRenderer.Page.useAndClose(block: (PdfRenderer.Page) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
