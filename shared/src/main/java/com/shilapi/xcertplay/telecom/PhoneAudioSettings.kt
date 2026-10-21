package com.shilapi.xcertplay.telecom

import android.content.Context

/** Preface phone-audio defaults; saved choices take precedence. */
object PhoneAudioSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_phone_audio", Context.MODE_PRIVATE)
    fun phoneAudioRoute(context: Context) = prefs(context).getBoolean("carplay", true)
    fun setPhoneAudioRoute(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("carplay", enabled).apply()
    fun phoneAudioRouteApps(context: Context) = prefs(context).getBoolean("apps", false)
    fun setPhoneAudioRouteApps(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("apps", enabled).apply()
}
