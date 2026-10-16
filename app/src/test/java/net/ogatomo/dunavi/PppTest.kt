package net.ogatomo.dunavi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PppTest {
    @Test fun rawTransmitLogCanBeDisabledWithoutChangingFrames() {
        val sent = mutableListOf<ByteArray>()
        val directions = mutableListOf<String?>()
        val session = PppSession(
            sendRaw = { sent += it },
            onIpv4 = {},
            log = { _, direction, _ -> directions += direction },
            rawLogEnabled = false,
        )

        session.start()

        assertTrue(sent.isNotEmpty())
        assertFalse(directions.contains("TX RAW"))
    }

    @Test fun framingRoundTripEscapesControlBytes() {
        val payload = byteArrayOf(0xff.toByte(), 3, 0x7e, 0x7d, 0, 1, 2)
        var decoded: ByteArray? = null
        val decoder = PppDecoder { decoded = it }
        PppCodec.encode(payload).forEach { decoder.accept(it.toInt()) }
        assertArrayEquals(payload, decoded)
    }

    @Test fun decoderExposesCanonicalRawFrameForDebugLogging() {
        val payload = byteArrayOf(0xff.toByte(), 3, 0xc0.toByte(), 0x21, 1, 4, 0, 4)
        var raw: ByteArray? = null
        val decoder = PppDecoder(onFrame = {}, onError = {}, onRawFrame = { raw = it })
        val encoded = PppCodec.encode(payload)
        encoded.forEach { decoder.accept(it.toInt()) }
        assertArrayEquals(encoded, raw)
    }

    @Test fun badFcsIsRejected() {
        var called = false
        val decoder = PppDecoder { called = true }
        PppCodec.encode(byteArrayOf(1, 2, 3)).also { it[2] = (it[2].toInt() xor 1).toByte() }
            .forEach { decoder.accept(it.toInt()) }
        assertFalse(called)
    }

    @Test fun unescapedMappedControlByteIsDiscardedBeforeFcs() {
        val encoded = PppCodec.encode(byteArrayOf(0xff.toByte(), 3, 0xc0.toByte(), 0x21, 1, 1, 0, 4))
        val injected = encoded.copyOf(encoded.size + 1)
        injected[encoded.lastIndex] = 0x11
        injected[injected.lastIndex] = 0x7e
        var decoded: ByteArray? = null
        val decoder = PppDecoder { decoded = it }
        injected.forEach { decoder.accept(it.toInt()) }
        assertArrayEquals(byteArrayOf(0xff.toByte(), 3, 0xc0.toByte(), 0x21, 1, 1, 0, 4), decoded)
    }

    @Test fun malformedEscapeTerminatedByFlagIsDiscarded() {
        var called = false
        var errors = 0
        val decoder = PppDecoder({ called = true }, { errors++ })
        byteArrayOf(0x7e, 1, 2, 0x7d, 0x7e).forEach { decoder.accept(it.toInt()) }
        assertFalse(called)
        assertTrue(errors == 1)
    }

    @Test fun lcpAndIpcpReachNetworkOpen() {
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += decode(it) }, {})
        session.start()
        val lcpRequest = sent.last()
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 9, byteArrayOf()))
        assertTrue(sent.any { protocol(it) == PppSession.LCP && it[4].toInt() == PppSession.CONFIGURE_ACK })
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, lcpRequest[5].toInt() and 0xff, lcpRequest.copyOfRange(8, lcpRequest.size)))
        val ipcpRequest = sent.last { protocol(it) == PppSession.IPCP && it[4].toInt() == PppSession.CONFIGURE_REQUEST }
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 10, byteArrayOf(3, 6) + PppSession.CLIENT_IP))
        assertFalse(session.networkOpen)
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_ACK, ipcpRequest[5].toInt() and 0xff, ipcpRequest.copyOfRange(8, ipcpRequest.size)))
        assertTrue(session.networkOpen)
    }

    @Test fun zeroClientAddressGetsNak() {
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += decode(it) }, {})
        openLcp(session, sent)
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 7, byteArrayOf(3, 6, 0, 0, 0, 0)))
        val response = sent.last()
        assertTrue(response[4].toInt() == PppSession.CONFIGURE_NAK)
        assertArrayEquals(PppSession.CLIENT_IP, response.copyOfRange(10, 14))
    }

    @Test fun dnsAddressGetsVirtualGateway() {
        val sent = mutableListOf<ByteArray>()
        val session = PppSession({ sent += decode(it) }, {})
        openLcp(session, sent)
        session.accept(control(PppSession.IPCP, PppSession.CONFIGURE_REQUEST, 8, byteArrayOf(129.toByte(), 6, 0, 0, 0, 0)))
        assertArrayEquals(PppSession.SERVER_IP, sent.last().copyOfRange(10, 14))
    }

    private fun control(protocol: Int, code: Int, id: Int, data: ByteArray): ByteArray {
        val length = data.size + 4
        return byteArrayOf(0xff.toByte(), 3, (protocol ushr 8).toByte(), protocol.toByte(), code.toByte(), id.toByte(),
            (length ushr 8).toByte(), length.toByte()) + data
    }

    private fun openLcp(session: PppSession, sent: MutableList<ByteArray>) {
        session.start()
        val request = sent.last()
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_REQUEST, 1, byteArrayOf()))
        session.accept(control(PppSession.LCP, PppSession.CONFIGURE_ACK, request[5].toInt() and 0xff, request.copyOfRange(8, request.size)))
    }

    private fun decode(encoded: ByteArray): ByteArray {
        lateinit var result: ByteArray
        val decoder = PppDecoder { result = it }
        encoded.forEach { decoder.accept(it.toInt()) }
        return result
    }

    private fun protocol(frame: ByteArray) = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
}
