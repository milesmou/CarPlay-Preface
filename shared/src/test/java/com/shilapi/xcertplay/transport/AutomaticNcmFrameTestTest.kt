package com.shilapi.xcertplay.transport

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class AutomaticNcmFrameTestTest {
    private val mac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val address = InetAddress.getByName("fe80::2").address

    @Test fun runsOnceOnlyAfterStartSessionAndClosesAtTheDeadlineEvenWithoutRx() {
        var now = 0L; var writes = 0; var releases = 0; var launches = 0
        var work: (() -> Unit)? = null
        val log = mutableListOf<String>()
        val test = AutomaticNcmFrameTest(mac, address, { writes++; true },
            { now += 100_000_000L; null }, { releases++ }, log::add, { now }, { launches++; work = it })
        assertEquals(0, writes)
        test.startSessionSent(); test.startSessionSent()
        assertEquals(1, launches)
        work!!()
        assertEquals(15_000_000_000L, now)
        assertEquals(3, writes)
        assertEquals(1, releases)
        assertTrue(test.result!!.contains("txAccepted=3"))
        assertTrue(test.result!!.contains("bidirectionalConfirmed=false"))
        assertEquals(1, log.count { it.startsWith("NCM frame test result outcome=") })
        test.close(); assertEquals(1, releases)
    }

    @Test fun cancellationBeforeStartReleasesWithoutSending() {
        var releases = 0
        val test = AutomaticNcmFrameTest(mac, address, { throw AssertionError("sent before start") },
            { throw AssertionError("read before start") }, { releases++ }, {}, launch = { it() })
        test.close(); test.startSessionSent(); test.close()
        assertEquals(1, releases)
        assertTrue(test.result!!.contains("outcome=no_start_session"))
    }

    @Test fun ioFailurePublishesInconclusiveCountsAndReleases() {
        var releases = 0
        val test = AutomaticNcmFrameTest(mac, address, { false },
            { throw java.io.IOException("unplugged") }, { releases++ }, {}, launch = { it() })
        test.startSessionSent()
        assertEquals(1, releases)
        assertTrue(test.result!!.contains("outcome=io_failed_IOException"))
        assertTrue(test.result!!.contains("bidirectionalConfirmed=false"))
    }

    @Test fun classificationAndTransportDetailsSurviveTheExportLineLimitAndKeepOneOutcome() {
        var now = 0L
        val lines = mutableListOf<String>()
        val arp = ByteArray(42).apply { this[12] = 8; this[13] = 6 }
        val test = AutomaticNcmFrameTest(mac, address, { false },
            { now += 1_000_000_000L; if (now == 1_000_000_000L) arp else null }, {}, lines::add,
            { now }, { it() }, { "usbInTransfers=1\nusbOutCalls=3 negative=3" })
        test.startSessionSent()
        assertEquals(1, lines.count { it.startsWith("NCM frame test result outcome=") })
        assertTrue(lines.any { it.startsWith("NCM frame test result ethernetHeaders") && it.contains("arp=1") })
        assertTrue(lines.any { it.startsWith("NCM frame test result usbOutCalls=3") })
        assertTrue(lines.all { '\n' !in it && '\r' !in it && it.length < 500 })
        assertTrue(test.result!!.contains("unsupported=1"))
        assertTrue(test.result!!.contains("bidirectionalConfirmed=false"))
    }
}
