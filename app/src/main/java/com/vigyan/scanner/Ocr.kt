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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which reader to use: ML Kit Latin, ML Kit Devanagari (Hindi + English), or Tesseract (Odia + English). */
enum class OcrLang(val label: String) {
    ENGLISH("English"),
    HINDI("English + Hindi"),
    ODIA("English + Odia"),
}

/**
 * On-phone OCR. English by default; the Devanagari model reads Hindi and English together, and
 * Odia goes to Tesseract (OdiaOcr). The first use of a model may take a moment while it downloads.
 */
object Ocr {

    suspend fun readPages(context: Context, pages: List<File>, lang: OcrLang, onPage: (Int) -> Unit = {}): List<PageOcr> {
        // Tesseract is slow CPU work: keep it off the screen's thread.
        if (lang == OcrLang.ODIA) return withContext(Dispatchers.Default) { OdiaOcr.readPages(context, pages, onPage) }
        val hindi = lang == OcrLang.HINDI
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
