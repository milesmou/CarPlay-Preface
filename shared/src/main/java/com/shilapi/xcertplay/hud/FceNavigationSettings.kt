package com.shilapi.xcertplay.hud

import android.content.Context

object FceNavigationSettings {
    fun enabled(context: Context) = context.getSharedPreferences("diplay_fce_navigation", Context.MODE_PRIVATE)
        .getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences("diplay_fce_navigation", Context.MODE_PRIVATE)
        .edit().putBoolean("enabled", enabled).apply()
    fun available() = FceClusterBridge.available()
}
