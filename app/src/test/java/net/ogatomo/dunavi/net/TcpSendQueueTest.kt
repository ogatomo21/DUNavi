package net.ogatomo.dunavi.net

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSendQueueTest {
    @Test fun zeroAndSmallPeerWindowsAreNotInflatedToMss() {
        val queue = TcpSendQueue(100)
        queue.updatePeerWindow(0, 1_000)
        assertEquals(0, queue.sendCapacity(1460))
        assertFalse(queue.zeroWindowProbeDue(1_799, 800))
        assertTrue(queue.zeroWindowProbeDue(1_800, 800))
        assertFalse(queue.zeroWindowProbeDue(2_599, 800))
        assertTrue(queue.zeroWindowProbeDue(2_600, 800))
        queue.updatePeerWindow(37, 2_601)
        assertEquals(37, queue.sendCapacity(1460))
    }

    @Test fun partialAckTrimsOnlyAcknowledgedPrefix() {
        val queue = TcpSendQueue(100)
        queue.enqueue(TcpHeader.ACK or TcpHeader.PSH, ByteArray(100) { it.toByte() }, 1_000)
        assertTrue(queue.acknowledge(140))
        val remaining = queue.firstOutstanding()!!
        assertEquals(140, remaining.sequence)
        assertArrayEquals(ByteArray(60) { (it + 40).toByte() }, remaining.data)
    }

    @Test fun duplicateAndFutureAcksDoNotMutateQueue() {
        val queue = TcpSendQueue(100)
        queue.enqueue(TcpHeader.ACK, byteArrayOf(1, 2, 3), 1_000)
        assertFalse(queue.acknowledge(100))
        assertFalse(queue.acknowledge(104))
        assertEquals(100, queue.firstOutstanding()!!.sequence)
    }

    @Test fun retransmissionTimerRunsWheneverDataIsOutstanding() {
        val queue = TcpSendQueue(100)
        queue.enqueue(TcpHeader.ACK or TcpHeader.PSH, byteArrayOf(1), 1_000)
        assertNull(queue.retransmitDue(1_799, 800))
        assertEquals(100, queue.retransmitDue(1_800, 800)!!.sequence)
        assertNull(queue.retransmitDue(2_599, 800))
        assertEquals(100, queue.retransmitDue(2_600, 800)!!.sequence)
    }

    @Test fun finUsesSequenceSpaceAndCanBeRetransmittedUntilAcked() {
        val queue = TcpSendQueue(500)
        queue.enqueue(TcpHeader.FIN or TcpHeader.ACK, byteArrayOf(), 1_000)
        assertEquals(501, queue.sendNext)
        assertTrue(queue.hasOutstandingFin)
        assertTrue(queue.retransmitDue(1_800, 800)!!.flags and TcpHeader.FIN != 0)
        assertTrue(queue.acknowledge(501))
        assertFalse(queue.hasOutstandingFin)
        assertNull(queue.firstOutstanding())
    }

    @Test fun synMustBeAcknowledgedBeforeHandshakeIsComplete() {
        val queue = TcpSendQueue(700)
        queue.enqueue(TcpHeader.SYN or TcpHeader.ACK, byteArrayOf(), 1_000)
        assertTrue(queue.hasOutstandingSyn)
        assertFalse(queue.acknowledge(700))
        assertTrue(queue.hasOutstandingSyn)
        assertTrue(queue.acknowledge(701))
        assertFalse(queue.hasOutstandingSyn)
    }

    @Test fun sequenceArithmeticWrapsAt32Bits() {
        val queue = TcpSendQueue(0xffff_fffeL)
        queue.enqueue(TcpHeader.ACK, byteArrayOf(1, 2, 3, 4), 1_000)
        assertEquals(2, queue.sendNext)
        assertTrue(queue.acknowledge(0))
        val remaining = queue.firstOutstanding()!!
        assertEquals(0, remaining.sequence)
        assertArrayEquals(byteArrayOf(3, 4), remaining.data)
        assertTrue(queue.acknowledge(2))
        assertNull(queue.firstOutstanding())
    }

    @Test fun duplicateSynAndFinKeepStableHandshakeSequence() {
        val server = ServerSocket(0)
        val accepted = AtomicReference<Socket>()
        Thread { accepted.set(server.accept()) }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val key = SessionKey(0xc0a82c02.toInt(), 40_000, 0x7f000001, server.localPort)
        val session = TcpSession(key, scope, output::add, { _, _ -> }, log = { _, _, message -> logs.add(message) }) {}
        val syn = TcpHeader(40_000, server.localPort, 100, 0, 20, TcpHeader.SYN, 65_535)
        try {
            session.open(syn)
            assertTrue(await { output.isNotEmpty() })
            val first = tcp(output.first())
            assertFalse(logs.any { it.endsWith("ESTABLISHED") })

            session.onPacket(syn, byteArrayOf(), 0, 0)
            assertTrue(await { output.size >= 2 })
            assertEquals(first.sequence, tcp(output[1]).sequence)

            val ack = TcpHeader(40_000, server.localPort, 101, seqAdvance(first.sequence, 1), 20, TcpHeader.ACK, 65_535)
            session.onPacket(ack, byteArrayOf(), 0, 0)
            assertTrue(await { logs.any { it.endsWith("ESTABLISHED") } })

            val fin = TcpHeader(40_000, server.localPort, 101, seqAdvance(first.sequence, 1), 20, TcpHeader.ACK or TcpHeader.FIN, 65_535)
            val beforeFin = output.size
            session.onPacket(fin, byteArrayOf(), 0, 0)
            assertTrue(await { output.size > beforeFin })
            assertEquals(102, tcp(output.last()).acknowledgment)
            val beforeDuplicate = output.size
            session.onPacket(fin, byteArrayOf(), 0, 0)
            assertTrue(await { output.size > beforeDuplicate })
            assertEquals(102, tcp(output.last()).acknowledgment)
        } finally {
            session.finish()
            accepted.get()?.close()
            server.close()
            scope.cancel()
        }
    }

    private fun tcp(packet: ByteArray): TcpHeader {
        val ip = Ipv4Header.parse(packet, packet.size)!!
        return TcpHeader.parse(packet, ip.headerLength, ip.payloadLength)!!
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
