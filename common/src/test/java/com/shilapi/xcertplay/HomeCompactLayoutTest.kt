package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.view.View
import android.view.Gravity
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
    @Test fun headUnitLandscape() = capture("full", 1f)
    @Test fun headUnitLandscapeLargeFont() = capture("full-font150", 1.5f)
    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    fun highDensityLandscapeLargeFont() = capture("high-density-font150", 1.5f)
    @Test @Config(qualifiers = "zh-rCN-w480dp-h180dp-land-xxxhdpi")
    fun narrowAvailableWindowLargeFont() = capture("narrow-font150", 1.5f)

    @Test @Config(qualifiers = "ar-w1920dp-h720dp-land-mdpi")
    fun landscapeRightToLeftLargeFont() = capture("rtl-font150", 1.5f)

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun capture(name: String, fontScale: Float) {
        UsbAutoConnect.resetForTest()
        CarPlayBackgroundSession.clear()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        // Keep an explicit system-size profile for the constrained-window layout at 1920x720.
        if (name.startsWith("narrow")) InterfaceSize.save(app, 100)
        val previousFlags = app.applicationInfo.flags
        if (name.startsWith("rtl")) app.applicationInfo.flags = previousFlags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Authentication assets are absent in the unit-test APK; show the idle UI.
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.setField(activity, "connectedPhoneState", ConnectedPhoneState(true, true,
                listOf(ConnectedPhone("12:34:56:78:90:AB", "iPhone 16 Pro"),
                    ConnectedPhone("12:34:56:78:90:AC", "驾驶员的 iPhone"))))
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
            assertEquals(1, views(decor).filterIsInstance<TextView>().count {
                it.text == activity.getString(R.string.home_connection_hint)
            })
            val wireless = views(decor).filterIsInstance<TextView>().single { it.tag == "home-phone:12:34:56:78:90:AB" }
            val list = ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "homePhoneList")
            assertEquals("Only one device appears on home", 1, list.childCount)
            assertFalse(views(decor).any { it.tag == "home-phone:12:34:56:78:90:AC" })
            val body = views(decor).single { it.tag == "home-connection-content" }
            assertNull("Connection content has no card background", body.background)
            val bodyPosition = IntArray(2)
            val viewportPosition = IntArray(2)
            body.getLocationOnScreen(bodyPosition)
            homeScroll.getLocationOnScreen(viewportPosition)
            assertEquals("Connection content is horizontally centered", width / 2f,
                bodyPosition[0] + body.width / 2f, 1f)
            assertEquals("Connection content is vertically centered below the header",
                viewportPosition[1] + homeScroll.height / 2f, bodyPosition[1] + body.height / 2f, 1f)
            assertTrue("Connection content fits the window and has a bounded width",
                body.width <= minOf(homeScroll.width, (640 * activity.resources.displayMetrics.density).toInt()))
            assertEquals(Gravity.CENTER, wireless.gravity)
            assertEquals(Gravity.CENTER, text(R.string.home_connection_hint).gravity)
            assertTrue(bodyPosition[1] >= viewportPosition[1])
            val position = IntArray(2)
            wireless.getLocationOnScreen(position)
            assertTrue("Connection action must fit on the first screen", position[1] + wireless.height <= height)
            for (control in listOf(wireless, text(R.string.settings))) {
                assertTrue("${control.text}: height=${control.height}, minimum=${control.minimumHeight}", control.height >= control.minimumHeight)
                assertTrue(control.layout.height + control.compoundPaddingTop + control.compoundPaddingBottom <= control.height)
            }
            val settings = text(R.string.settings)
            val header = settings.parent as ViewGroup
            assertSame(settings, (0 until header.childCount).map(header::getChildAt).first { it.isClickable })
            assertFalse(views(decor).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_connect_usb) })
            assertFalse(views(decor).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.choose_iphone) })
            assertFalse(views(decor).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.a_familiar_drive) })
            val visibleText = views(decor).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }
                .map { it.text.toString() }.toList()
            for (id in listOf(R.string.connect_with_usb, R.string.wireless_carplay_transport, R.string.ready_when_you_are)) {
                assertFalse("Repeated home copy: ${activity.getString(id)}", activity.getString(id) in visibleText)
            }
            assertFalse(visibleText.any { it.startsWith("公开预览") || it.startsWith("Public preview") })
            for (id in listOf(R.string.wireless_carplay, R.string.usb_carplay,
                R.string.home_connected_devices, R.string.home_usb_waiting)) {
                assertFalse("No transport card heading", activity.getString(id) in visibleText)
            }
            if (name.startsWith("full")) {
                val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
                assertTrue("Home must fit without scrolling at normal density", scroll.getChildAt(0).height <= scroll.height)
            }
            val directory = File("../agent_docs/screenshots/home-centered-test-renders").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "1920x720-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
            UsbAutoConnect.resetForTest()
            app.applicationInfo.flags = previousFlags
            AppAppearanceRuntime.resetForTest()
        }
    }
}
