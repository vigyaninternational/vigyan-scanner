package com.vigyan.scanner

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * "My signatures": see-through signature (and stamp) pictures saved for reuse on forms and
 * documents. They stay in the app's private storage (behind the app lock, and in its backups).
 */
class SignatureLibrary(context: Context) {

    class Item(val id: String, val name: String, val file: File)

    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }

    fun list(): List<Item> =
        dir.listFiles { f -> f.extension == "png" }.orEmpty()
            .map { f -> Item(f.nameWithoutExtension, File(dir, f.nameWithoutExtension + ".txt").takeIf { it.exists() }?.readText()?.trim() ?: "Signature", f) }
            .sortedByDescending { it.id }

    fun add(name: String, image: Bitmap): Item {
        val id = System.currentTimeMillis().toString()
        val file = File(dir, "$id.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$id.txt").writeText(name)
        return Item(id, name, file)
    }

    fun delete(item: Item) {
        item.file.delete()
        File(dir, item.id + ".txt").delete()
    }
}
