package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [AudioFocusAutoYieldTest.VolumeTrackingAudioTrack::class])
class AudioFocusAutoYieldTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = mutableListOf<AudioTrack>()
    private val coordinators = mutableListOf<AudioFocusCoordinator>()

    @Before fun reset() {
        VolumeTrackingAudioTrack.volumes.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    @After fun cleanup() {
        coordinators.forEach { coordinator -> tracks.forEach(coordinator::release) }
        tracks.forEach(AudioTrack::release)
        VolumeTrackingAudioTrack.volumes.clear()
    }

    @Test fun oldUserGainsAreIgnoredAndFocusRestoresFullOutput() {
        context.getSharedPreferences("carplay_audio_volume", 0).edit()
            .putInt("media", 0).putInt("navigation", 25).commit()
        val coordinator = coordinator()
        val media = track(); val navigation = track()
        try {
            coordinator.registerOutput(media, AudioChannel.MEDIA, 3)
            coordinator.registerOutput(navigation, AudioChannel.NAVIGATION, 12)
            coordinator.acquire(media, AudioChannel.MEDIA, attributes)
            assertEquals(listOf(1f), volumes(media))
            assertEquals(listOf(1f), volumes(navigation))
            coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(listOf(1f, 0.3f, 1f), volumes(media))
            assertEquals(listOf(1f), volumes(navigation))
        } finally {
            CarPlayAudioOutputs.unregister(media)
            CarPlayAudioOutputs.unregister(navigation)
            context.getSharedPreferences("carplay_audio_volume", 0).edit().clear().commit()
        }
    }

    @Test fun transientLossMutesOnlyMediaAndGainPreservesUserStreamVolume() {
        val coordinator = coordinator()
        val media = track(); val phone = track(); val assistant = track(); val navigation = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        coordinator.acquire(assistant, AudioChannel.ASSISTANT, attributes)
        coordinator.acquire(navigation, AudioChannel.NAVIGATION, attributes)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val userVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(0f), volumes(media))
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0f, 1f), volumes(media))
        assertTrue(volumes(phone).isEmpty())
        assertTrue(volumes(assistant).isEmpty())
        assertTrue(volumes(navigation).isEmpty())
        assertEquals(userVolume, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun newMediaTrackInheritsLossWhilePhoneTrackRemainsAudible() {
        val coordinator = coordinator()
        val first = track(); val second = track(); val phone = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        assertEquals(listOf(0f), volumes(second))
        assertTrue(volumes(phone).isEmpty())
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0f, 1f), volumes(first))
        assertEquals(listOf(0f, 1f), volumes(second))
    }

    @Test fun duckingAlsoAppliesOnlyToMediaAndNewTracksInheritIt() {
        val coordinator = coordinator()
        val first = track(); val second = track(); val phone = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        assertEquals(listOf(0.3f), volumes(first))
        assertEquals(listOf(0.3f), volumes(second))
        assertTrue(volumes(phone).isEmpty())
    }

    @Test fun optionOffKeepsTransientAudioAndFocusOffIgnoresForwardedEvents() {
        val optionOff = coordinator(autoYield = false)
        val first = track()
        optionOff.acquire(first, AudioChannel.MEDIA, attributes)
        optionOff.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(volumes(first).isEmpty())
        val focusOff = coordinator(enabled = false)
        val second = track()
        focusOff.acquire(second, AudioChannel.MEDIA, attributes)
        focusOff.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusOff.onExternalFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(volumes(second).isEmpty())
    }

    @Test fun lastTrackReleaseClearsLossAndRejectsOldRequestCallbacks() {
        val coordinator = coordinator()
        val first = track(); val second = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        val oldListener = coordinator.listener
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        coordinator.release(first)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(0f), volumes(first))
        assertTrue(volumes(second).isEmpty())
    }

    @Test fun droppedQueuedFocusCallbackReportsWhyWithoutChangingNewMedia() {
        val diagnostics = mutableListOf<String>()
        val coordinator = AudioFocusCoordinator(context, true, true, diagnostics::add).also(coordinators::add)
        val first = track(); val second = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        val oldListener = coordinator.listener
        coordinator.release(first)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(diagnostics.last().contains("dropped stale=true noRequest=true activeTracks=0"))
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(diagnostics.last().contains("dropped stale=true noRequest=false activeTracks=1"))
        assertTrue(volumes(second).isEmpty())
    }

    @Test fun failedReplacementRequestDoesNotRestoreMutedMedia() {
        val coordinator = coordinator()
        val first = track(); val phone = track(); val second = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        coordinator.release(first)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        assertEquals(listOf(0f), volumes(second))
        assertTrue(volumes(phone).isEmpty())
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0f, 1f), volumes(second))
    }

    @Test fun sinkFocusTeardownRejectsLateWorkerAcquisitionAndQueuedCallbacks() {
        val coordinator = coordinator()
        val first = track(); val late = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        val oldListener = coordinator.listener
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        coordinator.close()
        val abandoned = shadowOf(manager).lastAbandonedAudioFocusRequest
        coordinator.close()
        coordinator.acquire(late, AudioChannel.MEDIA, attributes)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertSame(abandoned, shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(listOf(0f), volumes(first))
        assertTrue(volumes(late).isEmpty())
    }

    @Test fun confirmedNativeMusicYieldsMediaButRetainsPhoneAndRestoresTheSameMediaTrack() {
        val owners = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(context, true, onOwnershipChanged = owners::add).also(coordinators::add)
        val media = track(); val phone = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        val oldListener = coordinator.listener
        coordinator.setNativeBluetoothPlaying(true)
        assertEquals(listOf(0f), volumes(media))
        assertEquals(listOf(true, false), owners)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        assertTrue(volumes(phone).isEmpty())
        assertEquals(true, owners.last())
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0f), volumes(media))
        coordinator.setNativeBluetoothPlaying(false)
        assertEquals(listOf(0f, 1f), volumes(media))
        coordinator.close()
        assertEquals(false, owners.last())
    }

    @Test fun refusedFocusNeverClaimsBluetoothMusicOwnership() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val owners = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(context, true, onOwnershipChanged = owners::add).also(coordinators::add)
        coordinator.acquire(track(), AudioChannel.MEDIA, attributes)
        assertTrue(owners.isEmpty())
        coordinator.close()
        assertTrue(owners.isEmpty())
    }

    @Test fun duckingRetainsMediaOwnershipButPermanentLossMutesUntilAnActualGain() {
        val owners = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(context, true, onOwnershipChanged = owners::add).also(coordinators::add)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(listOf(true), owners)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf(true, false), owners)
        assertEquals(listOf(0.3f, 0f), volumes(media))
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(true, false, true), owners)
        assertEquals(listOf(0.3f, 0f, 1f), volumes(media))
    }

    @Test fun savedMediaFocusOptOutStillAllowsTransientVoiceFocus() {
        val owners = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(context, true, onOwnershipChanged = owners::add,
            mediaFocusEnabled = false).also(coordinators::add)
        coordinator.acquire(track(), AudioChannel.MEDIA, attributes)
        assertTrue(owners.isEmpty())
        coordinator.acquire(track(), AudioChannel.PHONE, attributes)
        assertEquals(listOf(true), owners)
        coordinator.close()
    }

    @Test fun nativeCallBlocksMusicFocusUntilCallEndsAndCloseCancelsTheRetry() {
        val owners = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(context, true, onOwnershipChanged = owners::add).also(coordinators::add)
        manager.mode = AudioManager.MODE_IN_CALL
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        assertTrue(owners.isEmpty())
        assertEquals(listOf(0f), volumes(media))
        manager.mode = AudioManager.MODE_NORMAL
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertEquals(listOf(true), owners)
        assertEquals(listOf(0f, 1f), volumes(media))
        coordinator.close()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(listOf(true, false), owners)
    }

    private fun coordinator(enabled: Boolean = true, autoYield: Boolean = true) =
        AudioFocusCoordinator(context, enabled, autoYield).also(coordinators::add)
    private fun track(): AudioTrack = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)
    private fun volumes(track: AudioTrack): List<Float> = VolumeTrackingAudioTrack.volumes[track].orEmpty()

    @Implements(AudioTrack::class)
    class VolumeTrackingAudioTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            volumes.getOrPut(track) { mutableListOf() }.add(left)
            return AudioTrack.SUCCESS
        }
        companion object { val volumes = linkedMapOf<AudioTrack, MutableList<Float>>() }
    }
}
