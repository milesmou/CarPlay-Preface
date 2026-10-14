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
    fun compactHomeAndSettingsWithLargeText() = verifyHomeAndSettings("compact", 400, 800)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun fullHomeAndSettingsWithLargeText() = verifyHomeAndSettings("full", 1280, 800)

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-land-xhdpi")
    fun arabicSystemStillShowsChineseWithLargeText() = verifyHomeAndSettings("arabic", 1280, 800)

    private fun verifyHomeAndSettings(name: String, widthDp: Int, heightDp: Int) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val configuration = Configuration(app.resources.configuration).apply { fontScale = 1.4f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        screen.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            ReflectionHelpers.setField(screen, "page", "home")
            render(screen)
            capture(screen, "$name-home", widthDp, heightDp)

            ReflectionHelpers.setField(screen, "page", "settings")
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            render(screen)
            assertFalse(labels(screen.window.decorView).any { it.text == screen.getString(R.string.language_section_title) })
            assertEquals("zh-CN", screen.resources.configuration.locales.toLanguageTags())
            capture(screen, "$name-settings", widthDp, heightDp)
            ReflectionHelpers.setField(screen, "page", "settings")
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
