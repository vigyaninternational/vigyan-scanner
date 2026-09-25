package com.vigyan.scanner.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.vigyan.scanner.R
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What to open after the scanner finishes. */
enum class ScanMode { DOCUMENT, TEXT, FILL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ScanViewModel, onOpen: (Scan, ScanMode) -> Unit) {
    val activity = LocalContext.current as Activity
    val scans by vm.scans.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(ScanMode.DOCUMENT) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var csvDialog by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pages = result?.pages?.map { it.imageUri }.orEmpty()
            if (pages.isNotEmpty()) vm.saveNewScan(pages, result?.pdf?.uri) { onOpen(it, mode) }
        }
    }

    fun startScanner(m: ScanMode) {
        mode = m
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (m == ScanMode.FILL) 5 else 60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { vm.say("Scanner could not start: ${it.message}. Update Google Play services and try again.") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vigyan Scanner") },
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.logo_vigyan),
                        contentDescription = "Vigyan International logo",
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(40.dp)
                            .clip(CircleShape).background(Color.White).padding(3.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Export all filled forms (CSV)") },
                            onClick = { menu = false; csvDialog = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.logo_vigyan),
                        contentDescription = "Vigyan International logo",
                        modifier = Modifier.size(120.dp),
                    )
                    Text(
                        "Vigyan International",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text("Document Scanner", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            item {
                ActionCard("Scan document", "Save as PDF or JPG, to your phone or Google Drive") { startScanner(ScanMode.DOCUMENT) }
            }
            item {
                ActionCard("Scan to text (OCR)", "Read the printed text, then edit, copy or save it as .txt") { startScanner(ScanMode.TEXT) }
            }
            item {
                ActionCard("Scan & fill a form", "Admission form, Aadhaar or ID card: name, DOB, mobile… filled in for you") { startScanner(ScanMode.FILL) }
            }
            item {
                Text(
                    if (scans.isEmpty()) "No scans yet. Tap one of the buttons above. You can also pick photos from the gallery inside the scanner."
                    else "My scans (${scans.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(scans, key = { it.id }) { scan -> ScanRow(scan) { onOpen(scan, ScanMode.DOCUMENT) } }
        }
    }

    if (csvDialog) {
        AlertDialog(
            onDismissRequest = { csvDialog = false },
            title = { Text("Export all filled forms") },
            text = {
                Column {
                    Text(
                        "One CSV file with a row for every scan you used Smart Fill on. It opens in Excel or " +
                            "Google Sheets, and its columns match the Vigyan ERP student import.",
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    SendButtons(onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                        csvDialog = false
                        vm.exportForms(null, null, target)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { csvDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ScanRow(scan: Scan, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = scan.pages.firstOrNull(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(scan.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(scan.created)) +
                        " · ${scan.pages.size} page(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                val tags = listOfNotNull("Text".takeIf { scan.text != null }, "Form filled".takeIf { scan.fields.isNotEmpty() })
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
