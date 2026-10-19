package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayModeDiagnosticsTest {
    @Test fun reportsOnlyKnownNumericAndBooleanState() {
        val summary = AirPlayModeDiagnostics.summarize(mapOf("modes" to mapOf(
            "mainAudio" to mapOf("entity" to 2L, "deviceID" to "private-device"),
            "resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 1L)),
            "phoneCall" to false, "name" to "private-name")))
        assertTrue(summary.contains("mainAudio=[entity=2]"))
        assertTrue(summary.contains("resourceID=2,transferType=1"))
        assertTrue(summary.contains("phoneCall=[false]"))
        assertFalse(summary.contains("private"))
    }

    @Test fun ignoresTextAndLimitsOutput() {
        assertEquals("state=unavailable", AirPlayModeDiagnostics.summarize(mapOf("mainAudio" to "private-route")))
        val many = List(100) { mapOf("resourceID" to it.toLong()) }
        val summary = AirPlayModeDiagnostics.summarize(mapOf("resources" to many))
        assertTrue(summary.contains("resourceID=5"))
        assertFalse(summary.contains("resourceID=6"))
        assertTrue(summary.length <= 1024)
    }
}
