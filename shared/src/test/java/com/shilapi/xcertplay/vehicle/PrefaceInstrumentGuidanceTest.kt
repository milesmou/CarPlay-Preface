package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.hud.CarPlayManeuver
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class PrefaceInstrumentGuidanceTest {
    private class Backend : PrefaceInstrumentBackend {
        var connects = 0
        var closes = 0
        var clears = 0
        var ready = true
        var available = true
        var failure: Exception? = null
        val sent = mutableListOf<PrefaceInstrumentGuidance>()
        override fun connect() { connects++ }
        override fun ready() = ready
        override fun available() = available
        override fun publish(guidance: PrefaceInstrumentGuidance) { failure?.let { throw it }; sent += guidance }
        override fun clear() { clears++ }
        override fun close() { closes++ }
    }

    private var now = 0L
    private val backend = Backend()
    private val logs = mutableListOf<String>()
    private val session = PrefaceInstrumentGuidanceSession(backend, { now }, logs::add)
    private fun guidance(type: Int = 1, distance: Long = 150) {
        session.accept(Iap2Messages.buildRaw(0x5202) { u16(1, 0); u8(3, type); string(4, "测试道路") })
        session.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16(0x0d, 0); u32(0x0a, distance) })
    }
    private fun status(value: Int) { session.accept(Iap2Messages.buildRaw(0x5201) { u8(1, value) }) }

    @Test fun confirmedArrowsKeepTheirDirectionAndUnconfirmedOnesAreHidden() {
        for ((apple, expected) in mapOf(1 to 102, 2 to 103, 13 to 104, 14 to 105,
            47 to 106, 48 to 107, 4 to 108, 3 to 109)) {
            assertEquals(expected, PrefaceInstrumentGuidance.from(CarPlayManeuver(100, apple, 0))?.turnId)
        }
        for (apple in listOf(0, 6, 7, 10, 28, 46, 255)) {
            assertNull(PrefaceInstrumentGuidance.from(CarPlayManeuver(100, apple, 0)))
        }
        // A right-hand U-turn has no confirmed base-arrow mapping on this instrument.
        assertNull(PrefaceInstrumentGuidance.from(CarPlayManeuver(100, 4, 1)))
    }

    @Test fun boundsDoNotSplitRoadEmojiOrAcceptNegativeDistances() {
        val value = PrefaceInstrumentGuidance.from(CarPlayManeuver(-1, 1, 0, "路".repeat(30) + "🚗\n",
            remainingMeters = -20, remainingSeconds = -1))!!
        assertEquals("路".repeat(30), value.road)
        assertEquals(0, value.distanceMeters)
        assertEquals(0L, value.remainingMeters)
        assertEquals(0L, value.remainingSeconds)
    }

    @Test fun noNavigationNeverConnectsOrClearsTheCar() {
        session.tick(); session.close()
        assertEquals(0, backend.connects)
        assertEquals(0, backend.clears)
    }

    @Test fun sendsParsedGuidanceAndLimitsWritesToTwicePerSecond() {
        guidance(); session.tick()
        assertEquals(102, backend.sent.single().turnId)
        assertEquals("测试道路", backend.sent.single().road)
        guidance(distance = 140); now += 100_000_000; session.tick()
        assertEquals(1, backend.sent.size)
        now += 400_000_000; session.tick()
        assertEquals(140, backend.sent.last().distanceMeters)
        now += 500_000_000; session.tick()
        assertEquals(2, backend.sent.size)
    }

    @Test fun nativeNavigationOrPhonePreventsWritesAndClear() {
        guidance(); session.tick()
        backend.available = false
        now += 2_000_000_000; session.tick(); session.close()
        assertEquals(1, backend.sent.size)
        assertEquals(0, backend.clears)
    }

    @Test fun nativeOutputReleasingAllowsFreshGuidanceAgain() {
        backend.available = false
        guidance(); session.tick()
        assertTrue(backend.sent.isEmpty())
        backend.available = true
        now += 500_000_000; session.tick()
        assertEquals(1, backend.sent.size)
    }

    @Test fun cancelAndArrivalClearOnlyOurPreviouslySentNavigation() {
        for (status in listOf(0, 2)) {
            guidance(); session.tick()
            status(status); session.tick()
        }
        assertEquals(2, backend.clears)
        session.close()
        assertEquals(2, backend.clears)
    }

    @Test fun silenceAndUnknownManeuverRemoveOldArrows() {
        guidance(); session.tick()
        now += 30_000_000_001; session.tick()
        assertEquals(1, backend.clears)
        guidance(); session.tick()
        guidance(type = 255); session.tick()
        assertEquals(2, backend.clears)
    }

    @Test fun disconnectInvalidatesDataAndLateFrames() {
        guidance(); session.tick(); session.close()
        guidance(); now += 3_000_000_000; session.tick()
        assertEquals(1, backend.sent.size)
        assertEquals(1, backend.clears)
    }

    @Test fun missingPermissionStopsTheSessionWithoutRetrying() {
        backend.failure = SecurityException("denied")
        guidance(); session.tick()
        backend.failure = null
        repeat(10) { now += 1_000_000_000; session.tick() }
        assertEquals(1, backend.connects)
        assertTrue(backend.sent.isEmpty())
        assertTrue(logs.any { "SecurityException" in it })
    }

    @Test fun unavailableCarServiceHasABoundedConnectionAttempt() {
        backend.ready = false
        guidance(); session.tick()
        now += 5_000_000_000; session.tick()
        backend.ready = true
        now += 1_000_000_000; session.tick()
        assertEquals(1, backend.connects)
        assertTrue(backend.sent.isEmpty())
        assertEquals(1, backend.closes)
    }
}
