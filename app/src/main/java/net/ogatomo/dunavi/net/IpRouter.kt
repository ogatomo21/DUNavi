package net.ogatomo.dunavi.net

import net.ogatomo.dunavi.CatLog
import net.ogatomo.dunavi.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class IpRouter(
    private val scope: CoroutineScope,
    private val dnsServer: InetAddress,
    private val output: (ByteArray) -> Unit,
    private val onTraffic: (Long, Long) -> Unit,
    private val linkMtu: () -> Int = { 1500 },
    private val log: CatLog = { _, _, _ -> },
) {
    private val tcp = ConcurrentHashMap<SessionKey, TcpSession>()
    private val udp = ConcurrentHashMap<SessionKey, UdpSession>()
    private val receivedPackets = AtomicLong()
    private val sentPackets = AtomicLong()
    private val cleanup: Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(5_000)
            val now = System.nanoTime() / 1_000_000
            tcp.values.filter { now - it.lastActivity > 300_000 }.forEach { it.finish() }
            udp.values.filter { now - it.lastActivity > 120_000 }.forEach { it.finish() }
        }
    }

    fun accept(packet: ByteArray) {
        val ip = Ipv4Header.parse(packet, packet.size) ?: return
        receivedPackets.incrementAndGet().also { if (it == 1L || it % 100L == 0L) log(LogCategory.IP, "RX", "packets=$it") }
        when (ip.protocol) {
            PROTO_TCP -> handleTcp(ip, packet)
            PROTO_UDP -> handleUdp(ip, packet)
        }
    }

    private fun handleTcp(ip: Ipv4Header, packet: ByteArray) {
        val header = TcpHeader.parse(packet, ip.headerLength, ip.payloadLength) ?: return
        val key = SessionKey(ip.sourceIp, header.sourcePort, ip.destIp, header.destPort)
        val payloadOffset = ip.headerLength + header.dataOffset
        val payloadLength = ip.totalLength - payloadOffset
        if (payloadLength < 0) return
        log(
            LogCategory.TCP,
            "RX",
            "${endpoint(ip.sourceIp, header.sourcePort)} -> ${endpoint(ip.destIp, header.destPort)} " +
                "flags=${header.flagDescription()} bytes=$payloadLength",
        )
        tcp[key]?.let { it.onPacket(header, packet, payloadOffset, payloadLength); return }
        if (!header.isSyn || tcp.size >= 128) { if (!header.isRst) sendReset(key, header); return }
        val session = TcpSession(key, scope, ::emit, onTraffic, linkMtu, log) { tcp.remove(it) }
        val existing = tcp.putIfAbsent(key, session)
        if (existing == null) session.open(header) else existing.onPacket(header, packet, payloadOffset, payloadLength)
    }

    private fun sendReset(key: SessionKey, tcp: TcpHeader) {
        val buffer = ByteArray(40)
        val size = PacketBuilder.writeTcp(buffer, key.destIp, key.destPort, key.sourceIp, key.sourcePort,
            tcp.acknowledgment, seqAdvance(tcp.sequence, if (tcp.isSyn) 1 else 0),
            TcpHeader.RST or TcpHeader.ACK, 0)
        output(buffer.copyOf(size))
    }

    private fun handleUdp(ip: Ipv4Header, packet: ByteArray) {
        val header = UdpHeader.parse(packet, ip.headerLength, ip.payloadLength) ?: return
        if (header.payloadLength <= 0) return
        val key = SessionKey(ip.sourceIp, header.sourcePort, ip.destIp, header.destPort)
        val isDns = key.destPort == DNS_PORT
        val payload = packet.copyOfRange(ip.headerLength + UdpHeader.SIZE, ip.headerLength + header.length)
        log(
            if (isDns) LogCategory.DNS else LogCategory.UDP,
            "RX",
            if (isDns) "query received id=${dnsId(payload)} peer=${endpoint(key.sourceIp, key.sourcePort)} " +
                "requested=${endpoint(key.destIp, key.destPort)} bytes=${header.payloadLength}"
            else "${endpoint(key.sourceIp, key.sourcePort)} -> ${endpoint(key.destIp, key.destPort)} bytes=${header.payloadLength}",
        )
        val existing = udp[key]
        val session = if (existing != null) existing else {
            if (udp.size >= 64) {
                log(if (isDns) LogCategory.DNS else LogCategory.UDP, null, "DROP: UDP session limit reached")
                return
            }
            val created = UdpSession(key, scope, dnsServer, ::emit, onTraffic, log) { udp.remove(it) }
            udp.putIfAbsent(key, created)?.also { created.closeUnregistered() } ?: created
        }
        session.send(payload)
    }

    private fun emit(packet: ByteArray) {
        sentPackets.incrementAndGet().also { if (it == 1L || it % 100L == 0L) log(LogCategory.IP, "TX", "packets=$it") }
        log(LogCategory.IP, "TX", "IPv4 packet handed to PPP bytes=${packet.size}")
        output(packet)
    }

    fun close() {
        cleanup.cancel(); tcp.values.forEach { it.finish() }; udp.values.forEach { it.finish() }
    }

    private fun endpoint(ip: Int, port: Int) = "${ip.toInetAddress().hostAddress}:$port"
}

private fun TcpHeader.flagDescription(): String = buildList {
    if (isSyn) add("SYN")
    if (isAck) add("ACK")
    if (isFin) add("FIN")
    if (isRst) add("RST")
    if (flags and TcpHeader.PSH != 0) add("PSH")
}.ifEmpty { listOf("NONE") }.joinToString("|")

private class UdpSession(
    private val key: SessionKey,
    private val scope: CoroutineScope,
    dnsServer: InetAddress,
    private val output: (ByteArray) -> Unit,
    private val onTraffic: (Long, Long) -> Unit,
    private val log: CatLog,
    private val onFinished: (SessionKey) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val destination = udpUpstreamAddress(key.destIp, key.destPort, dnsServer)
    private val socket = DatagramSocket().apply { connect(destination, key.destPort); soTimeout = 5_000 }
    private val isDns = key.destPort == DNS_PORT
    @Volatile var lastActivity = System.nanoTime() / 1_000_000
        private set

    init { scope.launch(Dispatchers.IO) { receiveLoop() } }

    fun send(payload: ByteArray) {
        if (closed.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                log(
                    if (isDns) LogCategory.DNS else LogCategory.UDP,
                    "TX",
                    if (isDns) "upstream send id=${dnsId(payload)} requested=${key.destIp.toInetAddress().hostAddress}:${key.destPort} " +
                        "upstream=${destination.hostAddress}:${key.destPort} bytes=${payload.size}"
                    else "${key.sourceIp.toInetAddress().hostAddress}:${key.sourcePort} -> " +
                        "${destination.hostAddress}:${key.destPort} bytes=${payload.size}",
                )
                socket.send(DatagramPacket(payload, payload.size)); onTraffic(payload.size.toLong(), 0)
                lastActivity = System.nanoTime() / 1_000_000
            } catch (error: Exception) {
                log(if (isDns) LogCategory.DNS else LogCategory.UDP, null,
                    "upstream send failed: ${error.javaClass.simpleName}: ${error.message ?: "no detail"}")
                finish()
            }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65507)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                log(
                    if (isDns) LogCategory.DNS else LogCategory.UDP,
                    "RX",
                    if (isDns) "upstream response id=${dnsId(buffer, packet.length)} upstream=${destination.hostAddress}:${key.destPort} " +
                        "peer=${key.sourceIp.toInetAddress().hostAddress}:${key.sourcePort} bytes=${packet.length}"
                    else "${destination.hostAddress}:${key.destPort} -> " +
                        "${key.sourceIp.toInetAddress().hostAddress}:${key.sourcePort} bytes=${packet.length}",
                )
                lastActivity = System.nanoTime() / 1_000_000
                onTraffic(0, packet.length.toLong())
                val reply = ByteArray(28 + packet.length)
                val size = PacketBuilder.writeUdp(reply, key.destIp, key.destPort, key.sourceIp, key.sourcePort,
                    buffer, 0, packet.length)
                if (isDns) log(LogCategory.DNS, "TX",
                    "IPv4 response built id=${dnsId(buffer, packet.length)} " +
                        "${key.destIp.toInetAddress().hostAddress}:${key.destPort} -> " +
                        "${key.sourceIp.toInetAddress().hostAddress}:${key.sourcePort} bytes=$size")
                output(reply.copyOf(size))
            } catch (_: java.net.SocketTimeoutException) {
                if (System.nanoTime() / 1_000_000 - lastActivity > 120_000) finish()
            } catch (error: Exception) {
                if (!closed.get()) log(if (isDns) LogCategory.DNS else LogCategory.UDP, null,
                    "return path failed: ${error.javaClass.simpleName}: ${error.message ?: "no detail"}")
                finish()
            }
        }
    }

    fun closeUnregistered() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    fun finish() {
        if (!closed.compareAndSet(false, true)) return
        socket.close(); onFinished(key)
    }
}

internal const val DNS_PORT = 53

/** Legacy head units may ignore IPCP DNS and keep sending to a carrier-private DNS address. */
internal fun udpUpstreamAddress(destIp: Int, destPort: Int, dnsServer: InetAddress): InetAddress =
    if (destPort == DNS_PORT) dnsServer else destIp.toInetAddress()

private fun dnsId(payload: ByteArray, length: Int = payload.size): String =
    if (length >= 2) "0x${payload.u16(0).toString(16).padStart(4, '0')}" else "missing"
