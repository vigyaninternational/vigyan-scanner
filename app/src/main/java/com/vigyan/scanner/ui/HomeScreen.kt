package com.vigyan.scanner.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vigyan.scanner.BuildConfig
import com.vigyan.scanner.Exporter
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
fun HomeScreen(vm: ScanViewModel, onOpen: (Scan, ScanMode) -> Unit, onNavigate: (String) -> Unit) {
    val scans by vm.scans.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val folder by vm.currentFolder.collectAsStateWithLifecycle()
    val hindi by vm.hindi.collectAsStateWithLifecycle()
    val autoSort by vm.autoSort.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mode by rememberSaveable { mutableStateOf(ScanMode.DOCUMENT) }
    var batchPerForm by rememberSaveable { mutableStateOf(0) } // 0 = not a batch scan
    var query by rememberSaveable { mutableStateOf("") }
    // Selected scan ids, in the order they were tapped (that is the merge order).
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") } // csv, language, newFolder, batch, move, delete, deleteFolder

    val startScanner = rememberScanner(onError = vm::say) { pages ->
        if (batchPerForm > 0) {
            vm.batchFill(pages, batchPerForm) {}
            batchPerForm = 0
        } else {
            vm.saveNewScan(pages, sort = mode == ScanMode.DOCUMENT) { onOpen(it, mode) }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris) { onOpen(it, ScanMode.DOCUMENT) }
    }

    // Resize for portal: pick a photo/PDF, then open it with the resize dialog showing.
    val resizePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, sort = false) { onNavigate("scan/${it.id}?resize=1") }
    }
    // Passport photo: from the camera or the gallery.
    var cameraUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.passportFromUri(uri) { onNavigate("passport") }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.passportFromUri(uri) { onNavigate("passport") }
    }
    // Signature cut-out: scan the signature with the document scanner.
    val signatureScanner = rememberScanner(onError = vm::say) { uris -> vm.cutoutFromUris(uris) { onNavigate("cutout") } }

    fun scan(m: ScanMode) {
        mode = m
        batchPerForm = 0
        startScanner(if (m == ScanMode.FILL) 5 else 100)
    }

    val inFolder = scans.filter { folder == null || it.folder == folder }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) inFolder else inFolder.filter { s ->
        s.name.lowercase().contains(q) || s.text?.lowercase()?.contains(q) == true ||
            s.fields.values.any { it.lowercase().contains(q) }
    }
    val selecting = selected.isNotEmpty()

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Default.Close, "Cancel") } },
                    actions = {
                        TextButton(onClick = {
                            vm.merge(selected) { selected = emptyList(); onOpen(it, ScanMode.DOCUMENT) }
                        }, enabled = selected.size >= 2) { Text("Merge") }
                        TextButton(onClick = { dialog = "move" }) { Text("Folder") }
                        TextButton(onClick = { dialog = "delete" }) { Text("Delete") }
                    },
                )
            } else {
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
                                text = { Text("Text language: " + if (hindi) "English + Hindi" else "English") },
                                onClick = { menu = false; dialog = "language" },
                            )
                            DropdownMenuItem(
                                text = { Text("Export filled forms (CSV)") },
                                onClick = { menu = false; dialog = "csv" },
                            )
                            DropdownMenuItem(
                                text = { Text("Merit list (from marksheets)") },
                                onClick = { menu = false; dialog = "merit" },
                            )
                            DropdownMenuItem(
                                text = { Text("College stamp & signature") },
                                onClick = { menu = false; onNavigate("branding") },
                            )
                            DropdownMenuItem(
                                text = { Text("Auto-sort new scans: " + if (autoSort) "On" else "Off") },
                                onClick = { menu = false; vm.setAutoSort(!autoSort) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sort scans not in a folder") },
                                onClick = { menu = false; vm.sortUnsorted() },
                            )
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                onClick = { menu = false; dialog = "newFolder" },
                            )
                            DropdownMenuItem(
                                text = { Text("Check for updates (you have ${BuildConfig.VERSION_NAME})") },
                                onClick = { menu = false; vm.checkForUpdate(manual = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share this app") },
                                onClick = { menu = false; dialog = "shareApp" },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            update?.let { u ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("New version ${u.name} is available", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "You have ${BuildConfig.VERSION_NAME}. Your scans are kept." +
                                        (if (u.size > 0) " (${u.size / (1024 * 1024)} MB)" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = { vm.downloadUpdate() }) { Text("Update") }
                        }
                    }
                }
            }
            if (q.isEmpty() && folder == null && !selecting) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_vigyan),
                            contentDescription = "Vigyan International logo",
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Vigyan International", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Document Scanner", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard("Scan document", "PDF or JPG", "📄", CardBlue, Modifier.weight(1f)) { scan(ScanMode.DOCUMENT) }
                        ActionCard("Scan to text", "OCR: read, edit, copy", "🔤", CardTeal, Modifier.weight(1f)) { scan(ScanMode.TEXT) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard("Scan & fill a form", "Form, Aadhaar, ID card", "📝", CardPurple, Modifier.weight(1f)) { scan(ScanMode.FILL) }
                        ActionCard("Batch fill forms", "Many forms → one sheet", "🗂️", CardIndigo, Modifier.weight(1f)) { dialog = "batch" }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard("Passport photo", "Face crop, white background", "🧑", CardPink, Modifier.weight(1f)) { dialog = "passport" }
                        ActionCard("Signature cut-out", "See-through signature / seal", "✍️", CardOrange, Modifier.weight(1f)) { signatureScanner(1) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard("Resize for portal", "Photo / PDF under X KB", "📐", CardGreen, Modifier.weight(1f)) {
                            resizePicker.launch(arrayOf("image/*", "application/pdf"))
                        }
                        ActionCard("Document checklist", "Who has submitted what", "✅", CardAmber, Modifier.weight(1f)) { onNavigate("checklist") }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { importPicker.launch(arrayOf("image/*", "application/pdf")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("📂  Open a photo or PDF from the phone") }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search names and text in all scans") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                    },
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = folder == null, onClick = { vm.showFolder(null) }, label = { Text("All (${scans.size})") }) }
                    items(folders) { f ->
                        FilterChip(
                            selected = folder == f,
                            onClick = { vm.showFolder(f) },
                            label = { Text("$f (${scans.count { it.folder == f }})") },
                        )
                    }
                    item { FilterChip(selected = false, onClick = { dialog = "newFolder" }, label = { Text("+ New folder") }) }
                }
            }

            folder?.let { f ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Folder: $f", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = "csv" }) { Text("Export forms (CSV)") }
                                TextButton(onClick = { dialog = "deleteFolder" }) { Text("Remove folder") }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    when {
                        q.isNotEmpty() -> "${shown.size} scan(s) match \"${query.trim()}\""
                        shown.isEmpty() -> "No scans here yet. Tip: press and hold a scan to select several (merge, move, delete)."
                        else -> "Scans (${shown.size}) · press and hold to select"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(shown, key = { it.id }) { s ->
                val order = selected.indexOf(s.id)
                ScanRow(
                    scan = s,
                    query = q,
                    selectedNumber = if (order >= 0) order + 1 else 0,
                    selecting = selecting,
                    onClick = {
                        if (selecting) selected = if (order >= 0) selected - s.id else selected + s.id
                        else onOpen(s, ScanMode.DOCUMENT)
                    },
                    onLongClick = { if (order < 0) selected = selected + s.id },
                )
            }
        }
    }

    when (dialog) {
        "csv" -> {
            val forms = inFolder.filter { it.fields.isNotEmpty() }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Export filled forms") },
                text = {
                    Column {
                        Text(
                            "One CSV with a row for each of the ${forms.size} filled form(s) " +
                                (folder?.let { "in \"$it\"" } ?: "in all scans") +
                                ". It opens in Excel or Google Sheets, and its columns match the Vigyan ERP student import.",
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        SendButtons(onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                            dialog = ""
                            vm.exportForms(forms, Exporter.csvName(folder ?: "Filled forms"), target)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
            )
        }
        "language" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Text language") },
            text = {
                Column {
                    ChoiceRow("English", !hindi) { vm.setHindi(false); dialog = "" }
                    ChoiceRow("English + Hindi", hindi) { vm.setHindi(true); dialog = "" }
                    Text(
                        "Used for new text reading. Odia can't be read yet. To re-read an old scan, open its text and tap the refresh button.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "newFolder" -> {
            var name by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("New folder") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("e.g. Admissions 2026") }) },
                confirmButton = { TextButton(onClick = { vm.addFolder(name); dialog = "" }, enabled = name.isNotBlank()) { Text("Create") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
            )
        }
        "batch" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Batch fill forms") },
            text = {
                Column {
                    Text(
                        "Scan all the forms one after another. Each form is filled on its own and named after the " +
                            "student, in a new folder. Then check them and export one CSV.\n\nHow many pages is each form?",
                    )
                    listOf(1, 2, 3).forEach { n ->
                        ChoiceRow(if (n == 1) "1 page (one page per student)" else "$n pages per student", false) {
                            dialog = ""
                            batchPerForm = n
                            mode = ScanMode.DOCUMENT
                            startScanner(100)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
        "move" -> FolderDialog(
            folders = folders,
            current = "",
            onPick = { f -> vm.moveToFolder(selected, f); selected = emptyList(); dialog = "" },
            onDismiss = { dialog = "" },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete ${selected.size} scan(s)?") },
            text = { Text("They are removed from this app. Files already saved to the phone or Drive stay there.") },
            confirmButton = { TextButton(onClick = { vm.deleteMany(selected); selected = emptyList(); dialog = "" }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
        "merit" -> MeritDialog(vm, inFolder, folder, onDismiss = { dialog = "" })
        "passport" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Passport photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use a clear, front-facing photo in good light.")
                    Button(onClick = {
                        dialog = ""
                        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        cameraUri = uri
                        runCatching { camera.launch(uri) }.onFailure { vm.say("Camera not available") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Take a photo") }
                    OutlinedButton(onClick = { dialog = ""; gallery.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose from gallery")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
        "shareApp" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Share Vigyan Scanner") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        dialog = ""
                        runCatching { Exporter.shareAppLink(context) }.onFailure { vm.say("Could not share: ${it.message}") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Send download link") }
                    Text(
                        "Best for WhatsApp, SMS and email. The other person opens the link in Chrome and installs, and always gets the newest version.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        dialog = ""
                        runCatching { Exporter.shareAppFile(context) }.onFailure { vm.say("Could not share: ${it.message}") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Send the app file (APK)") }
                    Text(
                        "For Quick Share / Nearby Share, Bluetooth, Google Drive or email, when there is no internet. Not for WhatsApp: it can't open app files.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "deleteFolder" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Remove folder \"${folder.orEmpty()}\"?") },
            text = { Text("Only the folder goes. Its scans are kept and appear under All.") },
            confirmButton = { TextButton(onClick = { folder?.let(vm::deleteFolder); dialog = "" }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) { Text(label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(title: String, subtitle: String, icon: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors))) {
            // A big faded copy of the icon in the corner, for decoration.
            Text(
                icon,
                fontSize = 64.sp,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 14.dp).alpha(0.22f),
            )
            Column(Modifier.padding(12.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) { Text(icon, fontSize = 18.sp) }
                Spacer(Modifier.height(6.dp))
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2)
            }
        }
    }
}

// Card colours (gradient start → end): the college logo's blue, green and pink, plus a few more.
private val CardBlue = listOf(Color(0xFF1E4FA3), Color(0xFF4A86E8))
private val CardTeal = listOf(Color(0xFF00695C), Color(0xFF26A69A))
private val CardPurple = listOf(Color(0xFF6A1B9A), Color(0xFFAB47BC))
private val CardIndigo = listOf(Color(0xFF283593), Color(0xFF5C6BC0))
private val CardPink = listOf(Color(0xFFAD1457), Color(0xFFEC407A))
private val CardOrange = listOf(Color(0xFFBF360C), Color(0xFFFF7043))
private val CardGreen = listOf(Color(0xFF1B5E20), Color(0xFF43A047))
private val CardAmber = listOf(Color(0xFFE65100), Color(0xFFFFA000))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanRow(
    scan: Scan,
    query: String,
    selectedNumber: Int,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selectedNumber > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selectedNumber > 0, onCheckedChange = { onClick() })
            }
            AsyncImage(
                model = scan.pages.firstOrNull(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (selectedNumber > 0) "$selectedNumber. " else "") + scan.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(scan.created)) +
                        " · ${scan.pages.size} page(s)" + (if (scan.folder.isNotEmpty()) " · ${scan.folder}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val info = listOfNotNull(
                    scan.fields["mobile1"],
                    scan.fields["dob"]?.let { "DOB $it" },
                    "Text".takeIf { scan.text != null && scan.fields.isEmpty() },
                ).joinToString(" · ")
                if (info.isNotEmpty()) {
                    Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                snippet(scan.text, query)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
                }
            }
        }
    }
}

/** A few words around the first place [query] appears in [text]. */
private fun snippet(text: String?, query: String): String? {
    if (text == null || query.isEmpty()) return null
    val i = text.lowercase().indexOf(query)
    if (i < 0) return null
    val start = (i - 30).coerceAtLeast(0)
    val end = (i + query.length + 50).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ') + (if (end < text.length) "…" else "")
}
