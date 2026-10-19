package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.json.JSONObject
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

/** Native Robolectric renders only; these are not installed APK screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticConnectionLayoutTest {
    @Test fun fullLargeFont() = capture("full")
    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun compactLargeFont() = capture("compact")
    @Test @Config(qualifiers = "ar-w1920dp-h720dp-land-mdpi")
    fun arabicLargeFont() = capture("rtl")

    private fun capture(profile: String) {
        val app = RuntimeEnvironment.getApplication()
        val flags = app.applicationInfo.flags
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        UsbAutoConnect.resetForTest()
        if (profile == "compact") InterfaceSize.save(app, 100)
        if (profile == "rtl") app.applicationInfo.flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).create()
        try {
            val screen = controller.get()
            screen.resources.updateConfiguration(Configuration(screen.resources.configuration).apply {
                fontScale = 1.5f
            }, screen.resources.displayMetrics)
            ReflectionHelpers.setField(screen, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val frame = screen.findViewById<ViewGroup>(android.R.id.content)
            val text = descendants(frame).filterIsInstance<TextView>().toList()
            val title = text.first { it.text == screen.getString(R.string.automatic_connection) }
            val boot = text.first { it.text == screen.getString(R.string.settings_start_on_boot) }
            val firstCard = title.parent.parent as View
            val cards = firstCard.parent as ViewGroup
            for (id in listOf(R.string.settings_wireless_hotspot, R.string.settings_usb_connection)) {
                val otherCard = text.first { it.text == screen.getString(id) }.parent.parent as View
                assertSame(cards, otherCard.parent)
                assertTrue("Auto-connect must precede ${screen.getString(id)}",
                    cards.indexOfChild(firstCard) < cards.indexOfChild(otherCard))
            }
            val readiness = ReflectionHelpers.getField<View>(screen, "readinessCard")
            assertTrue("Auto-connect must precede connection status", cards.indexOfChild(firstCard) < cards.indexOfChild(readiness))
            if (profile == "rtl") {
                val fixture = mapOf(
                    screen.getString(R.string.automatic_connection) to "الاتصال التلقائي",
                    screen.getString(R.string.settings_auto_connect) to "الاتصال التلقائي",
                    screen.getString(R.string.settings_auto_connect_hint) to "اتصل بالهاتف عند اتصال البلوتوث أو توصيل USB.",
                    screen.getString(R.string.settings_start_on_boot) to "البدء عند التشغيل",
                    screen.getString(R.string.settings_start_on_boot_hint) to "افتح التطبيق عند تشغيل السيارة، حسب إعدادات النظام."
                )
                descendants(frame).forEach {
                    it.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    if (it is TextView) fixture[it.text.toString()]?.let { value -> it.text = value }
                }
            }
            repeat(3) {
                descendants(frame).forEach { it.forceLayout() }
                frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                frame.layout(0, 0, 1920, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
            assertEquals(if (profile == "compact") 320 else 160, screen.resources.displayMetrics.densityDpi)
            val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            val directory = File("../agent_docs/screenshots/automatic-connection").apply { mkdirs() }
            for ((part, target) in listOf("page" to title, "options" to title, "boot" to boot)) {
                val rect = Rect(0, 0, target.width, target.height)
                scroll.offsetDescendantRectToMyCoords(target, rect)
                scroll.scrollTo(0, if (part == "page") 0 else
                    (rect.top - (48 * screen.resources.displayMetrics.density).toInt()).coerceAtLeast(0))
                val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                frame.draw(Canvas(bitmap))
                File(directory, "$profile-$part-font150.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                File(directory, "$profile-$part-font150.json").writeText(JSONObject().apply {
                    put("source", "Robolectric API28 native unit-test render; not installed APK")
                    put("widthPixels", 1920); put("heightPixels", 720)
                    put("densityDpi", screen.resources.displayMetrics.densityDpi)
                    put("fontScale", screen.resources.configuration.fontScale)
                    put("interfaceSize", if (profile == "compact") "100%" else "星瑞12.3英寸")
                    put("rtlArabicFixture", profile == "rtl")
                }.toString(2))
            }
        } finally {
            controller.destroy()
            app.applicationInfo.flags = flags
            app.getSharedPreferences("diplay", 0).edit().clear().commit()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            AppAppearanceRuntime.resetForTest()
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
