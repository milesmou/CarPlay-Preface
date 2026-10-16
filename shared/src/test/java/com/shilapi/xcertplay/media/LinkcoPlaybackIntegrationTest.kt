package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject

/** Exercises renderer -> registered output -> playback hint -> mixer, including a call on the media bus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [LinkcoPlaybackIntegrationTest.TestTrack::class])
class LinkcoPlaybackIntegrationTest {
    private val coordinator = AudioFocusCoordinator(null, false)
    private val renderers = mutableListOf<Any>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @After fun cleanup() {
        renderers.forEach { call(it, "release") }
        coordinator.close()
        AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes.clear()
    }

    @Test fun navigationAndCallOnMediaBusArbitrateTheActualRendererOutputs() {
        val media = renderer("media")
        val music = track(media)
        val nav = renderer("default")
        call(nav, "startPlayback", track(nav))
        assertEquals(0.3f, gain(music))
        val phone = renderer("telephony", speaker = true)
        call(phone, "startPlayback", track(phone))
        assertEquals(0f, gain(music))
        assertTrue(NavigationPlayback.snapshot().priorityVoiceActive)
        call(phone, "release")
        assertEquals(0.3f, gain(music))
        call(nav, "release")
        assertEquals(1f, gain(music))
    }

    @Test fun idleSiriTrackDoesNotMuteMusicButActualSiriPlaybackDoes() {
        val media = renderer("media")
        val siri = renderer("speechrecognition")
        assertEquals(1f, gain(track(media)))
        call(siri, "startPlayback", track(siri))
        assertEquals(0f, gain(track(media)))
        call(siri, "release")
        assertEquals(1f, gain(track(media)))
    }

    private fun renderer(role: String, speaker: Boolean = false): Any {
        val renderer = type.declaredConstructors.single { it.parameterCount == 12 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, role),
                false, false, 3, 12, coordinator, 12, 500, { _: String -> }, speaker, null, false)
        renderers.add(renderer)
        call(renderer, "createTrack")
        return renderer
    }
    private fun call(renderer: Any, method: String, track: AudioTrack? = null) {
        if (track == null) type.getDeclaredMethod(method).apply { isAccessible = true }.invoke(renderer)
        else type.getDeclaredMethod(method, AudioTrack::class.java).apply { isAccessible = true }.invoke(renderer, track)
    }
    private fun track(renderer: Any) = type.getDeclaredField("track").apply { isAccessible = true }.get(renderer) as AudioTrack
    private fun gain(track: AudioTrack) = AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes[track]?.lastOrNull() ?: 1f

    @Implements(AudioTrack::class)
    class TestTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun getState() = AudioTrack.STATE_INITIALIZED
        @Implementation fun getBufferSizeInFrames() = 8192
        @Implementation fun play() {}
        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes.getOrPut(track) { mutableListOf() }.add(left)
            return AudioTrack.SUCCESS
        }
        companion object {
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, encoding: Int) = 4096
        }
    }
}
