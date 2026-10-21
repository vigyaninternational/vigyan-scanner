package com.vigyan.scanner

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Scan for the ERP": the Vigyan ERP app opens this (action [ACTION]) from Admission Enquiry →
 * Documents → Scan. It opens Google's document scanner straight away (camera, edges, crop, filters,
 * several pages), makes one small A4 PDF of the pages and hands it back to the ERP, which stores it
 * with the student and ticks the document Received. Nothing is kept in the Scanner's own list.
 *
 * Extra [EXTRA_TITLE] (optional): what is being scanned, e.g. "Rahul Sahu — CLC / SLC".
 */
class ErpScanActivity : ComponentActivity() {

    companion object {
        const val ACTION = "com.vigyan.scanner.action.SCAN_FOR_ERP"
        const val EXTRA_TITLE = "title"
        private const val MAX_PAGES = 10
        // A4 in points; pages are drawn to fit with a small margin.
        private const val A4_W = 595.28f
        private const val A4_H = 841.89f
        private const val MARGIN = 14f
    }

    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val pages = if (res.resultCode == Activity.RESULT_OK)
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty() else emptyList()
        if (pages.isEmpty()) { setResult(Activity.RESULT_CANCELED); finish(); return@registerForActivityResult }
        lifecycleScope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { makePdf(pages) } }.getOrNull()
            if (file == null) {
                Toast.makeText(this@ErpScanActivity, "Could not make the PDF. Please try again.", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
            } else {
                val uri = FileProvider.getUriForFile(this@ErpScanActivity, "$packageName.files", file)
                val back = Intent().setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra("pages", pages.size)
                back.clipData = ClipData.newRawUri("scan", uri)
                setResult(Activity.RESULT_OK, back)
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // the scanner is already open (screen rotated)
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(this, "Scan: $it", Toast.LENGTH_LONG).show()
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(MAX_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                Toast.makeText(this, "Scanner could not start: ${it.message}. Update Google Play services and try again.", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED); finish()
            }
    }

    /** One page per scanned image, each fitted on A4; long side 1800 px, JPEG quality 72 (~150–300 KB a page). */
    private fun makePdf(pages: List<android.net.Uri>): File {
        val dir = File(cacheDir, "export").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("erp_scan_") }?.forEach { it.delete() }   // the last hand-over is not needed any more
        val out = File(dir, "erp_scan_${System.currentTimeMillis()}.pdf")
        val pdfPages = pages.map { uri ->
            val bmp = Images.decodeUri(this, uri, 1800)
            val img = try { Images.encode(bmp, 72) } finally { bmp.recycle() }
            val scale = minOf((A4_W - 2 * MARGIN) / img.pixelWidth, (A4_H - 2 * MARGIN) / img.pixelHeight)
            val w = img.pixelWidth * scale
            val h = img.pixelHeight * scale
            PdfWriter.Page(A4_W, A4_H, listOf(PdfWriter.Placement(img, (A4_W - w) / 2f, (A4_H - h) / 2f, w, h)))
        }
        out.outputStream().use { PdfWriter().write(pdfPages, it) }
        return out
    }
}
