package com.shilapi.xcertplay.transport

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NcmFrameProbeTest {
    private val host = InetAddress.getByName("fe80::2").address
    private val peer = InetAddress.getByName("fe80::1").address
    private val mac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val peerMac = byteArrayOf(2, 0, 0, 0, 0, 1)
    private val writes = mutableListOf<ByteArray>()
    private var accepted = true
    private val probe = NcmFrameProbe(mac, host, { writes.add(it); accepted }, ByteArray(18) { it.toByte() })

    @Test fun usbAcceptedWritesAloneNeverConfirmBidirectionalCommunication() {
        repeat(20) { probe.probe() }
        assertEquals(3, writes.size)
        assertEquals(3, probe.acceptedWrites)
        assertEquals(0, probe.echoReplies)
        assertTrue(probe.summary().contains("bidirectionalConfirmed=false"))
    }

    @Test fun onlyAnAcceptedProbeAndMatchingReplyConfirmBothDirections() {
        probe.probe()
        probe.receive(reply())
        assertEquals(1, probe.echoReplies)
        probe.receive(reply()) // A replay does not count twice.
        assertEquals(1, probe.echoReplies)
        assertEquals(1, probe.rejected)
        probe.probe()
        assertArrayEquals(peer, writes.last().copyOfRange(38, 54)) // Learned unicast peer.
    }

    @Test fun aReplyToANakedWriteDoesNotProveTransmission() {
        accepted = false
        probe.probe()
        probe.receive(reply())
        assertEquals(0, probe.acceptedWrites)
        assertEquals(0, probe.echoReplies)
    }

    @Test fun wrongNonceSequenceSourceDestinationAndChecksumAreRejected() {
        probe.probe()
        val body = writes.first().copyOfRange(54, 78).also { it[0] = 129.toByte() }
        fun frame(value: ByteArray = body, source: ByteArray = peer, destination: ByteArray = host) =
            NcmFrameProbe.icmpFrame(peerMac, mac, source, destination, value, 64)
        probe.receive(frame(body.copyOf().also { it[8] = 99 }))
        probe.receive(frame(body.copyOf().also { it[7] = 9 }))
        probe.receive(frame(destination = peer))
        probe.receive(frame(source = host))
        probe.receive(frame().also { it[77] = (it[77].toInt() xor 1).toByte() })
        assertEquals(0, probe.echoReplies)
        assertEquals(5, probe.rejected)
    }

    @Test fun validNeighborSolicitationGetsACompleteAdvertisement() {
        probe.receive(solicitation())
        assertEquals(1, probe.neighborSolicitations)
        assertEquals(1, writes.size)
        val advertisement = writes.single()
        assertEquals(136, advertisement[54].toInt() and 255)
        assertEquals(255, advertisement[21].toInt() and 255)
        assertEquals(0x60, advertisement[58].toInt() and 255)
        assertArrayEquals(host, advertisement.copyOfRange(62, 78))
        assertArrayEquals(mac, advertisement.copyOfRange(80, 86))
        assertArrayEquals(peer, advertisement.copyOfRange(38, 54))
        assertEquals(0, probe.echoReplies) // NDP reply written is not a correlated peer response.
    }

    @Test fun invalidNdpHopLimitOptionsAndChecksumNeverGenerateReplies() {
        probe.receive(solicitation(hops = 64))
        probe.receive(solicitation(optionLength = 0))
        probe.receive(solicitation(optionLength = 2))
        probe.receive(solicitation().also { it[60] = 1 })
        assertTrue(writes.isEmpty())
        assertEquals(4, probe.rejected)
    }

    @Test fun dadSolicitationUsesUnsolicitedMulticastAdvertisementAndRejectsSourceOption() {
        probe.receive(solicitation(source = ByteArray(16), option = false))
        assertEquals(0x20, writes.single()[58].toInt() and 255)
        assertEquals(0xff, writes.single()[38].toInt() and 255)
        probe.receive(solicitation(source = ByteArray(16)))
        assertEquals(1, writes.size)
        assertEquals(1, probe.rejected)
    }

    @Test fun dadSolicitationToAUnicastDestinationIsInvalid() {
        val body = ByteArray(24).also { it[0] = 135.toByte(); host.copyInto(it, 8) }
        probe.receive(NcmFrameProbe.icmpFrame(peerMac, mac, ByteArray(16), host, body, 255))
        assertTrue(writes.isEmpty())
        assertEquals(1, probe.rejected)
    }

    @Test fun truncatedAndWrongVersionFramesAreNotValidIpv6() {
        val frame = solicitation()
        probe.receive(frame.copyOf(55))
        probe.receive(frame.copyOf().also { it[14] = 0x40 })
        assertEquals(0, probe.ipv6)
        assertTrue(writes.isEmpty())
    }

    @Test fun summariesContainCountsWithoutPayloadsOrAddresses() {
        probe.receive(solicitation()); probe.probe()
        val summary = probe.summary()
        assertFalse(summary.contains("fe80"))
        assertFalse(summary.contains("payload"))
        assertFalse(summary.contains("nonce"))
        assertTrue(summary.contains("rxFrames=1"))
    }

    private fun reply(): ByteArray {
        val body = writes.first().copyOfRange(54, 78).also { it[0] = 129.toByte() }
        return NcmFrameProbe.icmpFrame(peerMac, mac, peer, host, body, 64)
    }

    private fun solicitation(hops: Int = 255, optionLength: Int = 1, source: ByteArray = peer,
        option: Boolean = true): ByteArray {
        val body = ByteArray(if (option) 32 else 24)
        body[0] = 135.toByte(); host.copyInto(body, 8)
        if (option) { body[24] = 1; body[25] = optionLength.toByte(); peerMac.copyInto(body, 26) }
        return NcmFrameProbe.icmpFrame(peerMac, byteArrayOf(0x33, 0x33, 0xff.toByte(), 0, 0, 2),
            source, NcmFrameProbe.solicitedNode(host), body, hops)
    }
}
