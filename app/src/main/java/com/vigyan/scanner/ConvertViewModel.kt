package com.vigyan.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What is picked in the "Convert files" tool (photos, PDFs, ticked pages, turns), kept while
 * the tool is open. The slow work runs through ScanViewModel.runConvert / sendConvert, which
 * show the progress dialog and save or share the files.
 */
class ConvertViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private var nextId = 1L

    data class Photo(val id: Long, val uri: Uri, val name: String, val size: Long, val turns: Int = 0)

    /** A picked PDF, copied into the app: page sizes (points) and small page pictures. */
    data class Pdf(val file: File, val name: String, val size: Long, val pageSizes: List<Pair<Int, Int>>, val thumbs: List<File>) {
        val pageCount get() = pageSizes.size
        val base get() = ConvertMath.baseName(name, "pages")
    }

    /** One entry in Merge: a PDF or a photo. */
    data class MergeItem(val id: Long, val name: String, val photo: Uri? = null, val pdf: Pdf? = null, val turns: Int = 0)

    /** The file being made smaller in Change size. */
    data class SizeSource(val uri: Uri, val name: String, val size: Long, val pdf: File?)

    // 1. Photos → PDF
    val photos = MutableStateFlow<List<Photo>>(emptyList())

    // 2. PDF → photos
    val toPhotos = MutableStateFlow<Pdf?>(null)
    val toPhotosPicked = MutableStateFlow<Set<Int>>(emptySet())

    // 3. Change size
    val sizeSource = MutableStateFlow<SizeSource?>(null)
    val sizeResult = MutableStateFlow<Convert.SizeResult?>(null)

    // 4. Merge
    val merge = MutableStateFlow<List<MergeItem>>(emptyList())

    // 5. Pick / split pages
    val pages = MutableStateFlow<Pdf?>(null)
    val pagesPicked = MutableStateFlow<List<Int>>(emptyList())
    val pagesTurns = MutableStateFlow<Map<Int, Int>>(emptyMap())

    // 6. Change photo format
    val formatPhotos = MutableStateFlow<List<Photo>>(emptyList())

    init {
        // Leftovers from the last time the tool was open.
        runCatching { Convert.dir(ctx).deleteRecursively() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun out() = Convert.outDir(ctx)

    // ---------------- Picking ----------------

    suspend fun photosFrom(uris: List<Uri>): List<Photo> = io {
        uris.map { uri ->
            val (name, size) = Convert.nameAndSize(ctx, uri)
            Photo(nextId++, uri, name, size)
        }
    }

    suspend fun openPdf(uri: Uri, step: (String) -> Unit): Pdf = io {
        val (name, size) = Convert.nameAndSize(ctx, uri)
        val id = nextId++
        val file = Convert.copyIn(ctx, uri, "src_$id.pdf")
        val sizes = Convert.pageSizes(file)
        if (sizes.isEmpty()) throw Notice("This PDF has no pages.")
        val thumbs = Convert.thumbnails(file, "$id", step)
        Pdf(file, name, if (size > 0) size else file.length(), sizes, thumbs)
    }

    suspend fun addToMerge(uris: List<Uri>, step: (String) -> Unit) {
        val added = mutableListOf<MergeItem>()
        for (uri in uris) {
            if (io { Convert.isPdf(ctx, uri) }) {
                val pdf = openPdf(uri, step)
                added += MergeItem(nextId++, pdf.name, pdf = pdf)
            } else {
                val (name, _) = io { Convert.nameAndSize(ctx, uri) }
                added += MergeItem(nextId++, name, photo = uri)
            }
        }
        merge.value = merge.value + added
    }

    suspend fun pickForSize(uri: Uri) {
        val src = io {
            val (name, size0) = Convert.nameAndSize(ctx, uri)
            if (Convert.isPdf(ctx, uri)) {
                val file = Convert.copyIn(ctx, uri, "size_${nextId++}.pdf")
                Convert.pageSizes(file) // checks it opens (and is not locked)
                SizeSource(uri, name, file.length(), file)
            } else {
                var size = size0
                if (size <= 0) size = runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> s.skip(Long.MAX_VALUE) } ?: -1L }.getOrDefault(-1L)
                SizeSource(uri, name, size, null)
            }
        }
        sizeSource.value = src
        sizeResult.value = null
    }

    // ---------------- Making files ----------------

    suspend fun photosToPdf(name: String, a4: Boolean, quality: ConvertMath.PdfQuality, maxBytes: Int?, step: (String) -> Unit): Pair<File, String?> = io {
        val list = photos.value
        val base = ConvertMath.baseName(name.ifBlank { list.firstOrNull()?.name.orEmpty() }, "Photos")
        Convert.photosToPdf(ctx, list.map { Convert.PhotoIn(it.uri, it.turns) }, a4, quality, maxBytes, File(out(), "$base.pdf"), step)
    }

    suspend fun pdfToPhotos(format: ConvertMath.PicFormat, dpi: Int, maxBytesEach: Int?, zip: Boolean, step: (String) -> Unit): Pair<List<File>, String?> = io {
        val pdf = toPhotos.value ?: throw Notice("Pick a PDF first")
        val chosen = toPhotosPicked.value.sorted()
        if (chosen.isEmpty()) throw Notice("Tick at least one page")
        val dir = out()
        val pics = File(dir, "pictures").apply { mkdirs() }
        val (files, note) = Convert.pdfToPhotos(pdf.file, pdf.base, chosen, format, dpi, maxBytesEach, pics, step)
        val result = if (zip && files.size > 1) listOf(Convert.zip(files, File(dir, "${pdf.base}_pictures.zip"))) else files
        result to note
    }

    suspend fun makeSmaller(maxBytes: Int, minBytes: Int, step: (String) -> Unit) {
        val src = sizeSource.value ?: throw Notice("Pick a photo or PDF first")
        val base = ConvertMath.baseName(src.name, "file") + "_small"
        val r = io {
            if (src.pdf != null) Convert.shrinkPdf(src.pdf, base, maxBytes, out(), step)
            else Convert.shrinkPhoto(ctx, src.uri, base, src.size, maxBytes, minBytes, out())
        }
        sizeResult.value = r
    }

    suspend fun mergeAll(name: String, step: (String) -> Unit): File = io {
        val items = merge.value
        if (items.size < 2) throw Notice("Add at least two files to join")
        val base = ConvertMath.baseName(name, "Merged")
        Convert.merge(ctx, items.map { Convert.MergeIn(it.photo, it.pdf?.file, it.turns) }, File(out(), "$base.pdf"), step)
    }

    suspend fun pickPages(separate: Boolean, step: (String) -> Unit): List<File> = io {
        val pdf = pages.value ?: throw Notice("Pick a PDF first")
        val chosen = pagesPicked.value
        if (chosen.isEmpty()) throw Notice("Choose at least one page")
        Convert.pickPages(pdf.file, pdf.base, chosen, pagesTurns.value, separate, out(), step)
    }

    suspend fun changeFormat(format: ConvertMath.PicFormat, quality: Int, zip: Boolean, step: (String) -> Unit): List<File> = io {
        val list = formatPhotos.value
        if (list.isEmpty()) throw Notice("Pick photos first")
        val dir = out()
        val pics = File(dir, "pictures").apply { mkdirs() }
        val files = Convert.changeFormat(ctx, list.map { it.uri to ConvertMath.baseName(it.name, "photo") }, format, quality, pics, step)
        if (zip && files.size > 1) listOf(Convert.zip(files, File(dir, "Photos_${format.ext}.zip"))) else files
    }
}
