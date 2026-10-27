package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.logging.DiagnosticLevel
import com.shilapi.xcertplay.logging.LogPolicy
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagnosticBuildPolicyTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var target: SessionLogFile
    private lateinit var listener: AirPlaySessionListener

    @Before fun prepare() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.javaClass.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(activity)
        target = activity.javaClass.getDeclaredField("sessionLog").apply { isAccessible = true }
            .get(activity) as SessionLogFile
        activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.set(activity, 2)
        listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 2) as AirPlaySessionListener
    }

    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        target.close()
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (activity.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun listenerFiltersDetailsButRetainsLifecycleAndFailureEvidence() {
        listener.onDebugLog("protocol receive counter=123")
        listener.onDiagnosticLog(DiagnosticLevel.EVENT, "Connection state=RunningControl")
        listener.onDiagnosticLog(DiagnosticLevel.WARNING, "audio fallback reason=unavailable")
        listener.onDiagnosticLog(DiagnosticLevel.ERROR, "instrument navigation stopped type=SecurityException")
        val text = target.file.readText()
        assertEquals(LogPolicy.detailedEnabled, text.contains("protocol receive counter=123"))
        assertTrue(text.contains("Connection state=RunningControl"))
        assertTrue(text.contains("audio fallback reason=unavailable"))
        assertTrue(text.contains("instrument navigation stopped type=SecurityException"))
        assertTrue(text.contains(if (LogPolicy.detailedEnabled) "logging=debug-detailed" else "logging=release-events"))
    }

    @Test fun openSettingsDoesNotDiscardRetainedFailures() {
        activity.javaClass.getDeclaredField("menuOpen").apply { isAccessible = true }.set(activity, true)
        listener.onDiagnosticLog(DiagnosticLevel.ERROR, "connection failed while settings open")
        assertTrue(target.file.readText().contains("connection failed while settings open"))
    }

    @Test fun queuedAndDirectFileWritersShareThePolicyAndStillRedactSecrets() {
        target.append("file decoder counter=1", DiagnosticLevel.DEBUG)
        target.append("file connection failed", DiagnosticLevel.ERROR)
        AsyncDiagnosticLog.append(target, "queued packet counter=2", 0, DiagnosticLevel.DEBUG)
        AsyncDiagnosticLog.append(target, "queued microphone failure", 0, DiagnosticLevel.WARNING)
        AsyncDiagnosticLog.append(target, "password=private-value", 0, DiagnosticLevel.ERROR)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val text = target.file.readText()
        assertEquals(LogPolicy.detailedEnabled, text.contains("file decoder counter=1"))
        assertEquals(LogPolicy.detailedEnabled, text.contains("queued packet counter=2"))
        assertTrue(text.contains("file connection failed"))
        assertTrue(text.contains("queued microphone failure"))
        assertFalse(text.contains("private-value"))
    }

    @Test fun anOldSessionCannotWriteRetainedMessagesIntoTheNewSession() {
        val old = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1) as AirPlaySessionListener
        old.onDiagnosticLog(DiagnosticLevel.ERROR, "old controller connection failed")
        assertFalse(target.file.readText().contains("old controller connection failed"))
    }
}
