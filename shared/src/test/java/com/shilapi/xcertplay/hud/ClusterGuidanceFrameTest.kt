package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterGuidanceFrameTest {
    @Test
    fun `maps basic turns to amap icons`() {
        assertEquals(ClusterGuidanceFrame(2, 0, 53), frame(type = 1, distance = 53))
        assertEquals(ClusterGuidanceFrame(3, 0, 53), frame(type = 2, distance = 53))
        assertEquals(ClusterGuidanceFrame(9, 0, 53), frame(type = 3, distance = 53))
        assertEquals(ClusterGuidanceFrame(4, 0, 53), frame(type = 49, distance = 53))
        assertEquals(ClusterGuidanceFrame(5, 0, 53), frame(type = 14, distance = 53))
    }

    @Test
    fun `numbered roundabout exit carries exit number`() {
        assertEquals(ClusterGuidanceFrame(11, 2, 120), frame(type = 29, distance = 120))
        assertEquals(ClusterGuidanceFrame(17, 2, 120), frame(type = 29, distance = 120, drivingSide = 1))
    }

    @Test
    fun `u-turn follows driving side and arrival is destination`() {
        assertEquals(8, frame(type = 4).icon)
        assertEquals(19, frame(type = 4, drivingSide = 1).icon)
        assertEquals(15, frame(type = 12).icon)
    }

    @Test
    fun `negative distance is clamped`() {
        assertEquals(0, frame(type = 1, distance = -5).distanceMeters)
    }

    @Test fun `unknown maneuver is not advertised as straight`() {
        for (type in listOf(0, 15, 16, 17, 54, 255)) assertEquals(0, frame(type).icon)
        for (type in listOf(3, 5, 8, 9, 11, 51)) assertEquals(9, frame(type).icon)
    }

    private fun frame(type: Int, distance: Int = 0, drivingSide: Int = 0) =
        ClusterGuidanceFrame.from(CarPlayManeuver(distance, type, drivingSide))
}
