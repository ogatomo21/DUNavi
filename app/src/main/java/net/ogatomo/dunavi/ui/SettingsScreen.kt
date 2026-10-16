package net.ogatomo.dunavi.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.ogatomo.dunavi.ConnectivityTestResult
import net.ogatomo.dunavi.DeviceProfile
import net.ogatomo.dunavi.R

@Composable
internal fun SettingsScreen(
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    debugMode: Boolean,
    debugEnabled: Boolean,
    onDebugChange: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onAbout: () -> Unit,
    profiles: List<DeviceProfile>,
    selfTestRunning: Boolean,
    selfTestResult: ConnectivityTestResult?,
    onSelfTest: () -> Unit,
    scrollState: ScrollState,
) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("接続") {
            SettingSwitch("端末起動時に自動待受", "端末の起動後、Bluetoothが有効になると待受を開始します", autoStart, onChange = onAutoStartChange)
        }
        Section("診断") {
            Text("接続前セルフテスト", style = MaterialTheme.typography.titleMedium)
            Text("上流ネットワーク、DNS名前解決、TCP 443を確認します。", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onSelfTest, enabled = !selfTestRunning) { Text(if (selfTestRunning) "確認中…" else "セルフテストを実行") }
            selfTestResult?.let { result ->
                Text(if (result.successful) "結果: 正常" else "結果: 要確認", color = if (result.successful) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                result.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            SettingSwitch("デバッグモード", if (debugEnabled) "AT全文、PPP raw hex、IP/TCP/UDP詳細を保存します" else "接続中は変更できません", debugMode, debugEnabled, onDebugChange)
            if (debugMode) Text("APN、認証情報、電話番号、通信先などが記録される可能性があります。共有時は内容を確認してください。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Section("接続端末プロファイル") {
            if (profiles.isEmpty()) Text("接続履歴はありません", style = MaterialTheme.typography.bodyMedium)
            profiles.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider()
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.address, style = MaterialTheme.typography.bodySmall)
                Text("接続 ${profile.connections}回 · ネットワーク成功 ${profile.successfulSessions}回", style = MaterialTheme.typography.bodyMedium)
                if (profile.observedAtCommands.isNotEmpty()) Text("AT: ${profile.observedAtCommands.joinToString(" → ")}", style = MaterialTheme.typography.bodySmall)
                Text("最終接続 ${formatDate(profile.lastConnectedAtMs)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("ログ") {
            LinkRow("ログ管理", "内部ストレージに5MB × 5ファイルまで保存します", R.drawable.ic_logs, onOpenLogs)
        }
        Section("アプリ情報") {
            LinkRow("このアプリについて", "バージョン・作者・ライセンス", onClick = onAbout)
        }
    }
}
