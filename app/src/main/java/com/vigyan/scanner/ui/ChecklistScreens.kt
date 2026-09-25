package com.vigyan.scanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vigyan.scanner.Checklist
import com.vigyan.scanner.ChecklistStudent
import com.vigyan.scanner.PAPER
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(vm: ScanViewModel, onBack: () -> Unit, onStudent: (String) -> Unit) {
    val data by vm.checklist.collectAsStateWithLifecycle()
    val scans by vm.scans.collectAsStateWithLifecycle()
    val ids = scans.map { it.id }.toSet()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyIncomplete by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") } // add, types, export
    var menu by rememberSaveable { mutableStateOf(false) }

    val shown = data.students
        .filter { query.isBlank() || it.name.contains(query.trim(), true) || it.klass.contains(query.trim(), true) || it.mobile.contains(query.trim()) }
        .filter { !onlyIncomplete || Checklist.missing(it, data.types, ids).isNotEmpty() }
        .sortedBy { it.name.lowercase() }
    val complete = data.students.count { Checklist.missing(it, data.types, ids).isEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document checklist") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export checklist (CSV)") }, onClick = { menu = false; dialog = "export" })
                        DropdownMenuItem(text = { Text("Edit list of documents") }, onClick = { menu = false; dialog = "types" })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "${data.students.size} student(s) · $complete complete · ${data.students.size - complete} with documents missing",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                Button(onClick = { dialog = "add" }, modifier = Modifier.fillMaxWidth()) { Text("+ Add student") }
            }
            item {
                OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search name, class or mobile") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = onlyIncomplete, onCheckedChange = { onlyIncomplete = it })
                    Text("Show only students with missing documents")
                }
            }
            items(shown, key = { it.id }) { s ->
                val missing = Checklist.missing(s, data.types, ids)
                val done = data.types.size - missing.size
                Card(
                    Modifier.fillMaxWidth().clickable { onStudent(s.id) },
                    colors = if (missing.isEmpty()) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(s.klass, s.mobile).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { if (data.types.isEmpty()) 1f else done.toFloat() / data.types.size },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                        Text(
                            if (missing.isEmpty()) "All ${data.types.size} documents received ✓" else "$done of ${data.types.size} · missing: ${missing.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        "add" -> StudentDialog(null, onDismiss = { dialog = "" }) { name, mobile, klass ->
            dialog = ""
            onStudent(vm.addStudent(name, mobile, klass))
        }
        "types" -> {
            var text by rememberSaveable { mutableStateOf(data.types.joinToString("\n")) }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Documents to collect") },
                text = {
                    Column {
                        Text("One per line.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.setChecklistTypes(text.lines()); dialog = "" }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
            )
        }
        "export" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Export checklist") },
            text = {
                Column {
                    Text("One row per student, Yes/No for each document. Opens in Excel or Google Sheets.", modifier = Modifier.padding(bottom = 12.dp))
                    SendButtons(onDenied = { vm.say("Storage permission is needed to save to the phone") }) { t -> dialog = ""; vm.exportChecklist(t) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
    }
}

@Composable
private fun StudentDialog(student: ChecklistStudent?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(student?.name.orEmpty()) }
    var mobile by rememberSaveable { mutableStateOf(student?.mobile.orEmpty()) }
    var klass by rememberSaveable { mutableStateOf(student?.klass.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (student == null) "Add student" else "Edit student") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Student name") }, singleLine = true)
                OutlinedTextField(
                    mobile, { mobile = it.filter { c -> c.isDigit() || c == '+' || c == ' ' } },
                    label = { Text("Parent's mobile (for WhatsApp reminders)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                OutlinedTextField(klass, { klass = it }, label = { Text("Class / stream (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, mobile, klass) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDocsScreen(vm: ScanViewModel, studentId: String, onBack: () -> Unit, onOpenScan: (String) -> Unit) {
    val data by vm.checklist.collectAsStateWithLifecycle()
    val scans by vm.scans.collectAsStateWithLifecycle()
    val student = data.students.firstOrNull { it.id == studentId } ?: return
    val ids = scans.map { it.id }.toSet()
    val missing = Checklist.missing(student, data.types, ids)
    val context = LocalContext.current
    var scanningType by rememberSaveable { mutableStateOf("") }
    var dialog by rememberSaveable { mutableStateOf("") } // edit, delete

    val scanner = rememberScanner(onError = vm::say) { uris ->
        if (scanningType.isNotEmpty()) vm.scanForStudent(student.id, scanningType, uris)
        scanningType = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(student.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { dialog = "edit" }) { Text("Edit") }
                    TextButton(onClick = { dialog = "delete" }) { Text("Delete") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    listOf(student.klass, student.mobile).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "No mobile number yet (tap Edit)" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (missing.isEmpty()) "All documents received ✓" else "${missing.size} missing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (missing.isNotEmpty()) {
                item {
                    val number = Checklist.whatsAppNumber(student.mobile)
                    Button(
                        onClick = {
                            val text = Checklist.reminder(student, missing, vm.branding.collegeName)
                            val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                .onFailure { vm.say("WhatsApp not found") }
                        },
                        enabled = number != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (number != null) "Remind parent on WhatsApp" else "Add a 10-digit mobile to send reminders") }
                }
            }
            items(data.types) { type ->
                val value = student.docs[type]
                val scan = scans.firstOrNull { it.id == value }
                val has = Checklist.has(student, type, ids)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scan != null) {
                            AsyncImage(
                                model = scan.pages.firstOrNull(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(4.dp)).clickable { onOpenScan(scan.id) },
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text((if (has) "✓ " else "✗ ") + type, style = MaterialTheme.typography.titleSmall,
                                color = if (has) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(
                                when {
                                    scan != null -> "Scanned · tap the picture to open"
                                    value == PAPER -> "Received on paper"
                                    else -> "Not received"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (has) {
                            TextButton(onClick = { vm.setDoc(student.id, type, null) }) { Text("Undo") }
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                OutlinedButton(onClick = { scanningType = type; scanner(20) }) { Text("Scan") }
                                TextButton(onClick = { vm.setDoc(student.id, type, PAPER) }) { Text("On paper ✓") }
                            }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "edit" -> StudentDialog(student, onDismiss = { dialog = "" }) { name, mobile, klass ->
            vm.updateStudent(student.copy(name = name.trim(), mobile = mobile.trim(), klass = klass.trim()))
            dialog = ""
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Remove ${student.name} from the checklist?") },
            text = { Text("Their scanned documents are kept in the \"Student documents\" folder.") },
            confirmButton = { TextButton(onClick = { dialog = ""; vm.deleteStudent(student.id); onBack() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    }
}
