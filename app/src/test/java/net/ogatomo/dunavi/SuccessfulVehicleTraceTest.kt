package net.ogatomo.dunavi

import net.ogatomo.dunavi.net.Checksum
import net.ogatomo.dunavi.net.Ipv4Header
import net.ogatomo.dunavi.net.PROTO_UDP
import net.ogatomo.dunavi.net.UdpHeader
import net.ogatomo.dunavi.net.udpUpstreamAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.Properties

class SuccessfulVehicleTraceTest {
    @Test fun observedHarrierDnsQueryAndReplyRemainCompatible() {
        val fixture = Properties().apply {
            SuccessfulVehicleTraceTest::class.java.getResourceAsStream(
                "/testFixtures/toyota/harrier-successful-ppp.properties",
            )!!.use(::load)
        }
        val request = ipv4(decode(fixture.getProperty("rx_ppp")))
        val response = ipv4(decode(fixture.getProperty("tx_ppp")))
        val requestIp = Ipv4Header.parse(request, request.size)!!
        val responseIp = Ipv4Header.parse(response, response.size)!!
        val requestUdp = UdpHeader.parse(request, requestIp.headerLength, requestIp.payloadLength)!!
        val responseUdp = UdpHeader.parse(response, responseIp.headerLength, responseIp.payloadLength)!!

        assertEquals(PROTO_UDP, requestIp.protocol)
        assertEquals("192.168.44.2", requestIp.sourceIp.address())
        assertEquals("10.137.27.107", requestIp.destIp.address())
        assertEquals(36662, requestUdp.sourcePort)
        assertEquals(53, requestUdp.destPort)
        assertEquals("contents.t-connectapps.com", dnsName(request, requestIp.headerLength + UdpHeader.SIZE))
        assertEquals("8.8.8.8", udpUpstreamAddress(
            requestIp.destIp,
            requestUdp.destPort,
            InetAddress.getByName(fixture.getProperty("active_dns")),
        ).hostAddress)

        assertEquals(requestIp.destIp, responseIp.sourceIp)
        assertEquals(requestIp.sourceIp, responseIp.destIp)
        assertEquals(requestUdp.destPort, responseUdp.sourcePort)
        assertEquals(requestUdp.sourcePort, responseUdp.destPort)
        assertEquals(dnsId(request, requestIp, requestUdp), dnsId(response, responseIp, responseUdp))
        assertEquals(0, Checksum.compute(response, 0, responseIp.headerLength))
        assertEquals(0, Checksum.compute(
            response,
            responseIp.headerLength,
            responseUdp.length,
            Checksum.pseudoHeaderSum(responseIp.sourceIp, responseIp.destIp, PROTO_UDP, responseUdp.length),
        ))
    }

    private fun decode(hex: String): ByteArray {
        lateinit var decoded: ByteArray
        val decoder = PppDecoder { decoded = it }
        hex.trim().split(Regex("\\s+")).forEach { decoder.accept(it.toInt(16)) }
        return decoded
    }

    private fun ipv4(frame: ByteArray): ByteArray {
        assertEquals(0xff, frame[0].toInt() and 0xff)
        assertEquals(0x03, frame[1].toInt() and 0xff)
        assertEquals(0x00, frame[2].toInt() and 0xff)
        assertEquals(0x21, frame[3].toInt() and 0xff)
        return frame.copyOfRange(4, frame.size)
    }

    private fun dnsId(packet: ByteArray, ip: Ipv4Header, udp: UdpHeader): Int {
        val offset = ip.headerLength + UdpHeader.SIZE
        check(udp.payloadLength >= 2)
        return ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
    }

    private fun dnsName(packet: ByteArray, dnsOffset: Int): String {
        val labels = mutableListOf<String>()
        var offset = dnsOffset + 12
        while (packet[offset].toInt() != 0) {
            val length = packet[offset++].toInt() and 0xff
            labels += packet.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }
        return labels.joinToString(".")
    }

    private fun Int.address() = InetAddress.getByAddress(byteArrayOf(
        (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte(),
    )).hostAddress
}
