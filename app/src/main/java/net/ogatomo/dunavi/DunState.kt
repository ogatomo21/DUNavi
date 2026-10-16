package net.ogatomo.dunavi

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DunPhase { STOPPED, WAITING, CONNECTED, ERROR }

enum class DunSessionStage(val label: String) {
    WAITING("待機中"),
    RFCOMM("RFCOMM接続"),
    AT("AT初期化"),
    PPP("PPPネゴシエーション"),
    NETWORK("ネットワーク接続"),
    DISCONNECTED("切断"),
}

data class DunUiState(
    val phase: DunPhase = DunPhase.STOPPED,
    val device: String? = null,
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val message: String = "停止中",
    val sessionId: Long = 0,
    val sessionStage: DunSessionStage = DunSessionStage.DISCONNECTED,
    val sessionStartedAtMs: Long = 0,
    val dnsQueries: Int = 0,
    val dnsResponses: Int = 0,
    val tcpConnections: Int = 0,
    val tcpFailures: Int = 0,
    val droppedPackets: Int = 0,
    val network: String = "未確認",
)

object DunState {
    private val mutable = MutableStateFlow(DunUiState())
    val state = mutable.asStateFlow()

    init {
        SessionLog.init(null) // in-memory only until the service binds a storage directory
    }

    fun phase(phase: DunPhase, message: String, device: String? = null) {
        mutable.update { it.copy(phase = phase, message = message, device = device) }
        log(message)
    }

    fun traffic(uploaded: Long, downloaded: Long) {
        mutable.update { it.copy(uploaded = it.uploaded + uploaded, downloaded = it.downloaded + downloaded) }
    }

    fun beginSession(id: Long, device: String) {
        mutable.update {
            it.copy(
                phase = DunPhase.CONNECTED,
                message = "モデム接続中",
                device = device,
                uploaded = 0,
                downloaded = 0,
                sessionId = id,
                sessionStage = DunSessionStage.RFCOMM,
                sessionStartedAtMs = System.currentTimeMillis(),
                dnsQueries = 0,
                dnsResponses = 0,
                tcpConnections = 0,
                tcpFailures = 0,
                droppedPackets = 0,
            )
        }
    }

    fun session(stage: DunSessionStage) {
        mutable.update { it.copy(sessionStage = stage) }
    }

    fun network(label: String) {
        mutable.update { it.copy(network = label) }
    }

    fun observe(category: LogCategory, direction: String?, message: String) {
        mutable.update { state ->
            when {
                category == LogCategory.DNS && direction == "RX" && message.startsWith("query received") ->
                    state.copy(dnsQueries = state.dnsQueries + 1)
                category == LogCategory.DNS && direction == "RX" && message.startsWith("upstream response") ->
                    state.copy(dnsResponses = state.dnsResponses + 1)
                category == LogCategory.TCP && direction == null && message.endsWith("ESTABLISHED") ->
                    state.copy(tcpConnections = state.tcpConnections + 1)
                category == LogCategory.ERROR && message.startsWith("TCP ") && message.endsWith("CONNECT FAILED") ->
                    state.copy(tcpFailures = state.tcpFailures + 1)
                "DROP" in message -> state.copy(droppedPackets = state.droppedPackets + 1)
                else -> state
            }
        }
    }

    fun log(message: String) {
        val clean = SessionLog.sanitize(message)
        Log.i("DUNavi", clean)
        SessionLog.diagnostic(LogCategory.SESSION, message = clean)
    }

    fun stopped() { mutable.value = DunUiState() }
}
