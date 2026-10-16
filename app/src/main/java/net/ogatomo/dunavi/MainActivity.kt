package net.ogatomo.dunavi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ogatomo.dunavi.ui.AboutScreen
import net.ogatomo.dunavi.ui.DUNaviTheme
import net.ogatomo.dunavi.ui.HomeScreen
import net.ogatomo.dunavi.ui.LicenseDetailScreen
import net.ogatomo.dunavi.ui.LicensesScreen
import net.ogatomo.dunavi.ui.LogScreen
import net.ogatomo.dunavi.ui.SettingsScreen
import net.ogatomo.dunavi.ui.licenseEntries
import net.ogatomo.dunavi.ui.openProjectLink
import java.io.File

internal const val KEY_DEBUG_LOG = "debug_log"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceProfiles.init(this)
        setContent { DUNaviTheme { DUNaviScreen() } }
    }
}

private enum class AppPage(val label: String, val icon: Int? = null) {
    HOME("ホーム", R.drawable.ic_home), LOG("ログ", R.drawable.ic_logs), SETTINGS("設定", R.drawable.ic_settings),
    ABOUT("このアプリについて"), LICENSES("ライセンス"), LICENSE_DETAIL("ライセンス詳細"),
}
private enum class PermissionAction { START, DISCOVERABLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DUNaviScreen() {
    val context = LocalContext.current
    val state by DunState.state.collectAsStateWithLifecycle()
    val logEntries by SessionLog.lines.collectAsStateWithLifecycle()
    val sessions by SessionLog.sessions.collectAsStateWithLifecycle()
    val profiles by DeviceProfiles.profiles.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE) }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    var selectedLicense by rememberSaveable { mutableStateOf<String?>(null) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean(BootReceiver.KEY_AUTO_START, false)) }
    var debugMode by remember { mutableStateOf(prefs.getBoolean(KEY_DEBUG_LOG, false)) }
    var permissionAction by remember { mutableStateOf<PermissionAction?>(null) }
    var sensitiveExport by remember { mutableStateOf<ExportedLog?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<ConnectivityTestResult?>(null) }
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val logsScroll = rememberLazyListState()
    val aboutScroll = rememberScrollState()
    val licensesScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun back() {
        page = when (page) {
            AppPage.LICENSE_DETAIL -> AppPage.LICENSES
            AppPage.LICENSES -> AppPage.ABOUT
            else -> AppPage.SETTINGS
        }
    }
    BackHandler(enabled = page.icon == null, onBack = ::back)

    fun launchDiscoverable() {
        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
    }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (permissionAction) {
            PermissionAction.START -> if (bluetoothAllowed(context)) DunService.start(context)
            PermissionAction.DISCOVERABLE -> if (advertiseAllowed(context)) launchDiscoverable()
            null -> Unit
        }
        permissionAction = null
    }
    fun request(action: PermissionAction) {
        val missing = permissionsFor(action).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (action == PermissionAction.START) DunService.start(context) else launchDiscoverable()
        } else {
            permissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    fun export(sessionId: Long?) {
        scope.launch(Dispatchers.IO) {
            val exported = SessionLog.export(File(context.cacheDir, "exports"), sessionId)
            withContext(Dispatchers.Main) {
                if (exported == null) exportFailed = true
                else if (exported.containsSensitiveDetails) sensitiveExport = exported
                else shareLog(context, shareLauncher::launch, exported.file)
            }
        }
    }
    fun setAutoStart(enabled: Boolean) {
        autoStart = enabled
        prefs.edit(commit = true) { putBoolean(BootReceiver.KEY_AUTO_START, enabled) }
    }
    LaunchedEffect(Unit) {
        SessionLog.init(File(context.filesDir, "logs"))
        SessionLog.detailed = debugMode
    }
    sensitiveExport?.let { exported ->
        AlertDialog(
            onDismissRequest = { sensitiveExport = null },
            title = { Text("デバッグログを書き出しますか？") },
            text = { Text("APN、認証情報、電話番号、通信先アドレスやポートなど、機微な情報が含まれる可能性があります。共有先を確認してください。") },
            confirmButton = { TextButton(onClick = { sensitiveExport = null; shareLog(context, shareLauncher::launch, exported.file) }) { Text("続行") } },
            dismissButton = { TextButton(onClick = { sensitiveExport = null }) { Text("キャンセル") } },
        )
    }
    if (exportFailed) AlertDialog(
        onDismissRequest = { exportFailed = false }, title = { Text("ログを書き出せませんでした") },
        text = { Text("保存済みログがあることと、端末の空き容量を確認してください。") },
        confirmButton = { TextButton(onClick = { exportFailed = false }) { Text("閉じる") } },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(if (page == AppPage.HOME) "DUNavi" else page.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    if (page.icon == null) IconButton(onClick = ::back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "戻る")
                    }
                },
            )
        },
        bottomBar = {
            if (page.icon != null) NavigationBar {
                AppPage.entries.filter { it.icon != null }.forEach { item ->
                    NavigationBarItem(
                        selected = page == item, onClick = { page = item },
                        icon = { Icon(painterResource(item.icon!!), contentDescription = null) }, label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                when (page) {
                    AppPage.HOME -> HomeScreen(
                        state, autoStart, ::setAutoStart,
                        onStartStop = { if (state.phase == DunPhase.STOPPED || state.phase == DunPhase.ERROR) request(PermissionAction.START) else DunService.stop(context) },
                        onPairing = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        onDiscoverable = { request(PermissionAction.DISCOVERABLE) }, scrollState = homeScroll,
                    )
                    AppPage.LOG -> LogScreen(logEntries, sessions, SessionLog.currentSessionId, logsScroll, ::export)
                    AppPage.SETTINGS -> SettingsScreen(
                        autoStart, ::setAutoStart, debugMode, state.phase != DunPhase.CONNECTED,
                        onDebugChange = { debugMode = it; SessionLog.detailed = it; prefs.edit { putBoolean(KEY_DEBUG_LOG, it) } },
                        onOpenLogs = { page = AppPage.LOG }, onAbout = { page = AppPage.ABOUT }, profiles = profiles,
                        selfTestRunning = selfTestRunning, selfTestResult = selfTestResult,
                        onSelfTest = {
                            selfTestRunning = true
                            selfTestResult = null
                            scope.launch { selfTestResult = runConnectivitySelfTest(context); selfTestRunning = false }
                        }, scrollState = settingsScroll,
                    )
                    AppPage.ABOUT -> AboutScreen(
                        aboutScroll, onLicenses = { page = AppPage.LICENSES },
                        onGithub = { openProjectLink(context, "") }, onReleases = { openProjectLink(context, "/releases") },
                    )
                    AppPage.LICENSES -> LicensesScreen(licensesScroll) { selectedLicense = it; page = AppPage.LICENSE_DETAIL }
                    AppPage.LICENSE_DETAIL -> LicenseDetailScreen(licenseEntries.firstOrNull { it.artifactId == selectedLicense })
                }
            }
        }
    }
}

private fun permissionsFor(action: PermissionAction): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        if (action == PermissionAction.START) add(Manifest.permission.BLUETOOTH_CONNECT) else add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (action == PermissionAction.START && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun shareLog(context: Context, launch: (Intent) -> Unit, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "DUNaviログを保存・共有"))
}

private fun bluetoothAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
private fun advertiseAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
