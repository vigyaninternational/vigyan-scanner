package com.vigyan.scanner

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min

/**
 * On-phone OCR with ML Kit (English / Latin script). The first use may need a moment while
 * Google Play services downloads the text model.
 */
object Ocr {

    class Result(
        /** Text in ML Kit's reading order: paragraphs stay together. */
        val text: String,
        /** Text rebuilt as visual rows, so a form's label and its value land on the same line. */
        val rows: String,
    )

    suspend fun read(context: Context, pages: List<File>): Result {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val texts = mutableListOf<String>()
            val rows = mutableListOf<String>()
            pages.forEachIndexed { i, page ->
                val result = recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(page))).await()
                val header = if (pages.size > 1) "--- Page ${i + 1} ---\n" else ""
                texts += header + result.text.trim()
                val lines = result.textBlocks.flatMap { it.lines }
                    .mapNotNull { line -> line.boundingBox?.let { it to line.text } }
                rows += toRows(lines)
            }
            return Result(texts.joinToString("\n\n").trim(), rows.joinToString("\n"))
        } finally {
            recognizer.close()
        }
    }

    /** Groups text lines whose vertical centres line up into one row, left to right. */
    private fun toRows(lines: List<Pair<Rect, String>>): String {
        val rows = mutableListOf<MutableList<Pair<Rect, String>>>()
        for (item in lines.sortedBy { it.first.centerY() }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.first.centerY() }.average()
                val rowHeight = row.map { it.first.height() }.average()
                if (abs(item.first.centerY() - rowCenter) < 0.5 * min(rowHeight, item.first.height().toDouble())) {
                    row += item
                    continue
                }
            }
            rows += mutableListOf(item)
        }
        // Three spaces mark a gap between separate pieces of text on the same row.
        return rows.joinToString("\n") { row -> row.sortedBy { it.first.left }.joinToString("   ") { it.second } }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
