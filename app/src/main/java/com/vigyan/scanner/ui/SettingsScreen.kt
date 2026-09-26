package com.vigyan.scanner.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigyan.scanner.Exporter
import com.vigyan.scanner.Naming
import com.vigyan.scanner.OcrLang
import com.vigyan.scanner.Quality
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ScanViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lang by vm.lang.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var template by rememberSaveable { mutableStateOf(s.nameTemplate) }
    var order by rememberSaveable { mutableStateOf(s.docOrder) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.updateSettings { it.copy(saveTree = uri.toString()) }
            vm.say("Files will be saved in ${Exporter.treeLabel(uri)}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Look") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "Phone setting", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                        FilterChip(selected = s.theme == key, onClick = { vm.updateSettings { it.copy(theme = key) } }, label = { Text(label) })
                    }
                }
            }

            Section("Saving") {
                Text("Size chosen first", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Quality.values().forEach { q ->
                        FilterChip(selected = s.quality == q, onClick = { vm.updateSettings { it.copy(quality = q) } }, label = { Text(q.label) })
                    }
                }
                Text("File names", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Naming.PRESETS.forEach { p ->
                        FilterChip(
                            selected = template == p,
                            onClick = { template = p; vm.updateSettings { it.copy(nameTemplate = p) } },
                            label = { Text(Naming.describe(p)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Your own format") },
                    singleLine = true,
                    supportingText = {
                        Text("Use {name} {date} {folder} {ref}. Example: " + Naming.apply(template, "Aadhaar card - Ravi", "", "Admissions") + ".pdf")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.updateSettings { it.copy(nameTemplate = template.ifBlank { Naming.DEFAULT }) }; vm.say("Saved") }) {
                    Text("Save file name format")
                }
                Text(
                    "Scan & merge by name always suggests the person's name (RAHUL_KUMAR.pdf). You can change any name before saving.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("\"Save to phone\" puts files in", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(
                    s.saveTree?.let { Exporter.treeLabel(android.net.Uri.parse(it)) } ?: "Download/Vigyan Scanner",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { pickFolder.launch(null) }.onFailure { vm.say("No folder picker on this phone") } }) {
                        Text("Choose a folder…")
                    }
                    if (s.saveTree != null) {
                        TextButton(onClick = { vm.updateSettings { it.copy(saveTree = null) } }) { Text("Use Download") }
                    }
                }
            }

            Section("Scan & merge by name") {
                Text("Usual order of documents (one per line), shown while scanning", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = order,
                    onValueChange = { order = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                TextButton(onClick = { vm.updateSettings { it.copy(docOrder = order) }; vm.say("Saved") }) { Text("Save order") }
            }

            Section("Quick scan") {
                SwitchRow("Start with Auto capture on", s.quickAuto) { v -> vm.updateSettings { it.copy(quickAuto = v) } }
                SwitchRow("Start with Enhance (brighten) on", s.quickEnhance) { v -> vm.updateSettings { it.copy(quickEnhance = v) } }
                SwitchRow("Warn about blurry, dark or glaring pages", s.qualityWarnings) { v -> vm.updateSettings { it.copy(qualityWarnings = v) } }
            }

            Section("Reading text (OCR)") {
                OcrLang.values().forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setLang(l) }) {
                        androidx.compose.material3.RadioButton(selected = lang == l, onClick = { vm.setLang(l) })
                        Text(l.label)
                    }
                }
            }

            Section("Backup, updates & security") {
                Text("Remind me to back up", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Never", 7 to "Every week", 30 to "Every month").forEach { (days, label) ->
                        FilterChip(selected = s.backupDays == days, onClick = { vm.updateSettings { it.copy(backupDays = days) } }, label = { Text(label) })
                    }
                }
                TextButton(onClick = { onNavigate("backup") }) { Text("Backup & restore now") }
                SwitchRow("Look for app updates when the app opens", s.autoUpdate) { v -> vm.updateSettings { it.copy(autoUpdate = v) } }
                TextButton(onClick = { onNavigate("applock") }) { Text("App lock (PIN / fingerprint)") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
