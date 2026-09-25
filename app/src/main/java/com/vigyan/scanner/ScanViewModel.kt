package com.vigyan.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScanRepository(app)

    private val _scans = MutableStateFlow<List<Scan>>(emptyList())
    val scans: StateFlow<List<Scan>> = _scans

    /** Non-null while something slow runs; the text is shown in a progress dialog. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /** One-off message for a snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refresh()
    }

    fun scan(id: String): Scan? = _scans.value.firstOrNull { it.id == id }

    fun messageShown() {
        _message.value = null
    }

    fun say(text: String) {
        _message.value = text
    }

    private fun refresh() {
        viewModelScope.launch { _scans.value = withContext(Dispatchers.IO) { repo.list() } }
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
                _scans.value = withContext(Dispatchers.IO) { repo.list() }
            }
        }
    }

    fun saveNewScan(pages: List<Uri>, pdf: Uri?, then: (Scan) -> Unit) = work("Saving scan…") {
        val scan = withContext(Dispatchers.IO) { repo.create(pages, pdf) }
        // The new scan must be in the list before its screen opens.
        _scans.value = withContext(Dispatchers.IO) { repo.list() }
        then(scan)
    }

    /** Reads text from every page (once; afterwards the saved text is used). */
    fun readText(scan: Scan, force: Boolean = false, then: (Scan) -> Unit = {}) {
        if (scan.text != null && !force) return then(scan)
        work("Reading text (OCR)…") {
            val result = Ocr.read(getApplication(), scan.pages)
            val updated = withContext(Dispatchers.IO) {
                repo.saveText(scan, result.text, result.rows)
                repo.get(scan.id)!!
            }
            if (result.text.isBlank()) _message.value = "No text found on this scan."
            then(updated)
        }
    }

    /** OCR if needed, then fills the form (keeps anything already filled and saved). */
    fun smartFill(scan: Scan, then: (Map<String, String>) -> Unit) = readText(scan) { s ->
        val found = FormExtractor.extract(s.rows ?: s.text.orEmpty())
        then(found + s.fields.filterValues { it.isNotBlank() })
    }

    fun refill(scan: Scan, then: (Map<String, String>) -> Unit) = readText(scan) { s ->
        then(FormExtractor.extract(s.rows ?: s.text.orEmpty()))
    }

    fun saveText(scan: Scan, text: String) = work("Saving…") {
        withContext(Dispatchers.IO) { repo.saveText(scan, text) }
        _message.value = "Text saved"
    }

    fun saveFields(scan: Scan, fields: Map<String, String>) = work("Saving…") {
        withContext(Dispatchers.IO) { repo.saveFields(scan, fields) }
        _message.value = "Form saved"
    }

    fun rename(scan: Scan, name: String) = work("Saving…") {
        withContext(Dispatchers.IO) { repo.rename(scan, name) }
    }

    fun delete(scan: Scan) = work("Deleting…") {
        withContext(Dispatchers.IO) { repo.delete(scan) }
    }

    enum class Target { PHONE, DRIVE, SHARE }

    fun export(scan: Scan, formats: Set<Format>, target: Target) {
        val go = { s: Scan -> send(target, "Exporting…") { Exporter.files(getApplication(), s, formats) } }
        if (Format.TXT in formats && scan.text == null) readText(scan, then = go) else go(scan)
    }

    /** Saves the (edited) text, then sends it as a .txt file. */
    fun exportText(scan: Scan, text: String, target: Target) = send(target, "Exporting…") {
        repo.saveText(scan, text)
        Exporter.files(getApplication(), scan.copy(text = text), setOf(Format.TXT))
    }

    /** CSV of one filled form, or of every saved form when [scan] is null. */
    fun exportForms(scan: Scan?, fields: Map<String, String>?, target: Target) = send(target, "Making CSV…") {
        val forms = if (scan != null) listOf(fields ?: scan.fields) else _scans.value.map { it.fields }.filter { it.isNotEmpty() }
        if (forms.isEmpty()) error("No filled forms yet. Open a scan and use Smart Fill first.")
        val name = scan?.name ?: Exporter.csvName("Filled forms")
        listOf(Exporter.formsCsv(getApplication(), forms, name))
    }

    private fun send(target: Target, label: String, makeFiles: () -> List<File>) = work(label) {
        val files = withContext(Dispatchers.IO) { makeFiles() }
        if (files.isEmpty()) error("Nothing to export")
        val ctx = getApplication<Application>()
        when (target) {
            Target.PHONE -> {
                withContext(Dispatchers.IO) { Exporter.saveToPhone(ctx, files) }
                _message.value = "Saved ${files.size} file(s) to Download/${Exporter.FOLDER}"
            }
            // These open another app, which needs an Activity context: the screen does it.
            Target.DRIVE, Target.SHARE -> _pendingSend.value = PendingSend(target, files)
        }
    }

    class PendingSend(val target: Target, val files: List<File>)

    private val _pendingSend = MutableStateFlow<PendingSend?>(null)
    val pendingSend: StateFlow<PendingSend?> = _pendingSend

    fun sendHandled() {
        _pendingSend.value = null
    }
}
