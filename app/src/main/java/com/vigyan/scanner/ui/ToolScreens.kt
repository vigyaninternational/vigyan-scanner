package com.vigyan.scanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import com.vigyan.scanner.Cutout
import com.vigyan.scanner.Passport
import com.vigyan.scanner.PhotoSheet
import com.vigyan.scanner.PhotoSize
import com.vigyan.scanner.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) { Text(label) }
    }
}

// ---------------- Passport photo studio ----------------

@Composable
fun PassportScreen(vm: ScanViewModel, onBack: () -> Unit) {
    val result by vm.passport.collectAsStateWithLifecycle()
    val look by vm.passportLook.collectAsStateWithLifecycle()
    var output by rememberSaveable { mutableStateOf(ScanViewModel.PassportOutput.PHOTO) }
    var zoom by remember(look.zoom) { mutableFloatStateOf(look.zoom) }
    var brightness by remember(look.brightness) { mutableFloatStateOf(look.brightness) }
    var contrast by remember(look.contrast) { mutableFloatStateOf(look.contrast) }
    var customW by rememberSaveable { mutableStateOf("") }
    var customH by rememberSaveable { mutableStateOf("") }
    var maxKb by rememberSaveable { mutableStateOf("50") }
    var margin by rememberSaveable { mutableStateOf("5") }
    var gap by rememberSaveable { mutableStateOf("2") }
    var cols by rememberSaveable { mutableStateOf("") }
    var rows by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { if (vm.passport.value == null) vm.makePassport() }

    val setup = ScanViewModel.SheetSetup(
        margin = margin.toFloatOrNull() ?: 5f,
        gap = gap.toFloatOrNull() ?: 2f,
        cols = cols.toIntOrNull() ?: 0,
        rows = rows.toIntOrNull() ?: 0,
    )

    ToolScaffold("Passport photo studio", onBack) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            result?.photo?.let { photo ->
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Passport photo",
                    modifier = Modifier.width(200.dp).height((200f * photo.height / photo.width).dp).border(1.dp, MaterialTheme.colorScheme.outline),
                )
            } ?: Text("Making the photo…")
        }
        Text(
            "${look.size.widthMm.toInt()}×${look.size.heightMm.toInt()} mm · ${look.size.widthPx}×${look.size.heightPx} px at 300 dpi",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Size", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            PhotoSize.PRESETS.forEach { s ->
                FilterChip(selected = look.size == s, onClick = { vm.makePassport(look.copy(size = s)) }, label = { Text(s.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallNumber("Width mm", customW, Modifier.weight(1f)) { customW = it }
            SmallNumber("Height mm", customH, Modifier.weight(1f)) { customH = it }
            TextButton(onClick = {
                val w = customW.toFloatOrNull() ?: 0f
                val h = customH.toFloatOrNull() ?: 0f
                if (w in 10f..120f && h in 10f..120f) vm.makePassport(look.copy(size = PhotoSize("Custom ${w}×$h mm", w, h, if (w >= h) 0.6f else 0.72f)))
                else vm.say("Type a width and height between 10 and 120 mm")
            }) { Text("Use") }
        }

        Text("Face size", style = MaterialTheme.typography.titleSmall)
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 0.75f..1.3f, onValueChangeFinished = { vm.makePassport(look.copy(zoom = zoom)) })
        Text("Move and turn", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { vm.makePassport(look.copy(shiftX = look.shiftX + 0.04f)) }) { Text("←") }
            OutlinedButton(onClick = { vm.makePassport(look.copy(shiftX = look.shiftX - 0.04f)) }) { Text("→") }
            OutlinedButton(onClick = { vm.makePassport(look.copy(shiftY = look.shiftY + 0.04f)) }) { Text("↑") }
            OutlinedButton(onClick = { vm.makePassport(look.copy(shiftY = look.shiftY - 0.04f)) }) { Text("↓") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { vm.rotatePassport(clockwise = false) }) { Text("⟲ Turn left") }
            OutlinedButton(onClick = { vm.rotatePassport(clockwise = true) }) { Text("⟳ Turn right") }
            TextButton(onClick = { vm.makePassport(look.copy(zoom = 1f, shiftX = 0f, shiftY = 0f)) }) { Text("Reset") }
        }

        Text("Background", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Passport.BACKGROUNDS.forEach { (label, color) ->
                FilterChip(
                    selected = look.background == color,
                    onClick = { vm.restylePassport(look.copy(background = color)) },
                    label = { Text(label) },
                    leadingIcon = if (color != null) {
                        { Box(Modifier.size(14.dp).background(Color(color)).border(1.dp, Color.Gray)) }
                    } else null,
                )
            }
        }
        Text("Brightness", style = MaterialTheme.typography.titleSmall)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.3f..0.3f, onValueChangeFinished = { vm.restylePassport(look.copy(brightness = brightness)) })
        Text("Contrast", style = MaterialTheme.typography.titleSmall)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.7f..1.4f, onValueChangeFinished = { vm.restylePassport(look.copy(contrast = contrast)) })
        Text(
            "Best results: face the camera, even light, plain wall behind. Check the rules of the office or portal you are applying to.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Get it as", style = MaterialTheme.typography.titleMedium)
                ScanViewModel.PassportOutput.values().forEach { o -> Choice(o.label, output == o) { output = o } }
                when (output) {
                    ScanViewModel.PassportOutput.UNDER_KB -> SmallNumber("Under how many KB", maxKb, Modifier.fillMaxWidth()) { maxKb = it }
                    ScanViewModel.PassportOutput.SHEET_4X6 -> Text(
                        "${vm.sheet4x6Count(look.size)} photos at real size on 6×4 inch photo paper (print at 100%, no \"fit to page\").",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ScanViewModel.PassportOutput.A4 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallNumber("Margin mm", margin, Modifier.weight(1f)) { margin = it }
                            SmallNumber("Gap mm", gap, Modifier.weight(1f)) { gap = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallNumber("Columns (max)", cols, Modifier.weight(1f)) { cols = it }
                            SmallNumber("Rows (max)", rows, Modifier.weight(1f)) { rows = it }
                        }
                        val layout = vm.a4Layout(setup, look.size)
                        Text(
                            "${layout.count} photos (${layout.cols} × ${layout.rows}) at the real ${look.size.widthMm.toInt()}×${look.size.heightMm.toInt()} mm. " +
                                "Print at 100% (\"actual size\"), not \"fit to page\". Leave columns / rows blank for as many as fit.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SheetPreview(layout)
                    }
                    else -> Unit
                }
                SendButtons(enabled = result != null, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                    vm.exportPassport(output, target, maxKb.toIntOrNull()?.coerceAtLeast(5) ?: 50, setup)
                }
            }
        }
    }
}

/** A4 sheet drawn to scale, with a box where each photo goes. */
@Composable
private fun SheetPreview(layout: PhotoSheet.Layout) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(150.dp).height((150f * PhotoSheet.A4_H / PhotoSheet.A4_W).dp).border(1.dp, Color.Gray).background(Color.White)) {
            val k = size.width / PhotoSheet.A4_W
            layout.positions.forEach { (x, y) ->
                drawRect(Color(0xFF90CAF9), Offset(x * k, y * k), Size(layout.photoW * k, layout.photoH * k))
            }
        }
    }
}

@Composable
private fun SmallNumber(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

// ---------------- Signature studio ----------------

private val SIGNATURE_PORTALS = listOf(
    intArrayOf(140, 60, 10, 20),
    intArrayOf(350, 150, 0, 30),
    intArrayOf(300, 80, 0, 20),
)

@Composable
fun CutoutScreen(vm: ScanViewModel, onBack: () -> Unit, onBranding: () -> Unit) {
    val result by vm.cutout.collectAsStateWithLifecycle()
    val look by vm.cutoutLook.collectAsStateWithLifecycle()
    val library by vm.signatureList.collectAsStateWithLifecycle()
    var strength by remember(look.strength) { mutableFloatStateOf(look.strength) }
    var darkness by remember(look.darkness) { mutableFloatStateOf(look.darkness) }
    var tilt by remember(look.tilt) { mutableFloatStateOf(look.tilt) }
    var png by rememberSaveable { mutableStateOf(true) }
    var width by rememberSaveable { mutableStateOf(0) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var eraser by rememberSaveable { mutableFloatStateOf(0.4f) }
    var portal by rememberSaveable { mutableStateOf(0) } // index in SIGNATURE_PORTALS, or -1 = custom
    var pw by rememberSaveable { mutableStateOf("") }
    var ph by rememberSaveable { mutableStateOf("") }
    var pmin by rememberSaveable { mutableStateOf("") }
    var pmax by rememberSaveable { mutableStateOf("20") }
    var libName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { if (vm.cutout.value == null) vm.makeCutout() }

    ToolScaffold("Signature studio", onBack) {
        // Checkerboard behind the picture shows which parts are see-through. With the eraser on,
        // dragging over the picture rubs marks out.
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(200.dp).border(1.dp, MaterialTheme.colorScheme.outline).drawBehind {
                val cell = 12.dp.toPx()
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = if (row % 2 == 0) 0f else cell
                    while (x < size.width) {
                        drawRect(Color(0xFFE0E0E0), Offset(x, y), Size(cell, cell))
                        x += cell * 2
                    }
                    y += cell
                    row++
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val img = result
            if (img == null) {
                Text("Working…")
            } else {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val s = minOf(boxW / img.width, boxH / img.height)
                val offX = (boxW - img.width * s) / 2
                val offY = (boxH - img.height * s) / 2
                val points = remember { mutableListOf<Pair<Float, Float>>() }
                Image(
                    img.asImageBitmap(), "Cut-out", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(erasing, img, eraser) {
                        if (!erasing) return@pointerInput
                        detectDragGestures(
                            onDragStart = { p -> points.clear(); points += ((p.x - offX) / s) to ((p.y - offY) / s) },
                            onDrag = { change, _ -> points += ((change.position.x - offX) / s) to ((change.position.y - offY) / s) },
                            onDragEnd = { vm.eraseCutout(points.toList(), maxOf(img.width, img.height) * (0.01f + 0.05f * eraser)) },
                        )
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = erasing, onClick = { erasing = !erasing }, label = { Text("🧽 Eraser") })
            if (erasing) {
                Slider(value = eraser, onValueChange = { eraser = it }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                TextButton(onClick = { vm.makeCutout(look) }) { Text("Undo") }
            }
        }
        if (erasing) Text("Drag over spots or lines to rub them out. Changing a setting below starts again.", style = MaterialTheme.typography.bodySmall)

        Text("Turn and straighten", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.makeCutout(look.copy(turns = look.turns - 1)) }) { Text("⟲") }
            OutlinedButton(onClick = { vm.makeCutout(look.copy(turns = look.turns + 1)) }) { Text("⟳") }
            Slider(value = tilt, onValueChange = { tilt = it }, valueRange = -15f..15f, onValueChangeFinished = { vm.makeCutout(look.copy(tilt = tilt)) }, modifier = Modifier.weight(1f))
        }
        Text("Keep fainter strokes (removes paper texture and shadows when low)", style = MaterialTheme.typography.titleSmall)
        Slider(value = strength, onValueChange = { strength = it }, onValueChangeFinished = { vm.makeCutout(look.copy(strength = strength)) })
        Text("Ink darkness", style = MaterialTheme.typography.titleSmall)
        Slider(value = darkness, onValueChange = { darkness = it }, onValueChangeFinished = { vm.makeCutout(look.copy(darkness = darkness)) })
        Text("Ink colour", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cutout.Ink.values().forEach { i ->
                FilterChip(selected = look.ink == i, onClick = { vm.makeCutout(look.copy(ink = i)) }, label = { Text(i.label) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
                Choice("PNG, see-through (for documents and certificates)", png) { png = true }
                Choice("JPG on white", !png) { png = false }
                Text("Width", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(0, 800, 600, 400, 300, 200).forEach { w ->
                        FilterChip(
                            selected = width == w,
                            onClick = { width = w },
                            label = { Text(if (w == 0) "As scanned" + (result?.let { " (${it.width} px)" } ?: "") else "$w px") },
                        )
                    }
                }
                SendButtons(enabled = result != null, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                    vm.exportCutout(png, target, width)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("For a portal (exact pixels and KB)", style = MaterialTheme.typography.titleMedium)
                SIGNATURE_PORTALS.forEachIndexed { i, p ->
                    Choice("${p[0]}×${p[1]} px · " + (if (p[2] > 0) "${p[2]}–${p[3]} KB" else "under ${p[3]} KB"), portal == i) { portal = i }
                }
                Choice("Custom", portal == -1) { portal = -1 }
                if (portal == -1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallNumber("Width px", pw, Modifier.weight(1f)) { pw = it }
                        SmallNumber("Height px", ph, Modifier.weight(1f)) { ph = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallNumber("Min KB", pmin, Modifier.weight(1f)) { pmin = it }
                        SmallNumber("Max KB", pmax, Modifier.weight(1f)) { pmax = it }
                    }
                }
                val spec = SIGNATURE_PORTALS.getOrNull(portal)
                    ?: intArrayOf(pw.toIntOrNull() ?: 0, ph.toIntOrNull() ?: 0, pmin.toIntOrNull() ?: 0, pmax.toIntOrNull() ?: 0)
                val ok = result != null && spec[0] > 0 && spec[1] > 0 && spec[3] > 0 && spec[2] <= spec[3]
                SendButtons(enabled = ok, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                    vm.exportCutoutForPortal(spec[0], spec[1], spec[2], spec[3], target)
                }
                Text("The signature is fitted onto white at exactly that size.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("My signatures", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Kept only inside this app (protected by the app lock). Use them on forms with ✏ › Signature.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(libName, { libName = it }, singleLine = true, label = { Text("Name, e.g. My signature") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.saveToLibrary(libName); libName = "" }, enabled = result != null) { Text("Save") }
                }
                library.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = item.file, contentDescription = item.name,
                            modifier = Modifier.width(90.dp).height(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text(item.name, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                        TextButton(onClick = { vm.openFromLibrary(item) }) { Text("Open") }
                        TextButton(onClick = { vm.shareFromLibrary(item, ScanViewModel.Target.SHARE) }) { Text("Share") }
                        TextButton(onClick = { vm.deleteFromLibrary(item) }) { Text("✕") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use for college documents", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.useCutoutAs(seal = false) }, enabled = result != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Use as principal's signature")
                }
                Button(onClick = { vm.useCutoutAs(seal = true) }, enabled = result != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Use as college seal")
                }
                OutlinedButton(onClick = onBranding, modifier = Modifier.fillMaxWidth()) { Text("College stamp & signature settings") }
            }
        }
    }
}

// ---------------- College stamp & signature ----------------

@Composable
fun BrandingScreen(vm: ScanViewModel, onBack: () -> Unit, onScanCutout: () -> Unit) {
    val version by vm.brandingVersion.collectAsStateWithLifecycle()
    val b = vm.branding
    var college by rememberSaveable { mutableStateOf(b.collegeName) }
    var signatory by rememberSaveable { mutableStateOf(b.signatory) }

    ToolScaffold("College stamp & signature", onBack) {
        Text(
            "Used by the stamp options when you save a scan: \"ATTESTED - TRUE COPY\", college seal and principal's signature. For the letter pad, tick \"Space for letter pad\" when saving.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(college, { college = it }, label = { Text("College name (on stamps)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(signatory, { signatory = it }, label = { Text("Signs as (e.g. Principal)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveBrandingText(college, signatory) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }

        listOf(false to "Principal's signature", true to "College seal").forEach { (seal, label) ->
            val file = if (seal) b.sealFile else b.signatureFile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (file.exists()) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(file).memoryCacheKey(file.path + version + file.lastModified()).build(),
                            contentDescription = label,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                        TextButton(onClick = { vm.removeBrandingImage(seal) }) { Text("Remove") }
                    } else {
                        Text("Not set yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onScanCutout, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan ${label.lowercase()} on white paper")
                    }
                }
            }
        }
        Text(
            "Tip: sign (or stamp) on clean white paper, scan it, then on the next screen tap \"Use as principal's signature\" or \"Use as college seal\".",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
