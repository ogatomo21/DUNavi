/*
 * Adapted from FreeProxy, Copyright xVanTuring, Apache License 2.0.
 * See THIRD_PARTY_NOTICES.md.
 */
package net.ogatomo.dunavi.net

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

const val PROTO_TCP = 6
const val PROTO_UDP = 17

internal fun ByteArray.u8(index: Int) = this[index].toInt() and 0xff
internal fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
internal fun ByteArray.u32(index: Int) = (u16(index).toLong() shl 16) or u16(index + 2).toLong()
internal fun ByteArray.ipv4(index: Int) =
    (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)
internal fun ByteArray.putU8(index: Int, value: Int) { this[index] = value.toByte() }
internal fun ByteArray.putU16(index: Int, value: Int) {
    this[index] = (value ushr 8).toByte()
    this[index + 1] = value.toByte()
}
internal fun ByteArray.putU32(index: Int, value: Long) {
    this[index] = (value ushr 24).toByte()
    this[index + 1] = (value ushr 16).toByte()
    this[index + 2] = (value ushr 8).toByte()
    this[index + 3] = value.toByte()
}
internal fun ByteArray.putIpv4(index: Int, value: Int) = putU32(index, value.toLong() and 0xffffffffL)
internal fun Int.toInetAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(
    (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte(),
))

data class SessionKey(val sourceIp: Int, val sourcePort: Int, val destIp: Int, val destPort: Int)
internal fun seqLessThan(a: Long, b: Long) = (a - b).toInt() < 0
internal fun seqLessOrEqual(a: Long, b: Long) = (a - b).toInt() <= 0
internal fun seqAdvance(seq: Long, delta: Int) = (seq + delta) and 0xffffffffL

class Ipv4Header(
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceIp: Int,
    val destIp: Int,
) {
    val payloadLength get() = totalLength - headerLength

    companion object {
        const val MIN_SIZE = 20
        fun parse(buffer: ByteArray, length: Int): Ipv4Header? {
            if (length < MIN_SIZE || buffer.u8(0) ushr 4 != 4) return null
            val headerLength = (buffer.u8(0) and 0x0f) * 4
            val totalLength = buffer.u16(2)
            if (headerLength < MIN_SIZE || headerLength > length || totalLength !in headerLength..length) return null
            val fragment = buffer.u16(6)
            if ((fragment and 0x3fff) != 0) return null
            return Ipv4Header(headerLength, totalLength, buffer.u8(9), buffer.ipv4(12), buffer.ipv4(16))
        }
    }
}

class TcpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val sequence: Long,
    val acknowledgment: Long,
    val dataOffset: Int,
    val flags: Int,
    val window: Int,
) {
    val isFin get() = flags and FIN != 0
    val isSyn get() = flags and SYN != 0
    val isRst get() = flags and RST != 0
    val isAck get() = flags and ACK != 0

    companion object {
        const val MIN_SIZE = 20
        const val FIN = 1
        const val SYN = 2
        const val RST = 4
        const val PSH = 8
        const val ACK = 16
        fun parse(buffer: ByteArray, offset: Int, length: Int): TcpHeader? {
            if (length < MIN_SIZE) return null
            val dataOffset = (buffer.u8(offset + 12) ushr 4) * 4
            if (dataOffset !in MIN_SIZE..length) return null
            return TcpHeader(
                buffer.u16(offset), buffer.u16(offset + 2), buffer.u32(offset + 4), buffer.u32(offset + 8),
                dataOffset, buffer.u8(offset + 13), buffer.u16(offset + 14),
            )
        }
    }
}

class UdpHeader(val sourcePort: Int, val destPort: Int, val length: Int) {
    val payloadLength get() = length - SIZE
    companion object {
        const val SIZE = 8
        fun parse(buffer: ByteArray, offset: Int, available: Int): UdpHeader? {
            if (available < SIZE) return null
            val length = buffer.u16(offset + 4)
            if (length !in SIZE..available) return null
            return UdpHeader(buffer.u16(offset), buffer.u16(offset + 2), length)
        }
    }
}

object Checksum {
    fun compute(data: ByteArray, offset: Int, length: Int, initial: Long = 0): Int {
        var sum = initial
        var index = offset
        val end = offset + length
        while (index + 1 < end) { sum += data.u16(index); index += 2 }
        if (index < end) sum += data.u8(index) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    fun pseudoHeaderSum(sourceIp: Int, destIp: Int, protocol: Int, length: Int): Long =
        ((sourceIp ushr 16) and 0xffff).toLong() + (sourceIp and 0xffff).toLong() +
            ((destIp ushr 16) and 0xffff).toLong() + (destIp and 0xffff).toLong() + protocol + length
}

object PacketBuilder {
    private val identification = AtomicInteger(1)

    fun writeTcp(
        output: ByteArray, sourceIp: Int, sourcePort: Int, destIp: Int, destPort: Int,
        sequence: Long, acknowledgment: Long, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOffset: Int = 0, payloadLength: Int = 0, mss: Int = 0,
    ): Int {
        val options = if (mss > 0) 4 else 0
        val tcpLength = TcpHeader.MIN_SIZE + options + payloadLength
        val totalLength = Ipv4Header.MIN_SIZE + tcpLength
        writeIp(output, totalLength, PROTO_TCP, sourceIp, destIp)
        val tcp = Ipv4Header.MIN_SIZE
        output.putU16(tcp, sourcePort); output.putU16(tcp + 2, destPort)
        output.putU32(tcp + 4, sequence); output.putU32(tcp + 8, acknowledgment)
        output.putU8(tcp + 12, ((TcpHeader.MIN_SIZE + options) / 4) shl 4)
        output.putU8(tcp + 13, flags); output.putU16(tcp + 14, window)
        output.putU16(tcp + 16, 0); output.putU16(tcp + 18, 0)
        if (mss > 0) { output.putU8(tcp + 20, 2); output.putU8(tcp + 21, 4); output.putU16(tcp + 22, mss) }
        if (payload != null && payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, output, tcp + TcpHeader.MIN_SIZE + options, payloadLength)
        }
        output.putU16(tcp + 16, Checksum.compute(output, tcp, tcpLength, Checksum.pseudoHeaderSum(sourceIp, destIp, PROTO_TCP, tcpLength)))
        return totalLength
    }

    fun writeUdp(
        output: ByteArray, sourceIp: Int, sourcePort: Int, destIp: Int, destPort: Int,
        payload: ByteArray, payloadOffset: Int, payloadLength: Int,
    ): Int {
        val udpLength = UdpHeader.SIZE + payloadLength
        val totalLength = Ipv4Header.MIN_SIZE + udpLength
        writeIp(output, totalLength, PROTO_UDP, sourceIp, destIp)
        val udp = Ipv4Header.MIN_SIZE
        output.putU16(udp, sourcePort); output.putU16(udp + 2, destPort)
        output.putU16(udp + 4, udpLength); output.putU16(udp + 6, 0)
        System.arraycopy(payload, payloadOffset, output, udp + UdpHeader.SIZE, payloadLength)
        val checksum = Checksum.compute(output, udp, udpLength, Checksum.pseudoHeaderSum(sourceIp, destIp, PROTO_UDP, udpLength))
        output.putU16(udp + 6, if (checksum == 0) 0xffff else checksum)
        return totalLength
    }

    private fun writeIp(output: ByteArray, totalLength: Int, protocol: Int, sourceIp: Int, destIp: Int) {
        output.putU8(0, 0x45); output.putU8(1, 0); output.putU16(2, totalLength)
        output.putU16(4, identification.getAndIncrement()); output.putU16(6, 0x4000)
        output.putU8(8, 64); output.putU8(9, protocol); output.putU16(10, 0)
        output.putIpv4(12, sourceIp); output.putIpv4(16, destIp)
        output.putU16(10, Checksum.compute(output, 0, Ipv4Header.MIN_SIZE))
    }
}
