package com.vigyan.scanner

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Format(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    JPG("JPG images", "jpg", "image/jpeg"),
    TXT("Text (OCR)", "txt", "text/plain"),
}

/** What to export: formats plus PDF options. */
data class ExportOptions(
    val formats: Set<Format>,
    val quality: Quality = Quality.NORMAL,
    /** Invisible OCR text in the PDF so it can be searched and copied. */
    val searchable: Boolean = true,
    /** All pages at real ID-card size on A4 (Aadhaar front + back on one sheet). */
    val idCard: Boolean = false,
    /** Open password for the PDF, or null. */
    val password: String? = null,
    /** "ATTESTED - TRUE COPY" stamp with the date (bottom-right of every page). */
    val attested: Boolean = false,
    /** College seal (made with the signature cut-out tool). */
    val seal: Boolean = false,
    /** Principal's signature (made with the signature cut-out tool). */
    val signature: Boolean = false,
    /** Blank space at the top and bottom of an A4 page, to print on the college letter pad. */
    val letterPad: Boolean = false,
) {
    val decorated get() = !idCard && (attested || seal || signature || letterPad)
}

/** Turns a scan into files and sends them to phone storage, Google Drive, or any app. */
object Exporter {

    const val FOLDER = "Vigyan Scanner"
    private const val DRIVE_PACKAGE = "com.google.android.apps.docs"

    private fun exportDir(context: Context) = File(context.cacheDir, "export").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun safeName(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "scan" }

    /**
     * Writes the chosen formats of [scan] into the cache export folder. [ocr] is the text of each
     * page (for a searchable PDF; null entries get no text layer).
     */
    fun files(context: Context, scan: Scan, options: ExportOptions, ocr: List<PageOcr?>): List<File> {
        val dir = exportDir(context)
        val base = safeName(scan.name)
        val out = mutableListOf<File>()
        val branding = Branding(context)
        if (Format.PDF in options.formats) {
            val file = File(dir, "$base.pdf")
            file.outputStream().use { stream ->
                PdfWriter().write(pdfPages(scan.pages, if (options.searchable) ocr else emptyList(), options, branding), stream, options.password)
            }
            out += file
        }
        if (Format.JPG in options.formats) {
            scan.pages.forEachIndexed { i, page ->
                val name = if (scan.pages.size == 1) "$base.jpg" else "${base}_page${i + 1}.jpg"
                val dest = File(dir, name)
                when {
                    options.decorated -> dest.writeBytes(decoratedJpeg(page, options, branding, withPad = true).jpeg)
                    options.quality == Quality.HIGH -> page.copyTo(dest, overwrite = true)
                    else -> dest.writeBytes(Images.jpeg(page, options.quality).jpeg)
                }
                out += dest
            }
        }
        if (Format.TXT in options.formats && scan.text != null) {
            out += File(dir, "$base.txt").apply { writeText(scan.text) }
        }
        return out
    }

    private const val A4_W = 595f
    private const val A4_H = 842f

    // An ID card (ID-1) is 85.6 x 54 mm = 243 x 153 points.
    private const val CARD_LONG = 243f
    private const val CARD_SHORT = 153f

    // Letter pad: blank space so the page prints inside the college letter pad's printed header
    // and footer. The page is A4.
    private const val PAD_TOP = 113f // 40 mm
    private const val PAD_BOTTOM = 71f // 25 mm
    private const val PAD_SIDE = 34f // 12 mm

    /** Where the scan goes on an A4 letter-pad page: [x, y, w, h] in points, fitted inside the margins. */
    private fun padBox(imgW: Int, imgH: Int): FloatArray {
        val boxW = A4_W - 2 * PAD_SIDE
        val boxH = A4_H - PAD_TOP - PAD_BOTTOM
        val s = minOf(boxW / imgW, boxH / imgH)
        val w = imgW * s
        val h = imgH * s
        return floatArrayOf((A4_W - w) / 2, PAD_TOP, w, h)
    }

    /** A page with the chosen stamps drawn on it (and, for JPG files, placed on a blank A4 letter-pad page). */
    private fun decoratedJpeg(file: File, options: ExportOptions, branding: Branding, withPad: Boolean): PdfWriter.Image {
        val page = Images.decode(file, options.quality.maxSide)
        val stamped = branding.stamp(page, options.attested, options.seal, options.signature)
        if (stamped !== page) page.recycle()
        val result = if (withPad && options.letterPad) {
            val box = padBox(stamped.width, stamped.height)
            val k = stamped.width / box[2] // pixels per point
            val sheet = Bitmap.createBitmap((A4_W * k).toInt(), (A4_H * k).toInt(), Bitmap.Config.ARGB_8888)
            Canvas(sheet).apply {
                drawColor(Color.WHITE)
                drawBitmap(stamped, box[0] * k, box[1] * k, null)
            }
            stamped.recycle()
            sheet
        } else {
            stamped
        }
        return Images.encode(result, options.quality.jpeg).also { result.recycle() }
    }

    private fun pdfPages(pages: List<File>, ocr: List<PageOcr?>, options: ExportOptions, branding: Branding): List<PdfWriter.Page> {
        fun placement(i: Int, x: Float, y: Float, w: Float, h: Float, image: PdfWriter.Image): PdfWriter.Placement {
            val o = ocr.getOrNull(i)
            return if (o != null) PdfWriter.Placement(image, x, y, w, h, o.lines, o.width, o.height)
            else PdfWriter.Placement(image, x, y, w, h)
        }
        if (!options.idCard) {
            return pages.mapIndexed { i, file ->
                val image = if (options.decorated) decoratedJpeg(file, options, branding, withPad = false) else Images.jpeg(file, options.quality)
                if (options.letterPad) {
                    // A4 with blank space at the top and bottom, to print on the college letter pad.
                    val b = padBox(image.pixelWidth, image.pixelHeight)
                    PdfWriter.Page(A4_W, A4_H, listOf(placement(i, b[0], b[1], b[2], b[3], image)))
                } else {
                    // One PDF page per scanned page, A4 width, same shape as the scan.
                    val h = A4_W * image.pixelHeight / image.pixelWidth
                    PdfWriter.Page(A4_W, h, listOf(placement(i, 0f, 0f, A4_W, h, image)))
                }
            }
        }
        // ID card: every page shrunk to real card size on A4 sheets, one column for a front and
        // back, two columns when there are more, so it prints like a photocopy of the card.
        val perSheet = 6 // 2 columns x 3 rows fit on A4
        return pages.indices.chunked(perSheet).map { chunk ->
            val cols = if (pages.size <= 2) 1 else 2
            val cellW = A4_W / cols
            val cellH = CARD_LONG + 24f
            val placements = chunk.mapIndexed { k, i ->
                val image = Images.jpeg(pages[i], options.quality)
                val landscape = image.pixelWidth >= image.pixelHeight
                val boxW = if (landscape) CARD_LONG else CARD_SHORT
                val boxH = if (landscape) CARD_SHORT else CARD_LONG
                val scale = minOf(boxW / image.pixelWidth, boxH / image.pixelHeight)
                val w = image.pixelWidth * scale
                val h = image.pixelHeight * scale
                val col = k % cols
                val row = k / cols
                val x = col * cellW + (cellW - w) / 2
                val y = 40f + row * cellH + (cellH - h) / 2
                placement(i, x, y, w, h, image)
            }
            PdfWriter.Page(A4_W, A4_H, placements)
        }
    }

    /**
     * Portal resizer: a photo / signature JPG of exact pixel size, or a PDF, made as good as
     * possible while staying under the portal's size limit. Returns the file and a note when
     * the limit could not be met.
     */
    fun portalFile(context: Context, scan: Scan, spec: PortalSpec, pageIndex: Int): Pair<File, String?> {
        val dir = exportDir(context)
        val base = safeName(scan.name)
        val maxBytes = spec.maxKb * 1024
        val minBytes = spec.minKb * 1024
        val result: SizeFit.Result
        val file: File
        if (spec.pdf) {
            file = File(dir, "$base.pdf")
            result = SizeFit.fit(maxBytes, minBytes) { scale, q ->
                val maxSide = (2000 * scale).toInt().coerceAtLeast(300)
                val pages = scan.pages.map { page ->
                    val bmp = Images.decode(page, maxSide)
                    val gray = if (spec.gray) Images.grayscale(bmp) else bmp
                    if (gray !== bmp) bmp.recycle()
                    val image = Images.encode(gray, q)
                    gray.recycle()
                    val h = A4_W * image.pixelHeight / image.pixelWidth
                    PdfWriter.Page(A4_W, h, listOf(PdfWriter.Placement(image, 0f, 0f, A4_W, h)))
                }
                java.io.ByteArrayOutputStream().also { PdfWriter().write(pages, it) }.toByteArray()
            }
        } else {
            file = File(dir, "${base}_${spec.suffix}.jpg")
            val source = Images.decode(scan.pages[pageIndex.coerceIn(scan.pages.indices)], 3000)
            val fixed = spec.width > 0 && spec.height > 0
            val sized = if (fixed) Images.cropResize(source, spec.width, spec.height) else source
            if (sized !== source) source.recycle()
            val base0 = if (spec.gray) Images.grayscale(sized).also { if (it !== sized) sized.recycle() } else sized
            result = SizeFit.fit(maxBytes, minBytes, allowShrink = !fixed) { scale, q ->
                if (scale >= 1f) {
                    Images.encode(base0, q).jpeg
                } else {
                    val small = Bitmap.createScaledBitmap(base0, (base0.width * scale).toInt().coerceAtLeast(1), (base0.height * scale).toInt().coerceAtLeast(1), true)
                    Images.encode(small, q).jpeg.also { small.recycle() }
                }
            }
            base0.recycle()
        }
        file.writeBytes(result.bytes)
        val kb = (result.bytes.size + 1023) / 1024
        val note = when {
            !result.fits -> "Could not get it under ${spec.maxKb} KB (smallest: $kb KB). Try a smaller size or black & white."
            result.bytes.size < minBytes -> "It is $kb KB, under the ${spec.minKb} KB minimum. Try a larger pixel size."
            else -> null
        }
        return file to note
    }

    /** Merit list as a printable PDF (overall, then category-wise) and/or a CSV for Excel. */
    fun meritFiles(context: Context, title: String, ranked: List<Merit.Ranked>, wantPdf: Boolean, wantCsv: Boolean): List<File> {
        val dir = exportDir(context)
        val base = safeName(title)
        val out = mutableListOf<File>()
        if (wantCsv) {
            val sb = StringBuilder("Rank,Category rank,Name,Roll,Category,Total,Max,Percent\r\n")
            val catRanks = categoryRanks(ranked)
            ranked.forEach { r ->
                val e = r.entry
                sb.append(listOf(r.rank.toString(), catRanks[e.id]?.toString().orEmpty(), e.name, e.roll, e.category, e.total.toString(), e.max.toString(), Merit.pct(e.percent))
                    .joinToString(",") { csv(it) }).append("\r\n")
            }
            out += File(dir, "$base.csv").apply { writeText(sb.toString()) }
        }
        if (wantPdf) out += File(dir, "$base.pdf").also { MeritPdf.write(context, title, ranked, it) }
        return out
    }

    fun categoryRanks(ranked: List<Merit.Ranked>): Map<String, Int> =
        ranked.groupBy { it.entry.category }.filterKeys { it.isNotBlank() }
            .flatMap { (_, list) -> Merit.rank(list.map { it.entry }).map { it.entry.id to it.rank } }.toMap()

    /** One CSV row per filled form. Column names match the Vigyan ERP student CSV import. */
    fun formsCsv(context: Context, forms: List<Map<String, String>>, name: String): File {
        val fields = FormExtractor.FIELDS
        val sb = StringBuilder()
        sb.append(fields.joinToString(",") { csv(it.csvHeader) }).append("\r\n")
        for (form in forms) {
            sb.append(fields.joinToString(",") { f ->
                val v = form[f.key].orEmpty()
                csv(if (f.key == "dob") FormExtractor.toIsoDate(v).ifBlank { v } else v)
            }).append("\r\n")
        }
        return File(exportDir(context), "${safeName(name)}.csv").apply { writeText(sb.toString()) }
    }

    fun csvName(prefix: String) = prefix + " " + SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    /**
     * Saves into Download/Vigyan Scanner on the phone. On Android 9 and older this needs the
     * storage permission, which the screen asks for first.
     */
    fun saveToPhone(context: Context, files: List<File>) {
        for (file in files) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create ${file.name}")
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not write ${file.name}")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                dir.mkdirs()
                var dest = File(dir, file.name)
                var n = 1
                while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
                file.copyTo(dest)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mimeOf(file)), null)
            }
        }
    }

    fun isDriveInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE) != null

    /** Opens Google Drive's upload screen (pick account and folder there). Falls back to the share sheet. */
    fun uploadToDrive(context: Context, files: List<File>) {
        val intent = sendIntent(context, files)
        if (isDriveInstalled(context)) {
            intent.setPackage(DRIVE_PACKAGE)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                intent.setPackage(null)
            }
        }
        context.startActivity(Intent.createChooser(intent, "Upload to…"))
    }

    fun share(context: Context, files: List<File>) {
        context.startActivity(Intent.createChooser(sendIntent(context, files), "Share"))
    }

    const val APP_LINK = "https://github.com/vigyaninternational/vigyan-scanner/releases/latest/download/VigyanScanner.apk"

    /** Sends the download link: works everywhere, including WhatsApp (which can't open APK files). */
    fun shareAppLink(context: Context) {
        shareText(
            context,
            "Vigyan Scanner: scan documents to PDF, read text (OCR) and fill forms.\n\n" +
                "Open this link in Chrome to install:\n$APP_LINK",
        )
    }

    /** Sends this installed app's own APK file (for Bluetooth, Quick Share, Drive, email…). */
    fun shareAppFile(context: Context) {
        val dir = File(context.cacheDir, "apk").apply { deleteRecursively(); mkdirs() }
        val apk = File(context.applicationInfo.sourceDir).copyTo(File(dir, "VigyanScanner.apk"), overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uri)
        context.startActivity(Intent.createChooser(intent, "Share Vigyan Scanner app"))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Share text"))
    }

    private fun sendIntent(context: Context, files: List<File>): Intent {
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
        val mimes = files.map(::mimeOf).distinct()
        val mime = mimes.singleOrNull() ?: if (mimes.all { it.startsWith("image/") }) "image/*" else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        intent.type = mime
        intent.putExtra(Intent.EXTRA_SUBJECT, files.first().nameWithoutExtension)
        // Grants the receiving app (Drive, WhatsApp, Gmail…) read access to every file.
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Opens the phone's "new contact" screen pre-filled from a filled form (e.g. a visiting card). */
    fun addContact(context: Context, fields: Map<String, String>) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            fields["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            fields["mobile1"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            fields["mobile2"]?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
            fields["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            fields["address"]?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        }
        context.startActivity(intent)
    }
}
