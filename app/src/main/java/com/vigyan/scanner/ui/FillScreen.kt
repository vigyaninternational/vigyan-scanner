package com.vigyan.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vigyan.scanner.Exporter
import com.vigyan.scanner.FormExtractor
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillScreen(vm: ScanViewModel, scan: Scan, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Saved as a plain HashMap so it survives screen rotation.
    var fields by rememberSaveable(scan.id) { mutableStateOf(HashMap(scan.fields)) }
    var loaded by rememberSaveable(scan.id) { mutableStateOf(false) }

    LaunchedEffect(scan.id) {
        if (!loaded) vm.smartFill(scan) { fields = HashMap(it); loaded = true }
    }

    fun asText() = FormExtractor.FIELDS.mapNotNull { f ->
        fields[f.key]?.takeIf { it.isNotBlank() }?.let { "${f.label}: $it" }
    }.joinToString("\n")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Fill") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.refill(scan) { fields = HashMap(it) } }) { Icon(Icons.Default.Refresh, "Fill again from scan") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AsyncImage(
                model = scan.pages.firstOrNull(),
                contentDescription = "Scanned page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(220.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
            )
            Text(
                "Filled from the scan. Please check every box against the paper and correct anything wrong.",
                style = MaterialTheme.typography.bodySmall,
            )
            FormExtractor.FIELDS.forEach { f ->
                OutlinedTextField(
                    value = fields[f.key].orEmpty(),
                    onValueChange = { v -> fields = HashMap(fields).apply { put(f.key, v) } },
                    label = { Text(f.label) },
                    singleLine = !f.multiLine,
                    minLines = if (f.multiLine) 2 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { vm.saveFields(scan, fields) }, modifier = Modifier.fillMaxWidth()) { Text("Save form") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    clipboard.setText(AnnotatedString(asText()))
                    vm.say("Copied")
                }, modifier = Modifier.weight(1f)) { Text("Copy") }
                FilledTonalButton(onClick = { Exporter.shareText(context, asText()) }, modifier = Modifier.weight(1f)) { Text("Send") }
                FilledTonalButton(onClick = {
                    try {
                        Exporter.addContact(context, fields.filterValues { it.isNotBlank() })
                    } catch (e: Exception) {
                        vm.say("No contacts app found")
                    }
                }, modifier = Modifier.weight(1f)) { Text("Contact") }
            }
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Save as CSV (Excel / Google Sheets)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Columns match the Vigyan ERP student CSV import. To put many forms in one sheet, use ⋮ › Export all filled forms on the home screen.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SendButtons(onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                        vm.saveFields(scan, fields)
                        vm.exportForms(scan, fields, target)
                    }
                }
            }
        }
    }
}
