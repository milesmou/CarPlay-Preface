package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayWheelVolumeTest {
    @Test fun mediaPlayingWinsEvenDuringNavigationAndPauseWinsOverBufferedMedia() {
        assertFalse(wheelVolumeNavigation(true, false))
        assertFalse(wheelVolumeNavigation(true, true))
        assertTrue(wheelVolumeNavigation(false, true))
        assertTrue(wheelVolumeNavigation(false, false))
        assertTrue(wheelVolumeNavigation(null, false))
        assertFalse(wheelVolumeNavigation(null, true))
    }

    @Test fun physicalPressRepeatsOncePerTimerAndStopsOnReleaseOrLostEligibility() {
        val queue = mutableListOf<Runnable>()
        val steps = mutableListOf<Int>()
        var eligible = true
        val press = PrefaceVolumePress({ queue += it }, { queue.remove(it) }) {
            if (eligible) { steps += it; true } else false
        }
        press.onKey(true, 301)
        press.onKey(true, 301)
        assertEquals(listOf(1), steps)
        queue.removeAt(0).run()
        assertEquals(listOf(1, 1), steps)
        val stale = queue.single()
        press.onKey(false, 301)
        assertTrue(queue.isEmpty())
        press.onKey(true, 302)
        stale.run()
        assertEquals(listOf(1, 1, -1), steps)
        eligible = false
        queue.removeAt(0).run()
        assertTrue(queue.isEmpty())
        assertEquals(listOf(1, 1, -1), steps)
    }

    @Test fun vendorNavigationMinimumDoesNotCallPublicValidator() {
        assertEquals(0, navigationWheelMinimum(12, 28) { error("public validator rejects vendor stream") })
    }
}
