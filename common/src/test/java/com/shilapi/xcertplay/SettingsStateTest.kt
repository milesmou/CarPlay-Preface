package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStateTest {
    @After fun tearDown() = PendingReconnect.clear()

    @Test fun readinessReportsTheMostUrgentStateFirst() {
        fun of(error: Boolean = false, active: Boolean = false, running: Boolean = false,
            wireless: Boolean = true, hotspotSetup: Boolean = false) =
            SettingsReadiness.of(error, active, running, wireless, hotspotSetup)

        assertEquals(SettingsReadiness.SETUP_ERROR, of(error = true, active = true))
        assertEquals(SettingsReadiness.CONNECTED, of(active = true, running = true))
        assertEquals(SettingsReadiness.CONNECTING, of(running = true))
        assertEquals(SettingsReadiness.HOTSPOT_SETUP, of(hotspotSetup = true))
        assertEquals(SettingsReadiness.READY_WIRELESS, of())
        assertEquals(SettingsReadiness.READY_USB, of(wireless = false))
        assertTrue(SettingsReadiness.HOTSPOT_SETUP.needsAction)
        assertFalse(SettingsReadiness.READY_USB.needsAction)
    }

    @Test fun temporaryHotspotNeedsNoSetupAndDoesNotHideAnActiveSession() {
        fun of(active: Boolean = false, running: Boolean = false, wireless: Boolean = true) =
            SettingsReadiness.of(false, active, running, wireless)

        assertEquals(SettingsReadiness.READY_WIRELESS, of())
        assertFalse(SettingsReadiness.READY_WIRELESS.needsAction)
        assertEquals(SettingsReadiness.CONNECTED, of(active = true))
        assertEquals(SettingsReadiness.CONNECTING, of(running = true))
        assertEquals(SettingsReadiness.READY_USB, of(wireless = false))
    }

    @Test fun pendingReconnectBelongsToTheSessionThatSavedIt() {
        val first = Any()
        val second = Any()
        assertFalse(PendingReconnect.isPending(first))

        PendingReconnect.mark(first)

        assertTrue(PendingReconnect.isPending(first))
        assertFalse(PendingReconnect.isPending(second))
        assertFalse(PendingReconnect.isPending(null))
        PendingReconnect.clear()
        assertFalse(PendingReconnect.isPending(first))
    }
}
