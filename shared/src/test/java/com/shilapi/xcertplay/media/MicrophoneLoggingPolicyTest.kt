package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.shared.BuildConfig
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneLoggingPolicyTest {
    private val config = MicrophoneConfig("telephony", 24_000, 1, 100, 20,
        InetAddress.getLoopbackAddress(), 1234, ByteArray(32), AudioCodecKind.OPUS)

    @Test fun aQuietWindowIsFilteredButStillQueriesTheRouteAndResetsItsCounters() {
        var now = 0L
        var queries = 0
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.reading()
        stats.read(960)
        stats.sent()
        now = 5_000_000_000L
        stats.flush(routeType = { queries++; 15 })
        assertEquals(1, queries)
        assertEquals(BuildConfig.DEBUG, reports.any { it.startsWith("Microphone: stats") })
        reports.clear()
        stats.flush(ended = true)
        assertTrue(reports.single().contains("captureBytes=0 reads=0"))
        assertTrue(reports.single().contains("udpSent=0"))
        assertTrue(reports.single().contains("ended=true"))
    }

    @Test fun errorsAndRestartReasonsSurviveInBothVariants() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.failure(MicrophoneFailureStage.READ, SecurityException("private exception text"))
        stats.restarted(1, "no-audio", 15)
        stats.read(-3)
        stats.sendFailed()
        now = 5_000_000_000L
        stats.flush()
        assertTrue(reports.any { it.contains("stage=READ error=SecurityException") })
        assertTrue(reports.any { it.contains("reason=no-audio") })
        assertTrue(reports.any { it.contains("readErrors=1") && it.contains("sendErrors=1") })
        assertFalse(reports.any { it.contains("private exception text") })
    }
}
