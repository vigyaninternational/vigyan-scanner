package com.vigyan.scanner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigyan.scanner.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Last backup: 3 days ago" style text. */
fun backupAge(last: Long): String {
    if (last <= 0) return "never"
    val days = ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
    return when (days) {
        0 -> "today"
        1 -> "yesterday"
        else -> "$days days ago"
    }
}

@Composable
fun BackupScreen(vm: ScanViewModel, onBack: () -> Unit) {
    val scans by vm.scans.collectAsStateWithLifecycle()
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pending = uri }

    ToolScaffold("Backup & restore", onBack) {
        Text(
            "Each phone keeps its own backup. It contains only this phone's scans, saved signatures, form templates and college seal/signature, " +
                "and it goes only where you save it: this phone, or your own Google Drive. Nothing is shared with other staff.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Back up now", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${scans.size} scan(s) on this phone · last backup: ${backupAge(last)}" +
                        (if (last > 0) " (" + SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(last)) + ")" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SendButtons(onDenied = { vm.say("Storage permission is needed to save to the phone") }) { t -> vm.backup(t) }
                Text(
                    "Best: Google Drive. It survives if the phone is lost or reset. \"Save to phone\" puts it in Download/Vigyan Scanner " +
                        "(safe if the app is removed, but not if the phone is lost).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Restore from a backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a \"Vigyan Scanner backup ….zip\" file (from Downloads or Drive). Scans that are missing are added back; " +
                        "nothing on this phone is deleted or replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose backup file")
                }
            }
        }
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = { Text("Missing scans will be added to this phone. Nothing here is deleted.") },
            confirmButton = { TextButton(onClick = { vm.restore(uri); pending = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
