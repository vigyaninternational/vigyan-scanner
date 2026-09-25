package com.vigyan.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vigyan.scanner.Images
import com.vigyan.scanner.Perspective
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Drag the four corners onto the page's edges; Apply straightens the page to them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: ScanViewModel, scan: Scan, index: Int, onBack: () -> Unit) {
    val page = scan.pages.getOrNull(index) ?: return
    val bitmap by produceState<android.graphics.Bitmap?>(null, page.path, page.lastModified()) {
        value = withContext(Dispatchers.IO) { Images.decode(page, 3000) }
    }
    // Corners in the page picture's pixels: TL, TR, BR, BL.
    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var dragging by remember { mutableIntStateOf(-1) }
    var found by remember { mutableStateOf(true) }

    fun full(b: android.graphics.Bitmap): FloatArray {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        return floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    }

    fun detect(b: android.graphics.Bitmap) {
        val q = Perspective.sample(b).quad()
        found = q != null
        quad = q ?: full(b)
    }

    LaunchedEffect(bitmap) { bitmap?.let { b -> if (quad == null) withContext(Dispatchers.Default) { detect(b) } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust corners · page ${index + 1}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Button(
                        onClick = {
                            val b = bitmap ?: return@Button
                            val q = quad ?: return@Button
                            vm.savePageEdit(scan, index, Perspective.warp(b, q), reshaped = true) { onBack() }
                        },
                        enabled = bitmap != null && quad != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Apply") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (found) "Page edges found. Drag a corner to fix it, then Apply."
                else "Edges not found. Drag the 4 corners onto the page's corners, then Apply.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                val b = bitmap
                val q = quad
                if (b == null || q == null) {
                    Text("Opening the page…")
                } else {
                    val density = LocalDensity.current
                    val maxW = with(density) { maxWidth.toPx() }
                    val maxH = with(density) { maxHeight.toPx() }
                    val scale = minOf(maxW / b.width, maxH / b.height)
                    val wDp = with(density) { (b.width * scale).toDp() }
                    val hDp = with(density) { (b.height * scale).toDp() }
                    Box(Modifier.size(wDp, hDp)) {
                        Image(b.asImageBitmap(), "Page", modifier = Modifier.fillMaxSize())
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(scale) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        // Pick the nearest corner (within reach of a finger).
                                        val cur = quad ?: return@detectDragGestures
                                        val near = (0 until 4).minByOrNull { k ->
                                            val dx = cur[k * 2] * scale - p.x
                                            val dy = cur[k * 2 + 1] * scale - p.y
                                            dx * dx + dy * dy
                                        } ?: -1
                                        dragging = near
                                    },
                                    onDrag = { change, amount ->
                                        val cur = quad ?: return@detectDragGestures
                                        val k = dragging
                                        if (k >= 0) {
                                            val next = cur.copyOf()
                                            next[k * 2] = (cur[k * 2] + amount.x / scale).coerceIn(0f, b.width.toFloat())
                                            next[k * 2 + 1] = (cur[k * 2 + 1] + amount.y / scale).coerceIn(0f, b.height.toFloat())
                                            quad = next
                                            change.consume()
                                        }
                                    },
                                    onDragEnd = { dragging = -1 },
                                )
                            },
                        ) {
                            val pts = (0 until 4).map { k -> Offset(q[k * 2] * scale, q[k * 2 + 1] * scale) }
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                                close()
                            }
                            drawPath(path, Color(0x2200E676))
                            drawPath(path, Color(0xFF00C853), style = Stroke(width = 2.dp.toPx()))
                            pts.forEachIndexed { k, p ->
                                drawCircle(Color.White, radius = 14.dp.toPx(), center = p)
                                drawCircle(if (k == dragging) Color(0xFFFF6D00) else Color(0xFF00C853), radius = 10.dp.toPx(), center = p)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bitmap?.let { detect(it) } }, modifier = Modifier.weight(1f)) { Text("Find edges again") }
                OutlinedButton(onClick = { bitmap?.let { quad = full(it) } }, modifier = Modifier.weight(1f)) { Text("Whole page") }
            }
        }
    }
}
