package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class NcmStatusDiagnosticsTest {
    @Test fun linkAndSpeedIndicationsAreNotReportedAsIpConnectivity() {
        val status = NcmStatusDiagnostics()
        status.receive(byteArrayOf(0xa1.toByte(), 0, 1, 0, 3, 0, 0, 0), 8)
        status.receive(byteArrayOf(0xa1.toByte(), 0x2a, 0, 0, 3, 0, 8, 0,
            0, 0, 0, 0x80.toByte(), 0, 0xe1.toByte(), 0xf5.toByte(), 5), 16)
        val summary = status.summary()
        assertTrue(summary.contains("linkIndication=up"))
        assertTrue(summary.contains("downstreamBps=2147483648 upstreamBps=100000000"))
        assertTrue(summary.contains("ipReachability=unconfirmed"))
        status.receive(byteArrayOf(0xa1.toByte(), 0, 0, 0, 3, 0, 0, 0), 8)
        assertTrue(status.summary().contains("linkIndication=down"))
    }

    @Test fun truncatedOrWrongHeadersCannotInventALinkState() {
        val status = NcmStatusDiagnostics()
        val bytes = byteArrayOf(0xa1.toByte(), 0x2a, 0, 0, 3, 0, 8, 0)
        for (size in 0..8) status.receive(bytes, size)
        status.receive(bytes.copyOf().apply { this[0] = 0 }, 8)
        assertTrue(status.summary().contains("malformed=10"))
        assertTrue(status.summary().contains("linkIndication=unknown"))
        assertTrue(status.summary().contains("downstreamBps=unknown"))
    }
}
