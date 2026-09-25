package com.vigyan.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vigyan.scanner.ScanViewModel.Target

private val Blue = Color(0xFF1E4FA3)
private val Amber = Color(0xFFFFC107)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9DB8F0), secondary = Amber)
    } else {
        lightColorScheme(primary = Blue, secondary = Color(0xFF7A5900))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun BusyDialog(label: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(label)
            }
        },
    )
}

/**
 * Runs an action that writes to the Download folder. Android 9 and older need the storage
 * permission for that, so it is asked for first; newer phones need nothing.
 */
@Composable
fun rememberPhoneSaveGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else onDenied()
        pending = null
    }
    return { action ->
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pending = action
            launcher.launch(permission)
        }
    }
}

/** "Save to phone" / "Google Drive" / "Share" buttons. */
@Composable
fun SendButtons(enabled: Boolean = true, onDenied: () -> Unit, onTarget: (Target) -> Unit) {
    val gate = rememberPhoneSaveGate(onDenied)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { gate { onTarget(Target.PHONE) } }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("Save to phone", maxLines = 1)
        }
        Button(onClick = { onTarget(Target.DRIVE) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("Google Drive", maxLines = 1)
        }
    }
    OutlinedButton(
        onClick = { onTarget(Target.SHARE) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) { Text("Share (WhatsApp, Gmail, other apps)") }
}
