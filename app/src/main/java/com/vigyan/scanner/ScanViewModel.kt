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

    private val _settings = MutableStateFlow(AppSettings.load(prefs))
    val settings: StateFlow<AppSettings> = _settings

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        s.save(prefs)
        _settings.value = s
    }

    /** Scans, not counting the Recycle bin. */
    private val _scans = MutableStateFlow<List<Scan>>(emptyList())
    val scans: StateFlow<List<Scan>> = _scans

    /** The Recycle bin (kept 30 days). */
    private val _trash = MutableStateFlow<List<Scan>>(emptyList())
    val trash: StateFlow<List<Scan>> = _trash

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

    /** Text reading language (English, English + Hindi, English + Odia). */
    private val _lang = MutableStateFlow(
        OcrLang.values().firstOrNull { it.name == prefs.getString(KEY_LANG, null) }
            ?: if (prefs.getBoolean(KEY_HINDI, false)) OcrLang.HINDI else OcrLang.ENGLISH,
    )
    val lang: StateFlow<OcrLang> = _lang

    /** A scan the screen should open (after importing something shared from another app). */
    private val _openScan = MutableStateFlow<String?>(null)
    val openScan: StateFlow<String?> = _openScan

    enum class Target { PHONE, DRIVE, SHARE, PRINT }

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

    fun setLang(l: OcrLang) {
        _lang.value = l
        prefs.edit().putString(KEY_LANG, l.name).apply()
        if (l == OcrLang.ODIA && !OdiaOcr.isReady(ctx)) downloadOdia()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun reload() {
        val all = io { repo.list() }
        val list = all.filter { it.trashed == 0L }
        _scans.value = list
        _trash.value = all.filter { it.trashed > 0L }.sortedByDescending { it.trashed }
        _folders.value = io { repo.folders(list) }
    }

    /** [name], or "name (2)"… if another scan already has it. */
    private fun uniqueName(name: String, exceptId: String? = null) =
        Naming.unique(name.trim(), _scans.value.filter { it.id != exceptId }.map { it.name })

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

    fun saveNewScan(pages: List<Uri>, sort: Boolean = false, then: (Scan) -> Unit) = work("Saving scan…") {
        val scan = io { repo.create(pages, folder = folderForNew) }
        reload() // the new scan must be in the list before its screen opens
        then(scan)
        if (sort) autoSortInBackground(scan)
        checkQualityInBackground(scan)
    }

    private suspend fun importFiles(uris: List<Uri>): List<File> {
        val files = io { Images.import(ctx, uris, File(ctx.cacheDir, "import").apply { deleteRecursively() }) }
        if (files.isEmpty()) error("Nothing to import")
        return files
    }

    /** Photos / PDFs picked in the app or shared from WhatsApp, Gallery, Files… */
    fun importUris(uris: List<Uri>, sort: Boolean = true, then: (Scan) -> Unit = { _openScan.value = it.id }) = work("Importing…") {
        val files = importFiles(uris)
        val scan = io { repo.createFromFiles(files, "Imported ${stamp()}", folderForNew) }
        reload()
        then(scan)
        if (sort) autoSortInBackground(scan)
    }

    // ---- Scan & merge by name ----

    /**
     * Starts a person's document set: all their pages (10th, +2, CLC, Aadhaar…) in one scan named
     * after them, saved later as one PDF (RAHUL_KUMAR.pdf). No student record is made.
     */
    fun newNamedSession(uris: List<Uri>, person: String, ref: String, imported: Boolean, then: (Scan) -> Unit) =
        work(if (imported) "Importing…" else "Saving pages…") {
            val name = uniqueName(listOf(person.trim(), ref.trim()).filter { it.isNotEmpty() }.joinToString(" - "))
            val files = if (imported) importFiles(uris) else null
            val scan = io {
                val s = if (files != null) repo.createFromFiles(files, name, folderForNew) else repo.create(uris, name, folderForNew)
                repo.setExtra(s, "person", person.trim())
                repo.setExtra(s, "ref", ref.trim())
                repo.get(s.id)!!
            }
            reload()
            then(scan)
            checkQualityInBackground(scan)
        }

    /** More pages for an existing scan, from the camera ([uris]) or photos / PDFs ([imported]). */
    fun appendPages(scan: Scan, uris: List<Uri>, imported: Boolean) = work("Adding pages…") {
        val before = scan.pages.size
        val updated = if (imported) {
            val files = importFiles(uris)
            io { repo.addFiles(scan, files) }
        } else {
            io { repo.addPages(scan, uris) }
        }
        _message.value = "Added ${updated.pages.size - before} page(s)"
        checkQualityInBackground(updated)
    }

    // ---- Bulk scanning ----

    /**
     * Several documents scanned in one go (split with ✂ or blank separator pages): each becomes
     * its own scan in a new folder, named from its text where it can be recognised.
     */
    fun saveDocuments(sets: List<List<Uri>>, then: (String) -> Unit) = work("Saving documents…") {
        val folder = "Bulk scan ${stamp()}"
        io { repo.addFolder(folder, _scans.value) }
        var named = 0
        val flagged = mutableListOf<String>()
        sets.forEachIndexed { i, set ->
            val prefix = "Document ${i + 1} of ${sets.size}: "
            _busy.value = prefix + "saving…"
            val scan = io { repo.create(set, "Document ${i + 1} ${stamp()}", folder) }
            try {
                val s = ocrMissing(scan, prefix = prefix)
                val type = DocClassifier.classify(s.text.orEmpty())
                val person = FormExtractor.extract(s.rows ?: s.text.orEmpty())["name"]
                val name = when {
                    type != null && !person.isNullOrBlank() -> "${type.label} - $person"
                    type != null -> "${type.label} ${i + 1}"
                    !person.isNullOrBlank() -> person
                    else -> null
                }
                if (name != null) {
                    io { repo.rename(repo.get(scan.id)!!, uniqueName(name)) }
                    reload()
                    named++
                }
            } catch (e: Exception) {
                // Naming is a convenience: the document is saved either way.
            }
            val issues = io { pageIssues(scan.pages) }
            if (issues != null) flagged += "document ${i + 1} ($issues)"
        }
        _currentFolder.value = folder
        _message.value = "${sets.size} document(s) saved, $named named from their text." +
            (if (flagged.isNotEmpty()) " Check " + flagged.joinToString(", ") + "." else "")
        then(folder)
    }

    // ---- Scan quality ----

    /** "page 2 blurry, page 4 too dark", or null when every page looks fine. */
    private fun pageIssues(pages: List<File>): String? {
        val found = pages.mapIndexedNotNull { i, p ->
            val w = runCatching { Filters.quality(p).warnings }.getOrDefault(emptyList())
            if (w.isEmpty()) null else "page ${i + 1} ${ScanQuality.describe(w)}"
        }
        return found.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** Quietly checks new pages and suggests rescanning bad ones (never shows the progress dialog). */
    private fun checkQualityInBackground(scan: Scan) {
        if (!_settings.value.qualityWarnings) return
        viewModelScope.launch {
            val issues = withContext(Dispatchers.Default) { runCatching { pageIssues(scan.pages) }.getOrNull() }
            if (issues != null) _message.value = "Check ${scan.name}: $issues. Rescan with + Add pages if it can't be read."
        }
    }

    // ---- Page filters ----

    fun applyFilter(scan: Scan, indices: List<Int>, filter: PageFilter) = work("${filter.label}…") {
        indices.forEachIndexed { k, i ->
            if (indices.size > 1) _busy.value = "${filter.label}: page ${k + 1} of ${indices.size}…"
            io {
                val src = Images.decode(scan.pages[i], 2600)
                val out = Filters.apply(src, filter)
                if (out !== src) src.recycle()
                repo.replacePage(scan, i, out, reshaped = false)
                out.recycle()
            }
        }
        _message.value = if (indices.size == 1) "Done. ✏ › Restore the original page undoes it." else "Done on ${indices.size} pages"
    }

    // ---- Favourites, copies, Recycle bin ----

    fun toggleFavorite(scan: Scan) = viewModelScope.launch {
        io { repo.setExtra(scan, "favorite", !scan.favorite) }
        reload()
    }

    fun setFavorite(ids: List<String>, on: Boolean) = work("Saving…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.setExtra(it, "favorite", on) } }
    }

    fun copyScans(ids: List<String>) = work("Copying…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.copy(it, uniqueName(it.name + " copy")) } }
        _message.value = "${ids.size} copy(ies) made"
    }

    /** Renames the scans (in the order given) to "Base 1", "Base 2"… */
    fun bulkRename(ids: List<String>, base: String) = work("Renaming…") {
        val b = base.trim()
        if (b.isEmpty()) error("Type a name")
        io {
            ids.mapNotNull { repo.get(it) }.forEachIndexed { i, s ->
                repo.rename(s, if (ids.size == 1) b else "$b ${i + 1}")
            }
        }
        _message.value = "${ids.size} scan(s) renamed"
    }

    /** Pages of a scan as a new scan ([oneEach]: one new scan per page). The original is kept. */
    fun extractPages(scan: Scan, indices: List<Int>, oneEach: Boolean, then: (Scan?) -> Unit) = work("Splitting…") {
        if (indices.isEmpty()) error("Pick the pages first")
        val made = io {
            if (oneEach) {
                indices.map { i -> repo.extract(scan, listOf(i), uniqueName("${scan.name} - page ${i + 1}")) }
            } else {
                listOf(repo.extract(scan, indices, uniqueName("${scan.name} - pages ${indices.map { it + 1 }.joinToString(",")}")))
            }
        }
        reload()
        _message.value = if (made.size == 1) "New scan made: ${made[0].name}" else "${made.size} new scans made"
        then(made.singleOrNull())
    }

    fun restoreFromTrash(ids: List<String>) = work("Restoring…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.untrash(it) } }
        _message.value = "${ids.size} scan(s) restored"
    }

    fun deleteForever(ids: List<String>) = work("Deleting…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.delete(it) } }
    }

    fun emptyTrash() = work("Emptying the Recycle bin…") {
        io { _trash.value.forEach { repo.delete(it) } }
        _message.value = "Recycle bin emptied"
    }

    /** How much space the app uses, for the Storage screen. */
    fun storageReport(then: (String) -> Unit) = work("Measuring…") {
        fun size(f: File) = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val text = io {
            val scans = _scans.value
            val trash = _trash.value
            val scanBytes = scans.sumOf { size(it.dir) }
            val trashBytes = trash.sumOf { size(it.dir) }
            val cacheBytes = size(ctx.cacheDir)
            val odia = File(ctx.filesDir, "tesseract").let { if (it.exists()) size(it) else 0L }
            fun mb(b: Long) = String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
            buildString {
                append("Scans: ${scans.size} (${scans.sumOf { it.pages.size }} pages), ${mb(scanBytes)}\n")
                append("Recycle bin: ${trash.size} scan(s), ${mb(trashBytes)}\n")
                if (odia > 0) append("Odia reading data: ${mb(odia)}\n")
                append("Temporary files: ${mb(cacheBytes)}\n")
                append("\nTotal: ${mb(scanBytes + trashBytes + cacheBytes + odia)}")
            }
        }
        then(text)
    }

    fun clearTemporaryFiles() = work("Clearing…") {
        io { ctx.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
        _message.value = "Temporary files cleared"
    }

    /** Suggested file name for saving [scan]: RAHUL_KUMAR for a person's set, else the Settings template. */
    fun suggestFileName(scan: Scan): String =
        if (scan.person.isNotBlank()) Naming.personFile(scan.person, scan.ref)
        else Naming.apply(_settings.value.nameTemplate, scan.name, scan.ref, scan.folder, scan.created)

    // ---- In-app update ----

    /** A newer release than the one installed, once found. */
    private val _update = MutableStateFlow<Updater.Release?>(null)
    val update: StateFlow<Updater.Release?> = _update

    /** A downloaded update for the screen to hand to Android's installer. */
    private val _pendingInstall = MutableStateFlow<File?>(null)
    val pendingInstall: StateFlow<File?> = _pendingInstall

    fun installHandled() {
        _pendingInstall.value = null
    }

    init {
        // An update found earlier stays on offer (until it is installed) without asking GitHub again.
        val code = prefs.getInt("update_code", 0)
        if (code > BuildConfig.VERSION_CODE) {
            _update.value = Updater.Release(
                code, prefs.getString("update_name", "").orEmpty(),
                prefs.getString("update_url", "").orEmpty(), prefs.getLong("update_size", 0),
            )
        }
        checkForUpdate(manual = false)
    }

    /** Quietly at start-up (at most every 6 hours), or with a message when [manual]. */
    fun checkForUpdate(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && (!_settings.value.autoUpdate || now - prefs.getLong(KEY_LAST_CHECK, 0) < 6 * 3600_000L)) return
        val go: suspend () -> Unit = {
            val latest = io { Updater.latest() }
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (latest != null && latest.code > BuildConfig.VERSION_CODE) {
                _update.value = latest
                prefs.edit().putInt("update_code", latest.code).putString("update_name", latest.name)
                    .putString("update_url", latest.url).putLong("update_size", latest.size).apply()
                if (manual) _message.value = "Version ${latest.name} is available"
            } else if (manual) {
                _message.value = "You have the latest version (${BuildConfig.VERSION_NAME})"
            }
        }
        if (manual) {
            work("Checking for updates…") { go() }
        } else {
            viewModelScope.launch { runCatching { go() } } // no internet: just try again next time
        }
    }

    fun downloadUpdate() = work("Downloading the update…") {
        val release = _update.value ?: error("No update found")
        val file = File(ctx.cacheDir, "update/VigyanScanner-${release.code}.apk")
        io { Updater.download(release, file) { pct -> _busy.value = "Downloading the update… $pct%" } }
        _pendingInstall.value = file
    }

    // ---- Odia ----

    fun downloadOdia() = work("Downloading Odia reading data…") {
        io { OdiaOcr.download(ctx) { p -> _busy.value = "Downloading Odia reading data (once, about 5 MB)… $p%" } }
        _message.value = "Odia reading is ready. It works offline from now on."
    }

    // ---- Backup (each phone its own) ----

    private val _lastBackup = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP, 0L))
    val lastBackup: StateFlow<Long> = _lastBackup

    fun backup(target: Target) = send(target, "Making the backup…") {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Vigyan Scanner backup ${SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date())}.zip")
        val count = io { file.outputStream().use { Backup.create(ctx, it) } }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_BACKUP, now).apply()
        _lastBackup.value = now
        _message.value = "Backup made: $count scan(s), ${(file.length() + 1_048_575) / 1_048_576} MB"
        listOf(file)
    }

    fun restore(uri: Uri) = work("Restoring the backup…") {
        val result = io {
            ctx.contentResolver.openInputStream(uri)?.use { Backup.restore(ctx, it) } ?: error("Could not open the file")
        }
        _signatureList.value = signatures.list()
        _brandingVersion.value++
        _message.value = "Restored ${result.scansAdded} scan(s)" +
            (if (result.scansSkipped > 0) ", ${result.scansSkipped} already here" else "")
    }

    // ---- Filling in a paper form ----

    /** Where one detail goes on a scanned form, in the page picture's pixels (of a [pageW]-wide page). */
    class FillSpot(val key: String, val text: String, val x: Float, val baseline: Float, val height: Float, val pageW: Int)

    /**
     * Finds the form's blank labelled fields ("Name : ______") on page [index] and pairs them with
     * [details] (e.g. saved from Smart Fill on the person's Aadhaar or ID card).
     */
    fun planAutoFill(scan: Scan, index: Int, details: Map<String, String>, then: (List<FillSpot>) -> Unit) = work("Finding the blank fields…") {
        if (details.isEmpty()) error("No details to fill in. Open the person's scan (Aadhaar, ID or form), use Smart Fill and save it first.")
        val page = scan.pages.getOrNull(index) ?: error("Page not found")
        if (io { repo.pageOcr(page) } == null) ocrMissing(scan)
        val ocr = io { repo.pageOcr(page) } ?: error("Could not read this page")
        val used = mutableSetOf<String>()
        val spots = ocr.lines.mapNotNull { line ->
            val (key, end) = FormExtractor.blankLabelEnd(line.text) ?: return@mapNotNull null
            val raw = details[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!used.add(key)) return@mapNotNull null
            val value = raw.replace('\n', ' ')
            val h = (line.bottom - line.top).toFloat()
            val x = line.left + (line.right - line.left) * end.toFloat() / line.text.length.coerceAtLeast(1) + h * 0.3f
            FillSpot(key, value, x, line.bottom - h * 0.18f, h, ocr.width)
        }
        if (spots.isEmpty()) _message.value = "No empty labelled fields found on this page. Use 🔤 Details to place them by hand."
        else _message.value = "Filled ${spots.size} field(s). Check each one; drag with ✋ Move to adjust."
        then(spots)
    }

    /** Scans with saved details (Smart Fill), for filling forms. */
    fun detailSources(): List<Scan> = _scans.value.filter { it.fields.values.any { v -> v.isNotBlank() } }

    private fun draftFile(page: File) = File(page.parentFile, page.nameWithoutExtension + ".draft.json")

    fun loadDraft(page: File): String? = draftFile(page).takeIf { it.exists() }?.readText()

    fun saveDraft(page: File, json: String) {
        draftFile(page).writeText(json)
        _message.value = "Draft saved. Open ✏ on this page again to carry on."
    }

    fun deleteDraft(page: File) {
        draftFile(page).delete()
    }

    /** A picture placed on a form (photo), kept beside the page so a draft can show it again. */
    fun keepFormPicture(page: File, bitmap: android.graphics.Bitmap): String {
        val name = "formpic_${System.currentTimeMillis()}.png"
        File(page.parentFile, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    // Reusable form templates: where things go on a form, used again on the next copy of it.
    private val templateDir get() = File(ctx.filesDir, "form_templates").apply { mkdirs() }

    fun formTemplates(): List<String> =
        templateDir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

    fun saveFormTemplate(name: String, json: String) {
        File(templateDir, Exporter.safeName(name.trim()) + ".json").writeText(json)
        _message.value = "Template \"${name.trim()}\" saved"
    }

    fun loadFormTemplate(name: String): String? = File(templateDir, "$name.json").takeIf { it.exists() }?.readText()

    fun deleteFormTemplate(name: String) {
        File(templateDir, "$name.json").delete()
    }

    // ---- Writing on a page ----

    /** Saves a page after drawing / writing on it (the untouched original is kept for Undo). */
    fun savePageEdit(scan: Scan, index: Int, edited: android.graphics.Bitmap, reshaped: Boolean = false, then: () -> Unit) = work("Saving the page…") {
        io { repo.replacePage(scan, index, edited, reshaped) }
        _message.value = "Page saved"
        then()
    }

    fun hasOriginal(page: File) = repo.hasOriginal(page)

    fun restoreOriginalPage(scan: Scan, index: Int) = work("Restoring…") {
        io { repo.restoreOriginal(scan, index) }
        _message.value = "Original page restored"
    }

    // ---- Automatic sorting ----

    private val _autoSort = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SORT, true))
    val autoSort: StateFlow<Boolean> = _autoSort

    fun setAutoSort(on: Boolean) {
        _autoSort.value = on
        prefs.edit().putBoolean(KEY_AUTO_SORT, on).apply()
    }

    /**
     * Reads a new scan's text quietly (no progress dialog), works out what it is, and files it:
     * "Aadhaar card - Ravi Kumar" in "Aadhaar cards". Only scans that aren't in a folder and still
     * have their default name are touched.
     */
    private fun autoSortInBackground(scan: Scan) {
        if (!_autoSort.value || scan.folder.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val msg = sortOne(scan, quiet = true)
                reload()
                if (msg != null) _message.value = msg
            } catch (e: Exception) {
                // Sorting is a convenience; a failure just leaves the scan where it is.
            }
        }
    }

    /** Sorts one scan; returns a message, or null if it wasn't recognised. */
    private suspend fun sortOne(scan: Scan, quiet: Boolean = false): String? {
        val s = ocrMissing(scan, quiet = quiet)
        val type = DocClassifier.classify(s.text.orEmpty()) ?: return null
        val person = FormExtractor.extract(s.rows ?: s.text.orEmpty())["name"]
        val current = io { repo.get(scan.id) } ?: return null
        if (current.folder.isNotEmpty()) return null
        io {
            repo.setFolder(current, type.folder)
            if (defaultName.matches(current.name)) {
                repo.rename(repo.get(scan.id)!!, if (person.isNullOrBlank()) "${type.label} ${stamp()}" else "${type.label} - $person")
            }
        }
        return "Sorted as ${type.label} → folder \"${type.folder}\""
    }

    /** Sorts every scan that isn't in a folder yet. */
    fun sortUnsorted() = work("Sorting scans…") {
        val list = _scans.value.filter { it.folder.isEmpty() }
        if (list.isEmpty()) error("Every scan is already in a folder")
        var sorted = 0
        list.forEachIndexed { i, scan ->
            _busy.value = "Sorting scan ${i + 1} of ${list.size}…"
            if (sortOne(scan) != null) sorted++
        }
        _message.value = "Sorted $sorted of ${list.size} scan(s). The rest weren't recognised and stay under All."
    }

    // ---- Portal resizer ----

    fun portalExport(scan: Scan, spec: PortalSpec, pageIndex: Int, target: Target) = send(target, "Making it fit ${spec.maxKb} KB…") {
        val r = io { Exporter.portalFile(ctx, scan, spec, pageIndex) }
        _message.value = r.note ?: "${r.kb} KB" + (if (r.width > 0) ", ${r.width}×${r.height} px" else "")
        listOf(r.file)
    }

    // ---- Passport photo studio ----

    private var passportSource: android.graphics.Bitmap? = null
    private var passportCut: Passport.Cut? = null
    private val _passport = MutableStateFlow<Passport.Result?>(null)
    val passport: StateFlow<Passport.Result?> = _passport

    /** Everything chosen on the passport screen. */
    data class PassportLook(
        val size: PhotoSize = PhotoSize.PRESETS[0],
        val zoom: Float = 1f,
        val shiftX: Float = 0f,
        val shiftY: Float = 0f,
        /** Background colour, or null for the real background. */
        val background: Int? = android.graphics.Color.WHITE,
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        /** Cropped by hand: left, top and width as fractions of the photo (width < 0 = automatic). */
        val cropX: Float = 0f,
        val cropY: Float = 0f,
        val cropW: Float = -1f,
        /** Name and date printed in a white strip at the bottom (some portals ask for it). */
        val label: Boolean = false,
        val labelName: String = "",
        val labelDate: String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()),
    ) {
        val manual get() = cropW > 0f

        /** The size of the picture part (above the name strip, when there is one). */
        val pictureSize get() = if (label) size.copy(heightMm = size.heightMm * (1 - Passport.STRIP)) else size
    }

    private val _passportLook = MutableStateFlow(PassportLook())
    val passportLook: StateFlow<PassportLook> = _passportLook

    /** The photo as picked, for cropping by hand. */
    fun passportOriginal(): android.graphics.Bitmap? = passportSource

    private fun finishPassport(cut: Passport.Cut, look: PassportLook): android.graphics.Bitmap {
        val photo = Passport.render(cut, look.background, look.brightness, look.contrast)
        if (!look.label) return photo
        return Passport.withLabel(photo, look.labelName, look.labelDate, look.size.widthPx, look.size.heightPx).also { photo.recycle() }
    }

    private fun newPassportSource(bmp: android.graphics.Bitmap) {
        passportSource = bmp
        passportCut = null
        _passport.value = null
        _passportLook.value = _passportLook.value.copy(zoom = 1f, shiftX = 0f, shiftY = 0f, cropW = -1f)
    }

    fun passportFromUri(uri: Uri, then: () -> Unit) = work("Opening photo…") {
        newPassportSource(io { Images.decodeUri(ctx, uri, 2400) })
        then()
    }

    fun passportFromPage(page: File, then: () -> Unit) = work("Opening photo…") {
        newPassportSource(io { Images.decode(page, 2400) })
        then()
    }

    private suspend fun cutPassport(look: PassportLook) {
        val src = passportSource ?: error("Pick a photo first")
        _passportLook.value = look
        val cut = Passport.cut(
            src, look.pictureSize, look.zoom, look.shiftX, look.shiftY,
            manual = if (look.manual) floatArrayOf(look.cropX, look.cropY, look.cropW) else null,
        )
        passportCut = cut
        val photo = withContext(Dispatchers.Default) { finishPassport(cut, look) }
        _passport.value = Passport.Result(photo, cut.faceFound)
        when {
            !cut.faceFound -> _message.value = "No face found: cropped from the centre. Use a clear, front-facing photo."
            look.background != null && cut.mask == null -> _message.value = "Could not separate the person from the background, so it is kept."
        }
    }

    /** New size, face size or position: crops again (finds the face and the person). */
    fun makePassport(look: PassportLook = _passportLook.value) = work("Finding the face…") { cutPassport(look) }

    /** Background, brightness or contrast: quick, no new crop. */
    fun restylePassport(look: PassportLook) {
        // Turning the name strip on or off changes the picture's shape: crop again.
        if (look.label != _passportLook.value.label) {
            makePassport(look)
            return
        }
        _passportLook.value = look
        val cut = passportCut ?: return
        viewModelScope.launch {
            val photo = withContext(Dispatchers.Default) { finishPassport(cut, look) }
            _passport.value = Passport.Result(photo, cut.faceFound)
        }
    }

    /** Turns the original photo a quarter turn (for sideways photos) and crops again. */
    fun rotatePassport(clockwise: Boolean) = work("Turning…") {
        val src = passportSource ?: error("Pick a photo first")
        passportSource = io { Images.rotateOnWhite(src, if (clockwise) 90f else -90f) }
        cutPassport(_passportLook.value.copy(shiftX = 0f, shiftY = 0f, cropW = -1f))
    }

    enum class PassportOutput(val label: String) {
        PHOTO("Photo (JPG)"),
        PNG("Photo (PNG)"),
        UNDER_KB("Photo under a size limit (for portals)"),
        SHEET_4X6("Print: 6×4 inch photo paper (JPG)"),
        A4("Print: A4 sheet (PDF)"),
    }

    /** A4 sheet settings in mm; 0 columns / rows = as many as fit. */
    data class SheetSetup(val margin: Float = 5f, val gap: Float = 2f, val cols: Int = 0, val rows: Int = 0)

    fun a4Layout(setup: SheetSetup, size: PhotoSize = _passportLook.value.size) =
        PhotoSheet.layout(PhotoSheet.A4_W, PhotoSheet.A4_H, size.widthMm, size.heightMm, setup.margin, setup.gap, setup.cols, setup.rows)

    fun sheet4x6Count(size: PhotoSize = _passportLook.value.size) =
        PhotoSheet.layout(PhotoSheet.SIX_W, PhotoSheet.FOUR_H, size.widthMm, size.heightMm, 3f, 2f).count

    fun exportPassport(output: PassportOutput, target: Target, maxKb: Int = 50, setup: SheetSetup = SheetSetup()) = send(target, "Making the photo…") {
        val photo = _passport.value?.photo ?: error("Make the photo first")
        val size = _passportLook.value.size
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val name = "Photo ${size.widthMm.toInt()}x${size.heightMm.toInt()} ${stamp()}"
        io {
            listOf(
                when (output) {
                    PassportOutput.PHOTO -> File(dir, "$name.jpg").apply { writeBytes(Images.encode(photo, 95).jpeg) }
                    PassportOutput.PNG -> File(dir, "$name.png").apply {
                        outputStream().use { photo.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    PassportOutput.UNDER_KB -> File(dir, "$name.jpg").apply {
                        val r = SizeFit.fit(maxKb * 1024, 0, allowShrink = true) { scale, q ->
                            if (scale >= 1f) Images.encode(photo, q).jpeg
                            else {
                                val small = android.graphics.Bitmap.createScaledBitmap(photo, (photo.width * scale).toInt().coerceAtLeast(1), (photo.height * scale).toInt().coerceAtLeast(1), true)
                                Images.encode(small, q).jpeg.also { small.recycle() }
                            }
                        }
                        writeBytes(r.bytes)
                        _message.value = "${(r.bytes.size + 1023) / 1024} KB" + (if (r.scale < 1f) ", made smaller to fit" else "") +
                            (if (!r.fits) ": could not get under $maxKb KB" else "")
                    }
                    PassportOutput.SHEET_4X6 -> File(dir, "$name 6x4.jpg").apply {
                        val sheet = Passport.sheet4x6(photo, size)
                        writeBytes(Images.encode(sheet, 95).jpeg)
                        sheet.recycle()
                    }
                    PassportOutput.A4 -> File(dir, "$name A4.pdf").apply {
                        val layout = a4Layout(setup, size)
                        if (layout.count == 0) error("No photo fits: make the margin or gap smaller")
                        writeBytes(Passport.a4Sheet(photo, layout))
                    }
                },
            )
        }
    }

    // ---- Signature studio ----

    private var cutoutSource: android.graphics.Bitmap? = null
    private val _cutout = MutableStateFlow<android.graphics.Bitmap?>(null)
    val cutout: StateFlow<android.graphics.Bitmap?> = _cutout

    /** Cut-out settings: [turns] quarter turns plus a small [tilt] (degrees) to straighten it. */
    data class CutoutLook(
        val strength: Float = 0.5f,
        val ink: Cutout.Ink = Cutout.Ink.ORIGINAL,
        val darkness: Float = 0f,
        val turns: Int = 0,
        val tilt: Float = 0f,
    )

    private val _cutoutLook = MutableStateFlow(CutoutLook())
    val cutoutLook: StateFlow<CutoutLook> = _cutoutLook

    private fun newCutoutSource(bmp: android.graphics.Bitmap) {
        cutoutSource = bmp
        _cutout.value = null
        _cutoutLook.value = _cutoutLook.value.copy(turns = 0, tilt = 0f)
    }

    fun cutoutFromUris(uris: List<Uri>, then: () -> Unit) = work("Opening…") {
        newCutoutSource(io { Images.decodeUri(ctx, uris.first(), 1800) })
        then()
    }

    fun cutoutFromPage(page: File, then: () -> Unit) = work("Opening…") {
        newCutoutSource(io { Images.decode(page, 1800) })
        then()
    }

    fun makeCutout(look: CutoutLook = _cutoutLook.value) = work("Cutting out…") {
        _cutoutLook.value = look
        val src = cutoutSource ?: error("Scan a signature first")
        val result = io {
            val turned = Images.rotateOnWhite(src, look.turns * 90f + look.tilt)
            Cutout.cut(Images.toCutout(turned), look.strength, look.ink, look.darkness)?.let(Images::fromCutout)
                .also { if (turned !== src) turned.recycle() }
        }
        if (result == null) error("No ink found. Scan the signature on plain white paper.")
        _cutout.value = result
    }

    /** Eraser: makes the marks under the finger see-through. [points] are in the picture's pixels. */
    fun eraseCutout(points: List<Pair<Float, Float>>, radius: Float) {
        val img = _cutout.value ?: return
        if (points.isEmpty()) return
        val copy = img.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = radius * 2
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val path = android.graphics.Path().apply {
            moveTo(points[0].first, points[0].second)
            if (points.size == 1) lineTo(points[0].first + 0.1f, points[0].second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        android.graphics.Canvas(copy).drawPath(path, paint)
        _cutout.value = copy
    }

    /** [widthPx] > 0 resizes the signature to that width (keeping its shape). */
    fun exportCutout(png: Boolean, target: Target, widthPx: Int = 0) = send(target, "Saving…") {
        val img0 = _cutout.value ?: error("Nothing cut out yet")
        val img = if (widthPx > 0 && widthPx != img0.width) {
            android.graphics.Bitmap.createScaledBitmap(img0, widthPx, (img0.height * widthPx.toFloat() / img0.width).toInt().coerceAtLeast(1), true)
        } else img0
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        io {
            if (png) {
                listOf(File(dir, "Signature ${stamp()}.png").apply { outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } })
            } else {
                // JPG has no transparency: put it on white.
                val white = Images.onWhite(img)
                listOf(File(dir, "Signature ${stamp()}.jpg").apply { writeBytes(Images.encode(white, 95).jpeg) }).also { white.recycle() }
            }
        }
    }

    /** The signature on white, fitted into exactly [w]×[h] pixels and under [maxKb] (portal rules). */
    fun exportCutoutForPortal(w: Int, h: Int, minKb: Int, maxKb: Int, target: Target) = send(target, "Making it fit $maxKb KB…") {
        val img = _cutout.value ?: error("Nothing cut out yet")
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        io {
            val fitted = Images.fitOnWhite(img, w, h)
            val r = SizeFit.fit(maxKb * 1024, minKb * 1024, allowShrink = false) { _, q -> Images.encode(fitted, q).jpeg }
            fitted.recycle()
            val kb = (r.bytes.size + 1023) / 1024
            _message.value = when {
                !r.fits -> "Could not get it under $maxKb KB (smallest: $kb KB)"
                r.bytes.size < minKb * 1024 -> "It is $kb KB, under the $minKb KB minimum. Try a larger pixel size."
                else -> "$w×$h px, $kb KB"
            }
            listOf(File(dir, "Signature ${w}x$h.jpg").apply { writeBytes(r.bytes) })
        }
    }

    // ---- Personal signature library (kept in the app's private storage, behind the app lock) ----

    val signatures = SignatureLibrary(app)
    private val _signatureList = MutableStateFlow(signatures.list())
    val signatureList: StateFlow<List<SignatureLibrary.Item>> = _signatureList

    fun saveToLibrary(name: String) = work("Saving…") {
        val img = _cutout.value ?: error("Nothing cut out yet")
        io { signatures.add(name.trim().ifBlank { "Signature" }, img) }
        _signatureList.value = signatures.list()
        _message.value = "Saved in My signatures"
    }

    fun deleteFromLibrary(item: SignatureLibrary.Item) {
        signatures.delete(item)
        _signatureList.value = signatures.list()
    }

    fun openFromLibrary(item: SignatureLibrary.Item) = work("Opening…") {
        _cutout.value = io { android.graphics.BitmapFactory.decodeFile(item.file.path) } ?: error("Could not open it")
    }

    fun shareFromLibrary(item: SignatureLibrary.Item, target: Target) = send(target, "Saving…") {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        listOf(io { item.file.copyTo(File(dir, Exporter.safeName(item.name) + ".png"), overwrite = true) })
    }

    // ---- Portal presets ----

    private val _portalPresets = MutableStateFlow(PortalSpec.fromJson(prefs.getString(KEY_PORTAL_PRESETS, null)))
    val portalPresets: StateFlow<List<PortalSpec>> = _portalPresets

    fun savePortalPreset(spec: PortalSpec) {
        val list = _portalPresets.value.filter { it.label != spec.label } + spec
        prefs.edit().putString(KEY_PORTAL_PRESETS, PortalSpec.toJson(list)).apply()
        _portalPresets.value = list
        _message.value = "Saved \"${spec.label}\""
    }

    fun deletePortalPreset(spec: PortalSpec) {
        val list = _portalPresets.value.filter { it.label != spec.label }
        prefs.edit().putString(KEY_PORTAL_PRESETS, PortalSpec.toJson(list)).apply()
        _portalPresets.value = list
    }

    /** Makes the portal file and reports its size and whether it meets the rules (before saving). */
    fun portalCheck(scan: Scan, spec: PortalSpec, pageIndex: Int, then: (String) -> Unit) = work("Checking…") {
        val r = io { Exporter.portalFile(ctx, scan, spec, pageIndex) }
        then(r.report(spec))
    }

    // ---- College stamp & signature ----

    val branding = Branding(app)
    private val _brandingVersion = MutableStateFlow(0)
    val brandingVersion: StateFlow<Int> = _brandingVersion

    fun useCutoutAs(seal: Boolean) = work("Saving…") {
        val img = _cutout.value ?: error("Nothing cut out yet")
        io { branding.saveImage(if (seal) branding.sealFile else branding.signatureFile, img) }
        _brandingVersion.value++
        _message.value = if (seal) "Saved as the college seal" else "Saved as the principal's signature"
    }

    fun saveBrandingText(college: String, signatory: String) {
        branding.collegeName = college
        branding.signatory = signatory
        _brandingVersion.value++
        _message.value = "Saved"
    }

    fun removeBrandingImage(seal: Boolean) {
        (if (seal) branding.sealFile else branding.signatureFile).delete()
        _brandingVersion.value++
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
    /**
     * [quiet]: running in the background (automatic sorting). It must not touch the progress
     * dialog: nothing would close it afterwards, and it would cover the screen.
     */
    private suspend fun ocrMissing(scan: Scan, force: Boolean = false, prefix: String = "", quiet: Boolean = false): Scan {
        val missing = if (force) scan.pages else io { repo.pagesWithoutOcr(scan) }
        if (missing.isEmpty()) return scan
        if (_lang.value == OcrLang.ODIA && !OdiaOcr.isReady(ctx)) {
            if (quiet) return scan // never download 5 MB silently; sorting just waits
            io { OdiaOcr.download(ctx) { p -> _busy.value = "Downloading Odia reading data (once), $p%…" } }
        }
        val results = Ocr.readPages(ctx, missing, _lang.value) { i ->
            if (!quiet) _busy.value = prefix + "reading text, page ${i + 1} of ${missing.size}…"
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
        val original = FormExtractor.extract(s.rows ?: s.text.orEmpty())
        // Certificates and ID cards have patterned, coloured backgrounds that confuse the reader.
        // Read a copy with only the dark ink kept too, and take the better answer per field.
        _busy.value = prefix + "reading a cleaned-up copy…"
        val cleaned = runCatching { FormExtractor.extract(inkOnlyRows(s)) }.getOrNull()
        val fromText = if (cleaned != null) FormExtractor.merge(original, cleaned) else original
        _busy.value = prefix + "looking for an Aadhaar QR code…"
        val qr = Ocr.readQrCodes(ctx, s.pages.take(4)).firstNotNullOfOrNull { AadhaarQr.parse(it) }
        return if (qr != null) (fromText + qr) to true else fromText to false
    }

    /** Text rows of the scan's pages (first 4) with the background pattern and colours wiped out. */
    private suspend fun inkOnlyRows(scan: Scan): String {
        val dir = File(ctx.cacheDir, "inkonly").apply { deleteRecursively(); mkdirs() }
        val files = io {
            scan.pages.take(4).mapIndexed { i, page ->
                File(dir, "page$i.jpg").also { f ->
                    val src = Images.decode(page, 2400)
                    val clean = Filters.inkOnly(src)
                    src.recycle()
                    Images.save(clean, f, 92)
                    clean.recycle()
                }
            }
        }
        val results = Ocr.readPages(ctx, files, _lang.value)
        return OcrLayout.combine(results).second
    }

    private val defaultName = Regex("""^(Scan|Imported|Merged|Document \d+|Form \d+)\b.*""")

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

    fun rename(scan: Scan, name: String) = work("Saving…") {
        if (name.isBlank()) error("Type a name")
        io { repo.rename(scan, uniqueName(name, exceptId = scan.id)) }
    }

    /** Scan & merge by name: change the person's name / reference number. */
    fun setPerson(scan: Scan, person: String, ref: String) = work("Saving…") {
        io {
            repo.setExtra(scan, "person", person.trim())
            repo.setExtra(scan, "ref", ref.trim())
        }
    }

    /** Deleting moves scans to the Recycle bin (kept 30 days). */
    fun delete(scan: Scan) = work("Deleting…") {
        io { repo.trash(scan) }
        _message.value = "Moved to the Recycle bin (kept ${ScanRepository.TRASH_DAYS} days)"
    }

    fun deleteMany(ids: List<String>) = work("Deleting…") {
        io { ids.mapNotNull { repo.get(it) }.forEach { repo.trash(it) } }
        _message.value = "${ids.size} scan(s) moved to the Recycle bin (kept ${ScanRepository.TRASH_DAYS} days)"
    }

    /** Joins the scans, in the order given, into one new scan. */
    fun merge(ids: List<String>, then: (Scan) -> Unit) = work("Merging…") {
        val list = io { ids.mapNotNull { repo.get(it) } }
        if (list.size < 2) error("Pick at least two scans")
        val merged = io { repo.merge(list, uniqueName("Merged ${stamp()}")) }
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
        val full = if (needText) ocrMissing(scan) else scan
        _busy.value = "Making files…"
        // Only some pages: a copy of the scan with just those (and just their text).
        val chosen = options.pages?.filter { it in full.pages.indices }?.takeIf { it.isNotEmpty() && it.size < full.pages.size }
        val s = if (chosen == null) full else io {
            val pages = chosen.map { full.pages[it] }
            val texts = pages.map { repo.pageOcr(it)?.text }
            full.copy(pages = pages, text = if (texts.all { it != null }) texts.joinToString("\n\n") { it!!.trim() } else full.text)
        }
        val ocr = io { s.pages.map { repo.pageOcr(it) } }
        io { Exporter.files(ctx, s, options, ocr) }
    }

    /** The scan's text as an Excel table (Scan to Excel). */
    fun exportExcel(scan: Scan, target: Target) = send(target, "Making the Excel file…") {
        val s = ocrMissing(scan)
        _busy.value = "Making the Excel file…"
        val pages = io {
            s.pages.map { p -> repo.pageOcr(p)?.let { if (it.lines.isEmpty()) it.text else OcrLayout.rows(it.lines) }.orEmpty() }
        }
        listOf(io { Exporter.tableXlsx(ctx, pages, suggestFileName(s)) })
    }

    /** Saves the (edited) text, then sends it as a .txt file. */
    fun exportText(scan: Scan, text: String, target: Target) = send(target, "Exporting…") {
        io {
            repo.saveText(scan, text)
            Exporter.files(ctx, scan.copy(text = text), ExportOptions(setOf(Format.TXT)), emptyList())
        }
    }

    /** One CSV row per scan that has a filled form ([override] = unsaved edits for a single scan). */
    fun exportForms(scans: List<Scan>, name: String, target: Target, override: Map<String, String>? = null, excel: Boolean = false) =
        send(target, if (excel) "Making the Excel file…" else "Making CSV…") {
            val forms = if (override != null) listOf(override) else scans.map { it.fields }.filter { it.isNotEmpty() }
            if (forms.isEmpty()) error("No filled forms here yet. Open a scan and use Smart Fill first.")
            listOf(io { if (excel) Exporter.formsXlsx(ctx, forms, name) else Exporter.formsCsv(ctx, forms, name) })
        }

    private fun send(target: Target, label: String, makeFiles: suspend () -> List<File>) = work(label) {
        val files = makeFiles()
        if (files.isEmpty()) error("Nothing to export")
        when (target) {
            Target.PHONE -> {
                val where = io { Exporter.saveToPhone(ctx, files, _settings.value.saveTree?.let(Uri::parse)) }
                _message.value = "Saved ${files.size} file(s) to $where"
            }
            // These open another app, which needs an Activity: the screen does it.
            Target.DRIVE, Target.SHARE, Target.PRINT -> _pendingSend.value = PendingSend(target, files)
        }
    }

    private companion object {
        const val KEY_HINDI = "ocr_hindi"
        const val KEY_LANG = "ocr_lang"
        const val KEY_LAST_BACKUP = "last_backup"
        const val KEY_AUTO_SORT = "auto_sort"
        const val KEY_LAST_CHECK = "update_last_check"
        const val KEY_PORTAL_PRESETS = "portal_presets"
    }
}
