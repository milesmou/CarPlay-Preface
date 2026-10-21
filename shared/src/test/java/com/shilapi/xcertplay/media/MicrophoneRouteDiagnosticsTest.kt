package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class MicrophoneRouteDiagnosticsTest {
    @Test fun pollsAtMostOnceASecondAndReportsOnlyChangesPlusStartAndEnd() {
        var now = 0L
        var queries = 0
        var state = MicrophoneRouteState(mode = AudioManager.MODE_IN_COMMUNICATION)
        val reports = mutableListOf<String>()
        val diagnostics = MicrophoneRouteDiagnostics("telephony", { queries++; state }, reports::add) { now }
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.START)
        repeat(100_000) {
            now += 1_000
            diagnostics.sample(MicrophoneRouteDiagnostics.Stage.POLL)
        }
        assertEquals(1, queries)
        now = 1_000_000_000
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.POLL)
        assertEquals(2, queries)
        assertEquals(1, reports.size)
        state = state.copy(mode = AudioManager.MODE_IN_CALL, micMuted = true, softwareAec = true)
        now = 2_000_000_000
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.POLL)
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.END)
        assertEquals(4, queries)
        assertEquals(3, reports.size)
        assertTrue(reports[1].contains("stage=POLL mode=2 micMuted=true"))
        assertTrue(reports[1].contains("softwareAec=true"))
        assertTrue(reports.last().contains("stage=END"))
    }

    @Test fun observerFailuresCannotEscapeAndOutputContainsOnlyAllowlistedValues() {
        var fail = true
        val reports = mutableListOf<String>()
        val diagnostics = MicrophoneRouteDiagnostics("PRIVATE_PHONE_NAME\naddress=secret", {
            throw SecurityException("PRIVATE_PHONE_NUMBER")
        }, {
            if (fail) throw IllegalStateException("diagnostic export unavailable")
            reports += it
        }) { 0 }
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.START)
        fail = false
        diagnostics.sample(MicrophoneRouteDiagnostics.Stage.END)
        val line = reports.single()
        assertTrue(line.startsWith("Microphone: route type=other stage=END"))
        assertTrue(line.contains("configuration=UNAVAILABLE"))
        assertFalse(line.contains("PRIVATE"))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("SecurityException"))
        assertTrue(line.length < 700 && '\n' !in line)
    }

    @Test fun readsCurrentPermissionAndModeWithoutChangingAudioState() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        manager.mode = AudioManager.MODE_IN_CALL
        manager.isMicrophoneMute = true
        val granted = readMicrophoneRouteState(null, context, manager, false, emptyList())
        assertEquals(true, granted.permissionGranted)
        assertEquals(AudioManager.MODE_IN_CALL, granted.mode)
        assertEquals(true, granted.micMuted)
        assertEquals(MicrophoneRouteState.Configuration.NOT_EXPOSED, granted.configuration)
        assertNull(granted.clientSilenced)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(false, readMicrophoneRouteState(null, context, manager, false, emptyList()).permissionGranted)
        assertEquals(AudioManager.MODE_IN_CALL, manager.mode)
        assertTrue(manager.isMicrophoneMute)
    }

    @Test fun attributesConfigurationOnlyToOurPositiveSessionAndGuardsApi29SilenceState() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val audio = shadowOf(manager)
        val recorder = Shadow.newInstanceOf(AudioRecord::class.java)
        ReflectionHelpers.setField(recorder, "mSessionId", 77)
        val own = audio.createActiveRecordingConfiguration(77, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "PRIVATE_OWN_APP")
        val foreign = audio.createActiveRecordingConfiguration(88, MediaRecorder.AudioSource.MIC, "PRIVATE_OTHER_APP")
        val anonymous = audio.createActiveRecordingConfiguration(0, MediaRecorder.AudioSource.MIC, "PRIVATE_ANONYMOUS_APP")
        audio.setActiveRecordingConfigurations(listOf(foreign, anonymous, own), false)
        val state = readMicrophoneRouteState(recorder, context, manager, false, emptyList())
        assertEquals(MicrophoneRouteState.Configuration.MATCHED, state.configuration)
        assertEquals(own.format.sampleRate, state.deviceRate)
        if (Build.VERSION.SDK_INT >= 29) assertEquals(false, state.clientSilenced) else assertNull(state.clientSilenced)
        audio.setActiveRecordingConfigurations(listOf(foreign, anonymous), false)
        val missing = readMicrophoneRouteState(recorder, context, manager, false, emptyList())
        assertEquals(MicrophoneRouteState.Configuration.NOT_EXPOSED, missing.configuration)
        assertNull(missing.deviceRate)
        assertNull(missing.clientSilenced)
        ReflectionHelpers.setField(recorder, "mSessionId", 0)
        assertEquals(MicrophoneRouteState.Configuration.NOT_EXPOSED,
            readMicrophoneRouteState(recorder, context, manager, false, emptyList()).configuration)
        val reports = mutableListOf<String>()
        MicrophoneRouteDiagnostics("telephony", { state }, reports::add).sample(MicrophoneRouteDiagnostics.Stage.START)
        assertFalse(reports.single().contains("PRIVATE"))
    }

    @Test @Config(shadows = [RejectingRecordingQuery::class])
    fun deniedConfigurationQueryDoesNotHideOtherPublicState() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_CALL
        val state = readMicrophoneRouteState(null, context, manager, false, emptyList())
        assertEquals(AudioManager.MODE_IN_CALL, state.mode)
        assertEquals(MicrophoneRouteState.Configuration.UNAVAILABLE, state.configuration)
        assertNull(state.deviceRate)
        assertEquals(AudioManager.MODE_IN_CALL, manager.mode)
    }

    @Implements(AudioManager::class)
    class RejectingRecordingQuery : ShadowAudioManager() {
        @Implementation override fun getActiveRecordingConfigurations(): List<AudioRecordingConfiguration> =
            throw SecurityException("PRIVATE_OTHER_APP")
    }
}
