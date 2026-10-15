package com.shilapi.xcertplay

import android.content.Context

/** E02 volume routing is enabled by default; previously saved choices and grants are retained. */
object NavigationWheelSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    internal fun saveForNextConnection(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        WheelKeyService.settingsChanged()
        if (enabled) WheelKeyService.restoreIfNeeded(context)
    }
    internal fun recordAuthorization(context: Context) {
        prefs(context).edit().putBoolean("authorized", true).apply()
    }
    internal fun restoreAllowed(context: Context): Boolean =
        enabled(context) && prefs(context).getBoolean("authorized", false)
}

/** One choice represents the three valid combinations of the existing saved settings. */
internal enum class MusicWheelMode {
    CARPLAY_ADAPTIVE_VOLUME, CARPLAY_SYSTEM_VOLUME, CAR_BLUETOOTH;

    companion object {
        fun load(context: Context): MusicWheelMode = when {
            AirPlayPersistence.loadCarBluetoothAudio(context) -> CAR_BLUETOOTH
            NavigationWheelSettings.enabled(context) -> CARPLAY_ADAPTIVE_VOLUME
            else -> CARPLAY_SYSTEM_VOLUME
        }

        fun save(context: Context, mode: MusicWheelMode) {
            AirPlayPersistence.saveCarBluetoothAudio(context, mode == CAR_BLUETOOTH)
            if (mode != CAR_BLUETOOTH) NavigationWheelSettings.saveForNextConnection(context,
                mode == CARPLAY_ADAPTIVE_VOLUME)
        }
    }
}
