package com.shilapi.xcertplay.setup

import android.content.Context

/** Shared connection setup for the Xingrui E02 branch. */
object SetupGuide {
    const val STEP_CONNECTION = 0
    const val STEP_IPHONE = 1
    const val STEP_FEATURES = 2
    const val STEP_DONE = 3
    const val STEP_COUNT = 4
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun seen(context: Context): Boolean = prefs(context).getBoolean("setup_guide_seen", false)
    fun markSeen(context: Context) { prefs(context).edit().putBoolean("setup_guide_seen", true).apply() }
    fun shouldOpenOnLaunch(seen: Boolean, phoneChosen: Boolean): Boolean = !seen && !phoneChosen
}
