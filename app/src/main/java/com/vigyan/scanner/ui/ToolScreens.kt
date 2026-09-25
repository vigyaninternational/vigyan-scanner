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
import com.vigyan.scanner.Cutout
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

// ---------------- Passport photo ----------------

@Composable
fun PassportScreen(vm: ScanViewModel, onBack: () -> Unit) {
    val result by vm.passport.collectAsStateWithLifecycle()
    var white by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var output by rememberSaveable { mutableStateOf(ScanViewModel.PassportOutput.PHOTO) }

    LaunchedEffect(Unit) { if (vm.passport.value == null) vm.makePassport(white, zoom) }

    ToolScaffold("Passport photo", onBack) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            result?.photo?.let { photo ->
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Passport photo",
                    modifier = Modifier.width(210.dp).height(270.dp).border(1.dp, MaterialTheme.colorScheme.outline),
                )
            } ?: Text("Making the photo…")
        }
        Text("Size of the face in the photo", style = MaterialTheme.typography.titleSmall)
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 0.75f..1.3f, onValueChangeFinished = { vm.makePassport(white, zoom) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = white, onCheckedChange = { white = it; vm.makePassport(it, zoom) })
            Text("White background")
        }
        Text(
            "Best results: face the camera, even light, plain wall behind. The photo is 35×45 mm at 300 dpi.",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Get it as", style = MaterialTheme.typography.titleMedium)
                ScanViewModel.PassportOutput.values().forEach { o -> Choice(o.label, output == o) { output = o } }
                SendButtons(enabled = result != null, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                    vm.exportPassport(output, target)
                }
            }
        }
    }
}

// ---------------- Signature / stamp cut-out ----------------

@Composable
fun CutoutScreen(vm: ScanViewModel, onBack: () -> Unit, onBranding: () -> Unit) {
    val result by vm.cutout.collectAsStateWithLifecycle()
    var strength by rememberSaveable { mutableFloatStateOf(0.5f) }
    var ink by rememberSaveable { mutableStateOf(Cutout.Ink.ORIGINAL) }
    var png by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) { if (vm.cutout.value == null) vm.makeCutout(strength, ink) }

    ToolScaffold("Signature / stamp cut-out", onBack) {
        // Checkerboard behind the picture shows which parts are see-through.
        Box(
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
            }.background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            result?.let {
                Image(it.asImageBitmap(), "Cut-out", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            } ?: Text("Working…")
        }
        Text("Keep fainter strokes", style = MaterialTheme.typography.titleSmall)
        Slider(value = strength, onValueChange = { strength = it }, onValueChangeFinished = { vm.makeCutout(strength, ink) })
        Text("Ink colour", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cutout.Ink.values().forEach { i ->
                FilterChip(selected = ink == i, onClick = { ink = i; vm.makeCutout(strength, i) }, label = { Text(i.label) })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
                Choice("PNG, see-through (for documents and certificates)", png) { png = true }
                Choice("JPG on white (for portals)", !png) { png = false }
                SendButtons(enabled = result != null, onDenied = { vm.say("Storage permission is needed to save to the phone") }) { target ->
                    vm.exportCutout(png, target)
                }
                Text(
                    "For a portal's exact size and KB limit, open the scan and use Resize for portal.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
        OutlinedTextField(college, { college = it }, label = { Text("College name (on stamps and merit lists)") }, modifier = Modifier.fillMaxWidth())
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
