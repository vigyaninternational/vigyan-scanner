package com.vigyan.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One phone's own backup: a single .zip with all its scans, the document checklist and the
 * college seal/signature. It is kept wherever the user saves it (this phone, or their own Google
 * Drive), never shared with other staff. Restoring ADDS what is missing and never deletes.
 */
object Backup {

    private const val INFO = "backup-info.json"
    private val ROOTS = listOf("scans", "checklist.json", "branding", "signatures", "form_templates")

    class Result(val scansAdded: Int, val scansSkipped: Int, val studentsAdded: Int)

    /** Writes the backup zip; returns how many scans it holds. */
    fun create(context: Context, out: OutputStream): Int {
        val files = context.filesDir
        val scans = File(files, "scans").listFiles { f -> f.isDirectory }?.size ?: 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(1) // photos are already compressed: go for speed
            zip.putNextEntry(ZipEntry(INFO))
            val branding = context.getSharedPreferences("branding", Context.MODE_PRIVATE)
            zip.write(
                JSONObject()
                    .put("app", "Vigyan Scanner")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("created", System.currentTimeMillis())
                    .put("scans", scans)
                    .put("college", branding.getString("college", null) ?: JSONObject.NULL)
                    .put("signatory", branding.getString("signatory", null) ?: JSONObject.NULL)
                    .toString().toByteArray(),
            )
            zip.closeEntry()
            for (root in ROOTS) {
                val f = File(files, root)
                if (f.exists()) addTree(zip, f, root)
            }
        }
        return scans
    }

    private fun addTree(zip: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addTree(zip, it, "$path/${it.name}") }
        } else {
            zip.putNextEntry(ZipEntry(path))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Reads a backup zip and adds what this phone doesn't already have. */
    fun restore(context: Context, input: InputStream): Result {
        val temp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val tempPath = temp.canonicalPath + File.separator
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val dest = File(temp, e.name)
                    // Never write outside the temp folder (a crafted zip could try "../").
                    if (!dest.canonicalPath.startsWith(tempPath)) continue
                    if (e.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val info = File(temp, INFO)
            if (!info.exists()) error("This is not a Vigyan Scanner backup file")
            val meta = JSONObject(info.readText())
            return merge(context, temp, meta)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun merge(context: Context, temp: File, meta: JSONObject): Result {
        val files = context.filesDir
        var added = 0
        var skipped = 0

        // Scans: each is its own folder; copy the ones this phone doesn't have.
        val scansDir = File(files, "scans").apply { mkdirs() }
        File(temp, "scans").listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val dest = File(scansDir, f.name)
                if (dest.exists()) skipped++ else { f.copyRecursively(dest); added++ }
            } else if (f.name == "folders.json") {
                mergeFolders(File(scansDir, "folders.json"), f)
            }
        }

        // Checklist: add students (and document names) that aren't here yet.
        var students = 0
        val backupList = File(temp, "checklist.json")
        if (backupList.exists()) {
            val repo = ChecklistRepository(context)
            val mine = repo.load()
            val theirs = ChecklistRepository.parse(backupList.readText())
            val ids = mine.students.map { it.id }.toSet()
            val newOnes = theirs.students.filter { it.id !in ids }
            students = newOnes.size
            repo.save(ChecklistRepository.Data((mine.types + theirs.types).distinct(), mine.students + newOnes))
        }

        // Seal and signature, saved signatures and form templates: only files this phone doesn't have.
        for (folder in listOf("branding", "signatures", "form_templates")) {
            val mineDir = File(files, folder).apply { mkdirs() }
            File(temp, folder).listFiles()?.forEach { f ->
                val dest = File(mineDir, f.name)
                if (f.isFile && !dest.exists()) f.copyTo(dest)
            }
        }
        val prefs = context.getSharedPreferences("branding", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (!prefs.contains("college") && !meta.isNull("college")) edit.putString("college", meta.getString("college"))
        if (!prefs.contains("signatory") && !meta.isNull("signatory")) edit.putString("signatory", meta.getString("signatory"))
        edit.apply()

        return Result(added, skipped, students)
    }

    private fun mergeFolders(mine: File, theirs: File) {
        fun read(f: File) = try {
            JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
        mine.writeText(JSONArray((read(mine) + read(theirs)).distinct()).toString())
    }
}
