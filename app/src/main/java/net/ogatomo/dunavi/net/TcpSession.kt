/*
 * Adapted from FreeProxy, Copyright xVanTuring, Apache License 2.0.
 * See THIRD_PARTY_NOTICES.md.
 */
package net.ogatomo.dunavi.net

import net.ogatomo.dunavi.CatLog
import net.ogatomo.dunavi.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

internal class TcpSendQueue(initialSequence: Long) {
    internal data class Segment(var sequence: Long, val flags: Int, var data: ByteArray, var sentAt: Long)

    private val segments = ArrayDeque<Segment>()
    var sendUnacked: Long = initialSequence and 0xffff_ffffL
        private set
    var sendNext: Long = sendUnacked
        private set
    var peerWindow: Int = 65535
        private set
    private var zeroWindowSince = 0L
    private var lastZeroWindowProbe = 0L
    val hasOutstandingSyn get() = segments.any { it.flags and TcpHeader.SYN != 0 }
    val hasOutstandingFin get() = segments.any { it.flags and TcpHeader.FIN != 0 }

    fun sendCapacity(maxBytes: Int): Int {
        val inFlight = seqDistance(sendUnacked, sendNext)
        return min(maxBytes, (peerWindow - inFlight).coerceAtLeast(0))
    }

    fun updatePeerWindow(window: Int, now: Long) {
        if (window == 0 && peerWindow != 0) zeroWindowSince = now
        if (window != 0) {
            zeroWindowSince = 0
            lastZeroWindowProbe = 0
        }
        peerWindow = window.coerceIn(0, 65535)
    }

    fun zeroWindowProbeDue(now: Long, timeout: Long): Boolean {
        if (peerWindow != 0 || zeroWindowSince == 0L || now - zeroWindowSince < timeout || now - lastZeroWindowProbe < timeout) return false
        lastZeroWindowProbe = now
        return true
    }

    fun enqueue(flags: Int, data: ByteArray, now: Long): Segment {
        val segment = Segment(sendNext, flags, data, now)
        val sequenceLength = data.size + (if (flags and TcpHeader.SYN != 0) 1 else 0) + (if (flags and TcpHeader.FIN != 0) 1 else 0)
        require(sequenceLength > 0)
        segments.addLast(segment)
        sendNext = seqAdvance(sendNext, sequenceLength)
        return segment
    }

    fun acknowledge(acknowledgment: Long): Boolean {
        val ack = acknowledgment and 0xffff_ffffL
        if (!seqLessThan(sendUnacked, ack) || !seqLessOrEqual(ack, sendNext)) return false
        sendUnacked = ack
        while (true) {
            val first = segments.firstOrNull() ?: break
            val length = first.data.size + (if (first.flags and TcpHeader.SYN != 0) 1 else 0) + (if (first.flags and TcpHeader.FIN != 0) 1 else 0)
            val end = seqAdvance(first.sequence, length)
            if (seqLessOrEqual(end, ack)) {
                segments.removeFirst()
                continue
            }
            if (seqLessThan(first.sequence, ack) && first.data.isNotEmpty() && first.flags and (TcpHeader.SYN or TcpHeader.FIN) == 0) {
                val consumed = seqDistance(first.sequence, ack).coerceAtMost(first.data.size)
                first.sequence = ack
                first.data = first.data.copyOfRange(consumed, first.data.size)
            }
            break
        }
        return true
    }

    fun retransmitDue(now: Long, timeout: Long): Segment? {
        val first = segments.firstOrNull() ?: return null
        if (now - first.sentAt < timeout) return null
        first.sentAt = now
        return first
    }

    fun firstOutstanding(): Segment? = segments.firstOrNull()

    private fun seqDistance(from: Long, to: Long) = ((to - from) and 0xffff_ffffL).toInt()
}

class TcpSession(
    val key: SessionKey,
    private val scope: CoroutineScope,
    private val output: (ByteArray) -> Unit,
    private val onTraffic: (Long, Long) -> Unit,
    private val linkMtu: () -> Int = { 1500 },
    private val log: CatLog = { _, _, _ -> },
    private val onFinished: (SessionKey) -> Unit,
) {
    private enum class State { CONNECTING, SYN_RECEIVED, ESTABLISHED, CLOSED }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val closed = AtomicBoolean(false)
    private val upstream = Channel<ByteArray>(64)
    private var state = State.CONNECTING
    private var socket: Socket? = null
    private var job: Job? = null
    @Volatile var lastActivity = now()
        private set
    private val initialSequence = Random.nextLong(0, 0xffff_ffffL)
    private val sendQueue = TcpSendQueue(initialSequence)
    private var finSent = false
    private var receiveNext = 0L
    private var pendingUpstreamBytes = 0
    private var upstreamClosed = false

    fun open(syn: TcpHeader) {
        synchronized(lock) {
            receiveNext = seqAdvance(syn.sequence, 1)
            sendQueue.updatePeerWindow(syn.window, now())
        }
        job = scope.launch(Dispatchers.IO) {
            val connected = try {
                Socket().apply { connect(InetSocketAddress(key.destIp.toInetAddress(), key.destPort), 15_000); tcpNoDelay = true }
            } catch (_: Exception) {
                log(LogCategory.ERROR, null, "TCP ${key.label()} CONNECT FAILED")
                sendReset()
                finish()
                return@launch
            }
            val accepted = synchronized(lock) {
                if (state != State.CONNECTING) false else {
                    socket = connected
                    state = State.SYN_RECEIVED
                    sendSynAck()
                    true
                }
            }
            if (!accepted) {
                connected.close()
                return@launch
            }
            launch { retransmitLoop() }
            val established = synchronized(lock) {
                while (state == State.SYN_RECEIVED) lock.wait(100)
                state == State.ESTABLISHED
            }
            if (!established) return@launch
            launch { pumpUpstream(connected) }
            pumpDownstream(connected)
        }
    }

    fun onPacket(header: TcpHeader, buffer: ByteArray, payloadOffset: Int, payloadLength: Int) {
        lastActivity = now()
        if (header.isRst) {
            log(LogCategory.TCP, "RX", "${key.label()} RST")
            finish()
            return
        }
        var finishAfterAck = false
        var becameEstablished = false
        synchronized(lock) {
            sendQueue.updatePeerWindow(header.window, now())
            if (header.isAck) {
                val accepted = sendQueue.acknowledge(header.acknowledgment)
                if (accepted && state == State.SYN_RECEIVED && !sendQueue.hasOutstandingSyn) {
                    state = State.ESTABLISHED
                    becameEstablished = true
                }
                finishAfterAck = upstreamClosed && finSent && !sendQueue.hasOutstandingFin
            }
            lock.notifyAll()
        }
        if (becameEstablished) log(LogCategory.TCP, null, "${key.label()} ESTABLISHED")
        if (finishAfterAck) {
            finish()
            return
        }
        if (header.isSyn) {
            synchronized(lock) { if (state == State.SYN_RECEIVED || state == State.ESTABLISHED) sendOutstandingSyn() }
            return
        }
        if (state == State.CLOSED) {
            sendReset()
            return
        }
        val accepted = if (payloadLength > 0) acceptData(header.sequence, buffer, payloadOffset, payloadLength) else 0
        if (header.isFin) acceptFin(seqAdvance(header.sequence, accepted))
    }

    private fun acceptData(sequence: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val chunk = synchronized(lock) {
            if (sequence != receiveNext) {
                sendAck()
                return 0
            }
            buffer.copyOfRange(offset, offset + length)
        }
        if (!upstream.trySend(chunk).isSuccess) {
            synchronized(lock) { sendAck() }
            return 0
        }
        synchronized(lock) {
            receiveNext = seqAdvance(receiveNext, length)
            pendingUpstreamBytes += length
            sendAck()
        }
        return length
    }

    private fun acceptFin(sequence: Long) {
        var finishAfterFin = false
        synchronized(lock) {
            if (upstreamClosed || sequence != receiveNext) {
                sendAck()
                return
            }
            receiveNext = seqAdvance(receiveNext, 1)
            upstreamClosed = true
            sendAck()
            finishAfterFin = finSent && !sendQueue.hasOutstandingFin
        }
        log(LogCategory.TCP, "RX", "${key.label()} peer FIN")
        upstream.close()
        if (finishAfterFin) finish()
    }

    private suspend fun pumpUpstream(socket: Socket) {
        try {
            val stream = socket.getOutputStream()
            for (chunk in upstream) {
                stream.write(chunk)
                stream.flush()
                onTraffic(chunk.size.toLong(), 0)
                synchronized(lock) {
                    val before = advertisedWindow()
                    pendingUpstreamBytes = max(0, pendingUpstreamBytes - chunk.size)
                    if (state == State.ESTABLISHED && before < currentMss() && advertisedWindow() >= currentMss()) sendAck()
                }
            }
            runCatching { socket.shutdownOutput() }
        } catch (_: Exception) {
            finish()
        }
    }

    private fun pumpDownstream(socket: Socket) {
        try {
            val buffer = ByteArray(currentMss())
            val stream = socket.getInputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                onTraffic(0, read.toLong())
                sendData(buffer, read)
            }
            sendFin()
        } catch (_: Exception) {
            if (state == State.ESTABLISHED) sendReset()
            finish()
        }
    }

    private fun sendData(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val capacity = awaitSendCapacity(min(currentMss(), length - offset), data[offset])
            if (capacity <= 0) throw IOException("closed")
            synchronized(lock) {
                if (state != State.ESTABLISHED) throw IOException("closed")
                val segment = sendQueue.enqueue(TcpHeader.ACK or TcpHeader.PSH, data.copyOfRange(offset, offset + capacity), now())
                sendSegment(segment)
            }
            offset += capacity
        }
    }

    private fun awaitSendCapacity(maxBytes: Int, probeByte: Byte): Int = synchronized(lock) {
        while (state == State.ESTABLISHED) {
            val available = sendQueue.sendCapacity(maxBytes)
            if (available > 0) return@synchronized available
            if (sendQueue.zeroWindowProbeDue(now(), RETRANSMIT_MS)) {
                sendTcp(sendQueue.sendNext, TcpHeader.ACK or TcpHeader.PSH, byteArrayOf(probeByte), dataLength = 1)
            }
            lock.wait(100)
        }
        0
    }

    private suspend fun retransmitLoop() {
        while (scope.isActive && state != State.CLOSED) {
            delay(100)
            synchronized(lock) { sendQueue.retransmitDue(now(), RETRANSMIT_MS)?.let(::sendSegment) }
        }
    }

    private fun sendSynAck() {
        val segment = sendQueue.enqueue(TcpHeader.SYN or TcpHeader.ACK, byteArrayOf(), now())
        sendSegment(segment)
    }

    private fun sendOutstandingSyn() {
        val syn = sendQueue.firstOutstanding()?.takeIf { it.flags and TcpHeader.SYN != 0 }
        if (syn != null) sendSegment(syn)
        else sendTcp(initialSequence, TcpHeader.SYN or TcpHeader.ACK, mss = currentMss())
    }

    private fun sendAck() = sendTcp(sendQueue.sendNext, TcpHeader.ACK)

    private fun sendFin() = synchronized(lock) {
        if (finSent || state != State.ESTABLISHED) return@synchronized
        finSent = true
        val segment = sendQueue.enqueue(TcpHeader.FIN or TcpHeader.ACK, byteArrayOf(), now())
        sendSegment(segment)
    }

    private fun sendReset() = synchronized(lock) { sendTcp(sendQueue.sendNext, TcpHeader.RST or TcpHeader.ACK, window = 0) }

    private fun sendSegment(segment: TcpSendQueue.Segment) {
        sendTcp(segment.sequence, segment.flags, segment.data, 0, segment.data.size, mss = if (segment.flags and TcpHeader.SYN != 0) currentMss() else 0)
    }

    private fun sendTcp(
        sequence: Long,
        flags: Int,
        data: ByteArray? = null,
        dataOffset: Int = 0,
        dataLength: Int = 0,
        window: Int = advertisedWindow(),
        mss: Int = 0,
    ) {
        val buffer = ByteArray(44 + dataLength)
        val size = PacketBuilder.writeTcp(
            buffer, key.destIp, key.destPort, key.sourceIp, key.sourcePort,
            sequence, receiveNext, flags, window, data, dataOffset, dataLength, mss,
        )
        log(
            LogCategory.TCP,
            "TX",
            "${endpoint(key.destIp, key.destPort)} -> ${endpoint(key.sourceIp, key.sourcePort)} " +
                "flags=${flags.description()} bytes=$dataLength",
        )
        output(buffer.copyOf(size))
    }

    private fun advertisedWindow() = (65535 - pendingUpstreamBytes).coerceIn(0, 65535)
    private fun currentMss() = (linkMtu() - 40).coerceIn(MIN_MSS, MAX_MSS)

    fun finish() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            state = State.CLOSED
            lock.notifyAll()
        }
        upstream.close()
        runCatching { socket?.close() }
        job?.cancel()
        onFinished(key)
    }

    private fun SessionKey.label() = "${destIp.toInetAddress().hostAddress}:$destPort"
    private fun endpoint(ip: Int, port: Int) = "${ip.toInetAddress().hostAddress}:$port"
    private fun Int.description() = buildList {
        if (this@description and TcpHeader.SYN != 0) add("SYN")
        if (this@description and TcpHeader.ACK != 0) add("ACK")
        if (this@description and TcpHeader.FIN != 0) add("FIN")
        if (this@description and TcpHeader.RST != 0) add("RST")
        if (this@description and TcpHeader.PSH != 0) add("PSH")
    }.ifEmpty { listOf("NONE") }.joinToString("|")

    private companion object {
        const val MIN_MSS = 1
        const val MAX_MSS = 1460
        const val RETRANSMIT_MS = 800L
        fun now() = System.nanoTime() / 1_000_000
    }
}
