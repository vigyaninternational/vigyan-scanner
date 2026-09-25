package com.vigyan.scanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-phone OCR with ML Kit. English by default; with [hindi] on, the Devanagari model reads
 * Hindi and English together. The first use of a model may take a moment while Google Play
 * services downloads it.
 */
object Ocr {

    suspend fun readPages(context: Context, pages: List<File>, hindi: Boolean, onPage: (Int) -> Unit = {}): List<PageOcr> {
        val recognizer = if (hindi) {
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        try {
            return pages.mapIndexed { i, page ->
                onPage(i)
                val image = InputImage.fromFilePath(context, Uri.fromFile(page))
                val result = recognizer.process(image).await()
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                }
                PageOcr(image.width, image.height, result.text.trim(), lines)
            }
        } finally {
            recognizer.close()
        }
    }

    /** Text of every QR code found on the pages (used for Aadhaar QR). */
    suspend fun readQrCodes(context: Context, pages: List<File>): List<String> {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        try {
            val found = mutableListOf<String>()
            for (page in pages) {
                try {
                    val codes = scanner.process(InputImage.fromFilePath(context, Uri.fromFile(page))).await()
                    codes.mapNotNullTo(found) { it.rawValue }
                } catch (e: Exception) {
                    // A page that can't be read just has no QR code.
                }
            }
            return found
        } finally {
            scanner.close()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
