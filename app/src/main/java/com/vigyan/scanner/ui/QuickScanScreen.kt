package com.vigyan.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.vigyan.scanner.QuickScan
import com.vigyan.scanner.QuickScanLogic
import com.vigyan.scanner.VolumeKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Quick scan: the camera stays open and every tap (or volume key, or Auto) adds a page at once,
 * with no review screen in between. Pages are trimmed to the paper and brightened in the
 * background while you keep going.
 */
@Composable
fun QuickScanScreen(onCancel: () -> Unit, onDone: (List<File>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok
        if (!ok) onCancel()
    }
    LaunchedEffect(Unit) { if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA) }

    val dir = remember { File(context.cacheDir, "quick").apply { deleteRecursively(); mkdirs() } }
    val pages = remember { mutableStateListOf<File>() }
    var pending by remember { mutableIntStateOf(0) } // photos still being cleaned up
    var capturing by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }
    var enhance by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Hold the phone over the page") }
    var confirmLeave by remember { mutableStateOf(false) }
    val processLock = remember { Mutex() }
    val sound = remember { MediaActionSound() }
    val shutter = remember { QuickScanLogic.AutoShutter() }
    var counter by remember { mutableIntStateOf(0) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }

    fun capture() {
        if (capturing || !hasCamera) return
        capturing = true
        shutter.captured()
        val raw = File(dir, "raw_${counter}.jpg")
        val page = File(dir, "page_${counter}.jpg")
        counter++
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    flash = true
                    runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
                    pending++
                    scope.launch {
                        // One page at a time, in order.
                        processLock.withLock {
                            val ok = withContext(Dispatchers.Default) {
                                runCatching { QuickScan.process(raw, page, enhance); raw.delete() }.isSuccess
                            }
                            if (ok) pages.add(page)
                            pending--
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    hint = "Could not take the picture. Try again."
                }
            },
        )
    }

    // Volume keys take a picture too (handy with the other hand holding the page).
    DisposableEffect(Unit) {
        VolumeKeys.handler = { capture() }
        onDispose {
            VolumeKeys.handler = null
            runCatching { sound.release() }
        }
    }

    // Camera + Auto: watch the picture for "still" and "changed".
    DisposableEffect(hasCamera) {
        if (!hasCamera) return@DisposableEffect onDispose { }
        controller.bindToLifecycle(lifecycleOwner)
        val analysis = Executors.newSingleThreadExecutor()
        var previous: IntArray? = null
        controller.setImageAnalysisAnalyzer(analysis) { image ->
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val gw = 32
                val gh = 24
                val grid = IntArray(gw * gh) { i ->
                    val x = (i % gw) * image.width / gw
                    val y = (i / gw) * image.height / gh
                    buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                }
                val prev = previous
                previous = grid
                if (prev != null && auto) {
                    val snap = shutter.onFrame(QuickScanLogic.meanDiff(grid, prev), grid.average(), System.currentTimeMillis())
                    val waiting = shutter.waitingForNewPage
                    ContextCompat.getMainExecutor(context).execute {
                        hint = if (waiting) "Turn to the next page" else "Hold still…"
                        if (snap) capture()
                    }
                }
            } finally {
                image.close()
            }
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysis.shutdown()
        }
    }

    LaunchedEffect(flash) {
        if (flash) {
            kotlinx.coroutines.delay(120)
            flash = false
        }
    }
    val flashAlpha by animateFloatAsState(if (flash) 0.7f else 0f, label = "flash")

    fun leave() {
        if (pages.isEmpty() && pending == 0) onCancel() else confirmLeave = true
    }
    BackHandler { leave() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))

        // Top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.45f)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leave() }) { Text("✕", color = Color.White, fontSize = 20.sp) }
                Text("Quick scan", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${pages.size + pending} page(s)", color = Color.White, fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("⚡ Auto", auto) { auto = it }
                Chip("✨ Enhance", enhance) { enhance = it }
                Chip("🔦 Light", torch) { torch = it; controller.enableTorch(it) }
            }
        }

        // Bottom: last page, shutter, done
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (auto) "$hint  ·  or tap the button / volume key" else "Tap the button or press a volume key for each page",
                color = Color.White,
                fontSize = 13.sp,
            )
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    pages.lastOrNull()?.let { last ->
                        Box {
                            AsyncImage(
                                model = last,
                                contentDescription = "Last page",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, Color.White, RoundedCornerShape(6.dp)),
                            )
                            // Undo the last page.
                            Box(
                                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Color(0xFFC62828))
                                    .clickable { pages.removeAt(pages.lastIndex).delete() },
                                contentAlignment = Alignment.Center,
                            ) { Text("✕", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                    if (pending > 0) CircularProgressIndicator(Modifier.size(24.dp).padding(start = 64.dp), color = Color.White, strokeWidth = 2.dp)
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = if (capturing) 0.5f else 1f))
                        .border(4.dp, Color(0xFF1E4FA3), CircleShape).clickable { capture() },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = { onDone(pages.toList()) }, enabled = pages.isNotEmpty() && pending == 0) {
                        Text(if (pending > 0) "Wait…" else "Done")
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard ${pages.size + pending} page(s)?") },
            text = { Text("Tap Done instead to keep them.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onCancel() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep scanning") } },
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label, color = Color.White) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF1E4FA3)),
    )
}
