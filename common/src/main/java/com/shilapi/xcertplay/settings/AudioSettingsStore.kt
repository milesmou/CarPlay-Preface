package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.PREFS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_ADVANCED_AUDIO_CHANNEL_MAPPING
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_AUDIO_FOCUS_ENABLED
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_AUDIO_FOCUS_AUTO_YIELD
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MEDIA_AUDIO_CHANNEL
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_NAVIGATION_AUDIO_CHANNEL
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_NAVIGATION_STREAM_TYPE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MEDIA_BUFFER_MS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_WIRED_MEDIA_BUFFER_MS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MAIN_BUFFERED_AUDIO
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_SPEECH_WITHOUT_8_16_KHZ
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CAR_BLUETOOTH_AUDIO
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CALL_ECHO_CANCELLATION
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CALL_VOICE_FILTER

/** Audio persistence. Retains existing keys, defaults and validation. */
internal object AudioSettingsStore {
    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, true)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, true)

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, enabled)
            .apply()
    }

    /** Opt-out for head units that route physical volume buttons to DiPlay's focus owner. */
    fun loadDisableMediaKeyAudioFocus(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS, false)

    fun saveDisableMediaKeyAudioFocus(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS, disabled)
            .apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, com.shilapi.xcertplay.network.PrefaceE02HeadUnit.MEDIA_STREAM)
            .takeIf { it in AirPlayPersistence.AUDIO_CHANNELS } ?: 0

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { it in AirPlayPersistence.AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Inherit the legacy value only when the new key is absent; explicit automatic/custom routes win.
        val default = com.shilapi.xcertplay.network.PrefaceE02HeadUnit.NAVIGATION_STREAM
        return prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, prefs.getInt(KEY_NAVIGATION_STREAM_TYPE, default))
            .takeIf { it in AirPlayPersistence.AUDIO_CHANNELS } ?: 0
    }

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { it in AirPlayPersistence.AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadMediaBufferMillis(context: Context, wireless: Boolean = true): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shared = prefs.getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS)
        return if (wireless) {
            com.shilapi.xcertplay.media.MediaAudioBuffer.sanitizeWireless(shared)
        } else {
            // Until wired has its own value, keep the delay saved when one setting covered both transports.
            com.shilapi.xcertplay.media.MediaAudioBuffer.sanitizeWired(prefs.getInt(KEY_WIRED_MEDIA_BUFFER_MS, shared))
        }
    }

    /** CarPlay's buffered music (Apple Music sends ahead over TCP); off by default, applies at reconnect. */
    fun loadMainBufferedAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAIN_BUFFERED_AUDIO, false)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    /** Do not offer 8 and 16 kHz PCM for navigation prompts and alerts; applies at reconnect. */
    fun loadSpeechWithout8And16Khz(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEECH_WITHOUT_8_16_KHZ, false)

    fun saveSpeechWithout8And16Khz(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEECH_WITHOUT_8_16_KHZ, enabled).apply()
    }

    /** Offer no CarPlay audio, so the iPhone keeps audio on its Bluetooth link with the car; applies at reconnect. */
    fun loadCarBluetoothAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAR_BLUETOOTH_AUDIO, false)

    fun saveCarBluetoothAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAR_BLUETOOTH_AUDIO, enabled).apply()
    }

    fun saveMediaBufferMillis(context: Context, millis: Int) = saveMediaBufferMillis(context, true, millis)

    fun saveMediaBufferMillis(context: Context, wireless: Boolean, millis: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (wireless) {
            val wired = loadMediaBufferMillis(context, wireless = false)
            prefs.edit()
                // Pin the wired delay first, so it no longer follows the wireless value.
                .putInt(KEY_WIRED_MEDIA_BUFFER_MS, wired)
                .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitizeWireless(millis))
                .apply()
        } else {
            prefs.edit()
                .putInt(KEY_WIRED_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitizeWired(millis))
                .apply()
        }
    }

    /** DiPlay's own experimental echo canceller on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallEchoCancellation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_ECHO_CANCELLATION, false)

    fun saveCallEchoCancellation(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_ECHO_CANCELLATION, enabled).apply()
    }

    /** Experimental bass cut on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallVoiceFilter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_VOICE_FILTER, false)

    fun saveCallVoiceFilter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_VOICE_FILTER, enabled).apply()
    }
}
