package com.vigyan.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vigyan.scanner.ExportOptions
import com.vigyan.scanner.Format
import com.vigyan.scanner.PortalSpec
import com.vigyan.scanner.Quality
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    vm: ScanViewModel,
    scan: Scan,
    openResize: Boolean,
    onBack: () -> Unit,
    onText: () -> Unit,
    onFill: () -> Unit,
    /** "marks", "passport", "cutout" or "branding". */
    onTool: (String) -> Unit,
) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val brandingVersion by vm.brandingVersion.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(if (openResize) "resize" else "") } // rename, delete, folder, deletePage, resize
    var attested by rememberSaveable { mutableStateOf(false) }
    var seal by rememberSaveable { mutableStateOf(false) }
    var signature by rememberSaveable { mutableStateOf(false) }
    var letterPad by rememberSaveable { mutableStateOf(false) }
    var pageToDelete by rememberSaveable { mutableStateOf(-1) }

    var pdf by rememberSaveable { mutableStateOf(true) }
    var jpg by rememberSaveable { mutableStateOf(false) }
    var txt by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf(Quality.NORMAL) }
    var searchable by rememberSaveable { mutableStateOf(true) }
    var idCard by rememberSaveable { mutableStateOf(false) }
    var lock by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val addPages = rememberScanner(onError = vm::say) { uris -> vm.addPages(scan, uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scan.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { dialog = "rename" }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(scan.pages, key = { _, page -> page.name + page.lastModified() }) { i, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            // lastModified in the key reloads a page after it is rotated.
                            model = ImageRequest.Builder(LocalContext.current).data(page).memoryCacheKey(page.path + page.lastModified()).build(),
                            contentDescription = "Page ${i + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(280.dp).width(200.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text("Page ${i + 1} of ${scan.pages.size}", style = MaterialTheme.typography.labelMedium)
                        Row {
                            SmallButton("◀", enabled = i > 0) { vm.movePage(scan, i, i - 1) }
                            SmallButton("⟳") { vm.rotatePage(scan, i) }
                            SmallButton("⛶") { onTool("crop/${scan.id}/$i") }
                            SmallButton("✏") { onTool("annotate/${scan.id}/$i") }
                            SmallButton("✕", enabled = scan.pages.size > 1) { pageToDelete = i; dialog = "deletePage" }
                            SmallButton("▶", enabled = i < scan.pages.size - 1) { vm.movePage(scan, i, i + 1) }
                        }
                    }
                }
            }
            Text(
                "◀ ▶ move · ⟳ turn · ⛶ adjust corners / straighten · ✏ write, highlight or sign · ✕ delete",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addPages(100) }, modifier = Modifier.weight(1f)) { Text("+ Add pages") }
                OutlinedButton(onClick = { dialog = "folder" }, modifier = Modifier.weight(1f)) {
                    Text(if (scan.folder.isEmpty()) "Folder: none" else "Folder: ${scan.folder}", maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onText, modifier = Modifier.weight(1f)) { Text("Text (OCR)") }
                FilledTonalButton(onClick = onFill, modifier = Modifier.weight(1f)) { Text("Smart Fill") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { dialog = "resize" }, modifier = Modifier.weight(1f)) { Text("Resize for portal", maxLines = 1) }
                FilledTonalButton(onClick = { onTool("marks") }, modifier = Modifier.weight(1f)) { Text("Marksheet") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.passportFromPage(scan.pages.first()) { onTool("passport") } },
                    modifier = Modifier.weight(1f),
                ) { Text("Passport photo", maxLines = 1) }
                FilledTonalButton(
                    onClick = { vm.cutoutFromPage(scan.pages.first()) { onTool("cutout") } },
                    modifier = Modifier.weight(1f),
                ) { Text("Cut out signature", maxLines = 1) }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Save or upload as", style = MaterialTheme.typography.titleMedium)
                    CheckRow("PDF", pdf) { pdf = it }
                    CheckRow("JPG images (one per page)", jpg) { jpg = it }
                    CheckRow("Text file (.txt)", txt) { txt = it }

                    Text("Size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Quality.values().forEach { q ->
                            FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) })
                        }
                    }
                    Text(quality.hint, style = MaterialTheme.typography.bodySmall)

                    if (pdf) {
                        Text("PDF options", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        CheckRow("Searchable: you can find and copy text in the PDF", searchable) { searchable = it }
                        CheckRow("ID card: pages at real card size on one A4 sheet (Aadhaar front + back)", idCard) { idCard = it }
                        CheckRow("Lock with a password", lock) { lock = it }
                        if (lock) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password to open the PDF") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Remember it: nobody can open the PDF without it. Only English letters and numbers.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (pdf || jpg) {
                        // Re-read when the settings screen changes the seal / signature.
                        val hasSeal = remember(brandingVersion) { vm.branding.hasSeal() }
                        val hasSig = remember(brandingVersion) { vm.branding.hasSignature() }
                        Text("College stamp", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        if (idCard) {
                            Text("Not used with the ID card layout.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            CheckRow("Space for letter pad: blank space at top and bottom (A4)", letterPad) { letterPad = it }
                            CheckRow("\"ATTESTED - TRUE COPY\" stamp with date", attested) { attested = it }
                            CheckRow(if (hasSig) "Principal's signature" else "Principal's signature (not set up yet)", signature && hasSig) {
                                if (hasSig) signature = it else onTool("branding")
                            }
                            CheckRow(if (hasSeal) "College seal" else "College seal (not set up yet)", seal && hasSeal) {
                                if (hasSeal) seal = it else onTool("branding")
                            }
                            TextButton(onClick = { onTool("branding") }) { Text("College name, seal & signature settings") }
                        }
                    }

                    val formats = buildSet {
                        if (pdf) add(Format.PDF)
                        if (jpg) add(Format.JPG)
                        if (txt) add(Format.TXT)
                    }
                    val passwordOk = !pdf || !lock || (password.isNotEmpty() && password.all { it.code in 32..126 })
                    Column(Modifier.padding(top = 8.dp)) {
                        SendButtons(
                            enabled = formats.isNotEmpty() && passwordOk,
                            onDenied = { vm.say("Storage permission is needed to save to the phone") },
                        ) { target ->
                            val options = ExportOptions(
                                formats = formats,
                                quality = quality,
                                searchable = searchable,
                                idCard = idCard,
                                password = password.takeIf { lock && it.isNotEmpty() },
                                attested = attested,
                                seal = seal && vm.branding.hasSeal(),
                                signature = signature && vm.branding.hasSignature(),
                                letterPad = letterPad,
                            )
                            vm.export(scan, options, target)
                        }
                    }
                    Text(
                        "Save to phone puts files in Download/Vigyan Scanner. Google Drive opens Drive so you can pick the account and folder.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    when (dialog) {
        "rename" -> {
            var name by rememberSaveable { mutableStateOf(scan.name) }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Rename") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
                confirmButton = { TextButton(onClick = { vm.rename(scan, name); dialog = "" }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete this scan?") },
            text = { Text("It is removed from this app. Files you already saved to the phone or Drive stay there.") },
            confirmButton = { TextButton(onClick = { dialog = ""; vm.delete(scan); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
        "deletePage" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete page ${pageToDelete + 1}?") },
            confirmButton = {
                TextButton(onClick = {
                    if (pageToDelete in scan.pages.indices) vm.deletePage(scan, pageToDelete)
                    dialog = ""
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
        "resize" -> ResizeDialog(vm, scan, onDismiss = { dialog = "" })
        "folder" -> FolderDialog(
            folders = folders,
            current = scan.folder,
            onPick = { f -> vm.moveToFolder(listOf(scan.id), f); dialog = "" },
            onDismiss = { dialog = "" },
        )
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Text(label) }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** Portal resizer: pick a preset (or custom size) and get a file under the portal's KB limit. */
@Composable
private fun ResizeDialog(vm: ScanViewModel, scan: Scan, onDismiss: () -> Unit) {
    var preset by rememberSaveable { mutableStateOf(0) } // index in PRESETS, or -1 = custom
    var page by rememberSaveable { mutableStateOf(0) }
    var gray by rememberSaveable { mutableStateOf(false) }
    var customPdf by rememberSaveable { mutableStateOf(false) }
    var customW by rememberSaveable { mutableStateOf("") }
    var customH by rememberSaveable { mutableStateOf("") }
    var customMin by rememberSaveable { mutableStateOf("") }
    var customMax by rememberSaveable { mutableStateOf("100") }

    val spec = if (preset >= 0) {
        PortalSpec.PRESETS[preset].copy(gray = gray)
    } else {
        PortalSpec(
            label = "Custom",
            pdf = customPdf,
            width = if (customPdf) 0 else customW.toIntOrNull() ?: 0,
            height = if (customPdf) 0 else customH.toIntOrNull() ?: 0,
            minKb = customMin.toIntOrNull() ?: 0,
            maxKb = customMax.toIntOrNull() ?: 0,
            gray = gray,
            suffix = "resized",
        )
    }
    val valid = spec.maxKb > 0 && spec.minKb <= spec.maxKb && (spec.width > 0) == (spec.height > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize for portal") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Check the portal's instructions for the exact pixel size and KB limit, and use Custom if they differ.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PortalSpec.PRESETS.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { preset = i }) {
                        RadioButton(selected = preset == i, onClick = { preset = i })
                        Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { preset = -1 }) {
                    RadioButton(selected = preset == -1, onClick = { preset = -1 })
                    Text("Custom", style = MaterialTheme.typography.bodyMedium)
                }
                if (preset == -1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !customPdf, onClick = { customPdf = false }, label = { Text("JPG") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = customPdf, onClick = { customPdf = true }, label = { Text("PDF") })
                    }
                    if (!customPdf) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Width px", customW, Modifier.weight(1f)) { customW = it }
                            NumberField("Height px", customH, Modifier.weight(1f)) { customH = it }
                        }
                        Text("Leave both blank to keep the shape.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Min KB", customMin, Modifier.weight(1f)) { customMin = it }
                        NumberField("Max KB", customMax, Modifier.weight(1f)) { customMax = it }
                    }
                }
                CheckRow("Black & white (smaller files, fine for documents)", gray) { gray = it }
                if (!spec.pdf && scan.pages.size > 1) {
                    Text("Which page", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        scan.pages.indices.forEach { i ->
                            FilterChip(selected = page == i, onClick = { page = i }, label = { Text("Page ${i + 1}") })
                        }
                    }
                }
                if (!spec.pdf && spec.width > 0) {
                    Text(
                        "The page is cropped from the centre to this shape. For a face photo, use Passport photo first, then resize that.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Column(Modifier.padding(top = 8.dp)) {
                    SendButtons(enabled = valid, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                        vm.portalExport(scan, spec, page, target)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
