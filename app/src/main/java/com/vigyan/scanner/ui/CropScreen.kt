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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.vigyan.scanner.Dewarp
import com.vigyan.scanner.DewarpMath
import com.vigyan.scanner.DewarpMath.Pt
import com.vigyan.scanner.DewarpMath.Region
import com.vigyan.scanner.Images
import com.vigyan.scanner.Perspective
import com.vigyan.scanner.Scan
import com.vigyan.scanner.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the page is straightened: four corners only, a curved book page, or an open book split into two pages. */
private enum class CropMode(val label: String) { FLAT("Flat"), CURVED("Curved page"), BOOK("Book (2 pages)") }

// Handle ids: 0–3 corners (TL, TR, BR, BL); curved page: 4 top middle, 5 bottom middle;
// book: 6 spine top, 7 spine bottom, 8/9 left page top/bottom middle, 10/11 right page top/bottom middle.
private const val H_TOP = 4
private const val H_BOTTOM = 5
private const val H_SPINE_T = 6
private const val H_SPINE_B = 7
private const val H_LT = 8
private const val H_LB = 9
private const val H_RT = 10
private const val H_RB = 11

/**
 * Drag the four corners onto the page's edges; Apply straightens the page to them.
 * Curved page: the top and bottom edges also bend (found from the lines of text, or dragged by
 * their middle handles), and Apply flattens the page. Book: an open book is split at its spine
 * into two flattened pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: ScanViewModel, scan: Scan, index: Int, onBack: () -> Unit) {
    val page = scan.pages.getOrNull(index) ?: return
    var failed by remember { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, page.path, page.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching { Images.decode(page, 3000) }.getOrNull() }
        failed = value == null
    }
    // Corners in the page picture's pixels: TL, TR, BR, BL.
    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var dragging by remember { mutableIntStateOf(-1) }
    var found by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(CropMode.FLAT) }
    // Curved page: how far the top / bottom edge's middle bows out (picture pixels).
    var topBow by remember { mutableStateOf(Pt(0f, 0f)) }
    var bottomBow by remember { mutableStateOf(Pt(0f, 0f)) }
    // Book: the spine (as fractions 0..1 along the top and bottom edges) and each page's bows.
    var spineT by remember { mutableStateOf(0.5f) }
    var spineB by remember { mutableStateOf(0.5f) }
    var bows by remember { mutableStateOf(List(4) { Pt(0f, 0f) }) } // left top, left bottom, right top, right bottom
    var lines by remember { mutableStateOf<List<List<Pt>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun corner(q: FloatArray, k: Int) = Pt(q[k * 2], q[k * 2 + 1])
    fun along(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    fun curved(q: FloatArray) = Region(corner(q, 0), corner(q, 1), corner(q, 2), corner(q, 3), topBow, bottomBow)
    fun book(q: FloatArray): Pair<Region, Region> {
        val st = along(corner(q, 0), corner(q, 1), spineT); val sb = along(corner(q, 3), corner(q, 2), spineB)
        val (l, r) = DewarpMath.splitBook(corner(q, 0), corner(q, 1), corner(q, 2), corner(q, 3), st, sb)
        return l.copy(topBow = bows[0], bottomBow = bows[1]) to r.copy(topBow = bows[2], bottomBow = bows[3])
    }

    /** Every handle for the current mode: id → position in picture pixels. */
    fun handles(q: FloatArray): List<Pair<Int, Pt>> {
        val list = (0 until 4).map { it to corner(q, it) }.toMutableList()
        when (mode) {
            CropMode.FLAT -> {}
            CropMode.CURVED -> { val r = curved(q); list += H_TOP to r.topMid; list += H_BOTTOM to r.bottomMid }
            CropMode.BOOK -> {
                val (l, r) = book(q)
                list += H_SPINE_T to l.tr; list += H_SPINE_B to l.br
                list += H_LT to l.topMid; list += H_LB to l.bottomMid; list += H_RT to r.topMid; list += H_RB to r.bottomMid
            }
        }
        return list
    }

    /** Reads the text lines once, then sets the curve(s) from them. */
    fun findCurve(b: android.graphics.Bitmap, q: FloatArray) {
        scope.launch {
            busy = "Finding the curve from the text…"
            val ls = lines ?: runCatching { Dewarp.textLines(b) }.getOrDefault(emptyList()).also { lines = it }
            if (mode == CropMode.BOOK) {
                val sx = withContext(Dispatchers.Default) { Dewarp.spineX(b, q) }
                // The spine's place as a fraction along the top and bottom edges.
                fun frac(a: Pt, c: Pt) = ((sx - a.x) / (c.x - a.x).let { if (kotlin.math.abs(it) < 1f) 1f else it }).coerceIn(0.2f, 0.8f)
                spineT = frac(corner(q, 0), corner(q, 1)); spineB = frac(corner(q, 3), corner(q, 2))
                bows = List(4) { Pt(0f, 0f) }
                val (l, r) = book(q)
                val (l2, okL) = Dewarp.curveFromText(l, ls); val (r2, okR) = Dewarp.curveFromText(r, ls)
                bows = listOf(l2.topBow, l2.bottomBow, r2.topBow, r2.bottomBow)
                note = if (okL && okR) "Spine and curves found. Drag the orange spine or the edge handles to fix them, then Apply."
                else "Not enough text to find every curve. Drag the edge handles to follow the pages, then Apply."
            } else {
                val (r2, ok) = Dewarp.curveFromText(curved(q).copy(topBow = Pt(0f, 0f), bottomBow = Pt(0f, 0f)), ls)
                topBow = r2.topBow; bottomBow = r2.bottomBow
                note = if (ok) "Curve found from the text. Drag the middle handles to fix it, then Apply."
                else "Not enough lines of text to find the curve. Drag the middle handles of the top and bottom edges to follow the page, then Apply."
            }
            busy = null
        }
    }

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
                title = { Text((if (mode == CropMode.FLAT) "Adjust corners" else mode.label) + " · page ${index + 1}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    HelpHomeActions("pages", showHome = false)
                    Button(
                        onClick = {
                            val b = bitmap ?: return@Button
                            val q = quad ?: return@Button
                            when (mode) {
                                CropMode.FLAT -> vm.savePageEdit(scan, index, Perspective.warp(b, q), reshaped = true) { onBack() }
                                CropMode.CURVED -> scope.launch {
                                    busy = "Flattening the page…"
                                    val out = withContext(Dispatchers.Default) { runCatching { Dewarp.flatten(b, curved(q)) }.getOrNull() }
                                    busy = null
                                    if (out == null) note = "Could not flatten this page (not enough memory). Try Flat." else vm.savePageEdit(scan, index, out, reshaped = true) { onBack() }
                                }
                                CropMode.BOOK -> scope.launch {
                                    busy = "Making the two pages…"
                                    val (l, r) = book(q)
                                    val out = withContext(Dispatchers.Default) { runCatching { Dewarp.flatten(b, l) to Dewarp.flatten(b, r) }.getOrNull() }
                                    busy = null
                                    if (out == null) note = "Could not split this photo (not enough memory). Try Curved page." else vm.savePageSplit(scan, index, out.first, out.second) { onBack() }
                                }
                            }
                        },
                        enabled = bitmap != null && quad != null && busy == null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Apply") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CropMode.values().forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = {
                            if (mode != m) {
                                mode = m
                                note = ""
                                val b = bitmap; val q = quad
                                if (m != CropMode.FLAT && b != null && q != null) findCurve(b, q)
                            }
                        },
                        label = { Text(m.label) },
                        enabled = busy == null,
                    )
                }
            }
            Text(
                busy ?: note.ifBlank {
                    when {
                        mode == CropMode.BOOK -> "Put the 4 corners on the open book's outer corners."
                        !found -> "Edges not found. Drag the 4 corners onto the page's corners, then Apply."
                        mode == CropMode.CURVED -> "Drag the middle handles so the top and bottom edges follow the page, then Apply."
                        else -> "Page edges found. Drag a corner to fix it, then Apply. A bent book page? Choose Curved page."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                val b = bitmap
                val q = quad
                if (b == null || q == null) {
                    Text(if (failed) "This page picture could not be opened. Go back and use ✏ › Restore the original page, or delete the page and scan it again." else "Opening the page…")
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
                                        // Pick the nearest handle (within reach of a finger).
                                        val cur = quad ?: return@detectDragGestures
                                        dragging = handles(cur).minByOrNull { (_, h) ->
                                            val dx = h.x * scale - p.x
                                            val dy = h.y * scale - p.y
                                            dx * dx + dy * dy
                                        }?.first ?: -1
                                    },
                                    onDrag = { change, amount ->
                                        val cur = quad ?: return@detectDragGestures
                                        val k = dragging
                                        val d = Pt(amount.x / scale, amount.y / scale)
                                        when {
                                            k in 0..3 -> {
                                                val next = cur.copyOf()
                                                next[k * 2] = (cur[k * 2] + d.x).coerceIn(0f, b.width.toFloat())
                                                next[k * 2 + 1] = (cur[k * 2 + 1] + d.y).coerceIn(0f, b.height.toFloat())
                                                quad = next
                                            }
                                            k == H_TOP -> topBow = topBow + d
                                            k == H_BOTTOM -> bottomBow = bottomBow + d
                                            k == H_SPINE_T || k == H_SPINE_B -> {
                                                // The spine slides along the top / bottom edge.
                                                val a = corner(cur, if (k == H_SPINE_T) 0 else 3); val c = corner(cur, if (k == H_SPINE_T) 1 else 2)
                                                val span = (c.x - a.x).let { if (kotlin.math.abs(it) < 1f) 1f else it }
                                                if (k == H_SPINE_T) spineT = (spineT + d.x / span).coerceIn(0.1f, 0.9f)
                                                else spineB = (spineB + d.x / span).coerceIn(0.1f, 0.9f)
                                            }
                                            k in H_LT..H_RB -> bows = bows.toMutableList().also { it[k - H_LT] = it[k - H_LT] + d }
                                        }
                                        if (k >= 0) change.consume()
                                    },
                                    onDragEnd = { dragging = -1 },
                                )
                            },
                        ) {
                            fun o(p: Pt) = Offset(p.x * scale, p.y * scale)
                            // Each page's outline: the top edge (curved), right side, bottom edge back, left side.
                            fun outline(r: Region) = Path().apply {
                                moveTo(o(r.tl).x, o(r.tl).y)
                                for (i in 1..24) DewarpMath.edge(r.tl, r.topMid, r.tr, i / 24f).let { lineTo(o(it).x, o(it).y) }
                                lineTo(o(r.br).x, o(r.br).y)
                                for (i in 23 downTo 0) DewarpMath.edge(r.bl, r.bottomMid, r.br, i / 24f).let { lineTo(o(it).x, o(it).y) }
                                close()
                            }
                            val regions = when (mode) {
                                CropMode.FLAT -> listOf(curved(q).copy(topBow = Pt(0f, 0f), bottomBow = Pt(0f, 0f)))
                                CropMode.CURVED -> listOf(curved(q))
                                CropMode.BOOK -> book(q).toList()
                            }
                            regions.forEach { r ->
                                val path = outline(r)
                                drawPath(path, Color(0x2200E676))
                                drawPath(path, Color(0xFF00C853), style = Stroke(width = 2.dp.toPx()))
                            }
                            if (mode == CropMode.BOOK) {
                                val l = regions[0]
                                drawLine(Color(0xFFFF9100), o(l.tr), o(l.br), strokeWidth = 3.dp.toPx())
                            }
                            handles(q).forEach { (k, p) ->
                                val small = k >= 4 && k != H_SPINE_T && k != H_SPINE_B
                                val fill = when {
                                    k == dragging -> Color(0xFFFF6D00)
                                    k == H_SPINE_T || k == H_SPINE_B -> Color(0xFFFF9100)
                                    small -> Color(0xFF2962FF)
                                    else -> Color(0xFF00C853)
                                }
                                drawCircle(Color.White, radius = (if (small) 11 else 14).dp.toPx(), center = o(p))
                                drawCircle(fill, radius = (if (small) 8 else 10).dp.toPx(), center = o(p))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bitmap?.let { detect(it) } }, modifier = Modifier.weight(1f), enabled = busy == null) { Text("Find edges again") }
                if (mode == CropMode.FLAT) {
                    OutlinedButton(onClick = { bitmap?.let { quad = full(it) } }, modifier = Modifier.weight(1f)) { Text("Whole page") }
                } else {
                    OutlinedButton(
                        onClick = { val b = bitmap; val q = quad; if (b != null && q != null) findCurve(b, q) },
                        modifier = Modifier.weight(1f), enabled = busy == null,
                    ) { Text("Find curve again") }
                }
            }
        }
    }
}
