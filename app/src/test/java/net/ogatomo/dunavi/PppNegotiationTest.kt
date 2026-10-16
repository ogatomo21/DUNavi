package net.ogatomo.dunavi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PppNegotiationTest {
    @Test fun peerFirstLcpWaitsForLocalAck() {
        val harness = Harness()
        harness.session.start()
        val local = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)

        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 7, byteArrayOf()))
        assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))

        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, local.id, local.data))
        assertTrue(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
    }

    @Test fun localFirstLcpWaitsForPeerRequest() {
        val harness = Harness()
        harness.session.start()
        val local = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)

        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, local.id, local.data))
        assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))

        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 8, byteArrayOf()))
        assertTrue(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
    }

    @Test fun lcpNakAndRejectRenegotiateWithoutStartingIpcp() {
        listOf(PppSession.CONFIGURE_NAK, PppSession.CONFIGURE_REJECT).forEach { responseCode ->
            val harness = Harness()
            harness.session.start()
            val request = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)
            val before = harness.sent.size

            harness.session.accept(control(PppSession.LCP, responseCode, request.id, request.data.copyOfRange(0, 4)))

            assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
            assertTrue(harness.sent.drop(before).any { parse(it).protocol == PppSession.LCP && parse(it).code == PppSession.CONFIGURE_REQUEST })
        }
    }

    @Test fun unknownAndMalformedLcpOptionsAreNotAcked() {
        val unknown = Harness().also { it.session.start() }
        unknown.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 9, byteArrayOf(0x7f, 2)))
        assertArrayEquals(byteArrayOf(0x7f, 2), unknown.last(PppSession.LCP, PppSession.CONFIGURE_REJECT).data)

        val malformed = Harness().also { it.session.start() }
        val before = malformed.sent.size
        malformed.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 10, byteArrayOf(1, 5, 0, 1)))
        assertTrue(malformed.sent.size == before)
    }

    @Test fun lcpMruAccmPfcAndAcfcAreAccepted() {
        val options = byteArrayOf(
            1, 4, 0x02, 0x40,
            2, 6, 0, 0, 0, 0,
            7, 2,
            8, 2,
        )
        val harness = Harness()
        harness.session.start()
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 11, options, compressed = true))
        assertArrayEquals(options, harness.last(PppSession.LCP, PppSession.CONFIGURE_ACK).data)
    }

    @Test fun badConfigureAckDoesNotAdvanceState() {
        val harness = Harness()
        harness.session.start()
        val local = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 12, byteArrayOf()))
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, local.id, local.data + byteArrayOf(7, 2)))
        assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
    }

    @Test fun ipcpNeedsBothDirectionsAndRejectsUnknownOption() {
        val harness = openLcp()
        val local = harness.last(PppSession.IPCP, PppSession.CONFIGURE_REQUEST)

        harness.session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 13, byteArrayOf(200.toByte(), 2)))
        assertArrayEquals(byteArrayOf(200.toByte(), 2), harness.last(PppSession.IPCP, PppSession.CONFIGURE_REJECT).data)
        assertFalse(harness.session.networkOpen)

        harness.session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 14, byteArrayOf(3, 6) + PppSession.CLIENT_IP))
        assertFalse(harness.session.networkOpen)
        harness.session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_ACK, local.id, local.data))
        assertTrue(harness.session.networkOpen)
    }

    @Test fun dnsNegotiationNaksThenAcksRequestedAddresses() {
        val harness = openLcp()
        val request = byteArrayOf(3, 6, 0, 0, 0, 0, 129.toByte(), 6, 0, 0, 0, 0, 131.toByte(), 6, 0, 0, 0, 0)
        harness.session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 15, request))
        val nak = harness.last(PppSession.IPCP, PppSession.CONFIGURE_NAK)
        assertArrayEquals(byteArrayOf(3, 6) + PppSession.CLIENT_IP + byteArrayOf(129.toByte(), 6) + PppSession.SERVER_IP + byteArrayOf(131.toByte(), 6) + PppSession.SERVER_IP, nak.data)

        harness.session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 16, nak.data))
        assertArrayEquals(nak.data, harness.last(PppSession.IPCP, PppSession.CONFIGURE_ACK).data)
    }

    @Test fun duplicateConfigureRequestGetsSameAckWithoutOpeningEarly() {
        val harness = Harness()
        harness.session.start()
        val request = control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 17, byteArrayOf(7, 2, 8, 2))
        harness.session.accept(request)
        harness.session.accept(request)
        val acks = harness.sent.map(::parse).filter { it.protocol == PppSession.LCP && it.code == PppSession.CONFIGURE_ACK }
        assertTrue(acks.size == 2)
        assertArrayEquals(acks[0].data, acks[1].data)
        assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
    }

    @Test fun newerRejectedPeerRequestClearsPreviouslyAckedDirection() {
        val harness = Harness()
        harness.session.start()
        val local = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 22, byteArrayOf()))
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 23, byteArrayOf(0x7f, 2)))
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, local.id, local.data))
        assertFalse(harness.has(PppSession.IPCP, PppSession.CONFIGURE_REQUEST))
    }

    @Test fun unsolicitedChapResponseIsIgnored() {
        val harness = Harness()
        val before = harness.sent.size
        harness.session.accept(control(PppSession.CHAP, 2, 18, byteArrayOf(1, 0)))
        assertTrue(harness.sent.size == before)
    }

    @Test fun validPapRequestGetsCompatibilityAckWithoutNegotiatingAuth() {
        val harness = openLcp()
        val credentials = byteArrayOf(4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray()
        harness.session.accept(control(PppSession.PAP, 1, 19, credentials))
        assertTrue(harness.has(PppSession.PAP, 2))
    }

    @Test fun configureRequestRetransmitsAfterRestartTimeout() {
        var now = 1_000L
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += unframe(it) }, {}, clock = { now })
        session.start()
        val first = sent.single()
        now = 3_999
        session.tick()
        assertTrue(sent.size == 1)
        now = 4_000
        session.tick()
        assertTrue(sent.size == 2)
        assertArrayEquals(first, sent.last())
    }

    @Test fun negotiatedPeerMruLimitsOutboundIpv4InformationField() {
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += unframe(it) }, {})
        session.start()
        val localLcp = parse(sent.last())
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 20, byteArrayOf(1, 4, 0, 128.toByte())))
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, localLcp.id, localLcp.data))
        val localIpcp = sent.map(::parse).last { it.protocol == PppSession.IPCP && it.code == PppSession.CONFIGURE_REQUEST }
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 21, byteArrayOf(3, 6) + PppSession.CLIENT_IP))
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_ACK, localIpcp.id, localIpcp.data))
        val before = sent.size
        session.sendIpv4(ByteArray(129))
        assertTrue(sent.size == before)
        session.sendIpv4(ByteArray(128))
        assertTrue(sent.size == before + 1)
    }

    private fun openLcp(): Harness {
        val harness = Harness()
        harness.session.start()
        val local = harness.last(PppSession.LCP, PppSession.CONFIGURE_REQUEST)
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 1, byteArrayOf()))
        harness.session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, local.id, local.data))
        return harness
    }

    private inner class Harness {
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += unframe(it) }, {})
        fun has(protocol: Int, code: Int) = sent.map(::parse).any { it.protocol == protocol && it.code == code }
        fun last(protocol: Int, code: Int) = sent.map(::parse).last { it.protocol == protocol && it.code == code }
    }

    private data class Packet(val protocol: Int, val code: Int, val id: Int, val data: ByteArray)

    private fun parse(frame: ByteArray): Packet {
        var offset = if (frame[0] == 0xff.toByte() && frame[1] == 3.toByte()) 2 else 0
        val first = frame[offset].toInt() and 0xff
        val protocol = if (first and 1 != 0) first else (first shl 8) or (frame[offset + 1].toInt() and 0xff)
        offset += if (first and 1 != 0) 1 else 2
        val length = ((frame[offset + 2].toInt() and 0xff) shl 8) or (frame[offset + 3].toInt() and 0xff)
        return Packet(protocol, frame[offset].toInt() and 0xff, frame[offset + 1].toInt() and 0xff, frame.copyOfRange(offset + 4, offset + length))
    }

    private fun control(protocol: Int, code: Int, id: Int, data: ByteArray, compressed: Boolean = false): ByteArray {
        val length = data.size + 4
        val header = if (compressed) byteArrayOf((protocol ushr 8).toByte(), protocol.toByte()) else byteArrayOf(0xff.toByte(), 3, (protocol ushr 8).toByte(), protocol.toByte())
        return header + byteArrayOf(code.toByte(), id.toByte(), (length ushr 8).toByte(), length.toByte()) + data
    }

    private fun unframe(encoded: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        var escaped = false
        encoded.drop(1).dropLast(1).forEach { byte ->
            val value = byte.toInt() and 0xff
            if (escaped) { result += (value xor 0x20).toByte(); escaped = false }
            else if (value == 0x7d) escaped = true
            else result += byte
        }
        return result.dropLast(2).toByteArray()
    }
}
