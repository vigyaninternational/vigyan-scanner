package com.vigyan.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vigyan.scanner.ui.BusyDialog
import com.vigyan.scanner.ui.DetailScreen
import com.vigyan.scanner.ui.FillScreen
import com.vigyan.scanner.ui.HomeScreen
import com.vigyan.scanner.ui.ScanMode
import com.vigyan.scanner.ui.ScannerTheme
import com.vigyan.scanner.ui.TextScreen

class MainActivity : ComponentActivity() {

    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScannerTheme {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }

    @Composable
    private fun App(vm: ScanViewModel) {
        val nav = rememberNavController()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val pendingSend by vm.pendingSend.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(message) {
            message?.let {
                vm.messageShown()
                snackbar.showSnackbar(it)
            }
        }

        // Opening Drive / the share sheet needs this Activity, so the ViewModel hands the files here.
        LaunchedEffect(pendingSend) {
            val send = pendingSend ?: return@LaunchedEffect
            vm.sendHandled()
            try {
                when (send.target) {
                    ScanViewModel.Target.DRIVE -> {
                        if (!Exporter.isDriveInstalled(this@MainActivity)) {
                            vm.say("Google Drive app not found: pick Drive (or another app) from the list")
                        }
                        Exporter.uploadToDrive(this@MainActivity, send.files)
                    }
                    else -> Exporter.share(this@MainActivity, send.files)
                }
            } catch (e: Exception) {
                vm.say("Could not open another app: ${e.message}")
            }
        }

        Box(Modifier.fillMaxSize()) {
            Routes(nav, vm)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp))
        }
        busy?.let { BusyDialog(it) }
    }

    @Composable
    private fun Routes(nav: NavHostController, vm: ScanViewModel) {
        val scans by vm.scans.collectAsStateWithLifecycle()

        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(vm) { scan, mode ->
                    nav.navigate("scan/${scan.id}")
                    when (mode) {
                        ScanMode.TEXT -> nav.navigate("text/${scan.id}")
                        ScanMode.FILL -> nav.navigate("fill/${scan.id}")
                        ScanMode.DOCUMENT -> Unit
                    }
                }
            }
            composable("scan/{id}") { entry ->
                scans.firstOrNull { it.id == entry.arguments?.getString("id") }?.let { scan ->
                    DetailScreen(
                        vm, scan,
                        onBack = { nav.popBackStack() },
                        onText = { nav.navigate("text/${scan.id}") },
                        onFill = { nav.navigate("fill/${scan.id}") },
                    )
                }
            }
            composable("text/{id}") { entry ->
                scans.firstOrNull { it.id == entry.arguments?.getString("id") }
                    ?.let { TextScreen(vm, it, onBack = { nav.popBackStack() }) }
            }
            composable("fill/{id}") { entry ->
                scans.firstOrNull { it.id == entry.arguments?.getString("id") }
                    ?.let { FillScreen(vm, it, onBack = { nav.popBackStack() }) }
            }
        }
    }
}
