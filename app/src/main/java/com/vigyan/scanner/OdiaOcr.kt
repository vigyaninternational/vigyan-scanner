package com.vigyan.scanner

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Odia text reading with Tesseract (Google's ML Kit has no Odia). The Odia and English language
 * data (about 5.5 MB) downloads once, the first time it is used, then works offline.
 * It gives plain text only (no word positions), so an Odia PDF has no searchable layer.
 */
object OdiaOcr {

    private const val BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
    private val LANGS = listOf("ori", "eng")

    private fun root(context: Context) = File(context.filesDir, "tesseract")
    private fun data(context: Context, lang: String) = File(root(context), "tessdata/$lang.traineddata")

    fun isReady(context: Context) = LANGS.all { data(context, it).let { f -> f.exists() && f.length() > 100_000 } }

    /** Downloads the language data; [onProgress] gets 0..100 over both files. */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        LANGS.forEachIndexed { i, lang ->
            val dest = data(context, lang)
            if (dest.exists() && dest.length() > 100_000) return@forEachIndexed
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            val conn = (URL("$BASE$lang.traineddata").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) error("Download failed (${conn.responseCode})")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((i + done.toDouble() / total) * 100 / LANGS.size).toInt())
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!part.renameTo(dest)) error("Could not save the Odia data")
        }
    }

    fun readPages(context: Context, pages: List<File>, onPage: (Int) -> Unit): List<PageOcr> {
        val tess = TessBaseAPI()
        if (!tess.init(root(context).absolutePath, LANGS.joinToString("+"))) {
            tess.recycle()
            error("Odia reader could not start")
        }
        try {
            return pages.mapIndexed { i, page ->
                onPage(i)
                val (w, h) = Images.size(page)
                val bmp = Images.decode(page, 2500)
                tess.setImage(bmp)
                val text = tess.utF8Text.orEmpty().trim()
                tess.clear()
                bmp.recycle()
                PageOcr(w, h, text, emptyList())
            }
        } finally {
            tess.recycle()
        }
    }
}
