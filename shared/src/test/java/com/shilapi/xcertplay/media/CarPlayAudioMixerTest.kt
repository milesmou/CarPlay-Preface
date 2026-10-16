package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [AudioFocusAutoYieldTest.VolumeTrackingAudioTrack::class])
class CarPlayAudioMixerTest {
    private val mixers = mutableListOf<CarPlayAudioMixer>()
    private val tracks = mutableListOf<AudioTrack>()
    private val mixer = CarPlayAudioMixer().also(mixers::add)
    private val volumes get() = AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes
    @Before fun reset() { volumes.clear() }
    @After fun cleanup() { mixers.forEach { it.close() }; tracks.forEach { it.release() }; volumes.clear() }

    @Test fun negotiatedGuidanceDoesNotDuckUntilPcmPlaysAndDrainsBeforeRestore() {
        val media = track(AudioChannel.MEDIA); val guidance = track(AudioChannel.NAVIGATION)
        assertTrue(gains(media).isEmpty())
        mixer.played(guidance, 200)
        assertEquals(listOf(0.3f), gains(media))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1050))
        assertEquals(listOf(0.3f), gains(media))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(0.3f, 1f), gains(media))
        assertTrue(gains(guidance).isEmpty())
    }

    @Test fun overlappingSiriAndPhoneMuteOnlyMusicThenRestoreRemainingGuidance() {
        val media = track(AudioChannel.MEDIA); val guidance = track(AudioChannel.NAVIGATION)
        val phone = track(AudioChannel.PHONE); val siri = track(AudioChannel.ASSISTANT)
        mixer.played(guidance, 2000); mixer.played(phone, 0); mixer.played(siri, 100)
        assertEquals(listOf(0.3f, 0f), gains(media))
        mixer.unregister(phone)
        assertEquals(0f, gains(media).last())
        mixer.unregister(siri)
        assertEquals(0.3f, gains(media).last())
        mixer.unregister(guidance)
        assertEquals(1f, gains(media).last())
        assertTrue(gains(phone).isEmpty()); assertTrue(gains(siri).isEmpty())
    }

    @Test fun closingOneNavigationTrackDoesNotUnDuckAnotherAnnouncement() {
        val media = track(AudioChannel.MEDIA); val first = track(AudioChannel.NAVIGATION); val next = track(AudioChannel.NAVIGATION)
        mixer.played(first, 0); mixer.played(next, 1000)
        mixer.unregister(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(950))
        assertEquals(listOf(0.3f), gains(media))
        mixer.unregister(next)
        assertEquals(listOf(0.3f, 1f), gains(media))
    }

    @Test fun speechDrainDoesNotUndoFocusLossAndNewMusicInheritsSpeechGain() {
        val first = track(AudioChannel.MEDIA); val siri = track(AudioChannel.ASSISTANT)
        mixer.played(siri, 0)
        val next = track(AudioChannel.MEDIA)
        assertEquals(listOf(0f), gains(next))
        mixer.focus(0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(0f), gains(first)); assertEquals(listOf(0f), gains(next))
        mixer.focus(1f)
        assertEquals(listOf(0f, 1f), gains(next))
    }

    @Test fun focusGainCannotRestoreMusicWhileSiriStillSpeaksOrNativeMusicOwnsIt() {
        val media = track(AudioChannel.MEDIA); val siri = track(AudioChannel.ASSISTANT)
        mixer.played(siri, 100)
        mixer.focus(1f)
        assertEquals(listOf(0f), gains(media))
        mixer.nativeMusic(true); mixer.unregister(siri)
        assertEquals(listOf(0f), gains(media))
        mixer.nativeMusic(false)
        assertEquals(listOf(0f, 1f), gains(media))
    }

    @Test fun closedSinkCannotApplyLatePcmOrDrainToRetiredTracks() {
        val old = track(AudioChannel.MEDIA); val nav = track(AudioChannel.NAVIGATION)
        mixer.played(nav, 0); mixer.close()
        val replacement = CarPlayAudioMixer().also(mixers::add)
        val current = track(AudioChannel.MEDIA, replacement)
        mixer.played(nav, 100); mixer.focus(1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(0.3f), gains(old))
        assertTrue(gains(current).isEmpty())
    }

    @Test fun sinkMixingWorksWithRendererFocusDisabled() {
        val coordinator = AudioFocusCoordinator(null, false)
        val media = track(); val nav = track()
        try {
            coordinator.registerOutput(media, AudioChannel.MEDIA, 3)
            coordinator.registerOutput(nav, AudioChannel.NAVIGATION, 12)
            coordinator.onPlayback(nav, 100)
            assertEquals(listOf(1f, 0.3f), gains(media))
            coordinator.release(nav)
            assertEquals(listOf(1f, 0.3f, 1f), gains(media))
        } finally { coordinator.close(); CarPlayAudioOutputs.unregister(media); CarPlayAudioOutputs.unregister(nav) }
    }

    private fun gains(track: AudioTrack) = volumes[track].orEmpty()
    private fun track(role: AudioChannel? = null, target: CarPlayAudioMixer = mixer): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(4096).build().also { tracks.add(it); if (role != null) target.register(it, role) }
}
