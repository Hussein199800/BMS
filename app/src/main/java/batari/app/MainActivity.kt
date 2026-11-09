package batari.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import batari.app.data.SourceMode
import batari.app.ui.BatariTheme
import batari.app.ui.CellsScreen
import batari.app.ui.ConnectScreen
import batari.app.ui.DetailsScreen
import batari.app.ui.HistoryScreen
import batari.app.ui.HomeScreen
import batari.app.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            BatariTheme(settings.theme) { AppRoot(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.autoConnectIfPossible()
    }
}

private val TABS = listOf("الرئيسية", "الخلايا", "التفاصيل", "السجل", "الإعدادات")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val diag by vm.diag.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val scanItems by vm.scanner.items.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanner.error.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showConnect by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val csv = vm.exportCsv()
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
        }
    }

    BackHandler(enabled = showConnect || tab != 0) {
        if (showConnect) { vm.scanner.stop(); showConnect = false } else tab = 0
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (showConnect) "الاتصال بالبطارية" else TABS[tab], fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (showConnect) IconButton(onClick = { vm.scanner.stop(); showConnect = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    },
                )
                if (mode == SourceMode.DEMO) {
                    // شريط ثابت يظهر في كل الشاشات ما دام الوضع التجريبي فعّالًا.
                    Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "⚠ وضع تجريبي — ليست بيانات البطارية",
                            color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!showConnect) NavigationBar {
                val icons = listOf(Icons.Filled.Home, Icons.AutoMirrored.Filled.List, Icons.Filled.Info, Icons.Filled.DateRange, Icons.Filled.Settings)
                TABS.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = null) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (showConnect) {
                ConnectScreen(
                    settings = settings, items = scanItems, scanning = scanning, scanError = scanError, diag = diag,
                    onScan = { vm.scanner.start() }, onStopScan = { vm.scanner.stop() },
                    onConnect = { a, n -> vm.connect(a, n); showConnect = false; tab = 0 },
                    onDiagnose = { a, n -> vm.runDiagnostics(a, n) },
                    onCloseDiag = { vm.clearDiagnostics() },
                )
            } else when (tab) {
                0 -> HomeScreen(snapshot, link, linkError, mode, settings, remember(now) { vm.repo.average(now) }, now,
                    onOpenConnect = { showConnect = true }, onStartDemo = { vm.startDemo() })
                1 -> CellsScreen(snapshot)
                2 -> DetailsScreen(snapshot)
                3 -> HistoryScreen(history, mode, refreshKey = now / 30_000, onLoad = vm::loadHistory,
                    onExport = { exportLauncher.launch("battery-history.csv") }, onClear = vm::clearHistory)
                else -> SettingsScreen(
                    s = settings, mode = mode, update = vm.repo.settings::update,
                    onOpenConnect = { showConnect = true },
                    onToggleDemo = { if (it) vm.startDemo() else vm.stopDemo() },
                    onEnableAlert = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }
    }
}
