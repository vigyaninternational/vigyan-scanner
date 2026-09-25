package com.vigyan.scanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.biometric.BiometricManager
import com.vigyan.scanner.AppLock
import com.vigyan.scanner.PinHash
import com.vigyan.scanner.R
import kotlinx.coroutines.delay

fun fingerprintAvailable(context: android.content.Context): Boolean = try {
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
} catch (e: Exception) {
    false
}

/** Full-screen PIN pad shown over the app while it is locked. */
@Composable
fun LockScreen(lock: AppLock, onFingerprint: (() -> Unit)?, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waiting = now < waitUntil

    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    fun press(k: String) {
        if (waiting) return
        when (k) {
            "⌫" -> pin = pin.dropLast(1)
            "OK" -> if (pin.isNotEmpty()) {
                if (lock.check(pin)) onUnlocked() else {
                    wrong++
                    pin = ""
                    if (wrong % 5 == 0) {
                        waitUntil = System.currentTimeMillis() + 30_000
                        error = "Too many tries. Wait 30 seconds."
                    } else {
                        error = "Wrong PIN"
                    }
                }
            }
            else -> if (pin.length < 6) {
                pin += k
                error = ""
                // Opens as soon as the right PIN is typed (4 to 6 digits).
                if (pin.length >= 4 && lock.check(pin)) onUnlocked()
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1E4FA3), Color(0xFF0D2A5C))))
            // Swallow every touch so nothing underneath can be used.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Image(
                painterResource(R.drawable.logo_vigyan), "Vigyan International logo",
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color.White).padding(6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Vigyan Scanner is locked", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Enter your PIN", color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (i < pin.length) Color.White else Color.White.copy(alpha = 0.3f)),
                    )
                }
            }
            Text(
                if (waiting) "Too many tries. Wait ${(waitUntil - now + 999) / 1000} s." else error,
                color = Color(0xFFFFCDD2),
                modifier = Modifier.padding(top = 8.dp).height(20.dp),
            )
            Spacer(Modifier.height(8.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable { press(k) },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, color = Color.White, fontSize = if (k.length > 1) 18.sp else 26.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            if (onFingerprint != null) {
                TextButton(onClick = onFingerprint, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Use fingerprint", color = Color.White)
                }
            }
        }
    }
}

/** App lock settings: set / change / remove the PIN, fingerprint, and when to lock again. */
@Composable
fun AppLockScreen(onBack: () -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    val lock = remember { AppLock(context) }
    var enabled by remember { mutableStateOf(lock.enabled) }
    var fingerprint by remember { mutableStateOf(lock.fingerprint) }
    var timeout by remember { mutableIntStateOf(lock.timeoutSeconds) }
    var current by rememberSaveable { mutableStateOf("") }
    var newPin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val canFinger = remember { fingerprintAvailable(context) }

    ToolScaffold("App lock", onBack) {
        Text(
            "Protects Aadhaar numbers, marksheets and forms on this phone. The PIN is stored only on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (enabled) "Lock is ON" else "Lock is OFF", style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (enabled) PinField("Current PIN", current) { current = it }
                PinField(if (enabled) "New PIN (4 to 6 digits)" else "Choose a PIN (4 to 6 digits)", newPin) { newPin = it }
                PinField("Type the new PIN again", confirm) { confirm = it }
                Button(
                    onClick = {
                        when {
                            enabled && !lock.check(current) -> say("Current PIN is wrong")
                            !PinHash.valid(newPin) -> say("The PIN must be 4 to 6 digits")
                            newPin != confirm -> say("The two PINs don't match")
                            else -> {
                                lock.setPin(newPin)
                                enabled = true
                                current = ""; newPin = ""; confirm = ""
                                say("PIN saved. The app is now locked with it.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (enabled) "Change PIN" else "Turn on app lock") }
                if (enabled) {
                    OutlinedButton(
                        onClick = {
                            if (lock.check(current)) {
                                lock.disable()
                                enabled = false
                                fingerprint = false
                                current = ""
                                say("App lock turned off")
                            } else {
                                say("Type your current PIN to turn the lock off")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn off app lock") }
                }
            }
        }
        if (enabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (canFinger) "The PIN always works too." else "This phone has no fingerprint set up.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = fingerprint && canFinger, enabled = canFinger, onCheckedChange = { fingerprint = it; lock.fingerprint = it })
                    }
                    Text("Lock again after the app is left for", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Right away", 30 to "30 s", 60 to "1 min", 300 to "5 min").forEach { (s, label) ->
                            FilterChip(selected = timeout == s, onClick = { timeout = s; lock.timeoutSeconds = s }, label = { Text(label) })
                        }
                    }
                }
            }
        }
        Text(
            "Forgot the PIN? It can't be recovered. The only way in is Android Settings › Apps › Vigyan Scanner › Storage › Clear data, which deletes all scans on this phone. Keep a backup (⋮ › Backup & restore) so you can restore them.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
