package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys
import android.content.Context
import android.content.SharedPreferences.Editor
import org.json.JSONArray
import org.json.JSONObject

data class SettingsImportOutcome(val applied: Int, val skipped: Int, val invalid: Boolean)

/** Reads and writes the settings documents exchanged through the backup web page. */
object SettingsBackupCodec {
    const val FORMAT = "diplay-settings"
    const val VERSION = 1

    /**
     * The settings of the main file that travel, by name. That file also holds the iPhones' pairing keys
     * (`pairing.<id>`), lockdown records, the authentication target and the car's hotspot name and
     * passphrase. An imported file must never set those: a pairing key from someone else's file would make
     * DiPlay trust their device. So a key travels only once it is listed here; car-specific values (the
     * detected screen size, the car's hotspot) and consents (sharing the map with other apps) stay out too.
     */
    private val AIRPLAY_KEYS = setOf(
        AirPlayPreferenceKeys.KEY_CLUSTER_CONTENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MODE,
        AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_X,
        AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
        AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OPACITY,
        AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_X_PERCENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_Y_PERCENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT,
        AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_THEME,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_THEME,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_X,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_Y,
        AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY,
        "cluster_safe_area_1920x720",
        "display_scale_percent", AirPlayPreferenceKeys.KEY_UI_SCALE_PERCENT, AirPlayPreferenceKeys.KEY_HEVC_ENABLED, AirPlayPreferenceKeys.KEY_HEVC_SOFTWARE_DECODER,
        AirPlayPreferenceKeys.KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, AirPlayPreferenceKeys.KEY_AUDIO_FOCUS_ENABLED, AirPlayPreferenceKeys.KEY_AUDIO_FOCUS_AUTO_YIELD,
        AirPlayPreferenceKeys.KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS, AirPlayPreferenceKeys.KEY_MEDIA_AUDIO_CHANNEL, AirPlayPreferenceKeys.KEY_NAVIGATION_AUDIO_CHANNEL,
        AirPlayPreferenceKeys.KEY_WIRELESS_ENABLED,
        AirPlayPreferenceKeys.KEY_APP_APPEARANCE,
        AirPlayPreferenceKeys.KEY_AMBIENT_LUX_THRESHOLD, AirPlayPreferenceKeys.KEY_FPS, AirPlayPreferenceKeys.KEY_MEDIA_BUFFER_MS, AirPlayPreferenceKeys.KEY_MAIN_BUFFERED_AUDIO,
        AirPlayPreferenceKeys.KEY_SPEECH_WITHOUT_8_16_KHZ, AirPlayPreferenceKeys.KEY_CAR_BLUETOOTH_AUDIO, AirPlayPreferenceKeys.KEY_CALL_ECHO_CANCELLATION, AirPlayPreferenceKeys.KEY_CALL_VOICE_FILTER,
        AirPlayPreferenceKeys.KEY_SMOOTH_VIDEO, AirPlayPreferenceKeys.KEY_DIRECT_VIDEO_OUTPUT, AirPlayPreferenceKeys.KEY_LOW_LATENCY_DECODER, AirPlayPreferenceKeys.KEY_MTK_DECODER_TUNING, AirPlayPreferenceKeys.KEY_FPS_COUNTER,
        AirPlayPreferenceKeys.KEY_SETTINGS_GESTURE_FINGERS, AirPlayPreferenceKeys.KEY_WIDTH_PHYSICAL_MM,
        AirPlayPreferenceKeys.KEY_PHYSICAL_SIZE_BASIS, AirPlayPreferenceKeys.KEY_RIGHT_HAND_DRIVE, AirPlayPreferenceKeys.KEY_HIDE_TOP_BAR, AirPlayPreferenceKeys.KEY_HIDE_BOTTOM_BAR,
        AirPlayPreferenceKeys.KEY_SAFE_AREA_DRAW_OUTSIDE, AirPlayPreferenceKeys.KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA,
        AirPlayPreferenceKeys.KEY_AUTO_START_ON_BOOT,
        AirPlayPreferenceKeys.KEY_LOCATION_REPORTING_ENABLED, AirPlayPreferenceKeys.KEY_USB_CHARGING_CURRENT,
    )

    /** Families of layout settings with many keys and nothing sensitive in them. */
    private val AIRPLAY_PREFIXES = listOf("cluster_music_", "cluster_speedometer_", "carplay_night_")
    private val DIPLAY_KEYS = setOf(
        "auto_connect",
        "settings_force_full_layout",
        "app_appearance_button",
        "picture_controls",
        "settings_gesture_fingers",
    )
    private val FILES = mapOf(
        "xcertplay_airplay" to Policy.Allow(AIRPLAY_KEYS, AIRPLAY_PREFIXES),
        "diplay" to Policy.Allow(DIPLAY_KEYS),
        "carplay_picture" to Policy.All,
        "diplay_phone_audio" to Policy.All,
        "diplay_media_wheel" to Policy.Allow(setOf("PREVIOUS", "NEXT", "SIRI")),
    )

    private sealed interface Policy {
        fun includes(key: String): Boolean

        data object All : Policy {
            override fun includes(key: String) = true
        }

        data class Allow(val keys: Set<String>, val prefixes: List<String> = emptyList()) : Policy {
            override fun includes(key: String) = key in keys || prefixes.any(key::startsWith)
        }
    }

    fun export(context: Context, appVersion: String): ByteArray {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app", appVersion)
        root.put("exportedAt", System.currentTimeMillis())
        val files = JSONObject()
        for ((name, policy) in FILES) {
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterKeys { policy.includes(it) }
                .filterValues { it !is ByteArray }
                .mapValues { it.value!! }
            if (values.isEmpty()) continue
            val file = JSONObject()
            for ((key, value) in values) file.put(key, jsonValue(value))
            files.put(name, file)
        }
        root.put("settings", files)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun import(context: Context, document: ByteArray): SettingsImportOutcome {
        val root = runCatching { JSONObject(document.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        if (root.optString("format") != FORMAT || root.optInt("version", Int.MIN_VALUE) != VERSION) {
            return SettingsImportOutcome(0, 0, invalid = true)
        }
        val files = root.optJSONObject("settings")
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        var applied = 0
        var skipped = 0
        for (name in files.keys()) {
            val policy = FILES[name]
            val file = files.optJSONObject(name)
            if (policy == null || file == null) {
                skipped += files.optJSONObject(name)?.length() ?: 1
                continue
            }
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val current = prefs.all
            val editor = prefs.edit()
            for (key in file.keys()) {
                val value = file.opt(key)
                if (!policy.includes(key) || value === JSONObject.NULL) {
                    skipped++
                    continue
                }
                val stored = when (val target = current[key]) {
                    is Boolean -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
                    is Int -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { editor.putInt(key, it.toInt()); true } ?: false
                    is Long -> integral(value)?.let { editor.putLong(key, it); true } ?: false
                    is Float -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
                    is String -> if (value is String) { editor.putString(key, value); true } else false
                    is Set<*> -> stringSet(value)?.let { editor.putStringSet(key, it); true } ?: false
                    null -> inferred(editor, key, value)
                    else -> false
                }
                if (stored) applied++ else skipped++
            }
            editor.apply()
        }
        return SettingsImportOutcome(applied, skipped, invalid = false)
    }

    private fun inferred(editor: Editor, key: String, value: Any?): Boolean = when (value) {
        is Boolean -> { editor.putBoolean(key, value); true }
        is String -> { editor.putString(key, value); true }
        else -> {
            val integral = integral(value)
            when {
                integral != null && integral in Int.MIN_VALUE..Int.MAX_VALUE -> { editor.putInt(key, integral.toInt()); true }
                integral != null -> { editor.putLong(key, integral); true }
                else -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
            }
        }
    }

    private fun integral(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE || double > Long.MAX_VALUE) return null
        return double.toLong()
    }

    private fun decimal(value: Any?): Float? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite()) return null
        val float = double.toFloat()
        return float.takeIf { it.isFinite() }
    }

    private fun stringSet(value: Any?): Set<String>? {
        val array = value as? JSONArray ?: return null
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index) as? String ?: return null
            result.add(element)
        }
        return result
    }

    private fun jsonValue(value: Any): Any = when (value) {
        is Set<*> -> JSONArray().apply { value.forEach { put(it.toString()) } }
        is Float -> value.toDouble()
        else -> value
    }
}
