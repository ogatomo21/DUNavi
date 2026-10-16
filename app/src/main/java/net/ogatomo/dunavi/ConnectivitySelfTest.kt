package net.ogatomo.dunavi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

data class ConnectivityTestResult(val successful: Boolean, val details: List<String>)

suspend fun runConnectivitySelfTest(context: Context): ConnectivityTestResult = withContext(Dispatchers.IO) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
        ?: return@withContext ConnectivityTestResult(false, listOf("上流ネットワークがありません"))
    val capabilities = connectivity.getNetworkCapabilities(network)
    val link = connectivity.getLinkProperties(network)
    val details = mutableListOf<String>()
    details += if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
        "インターネット検証: OK"
    } else {
        "インターネット検証: 未確認"
    }
    details += "DNS: ${link?.dnsServers?.joinToString { it.hostAddress ?: "?" }?.ifBlank { "なし" } ?: "なし"}"
    try {
        val address = network.getAllByName("example.com").first()
        details += "名前解決: OK (${address.hostAddress})"
        network.socketFactory.createSocket().use { socket -> socket.connect(InetSocketAddress(address, 443), 5_000) }
        details += "TCP 443: OK"
        ConnectivityTestResult(true, details)
    } catch (error: Exception) {
        details += "接続失敗: ${error.javaClass.simpleName}"
        ConnectivityTestResult(false, details)
    }
}
