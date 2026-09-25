package com.vigyan.scanner

import android.content.Intent
import android.net.Uri
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
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.vigyan.scanner.ui.BusyDialog
import com.vigyan.scanner.ui.BrandingScreen
import com.vigyan.scanner.ui.ChecklistScreen
import com.vigyan.scanner.ui.CutoutScreen
import com.vigyan.scanner.ui.MarksheetScreen
import com.vigyan.scanner.ui.PassportScreen
import com.vigyan.scanner.ui.StudentDocsScreen
import com.vigyan.scanner.ui.rememberScanner
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
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            ScannerTheme {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A photo or PDF shared to the app (WhatsApp, Gallery, Files…) becomes a new scan. */
    private fun handleShared(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    @Composable
    private fun App(vm: ScanViewModel) {
        val nav = rememberNavController()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val pendingSend by vm.pendingSend.collectAsStateWithLifecycle()
        val openScan by vm.openScan.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(message) {
            message?.let {
                vm.messageShown()
                snackbar.showSnackbar(it)
            }
        }

        // Something shared from another app was imported: show it.
        LaunchedEffect(openScan) {
            val id = openScan ?: return@LaunchedEffect
            vm.openHandled()
            nav.popBackStack("home", false)
            nav.navigate("scan/$id")
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
                HomeScreen(
                    vm,
                    onOpen = { scan, mode ->
                        nav.navigate("scan/${scan.id}")
                        when (mode) {
                            ScanMode.TEXT -> nav.navigate("text/${scan.id}")
                            ScanMode.FILL -> nav.navigate("fill/${scan.id}")
                            ScanMode.DOCUMENT -> Unit
                        }
                    },
                    onNavigate = { nav.navigate(it) },
                )
            }
            composable(
                "scan/{id}?resize={resize}",
                arguments = listOf(navArgument("resize") { type = NavType.StringType; defaultValue = "0" }),
            ) { entry ->
                scans.firstOrNull { it.id == entry.arguments?.getString("id") }?.let { scan ->
                    DetailScreen(
                        vm, scan,
                        openResize = entry.arguments?.getString("resize") == "1",
                        onBack = { nav.popBackStack() },
                        onText = { nav.navigate("text/${scan.id}") },
                        onFill = { nav.navigate("fill/${scan.id}") },
                        onTool = { tool -> nav.navigate(if (tool == "marks") "marks/${scan.id}" else tool) },
                    )
                }
            }
            composable("marks/{id}") { entry ->
                scans.firstOrNull { it.id == entry.arguments?.getString("id") }
                    ?.let { MarksheetScreen(vm, it, onBack = { nav.popBackStack() }) }
            }
            composable("passport") { PassportScreen(vm, onBack = { nav.popBackStack() }) }
            composable("cutout") {
                CutoutScreen(vm, onBack = { nav.popBackStack() }, onBranding = { nav.navigate("branding") })
            }
            composable("branding") {
                val scanner = rememberScanner(onError = vm::say) { uris -> vm.cutoutFromUris(uris) { nav.navigate("cutout") } }
                BrandingScreen(vm, onBack = { nav.popBackStack() }, onScanCutout = { scanner(1) })
            }
            composable("checklist") {
                ChecklistScreen(vm, onBack = { nav.popBackStack() }, onStudent = { nav.navigate("student/$it") })
            }
            composable("student/{id}") { entry ->
                StudentDocsScreen(
                    vm, entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenScan = { nav.navigate("scan/$it") },
                )
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
