package com.vigyan.scanner.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Jumps any screen can make: back to Home, or to the help for a topic (see HelpTopics). */
class AppNav(val home: () -> Unit, val help: (topic: String) -> Unit)

val LocalAppNav = staticCompositionLocalOf { AppNav(home = {}, help = {}) }

/** Top-bar buttons: ⓘ help for [topic], and 🏠 Home (unless [showHome] is off). */
@Composable
fun HelpHomeActions(topic: String, showHome: Boolean = true, showHelp: Boolean = true) {
    val nav = LocalAppNav.current
    if (showHelp) IconButton(onClick = { nav.help(topic) }) { Icon(Icons.Default.Info, "Help") }
    if (showHome) IconButton(onClick = { nav.home() }) { Icon(Icons.Default.Home, "Home") }
}
