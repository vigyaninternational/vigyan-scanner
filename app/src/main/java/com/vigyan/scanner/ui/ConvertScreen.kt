package com.vigyan.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vigyan.scanner.ConvertMath
import com.vigyan.scanner.ConvertMath.PicFormat
import com.vigyan.scanner.ConvertViewModel
import com.vigyan.scanner.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The six parts of Convert files (same names as on the website). */
private enum class Part(val icon: String, val title: String, val hint: String) {
    PHOTOS_TO_PDF("🖼", "Photos → PDF", "Many photos into one PDF"),
    PDF_TO_PHOTOS("📄", "PDF → photos", "Each page as a JPG or PNG picture"),
    SIZE("📏", "Change size (KB)", "Photo or PDF under ___ KB"),
    MERGE("📚", "Merge PDFs", "Join PDFs and photos into one PDF"),
    PAGES("✂", "Pick / split pages", "Keep some pages, or each page as its own PDF"),
    FORMAT("🎨", "Change photo format", "JPG, PNG or WEBP"),
}

private fun today() = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

@Composable
fun ConvertScreen(vm: ScanViewModel, onBack: () -> Unit) {
    val cv: ConvertViewModel = viewModel()
    var partName by rememberSaveable { mutableStateOf("") }
    val part = Part.values().firstOrNull { it.name == partName }
    BackHandler(enabled = part != null) { partName = "" }

    ToolScaffold(part?.let { "${it.icon} ${it.title}" } ?: "Convert files", { if (part != null) partName = "" else onBack() }, "convert") {
        when (part) {
            null -> {
                Text("What do you want to do?", style = MaterialTheme.typography.titleMedium)
                Part.values().forEach { p ->
                    Card(
                        Modifier.fillMaxWidth().clickable { partName = p.name },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.icon, fontSize = 26.sp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(p.hint, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›", fontSize = 24.sp)
                        }
                    }
                }
                Text("Your original files are never changed. Pages of a new PDF are pictures, so its text can't be selected.", style = MaterialTheme.typography.bodySmall)
            }
            Part.PHOTOS_TO_PDF -> PhotosToPdf(vm, cv)
            Part.PDF_TO_PHOTOS -> PdfToPhotos(vm, cv)
            Part.SIZE -> ChangeSize(vm, cv)
            Part.MERGE -> MergePdfs(vm, cv)
            Part.PAGES -> PickPages(vm, cv)
            Part.FORMAT -> ChangeFormat(vm, cv)
        }
    }
}

private val denied: (ScanViewModel) -> () -> Unit = { vm -> { vm.say("Storage permission is needed to save to the phone") } }

// ---------------- Small pieces ----------------

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun KbField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        suffix = { Text("KB") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Small(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(label) }
}

/** One picked file in a list: picture, name, and ↑ ↓ ⟲ ⟳ ✕. */
@Composable
private fun ItemRow(
    number: Int,
    count: Int,
    picture: Any?,
    name: String,
    detail: String,
    turns: Int,
    canTurn: Boolean,
    onMove: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    AsyncImage(
                        model = picture, contentDescription = name, contentScale = ContentScale.Fit,
                        modifier = Modifier.size(60.dp).rotate(90f * turns),
                    )
                } else Text("📄", fontSize = 26.sp)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$number. $name", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Small("↑", enabled = number > 1) { onMove(-1) }
                    Small("↓", enabled = number < count) { onMove(1) }
                    if (canTurn) {
                        Small("⟲") { onTurn(-1) }
                        Small("⟳") { onTurn(1) }
                    }
                    Small("✕", onClick = onRemove)
                }
            }
        }
    }
}

/** Page pictures with a tick each; tap a page to tick / untick it. [turns] and [onTurn] add ⟲ ⟳ under each page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageTicks(
    pdf: ConvertViewModel.Pdf,
    picked: Collection<Int>,
    onToggle: (Int) -> Unit,
    turns: Map<Int, Int> = emptyMap(),
    onTurn: ((Int, Int) -> Unit)? = null,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pdf.thumbs.forEachIndexed { i, thumb ->
            val on = i in picked
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(84.dp)
                        .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.LightGray)
                        .clickable { onToggle(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = thumb, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit,
                        modifier = Modifier.size(78.dp).rotate(90f * (turns[i] ?: 0)),
                    )
                    Checkbox(checked = on, onCheckedChange = { onToggle(i) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp))
                    Text(
                        "${i + 1}", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                }
                if (onTurn != null) {
                    Row {
                        TextButton(onClick = { onTurn(i, -1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { Text("⟲") }
                        TextButton(onClick = { onTurn(i, 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { Text("⟳") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfHeader(pdf: ConvertViewModel.Pdf, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pdf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Note("${pdf.pageCount} page(s) · ${ConvertMath.sizeText(pdf.size)}")
        }
        TextButton(onClick = onChange) { Text("Change") }
    }
}

// ---------------- 1. Photos → PDF ----------------

@Composable
private fun PhotosToPdf(vm: ScanViewModel, cv: ConvertViewModel) {
    val photos by cv.photos.collectAsStateWithLifecycle()
    var a4 by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf(ConvertMath.PdfQuality.NORMAL) }
    var limit by rememberSaveable { mutableStateOf(false) }
    var kb by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("Photos ${today()}") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.runConvert("Adding photos…") { cv.photos.value = cv.photos.value + cv.photosFrom(uris) }
    }

    Note("Pick the photos in order. Use ↑ ↓ to change the order and ⟲ ⟳ to turn a photo.")
    photos.forEachIndexed { i, p ->
        ItemRow(
            i + 1, photos.size, p.uri, p.name, if (p.size > 0) ConvertMath.sizeText(p.size) else "", p.turns, canTurn = true,
            onMove = { d -> cv.photos.value = ConvertMath.move(cv.photos.value, i, d) },
            onTurn = { d -> cv.photos.value = cv.photos.value.map { if (it.id == p.id) it.copy(turns = ConvertMath.turn(it.turns, d)) else it } },
            onRemove = { cv.photos.value = cv.photos.value.filter { it.id != p.id } },
        )
    }
    Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
        Text(if (photos.isEmpty()) "Pick photos" else "+ Add more photos")
    }
    if (photos.isEmpty()) return

    Title("Page")
    Chips(listOf(false, true), a4, { if (it) "A4 (white edges)" else "Same shape as photo" }) { a4 = it }
    Title("Quality")
    Chips(ConvertMath.PdfQuality.values().toList(), quality, { it.label }) { quality = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = limit, onCheckedChange = { limit = it })
        Text("Keep under a size (KB)")
    }
    if (limit) KbField("Keep under", kb) { kb = it }
    NameField(name) { name = it }
    val maxBytes = if (limit) ConvertMath.kbToBytes(kb) else null
    val ok = !limit || maxBytes != null
    if (!ok) Note("Type a size of at least 5 KB.")
    SendButtons(enabled = ok, onDenied = denied(vm)) { target ->
        vm.sendConvert(target, "Making the PDF…") { step ->
            val (file, note) = cv.photosToPdf(name, a4, quality, maxBytes, step)
            listOf(file) to note
        }
    }
}

// ---------------- 2. PDF → photos ----------------

@Composable
private fun PdfToPhotos(vm: ScanViewModel, cv: ConvertViewModel) {
    val pdf by cv.toPhotos.collectAsStateWithLifecycle()
    val picked by cv.toPhotosPicked.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(PicFormat.JPG) }
    var dpi by rememberSaveable { mutableIntStateOf(200) }
    var limit by rememberSaveable { mutableStateOf(false) }
    var kb by rememberSaveable { mutableStateOf("") }
    var zip by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.runConvert("Opening the PDF…") { step ->
            val opened = cv.openPdf(uri, step)
            cv.toPhotos.value = opened
            cv.toPhotosPicked.value = (0 until opened.pageCount).toSet()
        }
    }

    val doc = pdf
    if (doc == null) {
        Note("Each page of a PDF becomes a picture (JPG, PNG or WEBP).")
        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("Pick a PDF") }
        return
    }
    PdfHeader(doc) { picker.launch(arrayOf("application/pdf")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${picked.size} of ${doc.pageCount} page(s) ticked", modifier = Modifier.weight(1f))
        TextButton(onClick = { cv.toPhotosPicked.value = (0 until doc.pageCount).toSet() }) { Text("All") }
        TextButton(onClick = { cv.toPhotosPicked.value = emptySet() }) { Text("None") }
    }
    PageTicks(doc, picked, onToggle = { i -> cv.toPhotosPicked.value = if (i in picked) picked - i else picked + i })

    Title("Format")
    Chips(PicFormat.values().toList(), format, { it.label }) { format = it }
    Title("Sharpness")
    Chips(listOf(150, 200, 300), dpi, { "$it dpi" }) { dpi = it }
    Note("150 dpi: small files. 200 dpi: clear. 300 dpi: best for printing (bigger files).")
    if (format == PicFormat.JPG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = limit, onCheckedChange = { limit = it })
            Text("Each picture under a size (KB)")
        }
        if (limit) KbField("Each under", kb) { kb = it }
    }
    if (picked.size > 1) {
        Title("Get them as")
        Chips(listOf(false, true), zip, { if (it) "One ZIP file" else "Separate pictures" }) { zip = it }
    }
    val maxBytes = if (limit && format == PicFormat.JPG) ConvertMath.kbToBytes(kb) else null
    val ok = picked.isNotEmpty() && (!(limit && format == PicFormat.JPG) || maxBytes != null)
    SendButtons(enabled = ok, onDenied = denied(vm)) { target ->
        vm.sendConvert(target, "Making pictures…") { step -> cv.pdfToPhotos(format, dpi, maxBytes, zip, step) }
    }
}

// ---------------- 3. Change size (KB) ----------------

@Composable
private fun ChangeSize(vm: ScanViewModel, cv: ConvertViewModel) {
    val src by cv.sizeSource.collectAsStateWithLifecycle()
    val result by cv.sizeResult.collectAsStateWithLifecycle()
    var maxKb by rememberSaveable { mutableStateOf("200") }
    var minKb by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.runConvert("Opening…") { cv.pickForSize(uri) }
    }

    val s = src
    if (s == null) {
        Note("Make a photo or PDF smaller than a size limit, e.g. for a portal: \"under 200 KB\".")
        Button(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("Pick a photo or PDF") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.pdf == null) {
            AsyncImage(s.uri, s.name, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant))
        } else Text("📄", fontSize = 32.sp)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text("Now: " + (if (s.size > 0) ConvertMath.sizeText(s.size) else "size not known"), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }) { Text("Change") }
    }
    Title("Quick picks")
    Chips(listOf("50", "100", "200", "500", "1024"), maxKb, { if (it == "1024") "1 MB" else "$it KB" }) { maxKb = it; cv.sizeResult.value = null }
    KbField("Make it under", maxKb) { maxKb = it; cv.sizeResult.value = null }
    if (s.pdf == null) KbField("At least (optional)", minKb) { minKb = it; cv.sizeResult.value = null }
    val maxBytes = ConvertMath.kbToBytes(maxKb)
    val minBytes = if (s.pdf == null) (minKb.toIntOrNull() ?: 0) * 1024 else 0
    val ok = maxBytes != null && minBytes < maxBytes
    if (!ok) Note("Type a size of at least 5 KB (and the minimum below it).")
    if (s.pdf == null) Note("The photo is saved as a JPG.") else Note("The pages become pictures, made as clear as the size allows.")
    Button(
        onClick = { if (maxBytes != null) vm.runConvert("Making it smaller…") { step -> cv.makeSmaller(maxBytes, minBytes, step) } },
        enabled = ok,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Make it smaller") }

    result?.let { r ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val good = r.note == null || r.after <= (maxBytes ?: Int.MAX_VALUE)
                Text(
                    (if (r.before > 0) ConvertMath.sizeText(r.before) else "?") + " → " + ConvertMath.sizeText(r.after) + if (good) "  ✓" else "",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                r.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Note(r.file.name)
                SendButtons(onDenied = denied(vm)) { target ->
                    vm.sendConvert(target, "Saving…") { listOf(r.file) to null }
                }
            }
        }
    }
}

// ---------------- 4. Merge PDFs ----------------

@Composable
private fun MergePdfs(vm: ScanViewModel, cv: ConvertViewModel) {
    val items by cv.merge.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("Merged ${today()}") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.runConvert("Adding files…") { step -> cv.addToMerge(uris, step) }
    }

    Note("Add PDFs and photos, put them in order with ↑ ↓, and join them into one PDF.")
    items.forEachIndexed { i, item ->
        val pdf = item.pdf
        ItemRow(
            i + 1, items.size, pdf?.thumbs?.firstOrNull() ?: item.photo, item.name,
            if (pdf != null) "PDF · ${pdf.pageCount} page(s)" else "Photo", item.turns, canTurn = pdf == null,
            onMove = { d -> cv.merge.value = ConvertMath.move(cv.merge.value, i, d) },
            onTurn = { d -> cv.merge.value = cv.merge.value.map { if (it.id == item.id) it.copy(turns = ConvertMath.turn(it.turns, d)) else it } },
            onRemove = { cv.merge.value = cv.merge.value.filter { it.id != item.id } },
        )
    }
    Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (items.isEmpty()) "Pick PDFs or photos" else "+ Add more files")
    }
    if (items.isEmpty()) return
    val pages = items.sumOf { it.pdf?.pageCount ?: 1 }
    Note("$pages page(s) in all.")
    NameField(name) { name = it }
    SendButtons(enabled = items.size >= 2, onDenied = denied(vm)) { target ->
        vm.sendConvert(target, "Joining…") { step -> listOf(cv.mergeAll(name, step)) to null }
    }
    if (items.size < 2) Note("Add at least two files.")
}

// ---------------- 5. Pick / split pages ----------------

@Composable
private fun PickPages(vm: ScanViewModel, cv: ConvertViewModel) {
    val pdf by cv.pages.collectAsStateWithLifecycle()
    val picked by cv.pagesPicked.collectAsStateWithLifecycle()
    val turns by cv.pagesTurns.collectAsStateWithLifecycle()
    var typed by rememberSaveable { mutableStateOf("") }
    var separate by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.runConvert("Opening the PDF…") { step ->
            val opened = cv.openPdf(uri, step)
            cv.pages.value = opened
            cv.pagesPicked.value = emptyList()
            cv.pagesTurns.value = emptyMap()
            typed = ""
        }
    }

    val doc = pdf
    if (doc == null) {
        Note("Keep only some pages of a PDF, or make each page its own PDF.")
        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("Pick a PDF") }
        return
    }
    PdfHeader(doc) { picker.launch(arrayOf("application/pdf")) }
    OutlinedTextField(
        typed,
        { t ->
            typed = t
            ConvertMath.parsePages(t, doc.pageCount)?.let { cv.pagesPicked.value = it }
        },
        label = { Text("Pages, e.g. 1-3, 5") },
        singleLine = true,
        isError = typed.isNotBlank() && ConvertMath.parsePages(typed, doc.pageCount) == null,
        supportingText = if (typed.isNotBlank() && ConvertMath.parsePages(typed, doc.pageCount) == null) {
            { Text("Use page numbers from 1 to ${doc.pageCount}, like 1-3, 5") }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${picked.size} of ${doc.pageCount} page(s) chosen", modifier = Modifier.weight(1f))
        TextButton(onClick = {
            cv.pagesPicked.value = (0 until doc.pageCount).toList()
            typed = ConvertMath.formatPages(cv.pagesPicked.value)
        }) { Text("All") }
        TextButton(onClick = { cv.pagesPicked.value = emptyList(); typed = "" }) { Text("None") }
    }
    PageTicks(
        doc, picked,
        onToggle = { i ->
            // Ticking keeps the page order; the text box shows the same pages.
            cv.pagesPicked.value = if (i in picked) picked - i else (picked + i).sorted()
            typed = ConvertMath.formatPages(cv.pagesPicked.value)
        },
        turns = turns,
        onTurn = { i, d -> cv.pagesTurns.value = turns + (i to ConvertMath.turn(turns[i] ?: 0, d)) },
    )
    Note("Tap a page to choose it. ⟲ ⟳ turn a page.")
    Title("Make")
    Chips(listOf(false, true), separate, { if (it) "Each page as its own PDF (ZIP)" else "One PDF with these pages" }) { separate = it }
    SendButtons(enabled = picked.isNotEmpty(), onDenied = denied(vm)) { target ->
        vm.sendConvert(target, "Making the PDF…") { step -> cv.pickPages(separate, step) to null }
    }
}

// ---------------- 6. Change photo format ----------------

@Composable
private fun ChangeFormat(vm: ScanViewModel, cv: ConvertViewModel) {
    val photos by cv.formatPhotos.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(PicFormat.JPG) }
    var quality by rememberSaveable { mutableFloatStateOf(90f) }
    var zip by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.runConvert("Adding photos…") { cv.formatPhotos.value = cv.formatPhotos.value + cv.photosFrom(uris) }
    }

    Note("Change photos to JPG, PNG or WEBP. See-through parts become white in a JPG.")
    photos.forEachIndexed { i, p ->
        ItemRow(
            i + 1, photos.size, p.uri, p.name, if (p.size > 0) ConvertMath.sizeText(p.size) else "", 0, canTurn = false,
            onMove = { d -> cv.formatPhotos.value = ConvertMath.move(cv.formatPhotos.value, i, d) },
            onTurn = {},
            onRemove = { cv.formatPhotos.value = cv.formatPhotos.value.filter { it.id != p.id } },
        )
    }
    Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
        Text(if (photos.isEmpty()) "Pick photos" else "+ Add more photos")
    }
    if (photos.isEmpty()) return
    Title("Change to")
    Chips(PicFormat.values().toList(), format, { it.label }) { format = it }
    if (format != PicFormat.PNG) {
        Title("Quality: ${quality.toInt()}")
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..100f)
        Note("Lower = smaller file.")
    } else {
        Note("PNG keeps every detail (and see-through parts), but files are bigger.")
    }
    if (photos.size > 1) {
        Title("Get them as")
        Chips(listOf(false, true), zip, { if (it) "One ZIP file" else "Separate files" }) { zip = it }
    }
    SendButtons(onDenied = denied(vm)) { target ->
        vm.sendConvert(target, "Changing…") { step -> cv.changeFormat(format, quality.toInt(), zip, step) to null }
    }
}
