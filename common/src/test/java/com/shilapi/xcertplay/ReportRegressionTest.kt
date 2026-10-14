package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReportRegressionTest {
    @Test fun frameRateDefaultMatchesDisplayAndExplicitSavedChoiceSurvives() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().clear().commit()
        assertEquals(AirPlayDisplaySettings.DEFAULT_FPS, AirPlayPersistence.loadFps(context))
        assertFalse(prefs.contains("display_fps"))
        AirPlayPersistence.saveFps(context, 30)
        assertEquals(30, AirPlayPersistence.loadFps(context))
        AirPlayPersistence.saveFps(context, 60)
        assertEquals(60, AirPlayPersistence.loadFps(context))
    }

    @Test fun rejectedPhysicalRequestReleasesBeforeStandardRetryWithoutLeakingErrorDetails() {
        val events = mutableListOf<String>()
        val standard = intArrayOf(85, 87, 88, 298)
        val selected = requestPrefaceTrackKeys(standard, request = {
            events += "request:${it.joinToString(",")}"
            if (305 in it) throw InvocationTargetException(SecurityException("private device detail"))
        }, abandon = { events += "release" }, diagnostic = { events += it })
        assertArrayEquals(standard, selected)
        assertEquals("request:85,87,88,298,304,305", events[0])
        assertEquals("release", events[1])
        assertEquals("request:85,87,88,298", events.last())
        assertTrue(events[2].contains("failureClass=SecurityException"))
        assertFalse(events.any { it.contains("private device detail") })
    }

    @Test fun standardRejectionPropagatesAndNoRequestFollowsFailedRelease() {
        var attempts = 0
        assertThrows(InvocationTargetException::class.java) {
            requestPrefaceTrackKeys(intArrayOf(87), request = {
                attempts++
                throw InvocationTargetException(SecurityException())
            }, abandon = {}, diagnostic = {})
        }
        assertEquals(2, attempts)
        attempts = 0
        assertThrows(IllegalStateException::class.java) {
            requestPrefaceTrackKeys(intArrayOf(87), request = {
                attempts++
                throw InvocationTargetException(SecurityException())
            }, abandon = { error("cannot release") }, diagnostic = {})
        }
        assertEquals(1, attempts)
    }
}
