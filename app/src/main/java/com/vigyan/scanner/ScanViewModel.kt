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

    fun saveNewScan(pages: List<Uri>, sort: Boolean = false, then: (Scan) -> Unit) = work("Saving scan…") {
        val scan = io { repo.create(pages, folder = folderForNew) }
        reload() // the new scan must be in the list before its screen opens
        then(scan)
        if (sort) autoSortInBackground(scan)
    }

    /** Photos / PDFs picked in the app or shared from WhatsApp, Gallery, Files… */
    fun importUris(uris: List<Uri>, sort: Boolean = true, then: (Scan) -> Unit = { _openScan.value = it.id }) = work("Importing…") {
        val files = io { Images.import(ctx, uris, File(ctx.cacheDir, "import").apply { deleteRecursively() }) }
        if (files.isEmpty()) error("Nothing to import")
        val scan = io { repo.createFromFiles(files, "Imported ${stamp()}", folderForNew) }
        reload()
        then(scan)
        if (sort) autoSortInBackground(scan)
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
                val msg = sortOne(scan)
                reload()
                if (msg != null) _message.value = msg
            } catch (e: Exception) {
                // Sorting is a convenience; a failure just leaves the scan where it is.
            }
        }
    }

    /** Sorts one scan; returns a message, or null if it wasn't recognised. */
    private suspend fun sortOne(scan: Scan): String? {
        val s = ocrMissing(scan)
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
        val (file, note) = io { Exporter.portalFile(ctx, scan, spec, pageIndex) }
        note?.let { _message.value = it }
        listOf(file)
    }

    // ---- Passport photo ----

    private var passportSource: android.graphics.Bitmap? = null
    private val _passport = MutableStateFlow<Passport.Result?>(null)
    val passport: StateFlow<Passport.Result?> = _passport

    fun passportFromUri(uri: Uri, then: () -> Unit) = work("Opening photo…") {
        passportSource = io { Images.decodeUri(ctx, uri, 2400) }
        _passport.value = null
        then()
    }

    fun passportFromPage(page: File, then: () -> Unit) = work("Opening photo…") {
        passportSource = io { Images.decode(page, 2400) }
        _passport.value = null
        then()
    }

    fun makePassport(white: Boolean, zoom: Float) = work(if (white) "Finding the face, whitening the background…" else "Finding the face…") {
        val src = passportSource ?: error("Pick a photo first")
        val result = Passport.make(src, white, zoom)
        _passport.value = result
        if (!result.faceFound) _message.value = "No face found: cropped from the centre. Use a clear, front-facing photo."
    }

    enum class PassportOutput(val label: String) {
        PHOTO("Photo (JPG, 35×45 mm)"),
        PHOTO_50KB("Photo under 50 KB (for portals)"),
        SHEET_4X6("Print: 4×6 inch sheet, 8 photos"),
        A4("Print: A4 PDF, 30 photos"),
    }

    fun exportPassport(output: PassportOutput, target: Target) = send(target, "Making the photo…") {
        val photo = _passport.value?.photo ?: error("Make the photo first")
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val name = "Passport photo ${stamp()}"
        io {
            listOf(
                when (output) {
                    PassportOutput.PHOTO -> File(dir, "$name.jpg").apply { writeBytes(Images.encode(photo, 95).jpeg) }
                    PassportOutput.PHOTO_50KB -> File(dir, "$name.jpg").apply {
                        writeBytes(SizeFit.fit(50 * 1024, 20 * 1024, allowShrink = false) { _, q -> Images.encode(photo, q).jpeg }.bytes)
                    }
                    PassportOutput.SHEET_4X6 -> File(dir, "$name 4x6.jpg").apply {
                        val sheet = Passport.sheet4x6(photo)
                        writeBytes(Images.encode(sheet, 95).jpeg)
                        sheet.recycle()
                    }
                    PassportOutput.A4 -> File(dir, "$name A4.pdf").apply { writeBytes(Passport.a4Sheet(photo)) }
                },
            )
        }
    }

    // ---- Signature / stamp cut-out ----

    private var cutoutSource: android.graphics.Bitmap? = null
    private val _cutout = MutableStateFlow<android.graphics.Bitmap?>(null)
    val cutout: StateFlow<android.graphics.Bitmap?> = _cutout

    fun cutoutFromUris(uris: List<Uri>, then: () -> Unit) = work("Opening…") {
        cutoutSource = io { Images.decodeUri(ctx, uris.first(), 1800) }
        _cutout.value = null
        then()
    }

    fun cutoutFromPage(page: File, then: () -> Unit) = work("Opening…") {
        cutoutSource = io { Images.decode(page, 1800) }
        _cutout.value = null
        then()
    }

    fun makeCutout(strength: Float, ink: Cutout.Ink) = work("Cutting out…") {
        val src = cutoutSource ?: error("Scan a signature first")
        val result = io { Cutout.cut(Images.toCutout(src), strength, ink)?.let(Images::fromCutout) }
        if (result == null) error("No ink found. Scan the signature on plain white paper.")
        _cutout.value = result
    }

    fun exportCutout(png: Boolean, target: Target) = send(target, "Saving…") {
        val img = _cutout.value ?: error("Nothing cut out yet")
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        io {
            if (png) {
                listOf(File(dir, "Signature ${stamp()}.png").apply { outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } })
            } else {
                // JPG has no transparency: put it on white.
                val white = android.graphics.Bitmap.createBitmap(img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(white).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(img, 0f, 0f, null) }
                listOf(File(dir, "Signature ${stamp()}.jpg").apply { writeBytes(Images.encode(white, 95).jpeg) }).also { white.recycle() }
            }
        }
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

    fun saveBrandingText(college: String, address: String, signatory: String) {
        branding.collegeName = college
        branding.address = address
        branding.signatory = signatory
        _brandingVersion.value++
        _message.value = "Saved"
    }

    fun removeBrandingImage(seal: Boolean) {
        (if (seal) branding.sealFile else branding.signatureFile).delete()
        _brandingVersion.value++
    }

    // ---- Document checklist ----

    private val checklistRepo = ChecklistRepository(app)
    private val _checklist = MutableStateFlow(checklistRepo.load())
    val checklist: StateFlow<ChecklistRepository.Data> = _checklist

    private fun saveChecklist(types: List<String> = _checklist.value.types, students: List<ChecklistStudent>) {
        val data = ChecklistRepository.Data(types, students)
        checklistRepo.save(data)
        _checklist.value = data
    }

    fun addStudent(name: String, mobile: String, klass: String): String {
        val id = System.currentTimeMillis().toString()
        saveChecklist(students = _checklist.value.students + ChecklistStudent(id, name.trim(), mobile.trim(), klass.trim()))
        _message.value = "${name.trim()} added to the document checklist"
        return id
    }

    fun updateStudent(student: ChecklistStudent) =
        saveChecklist(students = _checklist.value.students.map { if (it.id == student.id) student else it })

    fun deleteStudent(id: String) = saveChecklist(students = _checklist.value.students.filter { it.id != id })

    fun setDoc(studentId: String, type: String, value: String?) {
        val s = _checklist.value.students.firstOrNull { it.id == studentId } ?: return
        updateStudent(s.copy(docs = if (value == null) s.docs - type else s.docs + (type to value)))
    }

    fun setChecklistTypes(types: List<String>) =
        saveChecklist(types = types.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), students = _checklist.value.students)

    /** Scans one document for a student, names it "<student> - <document>" and ticks it off. */
    fun scanForStudent(studentId: String, type: String, pages: List<Uri>) = work("Saving…") {
        val s = _checklist.value.students.firstOrNull { it.id == studentId } ?: error("Student not found")
        val scan = io { repo.create(pages, "${s.name} - $type", STUDENT_DOCS) }
        setDoc(studentId, type, scan.id)
        _message.value = "$type saved for ${s.name}"
    }

    /** Checklist as a CSV: one row per student, Yes/No per document. */
    fun exportChecklist(target: Target) = send(target, "Making CSV…") {
        val data = _checklist.value
        val ids = _scans.value.map { it.id }.toSet()
        val sb = StringBuilder()
        fun cell(v: String) = if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        sb.append((listOf("Name", "Class", "Mobile") + data.types + "Missing").joinToString(",") { cell(it) }).append("\r\n")
        data.students.sortedBy { it.name.lowercase() }.forEach { s ->
            val cells = listOf(s.name, s.klass, s.mobile) +
                data.types.map { if (Checklist.has(s, it, ids)) "Yes" else "No" } +
                Checklist.missing(s, data.types, ids).size.toString()
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        listOf(File(dir, Exporter.csvName("Document checklist") + ".csv").apply { writeText(sb.toString()) })
    }

    // ---- Marksheet & merit list ----

    /** Saved marks, or read them from the scan (OCR) with the name and roll number filled in. */
    fun readMarks(scan: Scan, force: Boolean = false, then: (MarksRecord) -> Unit) = work("Reading the marksheet…") {
        if (!force) io { repo.loadMarks(scan) }?.let { return@work then(it) }
        val s = ocrMissing(scan)
        val rows = s.rows ?: s.text.orEmpty()
        val found = MarksheetExtractor.extract(rows)
        val fields = FormExtractor.extract(rows)
        val saved = io { repo.loadMarks(scan) }
        then(
            found.copy(
                name = saved?.name ?: fields["name"] ?: scan.fields["name"].orEmpty(),
                roll = saved?.roll ?: fields["roll"] ?: scan.fields["roll"].orEmpty(),
                category = saved?.category.orEmpty(),
            ),
        )
        if (found.subjects.isEmpty()) _message.value = "No subject marks found. Add them by hand, or rescan more clearly."
    }

    fun saveMarks(scan: Scan, marks: MarksRecord) = work("Saving…") {
        io {
            repo.saveMarks(scan, marks)
            if (marks.name.isNotBlank() && defaultName.matches(scan.name)) repo.rename(scan, "Marksheet - ${marks.name}")
        }
        _message.value = "Marks saved (${Merit.pct(marks.percent)}%)"
    }

    /** Scans in [scans] with saved marks, ranked. */
    fun meritEntries(scans: List<Scan>, then: (List<Merit.Ranked>) -> Unit) = work("Collecting marks…") {
        val entries = io {
            scans.filter { it.hasMarks }.mapNotNull { s ->
                repo.loadMarks(s)?.let { m -> Merit.Entry(s.id, m.name.ifBlank { s.name }, m.roll, m.category, m.total, m.maxTotal) }
            }
        }
        then(Merit.rank(entries))
    }

    fun exportMerit(title: String, ranked: List<Merit.Ranked>, pdf: Boolean, csv: Boolean, target: Target) =
        send(target, "Making the merit list…") {
            if (ranked.isEmpty()) error("No saved marksheets here. Open a scanned marksheet and tap Marksheet first.")
            io { Exporter.meritFiles(ctx, title, ranked, pdf, csv) }
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
        const val KEY_AUTO_SORT = "auto_sort"
        const val STUDENT_DOCS = "Student documents"
    }
}
