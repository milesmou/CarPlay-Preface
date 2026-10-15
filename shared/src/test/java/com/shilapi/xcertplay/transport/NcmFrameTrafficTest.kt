package com.shilapi.xcertplay.transport

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NcmFrameTrafficTest {
    @Test fun dhcpMessageTypeIsCountedWithoutExportingClientFieldsOrInventingAnIpv4Session() {
        val traffic = NcmFrameTraffic()
        fun dhcp(message: Int): ByteArray {
            val bootp = ByteArray(240).apply {
                this[0] = 1
                "private-client-host-and-id".toByteArray().copyInto(this, 40)
                byteArrayOf(99, 130.toByte(), 83, 99).copyInto(this, 236)
            } + byteArrayOf(53, 1, message.toByte(), 255.toByte())
            return ipv4(17, ByteArray(8).apply {
                put16(this, 0, 68); put16(this, 2, 67); put16(this, 4, size + bootp.size)
            } + bootp)
        }
        traffic.receive(dhcp(1)); traffic.receive(dhcp(3)); traffic.receive(dhcp(5))
        traffic.receive(dhcp(1).apply { this[size - 1] = 54 }) // Missing end plus truncated next option.
        val summary = traffic.summary()
        assertTrue(summary.contains("dhcp4Messages discover=1 offer=0 request=1 ack=1 nak=0 other=0 malformed=1"))
        assertFalse(summary.contains("private"))
        assertTrue(summary.lineSequence().all { it.length < 500 })
    }

    @Test fun identifiesArpIpv4DhcpAndVlanIpv6WithoutTreatingHeadersAsConnectivity() {
        val traffic = NcmFrameTraffic()
        traffic.receive(ethernet(0x0806, ByteArray(28)))
        val udp = ByteArray(8).apply { put16(this, 0, 68); put16(this, 2, 67); put16(this, 4, 8) }
        traffic.receive(ipv4(17, udp))
        val v6 = icmp6()
        traffic.receive(v6.copyOfRange(0, 12) + byteArrayOf(0x81.toByte(), 0, 0, 1) + v6.copyOfRange(12, v6.size))
        val summary = traffic.summary()
        assertTrue(summary, summary.contains("ipv4=1 ipv6=1 arp=1 vlan=1"))
        assertTrue(summary.contains("dhcp4=1")); assertTrue(summary.contains("icmp6=1 ns=1"))
        assertTrue(summary.contains("headerOnly=true"))
        assertFalse(summary.contains("fe80")); assertFalse(summary.contains("payload"))
    }

    @Test fun tcpSynToTheListenerIsDistinctFromSynAckAndFragments() {
        val traffic = NcmFrameTraffic()
        val tcp = ByteArray(20).apply { put16(this, 2, 7000); this[12] = 0x50; this[13] = 2 }
        traffic.receive(ipv4(6, tcp))
        traffic.receive(ipv4(6, tcp.copyOf().apply { this[13] = 0x12 }))
        traffic.receive(ipv4(6, tcp).apply { this[20] = 0x20 })
        assertTrue(traffic.summary().contains("synTo7000=1"))
        assertTrue(traffic.summary().contains("fragments=1"))
    }

    @Test fun extensionHeadersAreCountedWithoutGuessingTransportOrReadingPastTruncatedFrames() {
        val traffic = NcmFrameTraffic()
        val frame = icmp6().apply { this[20] = 0 } // Hop-by-hop header, not a direct ICMPv6 packet.
        traffic.receive(frame)
        for (length in 0 until frame.size) traffic.receive(frame.copyOf(length))
        assertTrue(traffic.summary().contains("ipv6Extensions=1"))
        assertTrue(traffic.summary().contains("icmp6=0"))
        assertFalse(traffic.summary().lineSequence().first { it.startsWith("protocolHeaders ") }.contains("malformed=0"))
    }

    @Test fun unexpectedEtherTypesAndMalformedHeadersHaveBoundedNonPayloadEvidence() {
        val traffic = NcmFrameTraffic()
        repeat(100) { traffic.receive(ethernet(0x9000 + it, "private-payload".toByteArray())) }
        traffic.receive(ipv4(17, ByteArray(8)).apply { this[14] = 0x41 })
        val summary = traffic.summary()
        assertTrue(summary.contains("other=100"))
        assertTrue(summary.contains("otherTypes=0x9000,0x9001,0x9002,0x9003"))
        assertFalse(summary.contains("0x9004"))
        assertFalse(summary.contains("private"))
        assertTrue(summary.lineSequence().all { it.length < 500 })
    }

    private fun icmp6(): ByteArray = NcmFrameProbe.icmpFrame(byteArrayOf(2, 1, 2, 3, 4, 5),
        byteArrayOf(2, 5, 4, 3, 2, 1), InetAddress.getByName("fe80::1").address,
        InetAddress.getByName("fe80::2").address, ByteArray(24).apply { this[0] = 135.toByte() }, 255)

    private fun ipv4(protocol: Int, transport: ByteArray): ByteArray = ethernet(0x0800, ByteArray(20).apply {
        this[0] = 0x45; put16(this, 2, size + transport.size); this[9] = protocol.toByte()
    } + transport)

    private fun ethernet(type: Int, data: ByteArray): ByteArray = ByteArray(14).apply { put16(this, 12, type) } + data
    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
    }
}
