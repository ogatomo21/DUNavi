package net.ogatomo.dunavi

import net.ogatomo.dunavi.net.Ipv4Header
import net.ogatomo.dunavi.net.PacketBuilder
import net.ogatomo.dunavi.net.PROTO_UDP
import net.ogatomo.dunavi.net.UdpHeader
import net.ogatomo.dunavi.net.Checksum
import net.ogatomo.dunavi.net.udpUpstreamAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class PacketTest {
    @Test fun udpPacketBuildsAndParses() {
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00)
        val packet = ByteArray(28 + payload.size)
        val sourceIp = 0x0a891b6b
        val destIp = 0xc0a82c02.toInt()
        val size = PacketBuilder.writeUdp(packet, sourceIp, 53, destIp, 40000, payload, 0, payload.size)
        val ip = Ipv4Header.parse(packet, size)!!
        val udp = UdpHeader.parse(packet, ip.headerLength, ip.payloadLength)!!
        assertEquals(PROTO_UDP, ip.protocol)
        assertEquals(sourceIp, ip.sourceIp)
        assertEquals(destIp, ip.destIp)
        assertEquals(53, udp.sourcePort)
        assertEquals(40000, udp.destPort)
        assertArrayEquals(payload, packet.copyOfRange(28, size))
        assertEquals(0, Checksum.compute(packet, 0, ip.headerLength))
        assertEquals(0, Checksum.compute(packet, ip.headerLength, udp.length,
            Checksum.pseudoHeaderSum(sourceIp, destIp, PROTO_UDP, udp.length)))
    }

    @Test fun carrierPrivateDnsIsRedirectedToActiveNetworkDns() {
        val activeDns = InetAddress.getByName("10.0.1.1")
        assertEquals(activeDns, udpUpstreamAddress(0x0a891b6b, 53, activeDns))
        assertEquals("10.137.27.107", udpUpstreamAddress(0x0a891b6b, 443, activeDns).hostAddress)
    }
}
