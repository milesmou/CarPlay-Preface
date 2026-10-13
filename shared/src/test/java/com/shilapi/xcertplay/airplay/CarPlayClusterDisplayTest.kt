package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayClusterDisplayTest {
    @Test
    fun defaultClusterIsAdvertisedAsAnInputlessMapScreen() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = CarPlayClusterDisplay.config(1920, 720),
            ),
        )

        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        val alt = displays[1] as Map<*, *>
        assertEquals(111, alt["type"])
        // Keep the virtual panel's native resolution and aspect.
        assertEquals(1920, alt["widthPixels"])
        assertEquals(720, alt["heightPixels"])
        assertEquals(0, alt["features"])
        assertEquals(0, alt["primaryInputDevice"])
        assertEquals("maps:/car/instrumentcluster/map", alt["initialURL"])
        assertEquals(200, alt["widthPhysical"])
        assertEquals(75, alt["heightPhysical"])

        val view = (alt["viewAreas"] as List<*>).single() as Map<*, *>
        assertEquals(1920, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        val safe = view["safeArea"] as Map<*, *>
        // Balanced 8 % margins, independent of a factory cluster's overlays.
        assertEquals(153, safe["originXPixels"])
        assertEquals(57, safe["originYPixels"])
        assertEquals(1614, safe["widthPixels"])
        assertEquals(606, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])

        // The main screen keeps its touch and knob features.
        assertEquals(0x0A, (displays[0] as Map<*, *>)["features"])
    }

    @Test
    fun theDashboardCanAskForTheTurnCardInstead() {
        val card = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.TURN_CARD)

        assertEquals("maps:/car/instrumentcluster/instructioncard", card.initialUrl)
        val custom = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        assertEquals("maps:/car/instrumentcluster/map", custom.initialUrl)
        assertEquals(
            listOf(
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster/instructioncard",
                "maps:/car/instrumentcluster",
                "maps:/car/instrumentcluster/map",
            ),
            CarPlayClusterDisplay.Content.entries.map { it.url },
        )
    }

    @Test
    fun scaledStreamsKeepThePanelAspect() {
        val sizes = (CarPlayClusterDisplay.scalePresets + 50).map {
            CarPlayClusterDisplay.config(1920, 720, scalePercent = it).let { c -> c.widthPixels to c.heightPixels }
        }

        assertEquals(listOf(1920 to 720, 1600 to 600, 1280 to 480, 2408 to 904, 960 to 360), sizes)
        assertTrue(CarPlayClusterDisplay.STREAM_SCALE_PERCENT in CarPlayClusterDisplay.scalePresets)
    }

    @Test
    fun markerStepsMoveTheWholeSafeArea() {
        val centre = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100).safeArea!!
        val farLeftHigher = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = -2, verticalStep = -1).safeArea!!

        // Marker position moves while the safe area shrinks to fit the nearer edge.
        val centreX = (centre.left + 1920 - centre.right) / 2.0
        val centreY = (centre.top + 720 - centre.bottom) / 2.0
        assertEquals(centreX - 384, (farLeftHigher.left + 1920 - farLeftHigher.right) / 2.0, 1.0)
        assertEquals(centreY - 72, (farLeftHigher.top + 720 - farLeftHigher.bottom) / 2.0, 1.0)
    }

    @Test
    fun markerStepsAreBounded() {
        val beyond = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = 9, verticalStep = -9).safeArea
        val limit = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = 4, verticalStep = -3).safeArea

        assertEquals(limit, beyond)
    }

    @Test
    fun nearAnEdgeTheSafeAreaShrinksAroundTheMarker() {
        val top = CarPlayClusterDisplay.config(1000, 1000, scalePercent = 100, verticalStep = -3).safeArea!!

        // Marker at 20 % from the top: the area reaches the top edge and stays centred on it.
        assertEquals(0, top.top)
        assertEquals(400, 1000 - top.top - top.bottom)
        assertEquals(20.0, CarPlayClusterDisplay.markerPercent(0, -3).second, 0.001)
    }

    @Test
    fun theCarSitsNearThePanelCentre() {
        val safe = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val centreX = (safe.left + 100 - safe.right) / 2.0

        assertTrue("centre x $centreX", centreX in 48.0..52.0)
    }

    @Test
    fun virtualCluster16By9UsesBalancedSafeArea() {
        val config = CarPlayClusterDisplay.config(
            widthPixels = 1280,
            heightPixels = 720,
            scalePercent = 100,
        )
        assertEquals(1280, config.widthPixels)
        assertEquals(720, config.heightPixels)
        val safe = config.safeArea!!
        assertEquals(safe.left, safe.right)
        assertEquals(safe.top, safe.bottom)
    }
}
