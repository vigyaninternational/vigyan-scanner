package com.vigyan.scanner

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScanRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val ctx: Application get() = getApplication()

    private val _scans = MutableStateFlow<List<Scan>>(emptyList())
    val scans: StateFlow<List<Scan>> = _scans

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders

    /** Folder shown on the home screen; null = all scans. */
    private val _currentFolder = MutableStateFlow<String?>(null)
    val currentFolder: StateFlow<String?> = _currentFolder

    /** Non-null while something slow runs; the text is shown in a progress dialog. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /** One-off message for a snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Read Hindi as well as English. */
    private val _hindi = MutableStateFlow(prefs.getBoolean(KEY_HINDI, false))
    val hindi: StateFlow<Boolean> = _hindi

    /** A scan the screen should open (after importing something shared from another app). */
    private val _openScan = MutableStateFlow<String?>(null)
    val openScan: StateFlow<String?> = _openScan

    enum class Target { PHONE, DRIVE, SHARE }

    class PendingSend(val target: Target, val files: List<File>)

    private val _pendingSend = MutableStateFlow<PendingSend?>(null)
    val pendingSend: StateFlow<PendingSend?> = _pendingSend

    init {
        viewModelScope.launch { reload() }
    }

    fun scan(id: String): Scan? = _scans.value.firstOrNull { it.id == id }

    fun messageShown() {
        _message.value = null
    }

    fun say(text: String) {
        _message.value = text
    }

    fun openHandled() {
        _openScan.value = null
    }

    fun sendHandled() {
        _pendingSend.value = null
    }

    fun showFolder(folder: String?) {
        _currentFolder.value = folder
    }

    fun setHindi(on: Boolean) {
        _hindi.value = on
        prefs.edit().putBoolean(KEY_HINDI, on).apply()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun reload() {
        val list = io { repo.list() }
        _scans.value = list
        _folders.value = io { repo.folders(list) }
    }

    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = label
            try {
                block()
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
                reload()
            }
        }
    }

    private fun stamp() = SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date())

    private val folderForNew get() = _currentFolder.value.orEmpty()

    // ---- New scans ----

    fun saveNewScan(pages: List<Uri>, then: (Scan) -> Unit) = work("Saving scan…") {
        val scan = io { repo.create(pages, folder = folderForNew) }
        reload() // the new scan must be in the list before its screen opens
        then(scan)
    }

    /** Photos / PDFs picked in the app or shared from WhatsApp, Gallery, Files… */
    fun importUris(uris: List<Uri>, then: (Scan) -> Unit = { _openScan.value = it.id }) = work("Importing…") {
        val files = io { Images.import(ctx, uris, File(ctx.cacheDir, "import").apply { deleteRecursively() }) }
        if (files.isEmpty()) error("Nothing to import")
        val scan = io { repo.createFromFiles(files, "Imported ${stamp()}", folderForNew) }
        reload()
        then(scan)
    }

    /**
     * Batch Smart Fill: every [perForm] pages are one form. Each form becomes its own scan in a
     * new folder, filled and named after the person, ready to export as one CSV.
     */
    fun batchFill(pages: List<Uri>, perForm: Int, then: (String) -> Unit) = work("Saving forms…") {
        val folder = "Forms ${stamp()}"
        io { repo.addFolder(folder, _scans.value) }
        val groups = pages.chunked(perForm)
        groups.forEachIndexed { i, group ->
            val prefix = "Form ${i + 1} of ${groups.size}: "
            _busy.value = prefix + "saving…"
            val scan = io { repo.create(group, "Form ${i + 1}", folder) }
            val (fields, _) = extractFields(scan, prefix)
            io {
                repo.saveFields(scan, fields)
                autoName(repo.get(scan.id)!!, fields)
            }
        }
        _currentFolder.value = folder
        _message.value = "${groups.size} form(s) filled. Check each one, then export the CSV."
        then(folder)
    }

    // ---- Reading text ----

    /** Reads the pages whose text hasn't been read yet (or all pages with [force]). */
    private suspend fun ocrMissing(scan: Scan, force: Boolean = false, prefix: String = ""): Scan {
        val missing = if (force) scan.pages else io { repo.pagesWithoutOcr(scan) }
        if (missing.isEmpty()) return scan
        val results = Ocr.readPages(ctx, missing, _hindi.value) { i ->
            _busy.value = prefix + "reading text, page ${i + 1} of ${missing.size}…"
        }
        return io {
            missing.zip(results).forEach { (page, ocr) -> repo.savePageOcr(page, ocr) }
            repo.rebuildText(repo.get(scan.id)!!)
        }
    }

    fun readText(scan: Scan, force: Boolean = false, then: (Scan) -> Unit = {}) {
        if (scan.text != null && !force) return then(scan)
        work("Reading text…") {
            val updated = ocrMissing(scan, force)
            if (updated.text.isNullOrBlank()) _message.value = "No text found on this scan."
            then(updated)
        }
    }

    /** Fields from the text, with an Aadhaar QR (if the pages have one) taking priority. */
    private suspend fun extractFields(scan: Scan, prefix: String = ""): Pair<Map<String, String>, Boolean> {
        val s = ocrMissing(scan, prefix = prefix)
        val fromText = FormExtractor.extract(s.rows ?: s.text.orEmpty())
        _busy.value = prefix + "looking for an Aadhaar QR code…"
        val qr = Ocr.readQrCodes(ctx, s.pages.take(4)).firstNotNullOfOrNull { AadhaarQr.parse(it) }
        return if (qr != null) (fromText + qr) to true else fromText to false
    }

    private val defaultName = Regex("""^(Scan|Imported|Merged|Form \d+)\b.*""")

    /** Names a scan after the person once Smart Fill finds a name (only if it still has a default name). */
    private fun autoName(scan: Scan, fields: Map<String, String>) {
        val name = fields["name"]?.trim()
        if (!name.isNullOrBlank() && defaultName.matches(scan.name)) repo.rename(scan, name)
    }

    /** Fills the form: saved corrections win over freshly read values. */
    fun smartFill(scan: Scan, then: (Map<String, String>, Boolean) -> Unit) = work("Reading the form…") {
        val (found, qr) = extractFields(scan)
        val fields = found + scan.fields.filterValues { it.isNotBlank() }
        io { autoName(scan, fields) }
        then(fields, qr)
    }

    fun refill(scan: Scan, then: (Map<String, String>, Boolean) -> Unit) = work("Reading the form again…") {
        val (found, qr) = extractFields(scan)
        then(found, qr)
    }

    fun saveFields(scan: Scan, fields: Map<String, String>) = work("Saving…") {
        io {
            repo.saveFields(scan, fields)
            autoName(repo.get(scan.id)!!, fields)
        }
        _message.value = "Form saved"
    }

    fun saveText(scan: Scan, text: String) = work("Saving…") {
        io { repo.saveText(scan, text) }
        _message.value = "Text saved"
    }

    // ---- Organising ----

    fun rename(scan: Scan, name: String) = work("Saving…") { io { repo.rename(scan, name) } }

    fun delete(scan: Scan) = work("Deleting…") { io { repo.delete(scan) } }

    fun deleteMany(ids: List<String>) = work("Deleting…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.delete(it) } }
    }

    /** Joins the scans, in the order given, into one new scan. */
    fun merge(ids: List<String>, then: (Scan) -> Unit) = work("Merging…") {
        val list = io { ids.mapNotNull { repo.get(it) } }
        if (list.size < 2) error("Pick at least two scans")
        val merged = io { repo.merge(list, "Merged ${stamp()}") }
        reload()
        _message.value = "Merged ${list.size} scans (${merged.pages.size} pages). The originals are kept."
        then(merged)
    }

    fun moveToFolder(ids: List<String>, folder: String) = work("Moving…") {
        io {
            if (folder.isNotBlank()) repo.addFolder(folder, _scans.value)
            ids.mapNotNull { repo.get(it) }.forEach { repo.setFolder(it, folder) }
        }
    }

    fun addFolder(name: String) = work("Saving…") {
        if (name.isBlank()) error("Type a folder name")
        io { repo.addFolder(name, _scans.value) }
        _currentFolder.value = name.trim()
    }

    fun deleteFolder(name: String) = work("Deleting folder…") {
        io { repo.deleteFolder(name, _scans.value) }
        _currentFolder.value = null
        _message.value = "Folder removed. Its scans are kept under All."
    }

    // ---- Editing pages ----

    fun addPages(scan: Scan, uris: List<Uri>) = work("Adding pages…") { io { repo.addPages(scan, uris) } }

    fun deletePage(scan: Scan, index: Int) = work("Deleting page…") { io { repo.deletePage(scan, index) } }

    fun movePage(scan: Scan, from: Int, to: Int) = work("Moving page…") { io { repo.movePage(scan, from, to) } }

    fun rotatePage(scan: Scan, index: Int) = work("Rotating…") { io { repo.rotatePage(scan, index) } }

    // ---- Export ----

    fun export(scan: Scan, options: ExportOptions, target: Target) = send(target, "Making files…") {
        val needText = (Format.PDF in options.formats && options.searchable) || (Format.TXT in options.formats && scan.text == null)
        val s = if (needText) ocrMissing(scan) else scan
        _busy.value = "Making files…"
        val ocr = io { s.pages.map { repo.pageOcr(it) } }
        io { Exporter.files(ctx, s, options, ocr) }
    }

    /** Saves the (edited) text, then sends it as a .txt file. */
    fun exportText(scan: Scan, text: String, target: Target) = send(target, "Exporting…") {
        io {
            repo.saveText(scan, text)
            Exporter.files(ctx, scan.copy(text = text), ExportOptions(setOf(Format.TXT)), emptyList())
        }
    }

    /** One CSV row per scan that has a filled form ([override] = unsaved edits for a single scan). */
    fun exportForms(scans: List<Scan>, name: String, target: Target, override: Map<String, String>? = null) =
        send(target, "Making CSV…") {
            val forms = if (override != null) listOf(override) else scans.map { it.fields }.filter { it.isNotEmpty() }
            if (forms.isEmpty()) error("No filled forms here yet. Open a scan and use Smart Fill first.")
            listOf(io { Exporter.formsCsv(ctx, forms, name) })
        }

    private fun send(target: Target, label: String, makeFiles: suspend () -> List<File>) = work(label) {
        val files = makeFiles()
        if (files.isEmpty()) error("Nothing to export")
        when (target) {
            Target.PHONE -> {
                io { Exporter.saveToPhone(ctx, files) }
                _message.value = "Saved ${files.size} file(s) to Download/${Exporter.FOLDER}"
            }
            // These open another app, which needs an Activity: the screen does it.
            Target.DRIVE, Target.SHARE -> _pendingSend.value = PendingSend(target, files)
        }
    }

    private companion object {
        const val KEY_HINDI = "ocr_hindi"
    }
}
