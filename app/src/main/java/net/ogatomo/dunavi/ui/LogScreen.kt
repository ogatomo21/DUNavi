package net.ogatomo.dunavi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.ogatomo.dunavi.LogCategory
import net.ogatomo.dunavi.LogEntry
import net.ogatomo.dunavi.StoredSession

@Composable
internal fun LogScreen(entries: List<LogEntry>, sessions: List<StoredSession>, currentSessionId: Long, listState: LazyListState, onExport: (Long?) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("ログの書き出し") {
                Text("画面には最新750件を表示します。ファイルには保存容量の範囲内でセッション全体を記録します。", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { onExport(currentSessionId) }, enabled = currentSessionId > 0) { Text("現在を書き出す") }
                    FilledTonalButton(onClick = { onExport(null) }) { Text("全ログを書き出す") }
                }
            }
        }
        item { Text("現在の診断ログ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (entries.isEmpty()) item { Text("接続するとここに診断ログが表示されます。", style = MaterialTheme.typography.bodyMedium) }
        items(entries) { entry ->
            val text = remember(entry) { entry.format(withSession = false, withDate = false) }
            Text(
                text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = if (entry.category == LogCategory.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Text("過去セッション", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        val past = sessions.filter { it.id != currentSessionId }
        if (past.isEmpty()) item { Text("保存済みの過去セッションはありません。", style = MaterialTheme.typography.bodyMedium) }
        items(past, key = { it.id }) { session ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("セッション #${session.id}", style = MaterialTheme.typography.titleMedium)
                    Text(formatDate(session.startedAtMs), style = MaterialTheme.typography.bodyMedium)
                    if (session.debug) Text("デバッグログ · 機微な情報を含む可能性があります", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onExport(session.id) }) { Text("書き出す") }
                }
            }
        }
    }
}
