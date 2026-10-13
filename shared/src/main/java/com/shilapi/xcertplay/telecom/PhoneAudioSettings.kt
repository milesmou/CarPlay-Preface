package com.shilapi.xcertplay.telecom

import android.content.Context

/** Opt-in Android Telecom audio routing, independent of any vehicle vendor. */
object PhoneAudioSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_phone_audio", Context.MODE_PRIVATE)
    fun phoneAudioRoute(context: Context) = prefs(context).getBoolean("carplay", false)
    fun setPhoneAudioRoute(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("carplay", enabled).apply()
    fun phoneAudioRouteApps(context: Context) = prefs(context).getBoolean("apps", false)
    fun setPhoneAudioRouteApps(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("apps", enabled).apply()
}
