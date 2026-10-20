package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearLegacyPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun everySystemLanguageAndLegacyChoiceUsesSimplifiedChinese() {
        for (tag in listOf("en", "es", "ja", "zh-TW")) {
            context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putString("app_language", tag).commit()
            val system = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
                setLayoutDirection(Locale.forLanguageTag(tag))
                fontScale = 1.4f
                densityDpi = 320
            })
            val wrapped = AppLocale.wrap(system)
            assertEquals("zh-CN", wrapped.resources.configuration.locales.toLanguageTags())
            assertEquals(View.LAYOUT_DIRECTION_LTR, wrapped.resources.configuration.layoutDirection)
            assertEquals("连接", wrapped.getString(R.string.connection))
            assertEquals(1.4f, wrapped.resources.configuration.fontScale, 0.001f)
            assertEquals(320, wrapped.resources.configuration.densityDpi)
            assertEquals(tag, system.resources.configuration.locales[0].toLanguageTag())
        }
    }

    @Test @Config(sdk = [33])
    fun anExistingAndroidAppLanguageCannotChangeTheWrappedUi() {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        try {
            val wrapped = AppLocale.wrap(context)
            assertEquals("zh-CN", wrapped.resources.configuration.locales.toLanguageTags())
            assertEquals("连接", wrapped.getString(R.string.connection))
        } finally { manager.applicationLocales = LocaleList.getEmptyLocaleList() }
    }

    @Test fun headUnitConfigurationResetIsCorrectedWithoutChangingFontOrDensity() {
        val reset = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale("en"))
            setLayoutDirection(Locale("en"))
            fontScale = 1.4f
            densityDpi = 480
        })
        assertTrue(AppLocale.enforce(reset))
        assertEquals("zh-CN", reset.resources.configuration.locales.toLanguageTags())
        assertEquals(View.LAYOUT_DIRECTION_LTR, reset.resources.configuration.layoutDirection)
        assertEquals(1.4f, reset.resources.configuration.fontScale, 0.001f)
        assertEquals(480, reset.resources.configuration.densityDpi)
        assertFalse(AppLocale.enforce(reset))
    }
}
