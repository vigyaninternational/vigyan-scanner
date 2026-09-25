package com.vigyan.scanner.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vigyan.scanner.Images
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something written on the page. Positions and sizes are in the page picture's pixels. */
private sealed class Mark {
    class Stroke(val points: List<Offset>, val color: Int, val width: Float, val highlight: Boolean) : Mark()
    class Label(val text: String, val at: Offset, val size: Float, val color: Int) : Mark()
    class Picture(val bitmap: android.graphics.Bitmap, val center: Offset, val width: Float) : Mark()
}

private enum class Tool(val label: String) { PEN("✏️ Pen"), HIGHLIGHT("🖍 Highlight"), TEXT("T Text"), SIGN("✍️ Signature"), SEAL("🔵 Seal") }

private val INKS = listOf(Color(0xFF111111), Color(0xFF0D2A8A), Color(0xFFC62828), Color(0xFF2E7D32))

/** Draws marks on an Android canvas; [scale] = screen pixels per page pixel (1 when saving). */
private fun drawMarks(canvas: android.graphics.Canvas, marks: List<Mark>, scale: Float) {
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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.color; textSize = m.size * scale }
            m.text.lines().forEachIndexed { i, line ->
                canvas.drawText(line, m.at.x * scale, (m.at.y + m.size * 1.2f * i) * scale, paint)
            }
        }
        is Mark.Picture -> {
            val w = m.width * scale
            val h = w * m.bitmap.height / m.bitmap.width
            val cx = m.center.x * scale
            val cy = m.center.y * scale
            canvas.drawBitmap(m.bitmap, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(vm: ScanViewModel, scan: Scan, index: Int, onBack: () -> Unit) {
    val page = scan.pages.getOrNull(index) ?: return
    val bitmap by produceState<android.graphics.Bitmap?>(null, page.path, page.lastModified()) {
        value = withContext(Dispatchers.IO) { Images.decode(page, 2500) }
    }
    val signature = remember { vm.branding.signatureFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    val seal = remember { vm.branding.sealFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    val marks = remember { mutableStateListOf<Mark>() }
    val current = remember { mutableStateListOf<Offset>() }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var ink by remember { mutableStateOf(INKS[1]) }
    var size by remember { mutableFloatStateOf(0.5f) } // 0..1 for thickness / text size / picture size
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val hasOriginal = remember(page.lastModified()) { vm.hasOriginal(page) }

    fun leave() {
        if (marks.isEmpty()) onBack() else confirmLeave = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Write on page ${index + 1}") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }, enabled = marks.isNotEmpty()) { Text("Undo") }
                    Button(
                        onClick = {
                            val src = bitmap ?: return@Button
                            val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            drawMarks(android.graphics.Canvas(out), marks, 1f)
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
                        Tool.SIGN -> signature != null
                        Tool.SEAL -> seal != null
                        else -> true
                    }
                    if (available) FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.label) })
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tool == Tool.PEN || tool == Tool.TEXT) {
                    INKS.forEach { c ->
                        Box(
                            Modifier.padding(end = 8.dp).size(28.dp).clip(CircleShape).background(c)
                                .border(if (ink == c) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { ink = c },
                        )
                    }
                }
                Text(
                    when (tool) {
                        Tool.TEXT -> "Text size"
                        Tool.SIGN, Tool.SEAL -> "Size"
                        else -> "Thickness"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = size, onValueChange = { size = it }, modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            Text(
                when (tool) {
                    Tool.PEN -> "Draw with your finger."
                    Tool.HIGHLIGHT -> "Drag over text to highlight it."
                    Tool.TEXT -> "Tap where the text should go."
                    Tool.SIGN -> "Tap where the principal's signature should go."
                    Tool.SEAL -> "Tap where the college seal should go."
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
                    Box(
                        Modifier.size(wDp, hDp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .pointerInput(tool, scale, ink, size) {
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
                                    Tool.SIGN, Tool.SEAL -> detectTapGestures { p ->
                                        val pic = if (tool == Tool.SIGN) signature else seal
                                        if (pic != null) marks.add(Mark.Picture(pic, p / scale, bmp.width * (0.12f + 0.3f * size)))
                                    }
                                }
                            }
                            .drawWithMarks(marks, current, tool, ink, size, bmp.width, scale),
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
                    if (text.isNotBlank() && bmp != null) marks.add(Mark.Label(text, at, bmp.width * (0.015f + 0.04f * size), ink.toArgb()))
                    textAt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("What you wrote on this page will be lost.") },
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
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawIntoCanvas { c ->
            val live = if (current.isNotEmpty() && (tool == Tool.PEN || tool == Tool.HIGHLIGHT)) {
                val hl = tool == Tool.HIGHLIGHT
                val base = pageWidth / 1000f
                listOf(Mark.Stroke(current.toList(), if (hl) android.graphics.Color.YELLOW else ink.toArgb(), if (hl) base * (12 + 40 * size) else base * (1.5f + 8 * size), hl))
            } else emptyList()
            drawMarks(c.nativeCanvas, marks + live, scale)
        }
    },
)
