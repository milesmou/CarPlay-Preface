package com.shilapi.xcertplay

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-sw1333dp-w2666dp-h1225dp-land-mdpi", manifest = Config.NONE)
class InterfaceSizeActivityTest {
    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun pickerAppliesPrefaceOnlyAfterConfirmationAndCanSelect75Percent() {
        val app = RuntimeEnvironment.getApplication()
        InterfaceSize.save(app, 100)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            val original = controller.get()
            assertEquals(240, original.resources.configuration.densityDpi)
            InterfaceSize.showPicker(original)
            val cancelled = ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(cancelled).clickOnItem(InterfaceSize.CHOICES.indexOf(InterfaceSize.PREFACE_12_3))
            cancelled.getButton(android.app.Dialog.BUTTON_NEGATIVE).performClick()
            assertEquals(100, InterfaceSize.preference(original))

            InterfaceSize.showPicker(original)
            val confirmed = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(5, confirmed.listView.count)
            shadowOf(confirmed).clickOnItem(InterfaceSize.CHOICES.indexOf(InterfaceSize.PREFACE_12_3))
            confirmed.getButton(android.app.Dialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(InterfaceSize.PREFACE_12_3, InterfaceSize.preference(controller.get()))
            assertEquals(160, controller.get().resources.configuration.densityDpi)

            InterfaceSize.showPicker(controller.get())
            val smaller = ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(smaller).clickOnItem(InterfaceSize.CHOICES.indexOf(75))
            smaller.getButton(android.app.Dialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(75, InterfaceSize.preference(controller.get()))
            assertEquals(180, controller.get().resources.configuration.densityDpi)
        } finally {
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
        }
    }

    @Test
    @Config(sdk = [28, 33], qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi")
    fun selectedPrefaceSizingIsAppLocalAndTracksASmallerLandscapeWindow() {
        val app = RuntimeEnvironment.getApplication()
        InterfaceSize.save(app, InterfaceSize.PREFACE_12_3)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            assertEquals(160, activity.resources.configuration.densityDpi)
            assertEquals(1920, activity.resources.configuration.screenWidthDp)
            assertEquals(720, activity.resources.configuration.screenHeightDp)
            assertEquals(240, app.resources.configuration.densityDpi)
            assertEquals(1280, app.resources.configuration.screenWidthDp)
            val installed = ReflectionHelpers.getField<Configuration>(activity, "mOverrideConfiguration")
            val reported = Configuration(app.resources.configuration).apply {
                screenWidthDp = 640
                screenHeightDp = 480
                smallestScreenWidthDp = 480
                updateFrom(installed)
            }
            activity.onMultiWindowModeChanged(true, reported)
            activity.onConfigurationChanged(reported)
            assertEquals(160, activity.resources.configuration.densityDpi)
            assertEquals(960, activity.resources.configuration.screenWidthDp)
            assertEquals(720, activity.resources.configuration.screenHeightDp)
            assertEquals(240, app.resources.configuration.densityDpi)
        } finally {
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
        }
    }

    @After
    fun resetInterfaceSize() {
        InterfaceSize.save(RuntimeEnvironment.getApplication(), InterfaceSize.PREFACE_12_3)
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-sw800dp-w1280dp-h800dp-land-mdpi")
    fun fixedScalingKeepsFreshWindowDimensionsAndTheSelectedSettingsCategory() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        InterfaceSize.save(app, 150)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(853, activity.resources.configuration.screenWidthDp)
            assertEquals(160, app.resources.configuration.densityDpi)
            assertEquals(1280, app.resources.configuration.screenWidthDp)

            // ActivityThread merges the context override into the fresh system configuration. A
            // dimension override here would freeze the original 853x533dp window after rotation.
            val installed = ReflectionHelpers.getField<Configuration>(activity, "mOverrideConfiguration")
            fun reported(width: Int, height: Int) = Configuration().apply {
                densityDpi = 160
                screenWidthDp = width
                screenHeightDp = height
                smallestScreenWidthDp = minOf(width, height)
                setLocales(activity.resources.configuration.locales)
                updateFrom(installed)
            }
            assertEquals(0, installed.screenWidthDp)
            assertEquals(0, installed.screenHeightDp)

            activity.onConfigurationChanged(reported(800, 1280))
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(533, activity.resources.configuration.screenWidthDp)
            assertEquals(853, activity.resources.configuration.screenHeightDp)

            val split = reported(480, 800)
            activity.onMultiWindowModeChanged(true, split)
            activity.onConfigurationChanged(split)
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(320, activity.resources.configuration.screenWidthDp)
            assertEquals(533, activity.resources.configuration.screenHeightDp)
            assertEquals(SettingsCategory.DISPLAY,
                ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertEquals(1, texts(activity.findViewById(android.R.id.content)).count {
                it.text.startsWith(activity.getString(R.string.settings_interface_size) + " · ")
            })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-sw800dp-w1280dp-h800dp-land-mdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aRealSystemDensityChangeRecreatesAtTheNewScaledDensityAndKeepsSettings() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        InterfaceSize.save(app, 150)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val installed = ReflectionHelpers.getField<Configuration>(activity, "mOverrideConfiguration")
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(160, app.resources.configuration.densityDpi)

            RuntimeEnvironment.setQualifiers("en-sw400dp-w640dp-h400dp-land-xhdpi")
            assertEquals(320, app.resources.configuration.densityDpi)
            val reported = Configuration(app.resources.configuration).apply { updateFrom(installed) }
            assertEquals(240, reported.densityDpi)
            activity.onConfigurationChanged(reported)
            assertTrue(ReflectionHelpers.getField<Boolean>(activity, "interfaceRecreateRequested"))
            shadowOf(Looper.getMainLooper()).idle()

            val resized = controller.get()
            assertNotSame(activity, resized)
            assertEquals(480, resized.resources.configuration.densityDpi)
            assertEquals(427, resized.resources.configuration.screenWidthDp)
            assertEquals(267, resized.resources.configuration.screenHeightDp)
            assertEquals(320, app.resources.configuration.densityDpi)
            assertEquals("settings", ReflectionHelpers.getField<String>(resized, "page"))
            assertEquals(SettingsCategory.DISPLAY,
                ReflectionHelpers.getField<SettingsCategory>(resized, "settingsCategory"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun texts(root: View): List<TextView> =
        (if (root is TextView) listOf(root) else emptyList()) +
            (if (root is ViewGroup) (0 until root.childCount).flatMap { texts(root.getChildAt(it)) } else emptyList())

    @Test
    fun scaledSettingsStayChineseWithALegacyLanguagePreference() {
        CarPlayBackgroundSession.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay", 0).edit().putString("app_language", "en").commit()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            val configuration = activity.resources.configuration
            assertEquals(1333, configuration.smallestScreenWidthDp)
            assertEquals("zh-CN", configuration.locales.toLanguageTags())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun chineseIsWrittenBackWhenTheSystemLanguageReturns() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(
            android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) },
            app.resources.displayMetrics,
        )
        assertEquals(true, AppLocale.enforce(app))
        assertEquals("zh-CN", app.resources.configuration.locales.toLanguageTags())
        assertEquals(false, AppLocale.enforce(app))
    }
}
