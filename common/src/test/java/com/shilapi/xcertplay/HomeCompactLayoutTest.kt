package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCompactLayoutTest {
    @Test fun headUnitLandscape() = capture("full", 1f, true)
    @Test fun headUnitLandscapeLargeFont() = capture("full-font150", 1.5f, true)
    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    fun highDensityLandscapeLargeFont() = capture("high-density-font150", 1.5f, true)
    @Test @Config(qualifiers = "zh-rCN-w480dp-h180dp-land-xxxhdpi")
    fun narrowAvailableWindowLargeFont() = capture("narrow-font150", 1.5f, false)

    @Test @Config(qualifiers = "ar-w1920dp-h720dp-land-mdpi")
    fun landscapeRightToLeftLargeFont() = capture("rtl-font150", 1.5f, true)

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun capture(name: String, fontScale: Float, columns: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        val previousFlags = app.applicationInfo.flags
        if (name.startsWith("rtl")) app.applicationInfo.flags = previousFlags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Authentication assets are absent in the unit-test APK; show the idle UI.
            ReflectionHelpers.setField(activity, "setupError", null)
            activity.resources.updateConfiguration(Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
                if (name.startsWith("rtl")) setLocale(java.util.Locale("ar"))
            }, activity.resources.displayMetrics)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val decor = activity.window.decorView
            if (name.startsWith("rtl")) {
                // The product currently pins Chinese; exercise RTL geometry independently.
                views(decor).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }
                assertEquals(View.LAYOUT_DIRECTION_RTL, decor.layoutDirection)
            }
            val width = 1920
            val height = 720
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            assertEquals(width, (activity.resources.configuration.screenWidthDp * activity.resources.displayMetrics.density).toInt())
            assertEquals(height, (activity.resources.configuration.screenHeightDp * activity.resources.displayMetrics.density).toInt())
            repeat(3) {
                views(decor).forEach { it.forceLayout() }
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
            }
            val homeScroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            homeScroll.scrollTo(0, 0)
            fun text(id: Int) = views(decor).filterIsInstance<TextView>().single { it.text == activity.getString(id) }
            val wireless = text(R.string.settings_connect_wireless)
            val usb = text(R.string.settings_connect_usb)
            val first = wireless.parent as View
            val second = usb.parent as View
            assertEquals(first.width, second.width)
            if (columns) {
                assertTrue(second.left >= first.right || second.right <= first.left)
                assertEquals(first.top, second.top)
                assertEquals(first.height, second.height)
                val position = IntArray(2)
                wireless.getLocationOnScreen(position)
                assertTrue("Primary connection action must fit on the first screen", position[1] + wireless.height <= height)
            } else assertTrue(second.top >= first.bottom)
            for (control in listOf(wireless, usb, text(R.string.choose_iphone), text(R.string.settings))) {
                assertTrue("${control.text}: height=${control.height}, minimum=${control.minimumHeight}", control.height >= control.minimumHeight)
                assertTrue(control.layout.height + control.compoundPaddingTop + control.compoundPaddingBottom <= control.height)
            }
            assertFalse(views(decor).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.a_familiar_drive) })
            if (name.startsWith("full")) {
                val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
                assertTrue("Home must fit without scrolling at normal density", scroll.getChildAt(0).height <= scroll.height)
            }
            val directory = File("../agent_docs/screenshots/home").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "1920x720-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        } finally {
            controller.pause().stop().destroy()
            app.applicationInfo.flags = previousFlags
            AppAppearanceRuntime.resetForTest()
        }
    }
}
