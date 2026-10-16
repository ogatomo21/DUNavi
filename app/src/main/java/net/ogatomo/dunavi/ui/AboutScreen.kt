package net.ogatomo.dunavi.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ogatomo.dunavi.AppLicenses
import net.ogatomo.dunavi.BuildConfig
import net.ogatomo.dunavi.R
import net.syarihu.licensescribe.LicenseInfo

internal const val PROJECT_URL = "https://github.com/ogatomo21/DUNavi"
internal val licenseEntries: List<LicenseInfo> = listOf(
    LicenseInfo("net.ogatomo.dunavi", "DUNavi", PROJECT_URL, listOf("Copyright (c) 2026 Tomoya Ogawa"), "MIT License", "https://opensource.org/license/mit"),
    LicenseInfo("free-proxy:f13432fe12866fa6f66b150f25c2b1ec0549eb77", "FreeProxy (改変して使用)", "https://github.com/xVanTuring/free-proxy", listOf("xVanTuring and contributors"), "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
) + AppLicenses.all.sortedBy { it.artifactName.lowercase() }

@Composable
internal fun AboutScreen(scrollState: ScrollState, onLicenses: () -> Unit, onGithub: () -> Unit, onReleases: () -> Unit) {
    val context = LocalContext.current
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("DUNavi") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = CircleShape, color = Color(0xff7086bd), modifier = Modifier.size(72.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
                }
                Column(Modifier.widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DUNavi", style = MaterialTheme.typography.headlineSmall)
                    Text("by Tomoya Ogawa", style = MaterialTheme.typography.bodyLarge)
                    Text("@ogatomo21", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("T-Connectナビを、Bluetooth DUNでインターネットへ。", style = MaterialTheme.typography.bodyLarge)
            Text("root・VPN・システムテザリング不使用", style = MaterialTheme.typography.bodyMedium)
        }
        Section("バージョン情報") {
            Text("バージョン ${info.versionName ?: "不明"} (${PackageInfoCompat.getLongVersionCode(info)})", style = MaterialTheme.typography.titleMedium)
            Text(context.packageName, style = MaterialTheme.typography.bodyMedium)
            Text(if (BuildConfig.DEBUG) "Debug build" else "Release build", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Section("リンクとライセンス") {
            LinkRow("ライセンス", "本アプリと使用しているオープンソースソフトウェア", R.drawable.ic_logs, onLicenses)
            LinkRow("GitHub", "ソースコード・不具合報告", onClick = onGithub)
            LinkRow("リリース一覧", "公開済みバージョンを確認", onClick = onReleases)
        }
        Text("Copyright (c) 2026 Tomoya Ogawa", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LicensesScreen(listState: LazyListState, onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("DUNaviは以下のオープンソースソフトウェアを使用しています。ライセンス本文はオフラインでも確認できます。", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium) }
        items(licenseEntries, key = { it.artifactId }) { license ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LinkRow(license.artifactName, license.licenseName, R.drawable.ic_logs) { onSelect(license.artifactId) }
            }
        }
    }
}

internal fun licenseAsset(name: String): String? = when {
    name.contains("Apache", ignoreCase = true) && name.contains("2") -> "licenses/Apache-2.0.txt"
    name.contains("MIT", ignoreCase = true) -> "licenses/MIT.txt"
    else -> null
}

@Composable
internal fun LicenseDetailScreen(license: LicenseInfo?) {
    val context = LocalContext.current
    val text by produceState<String?>(null, license?.artifactId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val asset = licenseAsset(license?.licenseName.orEmpty()) ?: return@runCatching "ライセンス本文を確認できません。"
                val notice = if (license?.artifactId?.startsWith("free-proxy:") == true) context.assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } + "\n\n" else ""
                notice + context.assets.open(asset).bufferedReader().use { it.readText() }
            }.getOrElse { "ライセンス本文を読み込めませんでした。" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(license?.artifactName ?: "ライセンスが見つかりません", style = MaterialTheme.typography.headlineSmall)
        license?.let {
            Text(it.artifactId, style = MaterialTheme.typography.bodySmall)
            Text(it.licenseName, style = MaterialTheme.typography.titleMedium)
            it.copyrightHolders.forEach { holder -> Text(holder, style = MaterialTheme.typography.bodyMedium) }
            it.artifactUrl?.let { url -> TextButton(onClick = { openExternalLink(context, url) }) { Text("プロジェクトのWebサイト") } }
            it.licenseUrl?.let { url -> TextButton(onClick = { openExternalLink(context, url) }) { Text("ライセンスのWebサイト") } }
        }
        SelectionContainer { Text(text ?: "読み込み中…", style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun openProjectLink(context: Context, suffix: String) = openExternalLink(context, PROJECT_URL + suffix)

private fun openExternalLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "リンクを開くアプリが見つかりません", Toast.LENGTH_SHORT).show()
    }
}
