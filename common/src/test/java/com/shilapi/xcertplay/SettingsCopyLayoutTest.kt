package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
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

/** These are unit-test renders with simulated component state, not installed-APK screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCopyLayoutTest {
    @Test fun fullLargeFont() = capture("full")

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun compactLargeFont() = capture("compact")


    private fun capture(profile: String) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        if (profile == "compact") InterfaceSize.save(app, 100)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).create()
        try {
            val screen = controller.get()
            screen.resources.updateConfiguration(Configuration(screen.resources.configuration).apply {
                fontScale = 1.5f
            }, screen.resources.displayMetrics)
            ReflectionHelpers.setField(screen, "setupError", null)
            screen.authorizationComponentState = { AuthorizationComponentInstaller.State.INSTALLABLE }
            val categories = SettingsCategory.entries
            var separatedControls = 0
            for (category in categories) {
                ReflectionHelpers.setField(screen, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val frame = screen.findViewById<ViewGroup>(android.R.id.content)

                repeat(3) {
                    descendants(frame).forEach { it.forceLayout() }
                    frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                    frame.layout(0, 0, 1920, 720)
                    shadowOf(Looper.getMainLooper()).idle()
                }
                assertEquals(Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
                assertEquals(if (profile == "compact") 320 else 160, screen.resources.displayMetrics.densityDpi)
                assertEquals(1.5f, screen.resources.configuration.fontScale, 0.001f)
                val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
                val density = screen.resources.displayMetrics.density
                for (parent in descendants(scroll).filterIsInstance<LinearLayout>().filter { it.orientation == LinearLayout.VERTICAL }) {
                    if (generateSequence(parent as View) { it.parent as? View }.any { it.visibility == View.GONE }) continue
                    for (index in 0 until parent.childCount - 1) {
                        val current = parent.getChildAt(index)
                        val next = parent.getChildAt(index + 1)
                        if (current.visibility == View.GONE || next.visibility == View.GONE) continue
                        if (isControl(current) && isControl(next)) {
                            assertEquals("$category: ${(current as? TextView)?.text ?: current.javaClass.simpleName} -> ${(next as? TextView)?.text ?: next.javaClass.simpleName}", Math.round(20 * density), next.top - current.bottom)
                            separatedControls++
                        } else if (isControl(current) && next is TextView && next !is Button) {
                            assertEquals("Keep help with its control", Math.round(6 * density), next.top - current.bottom)
                        }
                    }
                }
                if (category == SettingsCategory.AUDIO) assertEquals("One shared buffer hint", 1,
                    descendants(scroll).filterIsInstance<TextView>().count { it.text == screen.getString(R.string.settings_music_buffer_next_connection) })
                for ((part, y) in listOf("top" to 0, "bottom" to scroll.getChildAt(0).height)) {
                    scroll.scrollTo(0, y)
                    val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                    frame.draw(Canvas(bitmap))
                    val directory = File("../agent_docs/screenshots/settings-concise-copy").apply { mkdirs() }
                    File(directory, "$profile-${category.name.lowercase()}-$part-font150.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                }
            }
            assertTrue("Exercise real feature boundaries", separatedControls > 0)
        } finally {
            controller.destroy()
            app.getSharedPreferences("diplay", 0).edit().clear().commit()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            AppAppearanceRuntime.resetForTest()
        }
    }

    private fun isControl(view: View) = view is Button || view is LinearLayout &&
        view.orientation == LinearLayout.HORIZONTAL && (0 until view.childCount).any { view.getChildAt(it) is Switch }



    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
