package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.SetupGuide
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrefaceSetupUiTest {
    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
    fun compactSetupStartsAtConnectionWithLargeText() = verifySetup("compact", 400, 800)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun fullSetupStartsAtConnectionWithLargeText() = verifySetup("full", 1280, 800)

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-land-xhdpi")
    fun arabicSystemStillShowsChineseWithLargeText() = verifySetup("arabic", 1280, 800)

    private fun verifySetup(name: String, widthDp: Int, heightDp: Int) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val configuration = Configuration(app.resources.configuration).apply { fontScale = 1.4f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        screen.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            ReflectionHelpers.setField(screen, "page", "setup")
            ReflectionHelpers.setField(screen, "setupStep", SetupGuide.STEP_CONNECTION)
            render(screen)
            val decor = screen.window.decorView
            assertTrue(labels(decor).any { it.text == screen.getString(R.string.setup_connection_title) })
            assertTrue(labels(decor).any { it.text == screen.getString(R.string.setup_step_of, 1, 4) })
            assertFalse(labels(decor).any { it.text.toString().contains("DiLink") })
            capture(screen, name, widthDp, heightDp)

            // Advancing from the first page follows the remaining setup pages, with no vehicle step.
            for ((step, title) in listOf(
                SetupGuide.STEP_IPHONE to R.string.setup_iphone_title,
                SetupGuide.STEP_FEATURES to R.string.setup_features_title,
                SetupGuide.STEP_DONE to R.string.setup_done_title,
            )) {
                labels(screen.window.decorView).single { it.text == screen.getString(R.string.setup_next) }.performClick()
                assertEquals(step, ReflectionHelpers.getField<Int>(screen, "setupStep"))
                assertTrue(labels(screen.window.decorView).any { it.text == screen.getString(title) })
                assertFalse(labels(screen.window.decorView).any { it.text.toString().contains("DiLink") })
            }
            ReflectionHelpers.setField(screen, "page", "settings")
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            render(screen)
            assertFalse(labels(screen.window.decorView).any { it.text == screen.getString(R.string.language_section_title) })
            assertEquals("zh-CN", screen.resources.configuration.locales.toLanguageTags())
            capture(screen, "$name-settings", widthDp, heightDp)
            for (category in listOf(SettingsCategory.EXPERIMENTAL, SettingsCategory.VEHICLE, SettingsCategory.AUDIO)) {
                ReflectionHelpers.setField(screen, "settingsCategory", category)
                render(screen)
                capture(screen, "$name-${category.name.lowercase()}", widthDp, heightDp)
            }
        } finally {
            screen.finish()
            app.getSharedPreferences("diplay", 0).edit().clear().commit()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    private fun render(screen: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(screen: DiPlayActivity, name: String, widthDp: Int, heightDp: Int) {
        val decor = screen.window.decorView
        val density = screen.resources.displayMetrics.density
        decor.measure(View.MeasureSpec.makeMeasureSpec(Math.round(widthDp * density), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Math.round(heightDp * density), View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        if (name.endsWith("-vehicle")) {
            val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            scroll.scrollTo(0, scroll.getChildAt(0).height)
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val module = Paths.get(System.getProperty("user.dir")).toAbsolutePath().let {
            if (it.fileName.toString() == "common") it else it.resolve("common")
        }
        val output = module.resolve("build/reports/preface-setup/$name.png")
        try {
            decor.draw(Canvas(bitmap))
            Files.createDirectories(output.parent)
            Files.newOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun labels(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(labels(view.getChildAt(index)))
    }
}
