package com.vigyan.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vigyan.scanner.Format
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: ScanViewModel, scan: Scan, onBack: () -> Unit, onText: () -> Unit, onFill: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var pdf by rememberSaveable { mutableStateOf(true) }
    var jpg by rememberSaveable { mutableStateOf(false) }
    var txt by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scan.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(scan.pages) { i, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = page,
                            contentDescription = "Page ${i + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(300.dp).width(220.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text("Page ${i + 1} of ${scan.pages.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onText, modifier = Modifier.weight(1f)) { Text("Text (OCR)") }
                FilledTonalButton(onClick = onFill, modifier = Modifier.weight(1f)) { Text("Smart Fill") }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Save or upload as", style = MaterialTheme.typography.titleMedium)
                    CheckRow("PDF (all pages in one file)", pdf) { pdf = it }
                    CheckRow("JPG images (one per page)", jpg) { jpg = it }
                    CheckRow("Text file (.txt, reads the text first)", txt) { txt = it }
                    val formats = buildSet {
                        if (pdf) add(Format.PDF)
                        if (jpg) add(Format.JPG)
                        if (txt) add(Format.TXT)
                    }
                    SendButtons(
                        enabled = formats.isNotEmpty(),
                        onDenied = { vm.say("Storage permission is needed to save to the phone") },
                    ) { target -> vm.export(scan, formats, target) }
                    Text(
                        "Save to phone puts files in Download/Vigyan Scanner. Google Drive opens Drive so you can pick the account and folder.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(scan.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
            confirmButton = { TextButton(onClick = { vm.rename(scan, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this scan?") },
            text = { Text("It is removed from this app. Files you already saved to the phone or Drive stay there.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(scan); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
