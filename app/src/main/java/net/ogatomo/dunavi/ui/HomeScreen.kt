package net.ogatomo.dunavi.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import net.ogatomo.dunavi.DunPhase
import net.ogatomo.dunavi.DunSessionStage
import net.ogatomo.dunavi.DunUiState
import net.ogatomo.dunavi.R

@Composable
internal fun HomeScreen(
    state: DunUiState,
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    onStartStop: () -> Unit,
    onPairing: () -> Unit,
    onDiscoverable: () -> Unit,
    scrollState: ScrollState,
) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StatusCard(state)
        Button(onClick = onStartStop, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.phase == DunPhase.STOPPED || state.phase == DunPhase.ERROR) "待受を開始" else "停止", Modifier.padding(vertical = 8.dp))
        }
        Section("接続") {
            SettingSwitch("端末起動時に自動待受", "継続通知を表示して接続を待ちます", autoStart, onChange = onAutoStartChange)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onPairing) { Text("ペアリング") }
                FilledTonalButton(onClick = onDiscoverable) { Text("検出可能にする") }
            }
        }
        Section("はじめての接続") {
            Text("1. ナビとAndroidをペアリング")
            Text("2. DUNaviの待受を開始")
            Text("3. ナビの通信設定からBluetooth接続")
        }
    }
}

@Composable
internal fun StatusCard(state: DunUiState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember(state.sessionId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner, state.sessionId, state.phase) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = System.currentTimeMillis()
            while (state.phase == DunPhase.CONNECTED) {
                delay(1_000)
                now = System.currentTimeMillis()
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val container = when (state.phase) {
        DunPhase.CONNECTED -> colors.primaryContainer
        DunPhase.ERROR -> colors.errorContainer
        else -> colors.secondaryContainer
    }
    val foreground = if (state.phase == DunPhase.ERROR) colors.onErrorContainer else if (state.phase == DunPhase.CONNECTED) colors.onPrimaryContainer else colors.onSecondaryContainer
    val title = when (state.phase) {
        DunPhase.STOPPED -> "停止中"
        DunPhase.ERROR -> "接続エラー"
        DunPhase.WAITING -> "待機中"
        DunPhase.CONNECTED -> state.sessionStage.label
    }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = container, contentColor = foreground)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
                Text("BLUETOOTH DUN", style = MaterialTheme.typography.labelLarge)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (state.message != title) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            state.device?.let { Text("接続中端末: $it", style = MaterialTheme.typography.titleMedium) }
            HorizontalDivider(color = foreground.copy(alpha = 0.16f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text("送信", style = MaterialTheme.typography.labelMedium)
                    Text(formatBytes(state.uploaded), style = MaterialTheme.typography.headlineSmall)
                }
                Column {
                    Text("受信", style = MaterialTheme.typography.labelMedium)
                    Text(formatBytes(state.downloaded), style = MaterialTheme.typography.headlineSmall)
                }
            }
            Text("上流: ${state.network}", style = MaterialTheme.typography.bodyMedium)
            if (state.sessionId > 0) {
                Text("セッション #${state.sessionId} ・ ${state.sessionStage.label}", style = MaterialTheme.typography.labelLarge)
            }
            if (state.sessionStartedAtMs > 0) {
                Text("接続時間 ${formatDuration(now - state.sessionStartedAtMs)}", style = MaterialTheme.typography.bodyMedium)
                Text("DNS ${state.dnsResponses}/${state.dnsQueries}成功 · TCP ${state.tcpConnections}接続/${state.tcpFailures}失敗 · DROP ${state.droppedPackets}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(name = "停止", showBackground = true)
@Composable
private fun HomePreview() = DUNaviTheme {
    HomeScreen(DunUiState(), false, {}, {}, {}, {}, rememberScrollState())
}

@Preview(name = "待受", showBackground = true)
@Composable
private fun WaitingPreview() = DUNaviTheme { StatusCard(DunUiState(phase = DunPhase.WAITING, message = "DUN接続を待機中")) }

@Preview(name = "RFCOMM", showBackground = true)
@Composable
private fun RfcommPreview() = DUNaviTheme { StatusCard(DunUiState(phase = DunPhase.CONNECTED, device = "CAR MULTIMEDIA", sessionStage = DunSessionStage.RFCOMM)) }

@Preview(name = "PPP", showBackground = true)
@Composable
private fun PppPreview() = DUNaviTheme { StatusCard(DunUiState(phase = DunPhase.CONNECTED, device = "CAR MULTIMEDIA", sessionStage = DunSessionStage.PPP)) }

@Preview(name = "接続・ダーク", showBackground = true)
@Composable
private fun ConnectedPreview() = DUNaviTheme(darkTheme = true) { StatusCard(DunUiState(phase = DunPhase.CONNECTED, device = "CAR MULTIMEDIA", sessionStage = DunSessionStage.NETWORK, uploaded = 24576, downloaded = 1258291)) }

@Preview(name = "エラー・文字拡大", showBackground = true, fontScale = 2f, widthDp = 320)
@Composable
private fun ErrorPreview() = DUNaviTheme { StatusCard(DunUiState(phase = DunPhase.ERROR, message = "Bluetoothの権限がありません")) }
