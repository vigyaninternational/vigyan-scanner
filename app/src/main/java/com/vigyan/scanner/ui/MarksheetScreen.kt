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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vigyan.scanner.MarksRecord
import com.vigyan.scanner.Merit
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import com.vigyan.scanner.SubjectMark

private val CATEGORIES = listOf("Gen", "SC", "ST", "OBC", "SEBC")

/** Rows are kept as text while editing: [subject, max, obtained]. */
private fun toRows(m: MarksRecord) = ArrayList(m.subjects.map { arrayListOf(it.subject, it.max.toString(), it.obtained.toString()) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksheetScreen(vm: ScanViewModel, scan: Scan, onBack: () -> Unit) {
    var name by rememberSaveable(scan.id) { mutableStateOf("") }
    var roll by rememberSaveable(scan.id) { mutableStateOf("") }
    var category by rememberSaveable(scan.id) { mutableStateOf("") }
    var rows by rememberSaveable(scan.id) { mutableStateOf(ArrayList<ArrayList<String>>()) }
    var printed by rememberSaveable(scan.id) { mutableStateOf("") }
    var loaded by rememberSaveable(scan.id) { mutableStateOf(false) }

    fun apply(m: MarksRecord) {
        name = m.name
        roll = m.roll
        category = m.category
        rows = toRows(m)
        printed = if (m.printedTotal > 0) "${m.printedTotal}" + (if (m.printedMax > 0) " / ${m.printedMax}" else "") else ""
        loaded = true
    }

    LaunchedEffect(scan.id) { if (!loaded) vm.readMarks(scan) { apply(it) } }

    val subjects = rows.mapNotNull { r ->
        val max = r[1].toIntOrNull() ?: return@mapNotNull null
        val got = r[2].toIntOrNull() ?: return@mapNotNull null
        SubjectMark(r[0].trim(), max, got)
    }
    val record = MarksRecord(name.trim(), roll.trim(), category, subjects)

    fun edit(i: Int, col: Int, v: String) {
        rows = ArrayList(rows.mapIndexed { k, r -> if (k == i) ArrayList(r).apply { this[col] = v } else r })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marksheet") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.readMarks(scan, force = true) { apply(it) } }) { Icon(Icons.Default.Refresh, "Read again") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AsyncImage(
                model = scan.pages.firstOrNull(),
                contentDescription = "Marksheet",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(220.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
            )
            Text("Check every number against the marksheet.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("Student name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(roll, { roll = it }, label = { Text("Roll no.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Category (for category-wise merit)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CATEGORIES.forEach { c -> FilterChip(selected = category == c, onClick = { category = if (category == c) "" else c }, label = { Text(c) }) }
            }

            Text("Subjects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            rows.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(r[0], { edit(i, 0, it) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        r[2], { edit(i, 2, it.filter(Char::isDigit)) }, label = { Text("Got") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(68.dp),
                    )
                    OutlinedTextField(
                        r[1], { edit(i, 1, it.filter(Char::isDigit)) }, label = { Text("Of") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(68.dp),
                    )
                    TextButton(onClick = { rows = ArrayList(rows.filterIndexed { k, _ -> k != i }) }, modifier = Modifier.width(40.dp)) { Text("✕") }
                }
            }
            OutlinedButton(onClick = { rows = ArrayList(rows + arrayListOf(arrayListOf("", "100", ""))) }) { Text("+ Add subject") }

            Text(
                "Total: ${record.total} / ${record.maxTotal}   ·   ${Merit.pct(record.percent)}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (printed.isNotEmpty()) {
                val printedTotal = printed.substringBefore(" ").toIntOrNull()
                Text(
                    "Printed on the marksheet: $printed" + if (printedTotal != null && printedTotal != record.total) "  ⚠ differs from the sum. Please check." else "  ✓ matches",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (printedTotal != null && printedTotal != record.total) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Button(onClick = { vm.saveMarks(scan, record) }, enabled = subjects.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Save marks")
            }
            Text(
                "Saved marksheets go into the merit list: Home › ⋮ › Merit list. Keep one folder per admission round to rank them together.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Merit list for the scans shown on the home screen (a folder, or all). */
@Composable
fun MeritDialog(vm: ScanViewModel, scans: List<Scan>, folder: String?, onDismiss: () -> Unit) {
    var ranked by remember { mutableStateOf<List<Merit.Ranked>?>(null) }
    var pdf by rememberSaveable { mutableStateOf(true) }
    var csv by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { vm.meritEntries(scans) { ranked = it } }
    val title = "Merit list" + (folder?.let { " - $it" } ?: "")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = ranked
                when {
                    list == null -> Text("Collecting marks…")
                    list.isEmpty() -> Text("No saved marksheets here yet. Open a scanned marksheet, tap Marksheet, check the marks and tap Save.")
                    else -> {
                        Text("${list.size} student(s). Top 5:", style = MaterialTheme.typography.titleSmall)
                        list.take(5).forEach { r -> Text("${r.rank}. ${r.entry.name}: ${Merit.pct(r.entry.percent)}%") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Checkbox(checked = pdf, onCheckedChange = { pdf = it }); Text("PDF (to print)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = csv, onCheckedChange = { csv = it }); Text("CSV (Excel)")
                        }
                        Text("Overall ranks, then separate ranks per category.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        SendButtons(enabled = pdf || csv, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { t ->
                            vm.exportMerit(title, list, pdf, csv, t)
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
