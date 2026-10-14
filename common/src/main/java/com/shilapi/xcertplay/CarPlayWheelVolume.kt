package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayAudioOutputs
import com.shilapi.xcertplay.media.NavigationPlayback

/** A known iPhone pause wins over buffered PCM; unknown status uses recent actual media output. */
internal fun wheelVolumeNavigation(iphonePlaying: Boolean?, mediaOutputActive: Boolean): Boolean =
    !(iphonePlaying ?: mediaOutputActive)

internal object CarPlayWheelVolume {
    fun adjust(context: Context, delta: Int): Boolean {
        if (!CarPlayMediaKeys.volumeKeysEligible(context)) return false
        val playback = NavigationPlayback.snapshot()
        val navigation = wheelVolumeNavigation(CarPlayMediaKeys.iphoneMediaPlaying(), playback.mediaActive)
        val mediaStream = AirPlayPersistence.loadMediaAudioChannel(context)
        val configured = if (navigation) AirPlayPersistence.loadNavigationAudioChannel(context) else mediaStream
        val active = if (navigation) playback.active else playback.mediaActive
        val actual = if (navigation) playback.legacyStreamType else playback.mediaLegacyStreamType
        val liveRoute = CarPlayAudioOutputs.route(navigation)
        val stream = if (liveRoute != null) liveRoute.legacyStreamType else if (active) actual else configured.takeIf { it != 0 }
        // Unknown automatic/fallback routes use the media stream. Shared routes intentionally
        // share the head unit volume; there is no separate CarPlay output gain.
        val systemStream = stream ?: AudioManager.STREAM_MUSIC
        if (systemStream !in 1..20) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            val minimum = navigationWheelMinimum(systemStream, android.os.Build.VERSION.SDK_INT) {
                if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(it) else 0
            }
            val target = navigationWheelTarget(audio.getStreamVolume(systemStream), minimum, audio.getStreamMaxVolume(systemStream), delta)
                ?: return false
            if (!CarPlayMediaKeys.volumeKeysEligible(context)) return false
            audio.setStreamVolume(systemStream, target, AudioManager.FLAG_SHOW_UI)
            (audio.getStreamVolume(systemStream) == target).also {
                Log.d("DiPlay-MediaKeys", "wheel volume navigation=$navigation stream=$systemStream target=$target applied=$it")
            }
        } catch (error: Exception) {
            Log.w("DiPlay-MediaKeys", "wheel volume stream=$systemStream unavailable; retaining vehicle handling", error)
            false
        }
    }
}
