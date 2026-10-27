package com.shilapi.xcertplay.vehicle

import android.content.Context

/** Instrument output, sampled by each new CarPlay controller; saved choices take precedence. */
object PrefaceInstrumentSettings {
    const val PREFERENCES = "diplay_instrument_navigation"
    const val ENABLED = "enabled"
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }
}
