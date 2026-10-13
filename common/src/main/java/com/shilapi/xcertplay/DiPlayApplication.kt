package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Keep service notifications and other application-context UI in the same language as activities. */
class DiPlayApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        AppLocale.enforce(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        AppLocale.enforce(this)
    }
}
