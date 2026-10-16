package net.ogatomo.dunavi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import net.ogatomo.dunavi.net.IpRouter
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class DunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var listenJob: Job? = null
    private val notificationLock = Any()
    private var statsNotificationJob: Job? = null
    private var lastNotificationContent: NotificationContent? = null
    private var foregroundStarted = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkLabel = "未確認"

    override fun onCreate() {
        super.onCreate()
        SessionLog.detailed = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE).getBoolean(KEY_DEBUG_LOG, false)
        SessionLog.init(File(filesDir, "logs"))
        DeviceProfiles.init(this)
        createChannel()
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) {
            val content = notificationContent()
            startForeground(NOTIFICATION_ID, notification(content))
            synchronized(notificationLock) { lastNotificationContent = content }
            foregroundStarted = true
        }
        if (listenJob?.isActive != true) listenJob = scope.launch { listen() }
        return START_STICKY
    }

    private suspend fun listen() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            DunState.phase(DunPhase.ERROR, "Bluetoothの権限がありません")
            stopSelf()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) {
            DunState.phase(DunPhase.ERROR, "Bluetoothを利用できません")
            stopSelf()
            return
        }
        var retryMs = 1_000L
        while (currentCoroutineContext().isActive) {
            if (!adapter.isEnabled) {
                DunState.phase(DunPhase.WAITING, "Bluetoothの起動を待機中")
                DunState.session(DunSessionStage.WAITING)
                notifyImportantState()
                awaitBluetoothEnabled(adapter)
                retryMs = 1_000L
            }
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, DUN_UUID)
                SessionLog.diagnostic(LogCategory.BT, message = "DUN待受開始 (secure RFCOMM)")
                DunState.phase(DunPhase.WAITING, "DUN接続を待機中")
                DunState.session(DunSessionStage.WAITING)
                notifyImportantState()
                while (currentCoroutineContext().isActive) {
                    val socket = server?.accept() ?: break
                    retryMs = 1_000L
                    client = socket
                    val sessionId = SessionLog.beginSession()
                    handleClient(socket, sessionId)
                    client = null
                    DunState.phase(DunPhase.WAITING, "DUN接続を待機中")
                    DunState.session(DunSessionStage.WAITING)
                    SessionLog.diagnostic(LogCategory.BT, message = "DUN待受へ復帰")
                    notifyImportantState()
                }
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) break
                SessionLog.log(LogCategory.ERROR, null, "待受エラー: ${error.message ?: "不明"}; ${retryMs / 1_000}秒後に再試行")
                DunState.phase(DunPhase.WAITING, "待受を再試行中")
                notifyImportantState()
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(30_000L)
            } finally {
                runCatching { server?.close() }
                server = null
            }
        }
    }

    private fun handleClient(socket: BluetoothSocket, sessionId: Long) {
        val canReadDevice = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val name = (if (canReadDevice) runCatching { socket.remoteDevice.name }.getOrNull() else null)
            ?: "ペアリング済み機器"
        val address = (if (canReadDevice) runCatching { socket.remoteDevice.address }.getOrNull() else null)
            ?: "unknown-${name.hashCode()}"
        DeviceProfiles.connected(address, name)
        DunState.beginSession(sessionId, name)
        SessionLog.diagnostic(LogCategory.BT, message = "RFCOMM CONNECTED device=$name")
        notifyImportantState()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var router: IpRouter? = null
        var profileSucceeded = false
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val write: (ByteArray) -> Unit = { synchronized(output) { output.write(it); output.flush() } }
            val modem = AtModem(detailed = SessionLog.detailed, log = { direction, message ->
                SessionLog.log(LogCategory.AT, direction, message)
                if (direction == "RX") DeviceProfiles.observedAt(address, message)
            })
            val protocolLog: CatLog = { category, direction, message ->
                SessionLog.log(category, direction, message)
                DunState.observe(category, direction, message)
            }
            var ppp: PppSession? = null
            var decoder: PppDecoder? = null
            var dataMode = false
            var atInitialized = false
            DunState.session(DunSessionStage.AT)
            notifyImportantState()
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                if (dataMode) {
                    decoder?.accept(byte)
                } else {
                    val result = modem.accept(byte)
                    if (result.output.isNotEmpty()) write(result.output)
                    if (result.commandComplete && !atInitialized) {
                        atInitialized = true
                        SessionLog.diagnostic(LogCategory.AT, message = "AT modem initialized")
                    }
                    if (result.dial) {
                        SessionLog.diagnostic(LogCategory.PPP, message = "PPP negotiation started")
                        DunState.session(DunSessionStage.PPP)
                        notifyImportantState()
                        lateinit var currentPpp: PppSession
                        val dns = activeDns()
                        SessionLog.diagnostic(LogCategory.DNS, message = "DNS available")
                        SessionLog.log(LogCategory.DNS, null, "DNS upstream: ${dns.hostAddress}")
                        router = IpRouter(
                            sessionScope,
                            dns,
                            { currentPpp.sendIpv4(it) },
                            { uploaded, downloaded ->
                                DunState.traffic(uploaded, downloaded)
                                scheduleStatsNotification()
                            },
                            { currentPpp.negotiatedPeerMru },
                            protocolLog,
                        )
                        currentPpp = PppSession(
                            sendRaw = write,
                            onIpv4 = { router.accept(it) },
                            log = protocolLog,
                            rawLogEnabled = SessionLog.detailed,
                            onLcpOpened = {
                                SessionLog.diagnostic(LogCategory.LCP, message = "LCP OPEN")
                            },
                            onOpened = {
                                SessionLog.diagnostic(LogCategory.IPCP, message = "IPCP OPEN / NETWORK CONNECTED")
                                DunState.session(DunSessionStage.NETWORK)
                                if (!profileSucceeded) {
                                    profileSucceeded = true
                                    DeviceProfiles.succeeded(address)
                                }
                                notifyImportantState()
                            },
                        )
                        ppp = currentPpp
                        decoder = PppDecoder(
                            onFrame = currentPpp::accept,
                            onError = { message -> SessionLog.log(LogCategory.ERROR, null, message) },
                            onRawFrame = { frame ->
                                if (SessionLog.detailed) SessionLog.log(LogCategory.PPP, "RX RAW", frame.toHex())
                            },
                        )
                        dataMode = true
                        ppp.start()
                        sessionScope.launch {
                            while (isActive && dataMode) {
                                delay(500)
                                currentPpp.tick()
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            SessionLog.log(LogCategory.ERROR, null, "接続終了: ${error.message ?: "切断"}")
        } finally {
            SessionLog.diagnostic(LogCategory.BT, message = "RFCOMM DISCONNECTED")
            DunState.session(DunSessionStage.DISCONNECTED)
            SessionLog.endSession()
            router?.close()
            sessionScope.cancel()
            runCatching { socket.close() }
        }
    }

    private fun activeDns(): InetAddress {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        return network?.let { connectivity.getLinkProperties(it)?.dnsServers?.firstOrNull { address -> address.address.size == 4 } }
            ?: InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)).also {
                SessionLog.diagnostic(LogCategory.DNS, message = "DNS fallback: active network DNS unavailable; using 8.8.8.8")
            }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private suspend fun awaitBluetoothEnabled(adapter: BluetoothAdapter) {
        if (adapter.isEnabled) return
        suspendCancellableCoroutine { continuation ->
            val registered = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver
            fun finish() {
                if (registered.compareAndSet(true, false)) runCatching { unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(Unit)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) finish()
                }
            }
            registered.set(true)
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onFailure {
                registered.set(false)
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                if (registered.compareAndSet(true, false)) runCatching { unregisterReceiver(receiver) }
            }
            if (adapter.isEnabled) finish()
        }
    }

    private fun notificationContent(): NotificationContent = notificationContent(DunState.state.value)

    private fun notification(content: NotificationContent): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DunService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun notifyImportantState() {
        synchronized(notificationLock) {
            statsNotificationJob?.cancel()
            statsNotificationJob = null
        }
        postNotificationIfChanged()
    }

    private fun scheduleStatsNotification() {
        synchronized(notificationLock) {
            if (statsNotificationJob?.isActive == true) return
            statsNotificationJob = scope.launch {
                delay(STATS_NOTIFICATION_INTERVAL_MS)
                postNotificationIfChanged()
            }
        }
    }

    private fun postNotificationIfChanged() {
        val content = notificationContent()
        synchronized(notificationLock) {
            if (content == lastNotificationContent) return
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
            lastNotificationContent = content
        }
    }

    private fun watchNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル回線"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "その他"
                }
                updateNetwork("$transport (${if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "接続済み" else "未検証"})")
            }

            override fun onLost(network: Network) = updateNetwork("未接続")
        }
        networkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun updateNetwork(label: String) {
        if (label == networkLabel) return
        val previous = networkLabel
        networkLabel = label
        DunState.network(label)
        SessionLog.diagnostic(LogCategory.IP, message = "上流ネットワーク変更: $previous -> $label")
        if (listenJob?.isActive == true) notifyImportantState()
    }

    override fun onDestroy() {
        runCatching { client?.close() }
        runCatching { server?.close() }
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        scope.cancel()
        DunState.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "net.ogatomo.dunavi.START"
        const val ACTION_STOP = "net.ogatomo.dunavi.STOP"
        private const val CHANNEL_ID = "dun_waiting"
        private const val NOTIFICATION_ID = 103
        private const val STATS_NOTIFICATION_INTERVAL_MS = 30_000L
        private const val SERVICE_NAME = "Dial-up Networking"
        val DUN_UUID: UUID = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DunService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DunService::class.java))
        }
    }
}

internal data class NotificationContent(val title: String, val text: String, val subText: String)

internal fun notificationContent(state: DunUiState) = NotificationContent(
    title = if (state.phase == DunPhase.CONNECTED) state.sessionStage.label else "DUNavi 待受中",
    text = "送信 ${notificationBytes(state.uploaded)} / 受信 ${notificationBytes(state.downloaded)}",
    subText = state.device ?: state.network,
)

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

internal fun notificationBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1fMB".format(value / 1024.0 / 1024.0)
    value >= 1024 -> "%.1fKB".format(value / 1024.0)
    else -> "${value}B"
}
