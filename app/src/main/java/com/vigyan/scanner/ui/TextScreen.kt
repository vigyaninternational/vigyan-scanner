package com.vigyan.scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.vigyan.scanner.Exporter
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScreen(vm: ScanViewModel, scan: Scan, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable(scan.id) { mutableStateOf(scan.text.orEmpty()) }

    // First visit: read the text now.
    LaunchedEffect(scan.id) {
        if (scan.text == null) vm.readText(scan) { text = it.text.orEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text (OCR)") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.readText(scan, force = true) { text = it.text.orEmpty() } }) {
                        Icon(Icons.Default.Refresh, "Read again")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Check the text and correct any mistakes. It reads printed English best; handwriting and Odia/Hindi may not come out right.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                label = { Text("Text") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    vm.say("Copied")
                }, modifier = Modifier.weight(1f)) { Text("Copy") }
                FilledTonalButton(onClick = { vm.saveText(scan, text) }, modifier = Modifier.weight(1f)) { Text("Save edits") }
                FilledTonalButton(onClick = { Exporter.shareText(context, text) }, modifier = Modifier.weight(1f)) { Text("Send") }
            }
            Text("Save as .txt file", style = MaterialTheme.typography.titleMedium)
            SendButtons(
                enabled = text.isNotBlank(),
                onDenied = { vm.say("Storage permission is needed to save to the phone") },
            ) { target -> vm.exportText(scan, text, target) }
        }
    }
}
