package net.ogatomo.dunavi

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

object PppCodec {
    private const val INIT_FCS = 0xffff
    private const val GOOD_FCS = 0xf0b8

    fun encode(payload: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream(payload.size + 2)
        raw.write(payload)
        val fcs = fcs(payload).inv() and 0xffff
        raw.write(fcs and 0xff)
        raw.write((fcs ushr 8) and 0xff)
        val framed = ByteArrayOutputStream(raw.size() + 2)
        framed.write(0x7e)
        raw.toByteArray().forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value == 0x7e || value == 0x7d || value < 0x20) {
                framed.write(0x7d)
                framed.write(value xor 0x20)
            } else framed.write(value)
        }
        framed.write(0x7e)
        return framed.toByteArray()
    }

    fun valid(frameWithFcs: ByteArray): Boolean = frameWithFcs.size >= 4 && fcs(frameWithFcs) == GOOD_FCS

    private fun fcs(bytes: ByteArray): Int {
        var value = INIT_FCS
        bytes.forEach { byte ->
            value = value xor (byte.toInt() and 0xff)
            repeat(8) { value = if ((value and 1) != 0) (value ushr 1) xor 0x8408 else value ushr 1 }
        }
        return value and 0xffff
    }
}

class PppDecoder(
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onRawFrame: (ByteArray) -> Unit,
) {
    constructor(onFrame: (ByteArray) -> Unit) : this(onFrame, {}, {})
    constructor(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) : this(onFrame, onError, {})
    private val frame = ByteArrayOutputStream(2048)
    private var escaped = false

    fun accept(value: Int) {
        val byte = value and 0xff
        when {
            byte == 0x7e -> {
                val data = frame.toByteArray()
                val danglingEscape = escaped
                frame.reset()
                escaped = false
                if (danglingEscape) {
                    onError("PPP malformed frame: dangling escape")
                    return
                }
                if (data.isEmpty()) return
                if (PppCodec.valid(data)) {
                    val payload = data.copyOf(data.size - 2)
                    onRawFrame(PppCodec.encode(payload))
                    onFrame(payload)
                } else onError("PPP bad FCS")
            }
            byte == 0x7d -> escaped = true
            frame.size() >= MAX_FRAME -> {
                frame.reset()
                escaped = false
                onError("PPP oversized frame")
            }
            !escaped && byte < 0x20 -> Unit // RFC 1662 default receive ACCM is all control characters.
            else -> {
                frame.write(if (escaped) byte xor 0x20 else byte)
                escaped = false
            }
        }
    }

    private companion object { const val MAX_FRAME = 4096 }
}

class PppSession(
    private val sendRaw: (ByteArray) -> Unit,
    private val onIpv4: (ByteArray) -> Unit,
    private val log: CatLog = { _, _, _ -> },
    private val rawLogEnabled: Boolean = true,
    private val onLcpOpened: () -> Unit = {},
    private val onOpened: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var nextIdentifier = 1
    private val localMagic = randomMagic()
    private var lcpRequest: PendingRequest? = null
    private var lcpLocalAcked = false
    private var lcpPeerAcked = false
    private var lcpOpened = false
    private var ipcpRequest: PendingRequest? = null
    private var ipcpLocalAcked = false
    private var ipcpPeerAcked = false
    private var peerMru = DEFAULT_MRU
    val negotiatedPeerMru get() = peerMru
    var networkOpen: Boolean = false
        private set

    fun start() {
        resetLink()
        sendLcpRequest(byteArrayOf(1, 4, 0x05, 0xdc.toByte(), 5, 6) + localMagic)
    }

    fun accept(frame: ByteArray) {
        var offset = 0
        if (frame.size >= 2 && frame[0] == 0xff.toByte() && frame[1] == 0x03.toByte()) offset = 2
        if (offset >= frame.size) return
        val first = frame[offset].toInt() and 0xff
        val protocol = if ((first and 1) != 0) first else {
            if (offset + 1 >= frame.size) return
            (first shl 8) or (frame[offset + 1].toInt() and 0xff)
        }
        offset += if ((first and 1) != 0) 1 else 2
        val payload = frame.copyOfRange(offset, frame.size)
        log(LogCategory.PPP, "RX", "protocol=${protocolName(protocol)} len=${payload.size}")
        when (protocol) {
            LCP -> handleLcp(payload)
            IPCP -> if (lcpOpened) handleIpcp(payload)
            PAP -> if (lcpOpened) handlePap(payload)
            CHAP -> log(LogCategory.AUTH, "RX", "CHAP ignored: not negotiated")
            IPV4 -> if (networkOpen) onIpv4(payload)
        }
    }

    fun sendIpv4(packet: ByteArray) {
        if (!networkOpen) {
            log(LogCategory.IP, null, "IPv4 DROP: PPP network is not open")
            return
        }
        if (packet.size > peerMru) {
            log(LogCategory.IP, null, "IPv4 DROP: ${packet.size} exceeds peer MRU $peerMru")
            return
        }
        sendProtocol(IPV4, packet)
    }

    fun tick(now: Long = clock()) {
        val pending = if (!lcpOpened && !lcpLocalAcked) lcpRequest else if (lcpOpened && !networkOpen && !ipcpLocalAcked) ipcpRequest else null
        if (pending == null || now - pending.lastSent < RESTART_MS || pending.attempts >= MAX_CONFIGURE) return
        pending.lastSent = now
        pending.attempts++
        sendControl(pending.protocol, CONFIGURE_REQUEST, pending.id, pending.data)
        log(controlCategory(pending.protocol), null, "${protocolName(pending.protocol)} Configure-Request retransmit ${pending.attempts}/$MAX_CONFIGURE")
    }

    private fun handleLcp(packet: ByteArray) {
        val control = parseControl(packet) ?: run {
            log(LogCategory.ERROR, "RX", "LCP malformed frame")
            return
        }
        log(LogCategory.LCP, "RX", controlDescription(LCP, control))
        when (control.code) {
            CONFIGURE_REQUEST -> handleLcpRequest(control)
            CONFIGURE_ACK -> {
                val request = lcpRequest ?: return
                if (control.id != request.id || !control.data.contentEquals(request.data)) {
                    log(LogCategory.LCP, null, "Configure-Ack ignored: identifier/options mismatch")
                    return
                }
                lcpLocalAcked = true
                log(LogCategory.LCP, null, "local Configure-Request ACKed")
                maybeOpenLcp()
            }
            CONFIGURE_NAK -> renegotiateLcp(control, rejected = false)
            CONFIGURE_REJECT -> renegotiateLcp(control, rejected = true)
            ECHO_REQUEST -> {
                val reply = if (control.data.size >= 4) localMagic + control.data.copyOfRange(4, control.data.size) else localMagic
                sendControl(LCP, ECHO_REPLY, control.id, reply)
            }
            TERMINATE_REQUEST -> {
                networkOpen = false
                lcpOpened = false
                lcpLocalAcked = false
                lcpPeerAcked = false
                sendControl(LCP, TERMINATE_ACK, control.id, byteArrayOf())
                log(LogCategory.LCP, null, "TERMINATED")
            }
        }
    }

    private fun handleLcpRequest(control: Control) {
        val options = parseOptions(control.data) ?: return
        lcpPeerAcked = false
        val rejected = ByteArrayOutputStream()
        val nak = ByteArrayOutputStream()
        var acceptedMru = peerMru
        options.forEach { option ->
            when (option.type) {
                1 -> if (option.raw.size == 4) {
                    val value = option.raw.u16(2)
                    if (value >= MIN_MRU) acceptedMru = value else nak.write(byteArrayOf(1, 4, (MIN_MRU ushr 8).toByte(), MIN_MRU.toByte()))
                } else nak.write(byteArrayOf(1, 4, 0x05, 0xdc.toByte()))
                2 -> if (option.raw.size != 6) nak.write(byteArrayOf(2, 6, 0, 0, 0, 0))
                3, 4 -> rejected.write(option.raw)
                5 -> if (option.raw.size != 6) nak.write(byteArrayOf(5, 6) + randomMagic())
                7, 8 -> if (option.raw.size != 2) rejected.write(option.raw)
                else -> rejected.write(option.raw)
            }
        }
        when {
            rejected.size() > 0 -> {
                log(LogCategory.LCP, null, "peer options rejected: ${describeLcp(parseOptions(rejected.toByteArray()).orEmpty())}")
                sendControl(LCP, CONFIGURE_REJECT, control.id, rejected.toByteArray())
            }
            nak.size() > 0 -> {
                log(LogCategory.LCP, null, "peer options NAKed: ${describeLcp(parseOptions(nak.toByteArray()).orEmpty())}")
                sendControl(LCP, CONFIGURE_NAK, control.id, nak.toByteArray())
            }
            else -> {
                peerMru = acceptedMru
                lcpPeerAcked = true
                sendControl(LCP, CONFIGURE_ACK, control.id, control.data)
                log(LogCategory.LCP, null, "peer ACK sent: ${describeLcp(options)}")
                maybeOpenLcp()
            }
        }
    }

    private fun renegotiateLcp(control: Control, rejected: Boolean) {
        val request = lcpRequest ?: return
        if (control.id != request.id) return
        val response = parseOptions(control.data) ?: return
        val options = parseOptions(request.data)?.toMutableList() ?: return
        if (rejected) {
            if (response.any { rejectedOption -> options.none { it.raw.contentEquals(rejectedOption.raw) } }) return
            options.removeAll { option -> response.any { option.raw.contentEquals(it.raw) } }
        } else {
            response.forEach { replacement ->
                val index = options.indexOfFirst { it.type == replacement.type }
                if (index >= 0) options[index] = replacement
            }
        }
        lcpLocalAcked = false
        sendLcpRequest(options.flatMap { it.raw.asIterable() }.toByteArray())
        log(LogCategory.LCP, null, "${if (rejected) "Reject" else "Nak"} received; renegotiating")
    }

    private fun maybeOpenLcp() {
        if (!lcpLocalAcked || !lcpPeerAcked || lcpOpened) return
        lcpOpened = true
        log(LogCategory.LCP, null, "OPENED")
        onLcpOpened()
        startIpcp()
    }

    private fun startIpcp() {
        ipcpLocalAcked = false
        ipcpPeerAcked = false
        sendIpcpRequest(byteArrayOf(3, 6) + SERVER_IP)
        log(LogCategory.IPCP, null, "START")
    }

    private fun handleIpcp(packet: ByteArray) {
        val control = parseControl(packet) ?: run {
            log(LogCategory.ERROR, "RX", "IPCP malformed frame")
            return
        }
        log(LogCategory.IPCP, "RX", controlDescription(IPCP, control))
        when (control.code) {
            CONFIGURE_REQUEST -> handleIpcpRequest(control)
            CONFIGURE_ACK -> {
                val request = ipcpRequest ?: return
                if (control.id != request.id || !control.data.contentEquals(request.data)) {
                    log(LogCategory.IPCP, null, "Configure-Ack ignored: identifier/options mismatch")
                    return
                }
                ipcpLocalAcked = true
                log(LogCategory.IPCP, null, "local Configure-Request ACKed")
                maybeOpenNetwork()
            }
            CONFIGURE_NAK -> renegotiateIpcp(control, rejected = false)
            CONFIGURE_REJECT -> renegotiateIpcp(control, rejected = true)
            TERMINATE_REQUEST -> {
                networkOpen = false
                ipcpLocalAcked = false
                ipcpPeerAcked = false
                sendControl(IPCP, TERMINATE_ACK, control.id, byteArrayOf())
                log(LogCategory.IPCP, null, "TERMINATED")
            }
        }
    }

    private fun handleIpcpRequest(control: Control) {
        val options = parseOptions(control.data) ?: return
        ipcpPeerAcked = false
        networkOpen = false
        val rejected = ByteArrayOutputStream()
        val nak = ByteArrayOutputStream()
        var hasAddress = false
        options.forEach { option ->
            val expected = when (option.type) {
                3 -> CLIENT_IP.also { hasAddress = true }
                129, 131 -> SERVER_IP
                else -> null
            }
            when {
                expected == null || option.raw.size != 6 -> rejected.write(option.raw)
                !option.raw.copyOfRange(2, 6).contentEquals(expected) -> {
                    nak.write(option.type)
                    nak.write(6)
                    nak.write(expected)
                }
            }
        }
        if (!hasAddress && rejected.size() == 0) nak.write(byteArrayOf(3, 6) + CLIENT_IP)
        when {
            rejected.size() > 0 -> {
                log(LogCategory.IPCP, null, "peer options rejected: ${describeIpcp(parseOptions(rejected.toByteArray()).orEmpty())}")
                sendControl(IPCP, CONFIGURE_REJECT, control.id, rejected.toByteArray())
            }
            nak.size() > 0 -> {
                log(LogCategory.IPCP, null, "peer options NAKed: ${describeIpcp(parseOptions(nak.toByteArray()).orEmpty())}")
                sendControl(IPCP, CONFIGURE_NAK, control.id, nak.toByteArray())
            }
            else -> {
                ipcpPeerAcked = true
                sendControl(IPCP, CONFIGURE_ACK, control.id, control.data)
                log(LogCategory.IPCP, null, "peer ACK sent: ${describeIpcp(options)}")
                maybeOpenNetwork()
            }
        }
    }

    private fun renegotiateIpcp(control: Control, rejected: Boolean) {
        val request = ipcpRequest ?: return
        if (control.id != request.id) return
        val response = parseOptions(control.data) ?: return
        val options = parseOptions(request.data)?.toMutableList() ?: return
        if (rejected) {
            if (response.any { rejectedOption -> options.none { it.raw.contentEquals(rejectedOption.raw) } }) return
            options.removeAll { option -> response.any { option.raw.contentEquals(it.raw) } }
        } else {
            response.forEach { replacement ->
                val index = options.indexOfFirst { it.type == replacement.type }
                if (index >= 0 && replacement.raw.size == 6) options[index] = replacement
            }
        }
        ipcpLocalAcked = false
        sendIpcpRequest(options.flatMap { it.raw.asIterable() }.toByteArray())
        log(LogCategory.IPCP, null, "${if (rejected) "Reject" else "Nak"} received; renegotiating")
    }

    private fun maybeOpenNetwork() {
        if (!ipcpLocalAcked || !ipcpPeerAcked || networkOpen) return
        networkOpen = true
        log(LogCategory.IPCP, null, "NETWORK OPEN: local=192.168.44.1 peer=192.168.44.2 DNS=192.168.44.1 MRU=$peerMru")
        onOpened()
    }

    private fun handlePap(packet: ByteArray) {
        val control = parseControl(packet) ?: return
        if (control.code != 1 || !validPapRequest(control.data)) return
        // Legacy tolerance: some diallers send PAP even when LCP authentication was not negotiated.
        sendControl(PAP, 2, control.id, byteArrayOf(2, 'O'.code.toByte(), 'K'.code.toByte()))
        log(LogCategory.AUTH, null, "PAP accepted (legacy compatibility; credentials not logged)")
    }

    private fun validPapRequest(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val userLength = data[0].toInt() and 0xff
        val passwordLengthOffset = 1 + userLength
        if (passwordLengthOffset >= data.size) return false
        val passwordLength = data[passwordLengthOffset].toInt() and 0xff
        return passwordLengthOffset + 1 + passwordLength == data.size
    }

    private fun sendLcpRequest(data: ByteArray) {
        lcpRequest = PendingRequest(LCP, nextId(), data, clock(), 1)
        lcpRequest!!.also { sendControl(it.protocol, CONFIGURE_REQUEST, it.id, it.data) }
    }

    private fun sendIpcpRequest(data: ByteArray) {
        ipcpRequest = PendingRequest(IPCP, nextId(), data, clock(), 1)
        ipcpRequest!!.also { sendControl(it.protocol, CONFIGURE_REQUEST, it.id, it.data) }
    }

    private fun sendControl(protocol: Int, code: Int, identifier: Int, data: ByteArray) {
        val length = data.size + 4
        log(controlCategory(protocol), "TX", controlDescription(protocol, Control(code, identifier, data)))
        sendProtocol(protocol, byteArrayOf(code.toByte(), identifier.toByte(), (length ushr 8).toByte(), length.toByte()) + data)
    }

    private fun controlDescription(protocol: Int, control: Control): String {
        val options = when (protocol) {
            LCP -> " options=${describeLcp(parseOptions(control.data).orEmpty())}"
            IPCP -> " options=${describeIpcp(parseOptions(control.data).orEmpty())}"
            PAP, CHAP -> " length=${control.data.size}"
            else -> ""
        }
        return "${protocolName(protocol)} ${codeName(control.code)} id=${control.id}$options"
    }

    private fun sendProtocol(protocol: Int, payload: ByteArray) {
        val framed = PppCodec.encode(byteArrayOf(0xff.toByte(), 0x03, (protocol ushr 8).toByte(), protocol.toByte()) + payload)
        sendRaw(framed)
        log(LogCategory.PPP, "TX", "protocol=${protocolName(protocol)} len=${payload.size} RFCOMM_write=${framed.size} bytes")
        if (rawLogEnabled) log(LogCategory.PPP, "TX RAW", framed.toHex())
    }

    private fun parseControl(packet: ByteArray): Control? {
        if (packet.size < 4) return null
        val length = packet.u16(2)
        if (length !in 4..packet.size) return null
        return Control(packet.u8(0), packet.u8(1), packet.copyOfRange(4, length))
    }

    private fun parseOptions(data: ByteArray): List<Option>? {
        val options = mutableListOf<Option>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 2 > data.size) return null
            val length = data.u8(offset + 1)
            if (length < 2 || offset + length > data.size) return null
            options += Option(data.u8(offset), data.copyOfRange(offset, offset + length))
            offset += length
        }
        return options
    }

    private fun resetLink() {
        lcpLocalAcked = false
        lcpPeerAcked = false
        lcpOpened = false
        ipcpLocalAcked = false
        ipcpPeerAcked = false
        networkOpen = false
        peerMru = DEFAULT_MRU
        ipcpRequest = null
    }

    private fun describeLcp(options: List<Option>) = options.joinToString(", ") { option ->
        when (option.type) {
            1 -> "MRU=${option.raw.u16(2)}"
            2 -> "ACCM"
            5 -> "Magic"
            7 -> "PFC"
            8 -> "ACFC"
            else -> "type=${option.type}"
        }
    }.ifEmpty { "defaults" }

    private fun describeIpcp(options: List<Option>) = options.joinToString(", ") { option ->
        when (option.type) {
            3 -> "IP=${option.raw.ipv4(2)}"
            129 -> "DNS1=${option.raw.ipv4(2)}"
            131 -> "DNS2=${option.raw.ipv4(2)}"
            else -> "type=${option.type}"
        }
    }.ifEmpty { "defaults" }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xff
    private fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
    private fun ByteArray.ipv4(index: Int) = "${u8(index)}.${u8(index + 1)}.${u8(index + 2)}.${u8(index + 3)}"
    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
    private fun nextId() = (nextIdentifier++ and 0xff)
    private fun randomMagic() = ByteArray(4).also { RANDOM.nextBytes(it) }
    private fun controlCategory(protocol: Int) = when (protocol) {
        LCP -> LogCategory.LCP
        IPCP -> LogCategory.IPCP
        PAP, CHAP -> LogCategory.AUTH
        else -> LogCategory.PPP
    }

    private fun protocolName(protocol: Int) = when (protocol) { LCP -> "LCP"; IPCP -> "IPCP"; PAP -> "PAP"; CHAP -> "CHAP"; IPV4 -> "IPv4"; else -> "0x${protocol.toString(16)}" }
    private fun codeName(code: Int) = when (code) { CONFIGURE_REQUEST -> "Configure-Request"; CONFIGURE_ACK -> "Configure-Ack"; CONFIGURE_NAK -> "Configure-Nak"; CONFIGURE_REJECT -> "Configure-Reject"; TERMINATE_REQUEST -> "Terminate-Request"; TERMINATE_ACK -> "Terminate-Ack"; ECHO_REQUEST -> "Echo-Request"; ECHO_REPLY -> "Echo-Reply"; else -> "code=$code" }

    private data class Control(val code: Int, val id: Int, val data: ByteArray)
    private data class Option(val type: Int, val raw: ByteArray)
    private data class PendingRequest(val protocol: Int, val id: Int, val data: ByteArray, var lastSent: Long, var attempts: Int)

    companion object {
        const val LCP = 0xc021
        const val PAP = 0xc023
        const val CHAP = 0xc223
        const val IPCP = 0x8021
        const val IPV4 = 0x0021
        const val CONFIGURE_REQUEST = 1
        const val CONFIGURE_ACK = 2
        const val CONFIGURE_NAK = 3
        const val CONFIGURE_REJECT = 4
        const val TERMINATE_REQUEST = 5
        const val TERMINATE_ACK = 6
        const val ECHO_REQUEST = 9
        const val ECHO_REPLY = 10
        val SERVER_IP = byteArrayOf(192.toByte(), 168.toByte(), 44, 1)
        val CLIENT_IP = byteArrayOf(192.toByte(), 168.toByte(), 44, 2)
        private const val DEFAULT_MRU = 1500
        private const val MIN_MRU = 128
        private const val RESTART_MS = 3_000
        private const val MAX_CONFIGURE = 10
        private val RANDOM = SecureRandom()
    }
}
