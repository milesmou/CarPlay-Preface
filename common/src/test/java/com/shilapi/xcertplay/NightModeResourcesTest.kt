package com.shilapi.xcertplay

import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NightModeResourcesTest {
    private fun context(tag: String) = RuntimeEnvironment.getApplication().let { base ->
        AppLocale.wrap(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }))
    }

    @Test fun customSettingsStayChineseWithEverySystemLocale() {
        val expected = listOf("en", "zh-CN", "zh-TW", "ru", "es", "uk").associateWith { "切换延迟" }
        for ((tag, title) in expected) {
            val c = context(tag)
            assertEquals(tag, title, c.getString(R.string.ambient_delay_title))
            assertFalse(c.getString(R.string.carplay_night_time_note).isBlank())
            assertTrue(c.getString(R.string.ambient_light_threshold_summary, 30).endsWith(" lux"))
            assertFalse(c.getString(R.string.ambient_delay_title).contains("("))
        }
    }

    @Test fun traditionalAndEnglishSystemsBothUseCompleteSimplifiedChineseCopy() {
        val c = context("zh-TW")
        // Both standard and custom settings use the same fixed locale.
        val baselineChinese = c.getString(R.string.resolution).any { it.code in 0x4e00..0x9fff }
        val customChinese = c.getString(R.string.ambient_delay_title).any { it.code in 0x4e00..0x9fff }
        assertEquals(baselineChinese, customChinese)
        val english = context("en")
        assertEquals("切换延迟", english.getString(R.string.ambient_delay_title))
        assertEquals(context("zh-CN").getString(R.string.ambient_delay_summary, 2), english.getString(R.string.ambient_delay_summary, 2))
    }
}
