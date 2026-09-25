package com.vigyan.scanner

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved scan: its page images, the PDF the scanner made, and any OCR text / filled form. */
data class Scan(
    val id: String,
    val name: String,
    val created: Long,
    val dir: File,
    val pages: List<File>,
    val pdf: File?,
    val text: String?,
    /** Text rebuilt row by row (label and value side by side), used by Smart Fill. */
    val rows: String?,
    val fields: Map<String, String>,
)

/**
 * Keeps scans in the app's private storage: files/scans/<id>/ with page_001.jpg…, scan.pdf,
 * text.txt, rows.txt and meta.json (name, created, filled form fields). Nothing leaves the phone
 * until the user saves or uploads it.
 */
class ScanRepository(private val context: Context) {

    private val root = File(context.filesDir, "scans").apply { mkdirs() }

    fun list(): List<Scan> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { load(it) }
            .sortedByDescending { it.created }

    fun get(id: String): Scan? = load(File(root, id))

    /** Copies the scanner's result (temporary files) into a new saved scan. */
    fun create(pageUris: List<Uri>, pdfUri: Uri?): Scan {
        val now = System.currentTimeMillis()
        val id = now.toString()
        val dir = File(root, id).apply { mkdirs() }
        pageUris.forEachIndexed { i, uri ->
            copy(uri, File(dir, "page_%03d.jpg".format(i + 1)))
        }
        pdfUri?.let { copy(it, File(dir, PDF)) }
        val name = "Scan " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date(now))
        writeMeta(dir, name, now, emptyMap())
        return load(dir)!!
    }

    fun rename(scan: Scan, name: String) = writeMeta(scan.dir, name.trim().ifBlank { scan.name }, scan.created, scan.fields)

    fun saveFields(scan: Scan, fields: Map<String, String>) = writeMeta(scan.dir, scan.name, scan.created, fields)

    fun saveText(scan: Scan, text: String, rows: String? = null) {
        File(scan.dir, TEXT).writeText(text)
        if (rows != null) File(scan.dir, ROWS).writeText(rows)
    }

    fun delete(scan: Scan) {
        scan.dir.deleteRecursively()
    }

    private fun copy(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read the scanned file")
    }

    private fun writeMeta(dir: File, name: String, created: Long, fields: Map<String, String>) {
        val json = JSONObject()
            .put("name", name)
            .put("created", created)
            .put("fields", JSONObject(fields.filterValues { it.isNotBlank() }))
        File(dir, META).writeText(json.toString())
    }

    private fun load(dir: File): Scan? {
        val metaFile = File(dir, META)
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            val fieldsJson = json.optJSONObject("fields") ?: JSONObject()
            val fields = fieldsJson.keys().asSequence().associateWith { fieldsJson.optString(it) }
            Scan(
                id = dir.name,
                name = json.optString("name", dir.name),
                created = json.optLong("created", dir.lastModified()),
                dir = dir,
                pages = dir.listFiles { f -> f.name.startsWith("page_") && f.name.endsWith(".jpg") }
                    .orEmpty().sortedBy { it.name },
                pdf = File(dir, PDF).takeIf { it.exists() },
                text = File(dir, TEXT).takeIf { it.exists() }?.readText(),
                rows = File(dir, ROWS).takeIf { it.exists() }?.readText(),
                fields = fields,
            )
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val META = "meta.json"
        const val PDF = "scan.pdf"
        const val TEXT = "text.txt"
        const val ROWS = "rows.txt"
    }
}
