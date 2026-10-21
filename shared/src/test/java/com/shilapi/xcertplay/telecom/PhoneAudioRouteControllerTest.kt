package com.shilapi.xcertplay.telecom

import android.media.AudioAttributes
import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PhoneAudioRouteControllerTest {
    private class FakeBackend(var accept: Boolean = true) : PhoneAudioBackend {
        var starts = 0
        var stops = 0
        var active = false

        override fun start(): Boolean {
            starts++
            active = accept
            return accept
        }

        override fun stop() {
            stops++
            active = false
        }

        override fun isActive() = active
    }

    private class FakeScheduler : RouteScheduler {
        class Task(val at: Long, val block: () -> Unit) { var cancelled = false }

        var time = 0L
        var queueExecutions = false
        private val executions = mutableListOf<() -> Unit>()
        private val tasks = mutableListOf<Task>()

        override fun execute(block: () -> Unit) {
            if (queueExecutions) executions += block else block()
        }

        fun runExecutions() {
            val pending = executions.toList()
            executions.clear()
            pending.forEach { it() }
        }

        override fun schedule(delayMillis: Long, block: () -> Unit): () -> Unit {
            val task = Task(time + delayMillis, block)
            tasks += task
            return { task.cancelled = true }
        }

        fun advance(millis: Long) {
            time += millis
            val due = tasks.filter { it.at <= time }
            tasks.removeAll(due)
            due.filterNot { it.cancelled }.forEach { it.block() }
        }
    }

    private val backend = FakeBackend()
    private val scheduler = FakeScheduler()
    private val enabled = mutableSetOf(PhoneAudioReason.CARPLAY_CALL, PhoneAudioReason.VOIP_APP)

    private fun controller() = PhoneAudioRouteController(
        backend = backend,
        scheduler = scheduler,
        enabled = { it in enabled },
        now = { scheduler.time },
    )

    @Test fun aCarPlayCallHoldsTheTelecomCallUntilItHasEnded() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        assertEquals(1, backend.starts)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        assertEquals("the call is kept for a moment", 0, backend.stops)
        scheduler.advance(PhoneAudioRouteController.RELEASE_DELAY_MILLIS)
        assertEquals(1, backend.stops)
    }

    @Test fun bluetoothAudioCallNeverStartsTheAuxiliaryCallOrCarPlayProcessingPolicy() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        route.settingsChanged()
        assertEquals(0, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun enablingTheOptionDuringBluetoothAudioStillDoesNotStartAnAuxiliaryCall() {
        enabled.remove(PhoneAudioReason.CARPLAY_CALL)
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        enabled += PhoneAudioReason.CARPLAY_CALL
        route.settingsChanged()
        assertEquals(0, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun aNewCarPlayAudioCallCanStartAfterABluetoothAudioCall() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false, usesCarBluetoothAudio = true)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = false)
        assertEquals(1, backend.starts)
        assertTrue(route.carPlayCallsUseCarAudio())
    }

    @Test fun switchingToABluetoothAudioSessionReleasesThePreviousAuxiliaryCall() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        scheduler.advance(PhoneAudioRouteController.RELEASE_DELAY_MILLIS)
        assertEquals(1, backend.starts)
        assertEquals(1, backend.stops)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun queuedCarPlayAudioRequestRechecksTheCurrentBluetoothAudioMode() {
        scheduler.queueExecutions = true
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        scheduler.runExecutions()
        assertEquals(0, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun bluetoothAudioDoesNotDisableAnExplicitlyEnabledOtherAppsCall() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true, usesCarBluetoothAudio = true)
        route.setWanted(PhoneAudioReason.VOIP_APP, true)
        assertEquals(1, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
        route.setWanted(PhoneAudioReason.VOIP_APP, false)
        scheduler.advance(PhoneAudioRouteController.RELEASE_DELAY_MILLIS)
        assertEquals(1, backend.stops)
    }

    @Test fun aCallStartingInsideTheReleaseDelayKeepsTheSameTelecomCall() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        scheduler.advance(1_000)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        scheduler.advance(10_000)
        assertEquals(1, backend.starts)
        assertEquals(0, backend.stops)
    }

    @Test fun nothingStartsWhileTheSettingIsOff() {
        enabled.clear()
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        route.setWanted(PhoneAudioReason.VOIP_APP, true)
        assertEquals(0, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun turningTheSettingOffEndsTheTelecomCall() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        enabled.remove(PhoneAudioReason.CARPLAY_CALL)
        route.settingsChanged()
        scheduler.advance(PhoneAudioRouteController.RELEASE_DELAY_MILLIS)
        assertEquals(1, backend.stops)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun turningTheSettingOnDuringACallStartsTheTelecomCall() {
        enabled.clear()
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        enabled += PhoneAudioReason.CARPLAY_CALL
        route.settingsChanged()
        assertEquals(1, backend.starts)
    }

    @Test fun twoReasonsShareOneTelecomCallAndEndTogether() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        route.setWanted(PhoneAudioReason.VOIP_APP, true)
        assertEquals(1, backend.starts)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        scheduler.advance(10_000)
        assertEquals("the other app's call still needs it", 0, backend.stops)
        route.setWanted(PhoneAudioReason.VOIP_APP, false)
        scheduler.advance(PhoneAudioRouteController.RELEASE_DELAY_MILLIS)
        assertEquals(1, backend.stops)
    }

    @Test fun aVoipCallInAnotherAppIgnoresTheCarPlaySetting() {
        enabled.remove(PhoneAudioReason.CARPLAY_CALL)
        val route = controller()
        route.setWanted(PhoneAudioReason.VOIP_APP, true)
        assertEquals(1, backend.starts)
        assertFalse("DiPlay's own canceller stays in charge of CarPlay calls", route.carPlayCallsUseCarAudio())
    }

    @Test fun aRefusedCallLeavesCarPlayCallsToDiPlaysOwnCancellerForAWhile() {
        backend.accept = false
        val route = controller()
        assertTrue(route.carPlayCallsUseCarAudio())
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        assertEquals(1, backend.starts)
        assertFalse(route.carPlayCallsUseCarAudio())
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        assertEquals("no retry inside the back-off", 1, backend.starts)
        scheduler.advance(PhoneAudioRouteController.FAILURE_BACKOFF_MILLIS)
        assertTrue(route.carPlayCallsUseCarAudio())
        backend.accept = true
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, false)
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        assertEquals(2, backend.starts)
    }

    @Test fun aCallTheSystemEndedIsPlacedAgainWhileStillNeeded() {
        val route = controller()
        route.setWanted(PhoneAudioReason.CARPLAY_CALL, true)
        backend.active = false
        route.setWanted(PhoneAudioReason.VOIP_APP, true)
        assertEquals(2, backend.starts)
    }

    @Test fun phoneAudioIsLeftToTheCarOnlyWhileTheCarPlaySettingIsOn() {
        val route = controller()
        assertTrue(route.carPlayCallsUseCarAudio())
        enabled.remove(PhoneAudioReason.CARPLAY_CALL)
        assertFalse(route.carPlayCallsUseCarAudio())
    }

    @Test fun aVoipCallNeedsBothAVoiceCommunicationRecordingAndPlayback() {
        val voice = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val call = AudioAttributes.USAGE_VOICE_COMMUNICATION
        assertTrue(VoipCallWatcher.voipActive(listOf(voice), listOf(call)))
        assertTrue(VoipCallWatcher.voipActive(listOf(MediaRecorder.AudioSource.MIC, voice), listOf(AudioAttributes.USAGE_MEDIA, call)))
        // BYD's own helpers keep recording for as long as the phone state lasts, but play nothing.
        assertFalse(VoipCallWatcher.voipActive(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, voice), emptyList()))
        assertFalse(VoipCallWatcher.voipActive(listOf(voice), listOf(AudioAttributes.USAGE_MEDIA)))
        assertFalse(VoipCallWatcher.voipActive(emptyList(), listOf(call)))
    }

    @Test fun voiceNotesVideoAndAssistantsNeverCountAsAVoipCall() {
        // They record from other sources; the phone state mutes plain MIC, so they must never be routed.
        val call = AudioAttributes.USAGE_VOICE_COMMUNICATION
        assertFalse(VoipCallWatcher.voipActive(listOf(MediaRecorder.AudioSource.MIC), listOf(call)))
        assertFalse(VoipCallWatcher.voipActive(listOf(MediaRecorder.AudioSource.CAMCORDER), listOf(call)))
        assertFalse(VoipCallWatcher.voipActive(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), listOf(call)))
        assertFalse(VoipCallWatcher.voipActive(emptyList(), emptyList()))
    }
}
