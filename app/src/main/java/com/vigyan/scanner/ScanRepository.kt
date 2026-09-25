package com.vigyan.scanner

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved scan: its page images (in order), OCR text and any filled form. */
data class Scan(
    val id: String,
    val name: String,
    val created: Long,
    /** Folder name, or "" for none. */
    val folder: String,
    val dir: File,
    val pages: List<File>,
    val text: String?,
    /** Text rebuilt row by row (label and value side by side), used by Smart Fill. */
    val rows: String?,
    val fields: Map<String, String>,
    /** A marksheet has been read and saved for this scan (see MarksRecord). */
    val hasMarks: Boolean = false,
)

/**
 * Keeps scans in the app's private storage: files/scans/<id>/ holds the page images, a
 * <page>.ocr.json per page once its text is read, text.txt / rows.txt for the whole scan, and
 * meta.json (name, created, folder, page order, filled form). Nothing leaves the phone until
 * the user saves or uploads it.
 */
class ScanRepository(private val context: Context) {

    private val root = File(context.filesDir, "scans").apply { mkdirs() }
    private val foldersFile = File(root, "folders.json")

    fun list(): List<Scan> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { load(it) }
            .sortedByDescending { it.created }

    fun get(id: String): Scan? = load(File(root, id))

    /** A new scan from the scanner's result or other image files (copied in). */
    fun create(pages: List<Uri>, name: String? = null, folder: String = ""): Scan {
        val dir = newDir()
        val files = pages.map { uri -> File(dir, newPageName()).also { copy(uri, it) } }
        return finishNew(dir, files, name, folder)
    }

    fun createFromFiles(pages: List<File>, name: String? = null, folder: String = "", ocrToo: Boolean = false): Scan {
        val dir = newDir()
        val files = pages.map { src ->
            File(dir, newPageName()).also { dest ->
                src.copyTo(dest)
                if (ocrToo) ocrFile(src).takeIf { it.exists() }?.copyTo(ocrFile(dest))
            }
        }
        return finishNew(dir, files, name, folder)
    }

    private fun finishNew(dir: File, files: List<File>, name: String?, folder: String): Scan {
        val now = dir.name.toLong()
        val scanName = name ?: ("Scan " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date(now)))
        writeMeta(dir, scanName, now, folder, files.map { it.name }, emptyMap())
        return rebuildText(load(dir)!!)
    }

    /** Merges several scans (in the order given) into a new one; the originals are kept. */
    fun merge(scans: List<Scan>, name: String): Scan =
        createFromFiles(scans.flatMap { it.pages }, name, scans.first().folder, ocrToo = true)

    fun addPages(scan: Scan, uris: List<Uri>): Scan {
        val added = uris.map { uri -> File(scan.dir, newPageName()).also { copy(uri, it) } }
        return setPages(scan, scan.pages + added)
    }

    fun deletePage(scan: Scan, index: Int): Scan {
        val page = scan.pages[index]
        page.delete()
        ocrFile(page).delete()
        return setPages(scan, scan.pages.filterIndexed { i, _ -> i != index })
    }

    fun movePage(scan: Scan, from: Int, to: Int): Scan {
        if (to !in scan.pages.indices) return scan
        val pages = scan.pages.toMutableList()
        pages.add(to, pages.removeAt(from))
        return setPages(scan, pages)
    }

    fun rotatePage(scan: Scan, index: Int): Scan {
        val page = scan.pages[index]
        Images.rotate(page)
        ocrFile(page).delete() // the text boxes no longer fit the turned page
        return setPages(scan, scan.pages)
    }

    private fun setPages(scan: Scan, pages: List<File>): Scan {
        writeMeta(scan.dir, scan.name, scan.created, scan.folder, pages.map { it.name }, scan.fields)
        return rebuildText(load(scan.dir)!!)
    }

    fun rename(scan: Scan, name: String) =
        writeMeta(scan.dir, name.trim().ifBlank { scan.name }, scan.created, scan.folder, scan.pages.map { it.name }, scan.fields)

    fun setFolder(scan: Scan, folder: String) =
        writeMeta(scan.dir, scan.name, scan.created, folder.trim(), scan.pages.map { it.name }, scan.fields)

    fun saveFields(scan: Scan, fields: Map<String, String>) =
        writeMeta(scan.dir, scan.name, scan.created, scan.folder, scan.pages.map { it.name }, fields)

    /** The user's own edits to the text. */
    fun saveText(scan: Scan, text: String) {
        File(scan.dir, TEXT).writeText(text)
    }

    fun delete(scan: Scan) {
        scan.dir.deleteRecursively()
    }

    // ---- OCR per page ----

    private fun ocrFile(page: File) = File(page.parentFile, page.nameWithoutExtension + ".ocr.json")

    fun pageOcr(page: File): PageOcr? {
        val f = ocrFile(page)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            val arr = j.getJSONArray("lines")
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONArray(i)
                OcrLine(l.getString(0), l.getInt(1), l.getInt(2), l.getInt(3), l.getInt(4))
            }
            PageOcr(j.getInt("w"), j.getInt("h"), j.getString("text"), lines)
        } catch (e: Exception) {
            null
        }
    }

    fun savePageOcr(page: File, ocr: PageOcr) {
        val lines = JSONArray()
        ocr.lines.forEach { l -> lines.put(JSONArray().put(l.text).put(l.left).put(l.top).put(l.right).put(l.bottom)) }
        ocrFile(page).writeText(JSONObject().put("w", ocr.width).put("h", ocr.height).put("text", ocr.text).put("lines", lines).toString())
    }

    /** Pages whose text hasn't been read yet. */
    fun pagesWithoutOcr(scan: Scan) = scan.pages.filter { !ocrFile(it).exists() }

    /**
     * Re-makes text.txt / rows.txt from the pages once every page has been read. If any page is
     * unread (new or rotated), the old text is dropped so it can't describe the wrong pages.
     */
    fun rebuildText(scan: Scan): Scan {
        val ocr = scan.pages.map { pageOcr(it) }
        val text = File(scan.dir, TEXT)
        val rows = File(scan.dir, ROWS)
        if (ocr.isNotEmpty() && ocr.all { it != null }) {
            val (t, r) = OcrLayout.combine(ocr.filterNotNull())
            text.writeText(t)
            rows.writeText(r)
        } else {
            text.delete()
            rows.delete()
        }
        return load(scan.dir)!!
    }

    // ---- Marksheet ----

    private fun marksFile(scan: Scan) = File(scan.dir, "marks.json")

    fun loadMarks(scan: Scan): MarksRecord? {
        val f = marksFile(scan)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            val arr = j.getJSONArray("subjects")
            MarksRecord(
                name = j.optString("name"),
                roll = j.optString("roll"),
                category = j.optString("category"),
                subjects = (0 until arr.length()).map { i ->
                    val s = arr.getJSONArray(i)
                    SubjectMark(s.getString(0), s.getInt(1), s.getInt(2))
                },
                printedTotal = j.optInt("printedTotal"),
                printedMax = j.optInt("printedMax"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveMarks(scan: Scan, m: MarksRecord) {
        val subjects = JSONArray()
        m.subjects.forEach { subjects.put(JSONArray().put(it.subject).put(it.max).put(it.obtained)) }
        marksFile(scan).writeText(
            JSONObject().put("name", m.name).put("roll", m.roll).put("category", m.category)
                .put("subjects", subjects).put("printedTotal", m.printedTotal).put("printedMax", m.printedMax).toString(),
        )
    }

    // ---- Folders ----

    fun folders(scans: List<Scan>): List<String> {
        val saved = try {
            JSONArray(foldersFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
        return (saved + scans.map { it.folder }).filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }

    fun addFolder(name: String, scans: List<Scan>) = saveFolders(folders(scans) + name.trim())

    /** Removes a folder; its scans are kept and moved out of it. */
    fun deleteFolder(name: String, scans: List<Scan>) {
        scans.filter { it.folder == name }.forEach { setFolder(it, "") }
        saveFolders(folders(scans).filter { it != name })
    }

    private fun saveFolders(names: List<String>) {
        foldersFile.writeText(JSONArray(names.filter { it.isNotBlank() }.distinct()).toString())
    }

    // ---- Files ----

    private fun newDir(): File {
        var id = System.currentTimeMillis()
        while (File(root, id.toString()).exists()) id++
        return File(root, id.toString()).apply { mkdirs() }
    }

    private var pageCounter = 0
    private fun newPageName() = "p_${System.currentTimeMillis()}_${pageCounter++}.jpg"

    private fun copy(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read the scanned file")
    }

    private fun writeMeta(dir: File, name: String, created: Long, folder: String, pages: List<String>, fields: Map<String, String>) {
        val json = JSONObject()
            .put("name", name)
            .put("created", created)
            .put("folder", folder)
            .put("pages", JSONArray(pages))
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
            val order = json.optJSONArray("pages")
            val pages = if (order != null) {
                (0 until order.length()).map { File(dir, order.getString(it)) }.filter { it.exists() }
            } else {
                // Scans saved by version 1.0 (page_001.jpg …).
                dir.listFiles { f -> f.name.startsWith("page_") && f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
            }
            Scan(
                id = dir.name,
                name = json.optString("name", dir.name),
                created = json.optLong("created", dir.lastModified()),
                folder = json.optString("folder", ""),
                dir = dir,
                pages = pages,
                text = File(dir, TEXT).takeIf { it.exists() }?.readText(),
                rows = File(dir, ROWS).takeIf { it.exists() }?.readText(),
                fields = fields,
                hasMarks = File(dir, "marks.json").exists(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val META = "meta.json"
        const val TEXT = "text.txt"
        const val ROWS = "rows.txt"
    }
}
