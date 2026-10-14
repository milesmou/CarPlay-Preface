package com.shilapi.xcertplay

import android.content.Context

/** E02 volume routing is enabled by default; previously saved choices and grants are retained. */
object NavigationWheelSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
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
