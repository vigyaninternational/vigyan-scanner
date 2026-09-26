package com.vigyan.scanner.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigyan.scanner.FormExtractor
import com.vigyan.scanner.Images
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Something written on the page. Positions and sizes are in the page picture's pixels. */
private sealed class Mark {
    class Stroke(val points: List<Offset>, val color: Int, val width: Float, val highlight: Boolean) : Mark()

    /**
     * [align]: 0 left, 1 centre, 2 right of [at]. [kind]: "field:<key>" (a detail from Smart Fill),
     * "date", "tick", "cross", or null for typed text; templates use it to fill in the next form.
     */
    class Label(val text: String, val at: Offset, val size: Float, val color: Int, val align: Int = 0, val kind: String? = null) : Mark()

    /** [ref] says where the picture came from, so drafts and templates can find it again. */
    class Picture(val bitmap: android.graphics.Bitmap, val center: Offset, val width: Float, val ref: String) : Mark()
}

private enum class Tool(val label: String) {
    PEN("✏️ Pen"),
    HIGHLIGHT("🖍 Highlight"),
    TEXT("T Text"),
    DETAILS("🔤 Details"),
    TICK("✓ Tick"),
    CROSS("✗ Cross"),
    DATE("📅 Date"),
    SIGN("✍️ Signature"),
    PHOTO("🖼 Photo"),
    SEAL("🔵 Seal"),
    MOVE("✋ Move / size"),
}

private val INKS = listOf(Color(0xFF111111), Color(0xFF0D2A8A), Color(0xFFC62828), Color(0xFF2E7D32))

private fun today() = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

/** Draws marks on an Android canvas; [scale] = screen pixels per page pixel (1 when saving). */
private fun drawMarks(canvas: android.graphics.Canvas, marks: List<Mark>, scale: Float, selected: Mark? = null) {
    for (m in marks) when (m) {
        is Mark.Stroke -> {
            if (m.points.isEmpty()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = if (m.highlight) Paint.Cap.SQUARE else Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = m.width * scale
                color = m.color
                if (m.highlight) alpha = 90
            }
            val path = android.graphics.Path()
            path.moveTo(m.points[0].x * scale, m.points[0].y * scale)
            if (m.points.size == 1) path.lineTo(m.points[0].x * scale + 0.1f, m.points[0].y * scale)
            for (p in m.points.drop(1)) path.lineTo(p.x * scale, p.y * scale)
            canvas.drawPath(path, paint)
        }
        is Mark.Label -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = m.color
                textSize = m.size * scale
                textAlign = when (m.align) {
                    1 -> Paint.Align.CENTER
                    2 -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                if (m.kind == "tick" || m.kind == "cross") isFakeBoldText = true
            }
            m.text.lines().forEachIndexed { i, line ->
                canvas.drawText(line, m.at.x * scale, (m.at.y + m.size * 1.2f * i) * scale, paint)
            }
            if (m === selected) {
                val w = m.text.lines().maxOf { paint.measureText(it) }
                val left = when (m.align) {
                    1 -> m.at.x * scale - w / 2
                    2 -> m.at.x * scale - w
                    else -> m.at.x * scale
                }
                val top = (m.at.y - m.size) * scale
                val bottom = (m.at.y + m.size * 1.2f * (m.text.lines().size - 1) + m.size * 0.3f) * scale
                canvas.drawRect(RectF(left - 4, top - 4, left + w + 4, bottom + 4), selectionPaint())
            }
        }
        is Mark.Picture -> {
            val w = m.width * scale
            val h = w * m.bitmap.height / m.bitmap.width
            val cx = m.center.x * scale
            val cy = m.center.y * scale
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawBitmap(m.bitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            if (m === selected) canvas.drawRect(rect, selectionPaint())
        }
    }
}

private fun selectionPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3f
    color = android.graphics.Color.rgb(255, 109, 0)
    pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
}

/** Rough position of a mark, for picking the one under the finger. */
private fun anchor(m: Mark): Offset? = when (m) {
    is Mark.Label -> Offset(m.at.x, m.at.y - m.size / 2)
    is Mark.Picture -> m.center
    is Mark.Stroke -> null
}

private fun moved(m: Mark, by: Offset): Mark = when (m) {
    is Mark.Label -> Mark.Label(m.text, m.at + by, m.size, m.color, m.align, m.kind)
    is Mark.Picture -> Mark.Picture(m.bitmap, m.center + by, m.width, m.ref)
    is Mark.Stroke -> m
}

private fun resized(m: Mark, factor: Float): Mark = when (m) {
    is Mark.Label -> Mark.Label(m.text, m.at, (m.size * factor).coerceIn(4f, 400f), m.color, m.align, m.kind)
    is Mark.Picture -> Mark.Picture(m.bitmap, m.center, (m.width * factor).coerceIn(10f, 5000f), m.ref)
    is Mark.Stroke -> m
}

// ---- Drafts and templates: marks as JSON, with positions as fractions of the page width ----

private fun marksToJson(marks: List<Mark>, pageW: Int, forTemplate: Boolean): String {
    val w = pageW.toFloat()
    val arr = JSONArray()
    for (m in marks) when (m) {
        is Mark.Stroke -> arr.put(
            JSONObject().put("t", "stroke").put("c", m.color).put("w", (m.width / w).toDouble()).put("hl", m.highlight)
                .put("pts", JSONArray().apply { m.points.forEach { put((it.x / w).toDouble()); put((it.y / w).toDouble()) } }),
        )
        is Mark.Label -> arr.put(
            JSONObject().put("t", "label").put("text", m.text).put("x", (m.at.x / w).toDouble()).put("y", (m.at.y / w).toDouble())
                .put("s", (m.size / w).toDouble()).put("c", m.color).put("a", m.align).put("k", m.kind ?: ""),
        )
        is Mark.Picture -> {
            // A template leaves out photos: they belong to one person.
            if (forTemplate && m.ref.startsWith("file:")) continue
            arr.put(
                JSONObject().put("t", "pic").put("ref", m.ref).put("x", (m.center.x / w).toDouble()).put("y", (m.center.y / w).toDouble())
                    .put("w", (m.width / w).toDouble()),
            )
        }
    }
    return JSONObject().put("marks", arr).toString()
}

/**
 * Marks from JSON for a page [pageW] pixels wide. Pictures come from [picture]; with [details]
 * (a template), detail labels are filled in from them and dates become today's.
 */
private fun marksFromJson(
    json: String,
    pageW: Int,
    picture: (String) -> android.graphics.Bitmap?,
    details: Map<String, String>? = null,
): List<Mark> {
    val w = pageW.toFloat()
    val out = mutableListOf<Mark>()
    val arr = runCatching { JSONObject(json).getJSONArray("marks") }.getOrNull() ?: return out
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        when (o.optString("t")) {
            "stroke" -> {
                val p = o.getJSONArray("pts")
                val pts = (0 until p.length() / 2).map { k -> Offset(p.getDouble(2 * k).toFloat() * w, p.getDouble(2 * k + 1).toFloat() * w) }
                out += Mark.Stroke(pts, o.getInt("c"), o.getDouble("w").toFloat() * w, o.optBoolean("hl"))
            }
            "label" -> {
                val kind = o.optString("k").ifBlank { null }
                var text = o.getString("text")
                if (details != null) {
                    when {
                        kind == "date" -> text = today()
                        kind?.startsWith("field:") == true -> text = details[kind.removePrefix("field:")]?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: text
                    }
                }
                out += Mark.Label(text, Offset(o.getDouble("x").toFloat() * w, o.getDouble("y").toFloat() * w), o.getDouble("s").toFloat() * w, o.getInt("c"), o.optInt("a"), kind)
            }
            "pic" -> {
                val ref = o.getString("ref")
                val bmp = picture(ref) ?: continue
                out += Mark.Picture(bmp, Offset(o.getDouble("x").toFloat() * w, o.getDouble("y").toFloat() * w), o.getDouble("w").toFloat() * w, ref)
            }
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(vm: ScanViewModel, scan: Scan, index: Int, onBack: () -> Unit) {
    val page = scan.pages.getOrNull(index) ?: return
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, page.path, page.lastModified()) {
        value = withContext(Dispatchers.IO) { Images.decode(page, 2500) }
    }
    val library by vm.signatureList.collectAsStateWithLifecycle()
    val passport by vm.passport.collectAsStateWithLifecycle()
    val principal = remember { vm.branding.signatureFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    val seal = remember { vm.branding.sealFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    val marks = remember { mutableStateListOf<Mark>() }
    val current = remember { mutableStateListOf<Offset>() }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var ink by remember { mutableStateOf(INKS[1]) }
    var size by remember { mutableFloatStateOf(0.5f) } // 0..1 for thickness / text size / picture size
    var align by remember { mutableIntStateOf(0) }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var detailAt by remember { mutableStateOf<Offset?>(null) }
    var photoAt by remember { mutableStateOf<Offset?>(null) }
    var selected by remember { mutableStateOf<Mark?>(null) }
    var signatureRef by remember { mutableStateOf(if (principal != null) "principal" else "") }
    var confirmLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") } // details, template, saveTemplate
    // Whose details fill the form: a scan with saved Smart Fill details (this one by default).
    var detailsId by remember { mutableStateOf(scan.id.takeIf { scan.fields.isNotEmpty() } ?: vm.detailSources().firstOrNull()?.id.orEmpty()) }
    val details = vm.scan(detailsId)?.fields.orEmpty()
    val hasOriginal = remember(page.lastModified()) { vm.hasOriginal(page) }
    var loaded by remember { mutableStateOf(false) }

    fun pictureFor(ref: String): android.graphics.Bitmap? = when {
        ref == "principal" -> principal
        ref == "seal" -> seal
        ref.startsWith("sig:") -> library.firstOrNull { it.id == ref.removePrefix("sig:") }?.let { BitmapFactory.decodeFile(it.file.path) }
        ref.startsWith("file:") -> File(page.parentFile, ref.removePrefix("file:")).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        else -> null
    }

    // A saved draft comes back as it was left.
    LaunchedEffect(bitmap) {
        val bmp = bitmap ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        loaded = true
        vm.loadDraft(page)?.let { json ->
            marks.addAll(marksFromJson(json, bmp.width, ::pictureFor))
            if (marks.isNotEmpty()) vm.say("Draft opened: carry on, then Save")
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val at = photoAt
        val bmp = bitmap
        if (uri != null && at != null && bmp != null) {
            runCatching { Images.decodeUri(context, uri, 800) }.onSuccess { pic ->
                val name = vm.keepFormPicture(page, pic)
                marks.add(Mark.Picture(pic, at, bmp.width * (0.1f + 0.2f * size), "file:$name"))
            }.onFailure { vm.say("Could not open that photo") }
        }
        photoAt = null
    }

    fun leave() {
        if (marks.isEmpty()) onBack() else confirmLeave = true
    }

    fun signatureBitmap(): android.graphics.Bitmap? = pictureFor(signatureRef)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Write on page ${index + 1}") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { if (marks.isNotEmpty()) { marks.removeAt(marks.lastIndex); selected = null } }, enabled = marks.isNotEmpty()) { Text("Undo") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("❓ Help: filling in a form") }, onClick = { menu = false; nav.help("formfill") })
                        DropdownMenuItem(text = { Text("🪄 Auto-fill blank fields") }, onClick = {
                            menu = false
                            val bmp = bitmap ?: return@DropdownMenuItem
                            vm.planAutoFill(scan, index, details) { spots ->
                                spots.forEach { s ->
                                    val k = bmp.width.toFloat() / s.pageW
                                    marks.add(Mark.Label(s.text, Offset(s.x * k, s.baseline * k), s.height * 0.8f * k, ink.toArgb(), 0, "field:${s.key}"))
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Whose details: " + (vm.scan(detailsId)?.name ?: "none")) }, onClick = { menu = false; dialog = "details" })
                        DropdownMenuItem(text = { Text("💾 Save as draft (finish later)") }, enabled = marks.isNotEmpty(), onClick = {
                            menu = false
                            val bmp = bitmap ?: return@DropdownMenuItem
                            vm.saveDraft(page, marksToJson(marks, bmp.width, forTemplate = false))
                            marks.clear()
                            onBack()
                        })
                        DropdownMenuItem(text = { Text("📋 Save as a form template") }, enabled = marks.isNotEmpty(), onClick = { menu = false; dialog = "saveTemplate" })
                        DropdownMenuItem(text = { Text("📋 Use a form template") }, onClick = { menu = false; dialog = "template" })
                    }
                    Button(
                        onClick = {
                            val src = bitmap ?: return@Button
                            val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            drawMarks(android.graphics.Canvas(out), marks, 1f)
                            vm.deleteDraft(page)
                            vm.savePageEdit(scan, index, out) { marks.clear(); onBack() }
                        },
                        enabled = marks.isNotEmpty() && bitmap != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tools
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool.values().forEach { t ->
                    val available = when (t) {
                        Tool.SIGN -> principal != null || library.isNotEmpty()
                        Tool.SEAL -> seal != null
                        else -> true
                    }
                    if (available) FilterChip(selected = tool == t, onClick = { tool = t; if (t != Tool.MOVE) selected = null }, label = { Text(t.label) })
                }
            }
            if (tool == Tool.SIGN) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (principal != null) FilterChip(selected = signatureRef == "principal", onClick = { signatureRef = "principal" }, label = { Text("Principal") })
                    library.forEach { item ->
                        FilterChip(selected = signatureRef == "sig:${item.id}", onClick = { signatureRef = "sig:${item.id}" }, label = { Text(item.name) })
                    }
                }
            }
            if (tool == Tool.TEXT || tool == Tool.DETAILS || tool == Tool.DATE) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Left", "Centre", "Right").forEachIndexed { i, l ->
                        FilterChip(selected = align == i, onClick = { align = i }, label = { Text(l) })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tool in listOf(Tool.PEN, Tool.TEXT, Tool.DETAILS, Tool.TICK, Tool.CROSS, Tool.DATE)) {
                    INKS.forEach { c ->
                        Box(
                            Modifier.padding(end = 8.dp).size(28.dp).clip(CircleShape).background(c)
                                .border(if (ink == c) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { ink = c },
                        )
                    }
                }
                if (tool == Tool.MOVE) {
                    TextButton(onClick = { selected?.let { s -> val i = marks.indexOf(s); if (i >= 0) { val r = resized(s, 0.85f); marks[i] = r; selected = r } } }, enabled = selected != null) { Text("Smaller") }
                    TextButton(onClick = { selected?.let { s -> val i = marks.indexOf(s); if (i >= 0) { val r = resized(s, 1.18f); marks[i] = r; selected = r } } }, enabled = selected != null) { Text("Bigger") }
                    TextButton(onClick = { selected?.let { marks.remove(it) }; selected = null }, enabled = selected != null) { Text("Remove") }
                } else {
                    Text(
                        when (tool) {
                            Tool.TEXT, Tool.DETAILS, Tool.DATE, Tool.TICK, Tool.CROSS -> "Size"
                            Tool.SIGN, Tool.SEAL, Tool.PHOTO -> "Size"
                            else -> "Thickness"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(value = size, onValueChange = { size = it }, modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
            }
            Text(
                when (tool) {
                    Tool.PEN -> "Draw with your finger."
                    Tool.HIGHLIGHT -> "Drag over text to highlight it."
                    Tool.TEXT -> "Tap where the text should go."
                    Tool.DETAILS -> "Tap a blank field, then pick the detail to write there. Or ⋮ › Auto-fill blank fields."
                    Tool.TICK -> "Tap each box to tick."
                    Tool.CROSS -> "Tap each box to cross."
                    Tool.DATE -> "Tap where today's date should go."
                    Tool.SIGN -> "Pick whose signature, then tap where it goes."
                    Tool.PHOTO -> "Tap the photo box" + (if (passport != null) " (uses the passport photo you made)" else ", then pick a photo")
                    Tool.SEAL -> "Tap where the college seal should go."
                    Tool.MOVE -> if (selected == null) "Drag anything you placed to move it; tap it to select it." else "Drag to move; Smaller / Bigger to resize."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            // The page, with the marks on top.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                if (bmp == null) {
                    Text("Opening the page…")
                } else {
                    val density = LocalDensity.current
                    val maxW = with(density) { maxWidth.toPx() }
                    val maxH = with(density) { maxHeight.toPx() }
                    val scale = minOf(maxW / bmp.width, maxH / bmp.height)
                    val wDp = with(density) { (bmp.width * scale).toDp() }
                    val hDp = with(density) { (bmp.height * scale).toDp() }
                    val textSize = bmp.width * (0.015f + 0.04f * size)

                    fun nearest(p: Offset): Mark? = marks.filter { anchor(it) != null }
                        .minByOrNull { (anchor(it)!! - p).getDistance() }
                        ?.takeIf { (anchor(it)!! - p).getDistance() < bmp.width * 0.08f }

                    Box(
                        Modifier.size(wDp, hDp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .pointerInput(tool, scale, ink, size, align, signatureRef, passport) {
                                when (tool) {
                                    Tool.PEN, Tool.HIGHLIGHT -> detectDragGestures(
                                        onDragStart = { p -> current.clear(); current.add(p / scale) },
                                        onDrag = { change, _ -> current.add(change.position / scale) },
                                        onDragEnd = {
                                            val hl = tool == Tool.HIGHLIGHT
                                            val base = bmp.width / 1000f
                                            marks.add(
                                                Mark.Stroke(
                                                    current.toList(),
                                                    if (hl) android.graphics.Color.YELLOW else ink.toArgb(),
                                                    if (hl) base * (12 + 40 * size) else base * (1.5f + 8 * size),
                                                    hl,
                                                ),
                                            )
                                            current.clear()
                                        },
                                    )
                                    Tool.TEXT -> detectTapGestures { p -> textAt = p / scale }
                                    Tool.DETAILS -> detectTapGestures { p -> detailAt = p / scale }
                                    Tool.TICK, Tool.CROSS -> detectTapGestures { p ->
                                        val s = textSize * 1.4f
                                        val at = p / scale
                                        // Centred on the tap, so it lands inside the box.
                                        marks.add(Mark.Label(if (tool == Tool.TICK) "✓" else "✗", Offset(at.x, at.y + s * 0.35f), s, ink.toArgb(), 1, if (tool == Tool.TICK) "tick" else "cross"))
                                    }
                                    Tool.DATE -> detectTapGestures { p -> marks.add(Mark.Label(today(), p / scale, textSize, ink.toArgb(), align, "date")) }
                                    Tool.SIGN -> detectTapGestures { p ->
                                        signatureBitmap()?.let { marks.add(Mark.Picture(it, p / scale, bmp.width * (0.12f + 0.3f * size), signatureRef)) }
                                    }
                                    Tool.SEAL -> detectTapGestures { p ->
                                        if (seal != null) marks.add(Mark.Picture(seal, p / scale, bmp.width * (0.12f + 0.3f * size), "seal"))
                                    }
                                    Tool.PHOTO -> detectTapGestures { p ->
                                        val made = passport?.photo
                                        if (made != null) {
                                            val name = vm.keepFormPicture(page, made)
                                            marks.add(Mark.Picture(made, p / scale, bmp.width * (0.1f + 0.2f * size), "file:$name"))
                                        } else {
                                            photoAt = p / scale
                                            photoPicker.launch("image/*")
                                        }
                                    }
                                    Tool.MOVE -> detectDragGestures(
                                        onDragStart = { p -> selected = nearest(p / scale) },
                                        onDrag = { change, amount ->
                                            val s = selected
                                            val i = if (s == null) -1 else marks.indexOf(s)
                                            if (s != null && i >= 0) {
                                                val m = moved(s, amount / scale)
                                                marks[i] = m
                                                selected = m
                                                change.consume()
                                            }
                                        },
                                    )
                                }
                            }
                            .pointerInput(tool, scale) {
                                if (tool == Tool.MOVE) detectTapGestures { p -> selected = nearest(p / scale) }
                            }
                            .drawWithMarks(marks, current, tool, ink, size, bmp.width, scale, selected),
                    ) {
                        Image(bmp.asImageBitmap(), "Page ${index + 1}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
            if (hasOriginal) {
                TextButton(onClick = { vm.restoreOriginalPage(scan, index); onBack() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Undo all saved changes (restore the original page)")
                }
            }
        }
    }

    textAt?.let { at ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text("Add text") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 1) },
            confirmButton = {
                TextButton(onClick = {
                    val bmp = bitmap
                    if (text.isNotBlank() && bmp != null) marks.add(Mark.Label(text, at, bmp.width * (0.015f + 0.04f * size), ink.toArgb(), align))
                    textAt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("Cancel") } },
        )
    }
    detailAt?.let { at ->
        AlertDialog(
            onDismissRequest = { detailAt = null },
            title = { Text("Write which detail?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("From: " + (vm.scan(detailsId)?.name ?: "no scan with details yet"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { dialog = "details" }) { Text("Change whose details") }
                    FormExtractor.FIELDS.forEach { f ->
                        val v = details[f.key]?.replace('\n', ' ')
                        if (!v.isNullOrBlank()) {
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    val bmp = bitmap
                                    if (bmp != null) marks.add(Mark.Label(v, at, bmp.width * (0.015f + 0.04f * size), ink.toArgb(), align, "field:${f.key}"))
                                    detailAt = null
                                }.padding(vertical = 6.dp),
                            ) {
                                Text(f.label, style = MaterialTheme.typography.labelMedium)
                                Text(v, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (details.isEmpty()) {
                        Text("No details saved yet. Open the person's scan (Aadhaar, ID card or an old form), tap Smart Fill and Save.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailAt = null }) { Text("Cancel") } },
        )
    }
    when (dialog) {
        "details" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Whose details?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val sources = vm.detailSources()
                    if (sources.isEmpty()) Text("No scan has saved details yet. Use Smart Fill on a scan and Save first.")
                    sources.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { detailsId = s.id; dialog = "" }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = detailsId == s.id, onClick = { detailsId = s.id; dialog = "" })
                            Column {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(s.fields["name"], s.fields["dob"], s.fields["mobile1"]).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "saveTemplate" -> {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Save as a form template") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Form name, e.g. Scholarship form") })
                        Text(
                            "Keeps where each detail, tick, date and signature goes. Next time, scan a blank copy, open ✏ › Use a form template, and it is filled with the chosen person's details. Photos are not kept.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val bmp = bitmap
                        if (bmp != null) vm.saveFormTemplate(name, marksToJson(marks, bmp.width, forTemplate = true))
                        dialog = ""
                    }, enabled = name.isNotBlank()) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
            )
        }
        "template" -> {
            var names by remember { mutableStateOf(vm.formTemplates()) }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Use a form template") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("Fills with: " + (vm.scan(detailsId)?.name ?: "no details (placeholders stay)"), style = MaterialTheme.typography.bodySmall)
                        if (names.isEmpty()) Text("No templates yet. Fill a form once, then ⋮ › Save as a form template.")
                        names.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val bmp = bitmap
                                    val json = vm.loadFormTemplate(n)
                                    if (bmp != null && json != null) marks.addAll(marksFromJson(json, bmp.width, ::pictureFor, details))
                                    dialog = ""
                                }, modifier = Modifier.weight(1f)) { Text(n) }
                                TextButton(onClick = { vm.deleteFormTemplate(n); names = vm.formTemplates() }) { Text("✕") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
            )
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("What you wrote on this page will be lost. To finish later, use ⋮ › Save as draft.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep writing") } },
        )
    }
}

/** Draws the saved marks plus the stroke being drawn right now, over the page picture. */
private fun Modifier.drawWithMarks(
    marks: List<Mark>,
    current: List<Offset>,
    tool: Tool,
    ink: Color,
    size: Float,
    pageWidth: Int,
    scale: Float,
    selected: Mark?,
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawIntoCanvas { c ->
            val live = if (current.isNotEmpty() && (tool == Tool.PEN || tool == Tool.HIGHLIGHT)) {
                val hl = tool == Tool.HIGHLIGHT
                val base = pageWidth / 1000f
                listOf(Mark.Stroke(current.toList(), if (hl) android.graphics.Color.YELLOW else ink.toArgb(), if (hl) base * (12 + 40 * size) else base * (1.5f + 8 * size), hl))
            } else emptyList()
            drawMarks(c.nativeCanvas, marks + live, scale, selected)
        }
    },
)
