// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.util.Locale

/** This branch always uses Simplified Chinese, including on head units with another system language. */
object AppLocale {
    private fun configuration(context: Context) = Configuration(context.resources.configuration).apply {
        setLocale(Locale.SIMPLIFIED_CHINESE)
        setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
    }

    fun wrap(context: Context): Context = context.createConfigurationContext(configuration(context))

    /** Some head units restore the system configuration after attaching the activity context. */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        val resources = context.resources
        if (resources.configuration.locales[0] == Locale.SIMPLIFIED_CHINESE &&
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) return false
        resources.updateConfiguration(configuration(context), resources.displayMetrics)
        return true
    }
}
