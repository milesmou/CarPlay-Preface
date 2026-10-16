package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterfaceSizePickerUiTest {
    @Test fun fullLargeFont() = capture("full")
    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun compactLargeFont() = capture("compact")
    @Test @Config(qualifiers = "ar-w1280dp-h480dp-land-hdpi")
    fun rtlLargeFont() = capture("rtl")

    private fun capture(profile: String) {
        val app = RuntimeEnvironment.getApplication()
        val flags = app.applicationInfo.flags
        app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = 1.5f }, app.resources.displayMetrics)
        InterfaceSize.save(app, if (profile == "compact") 100 else InterfaceSize.PREFACE_12_3)
        if (profile == "rtl") app.applicationInfo.flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var dialog: AlertDialog? = null
        try {
            val activity = controller.get()
            InterfaceSize.showPicker(activity)
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            val decor = dialog.window!!.decorView
            if (profile == "rtl") {
                val fixtures = mapOf(
                    activity.getString(R.string.settings_interface_size) to "حجم الواجهة",
                    activity.getString(R.string.settings_interface_size_preface_12_3) to "ستار راي 12.3 بوصة",
                    activity.getString(R.string.language_apply) to "تطبيق",
                    activity.getString(R.string.common_cancel) to "إلغاء",
                )
                views(decor).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }
                views(decor).filterIsInstance<TextView>().forEach { text -> fixtures[text.text.toString()]?.let { text.text = it } }
                // List rows are created by the adapter during measurement, after the other fixtures.
                dialog.listView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child ?: return
                        views(child).filterIsInstance<TextView>().forEach { text ->
                            fixtures[text.text.toString()]?.let { text.text = it }
                        }
                    }
                    override fun onChildViewRemoved(parent: View?, child: View?) = Unit
                })
            }
            repeat(3) {
                views(decor).forEach { it.forceLayout() }
                decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            assertEquals(1920, activity.resources.displayMetrics.widthPixels)
            assertEquals(720, activity.resources.displayMetrics.heightPixels)
            assertEquals(1.5f, activity.resources.configuration.fontScale, 0f)
            assertEquals(5, dialog.listView.count)
            assertTrue(decor.width in 1..1920 && decor.height in 1..720)
            for (view in views(decor).filterIsInstance<TextView>()) {
                val layout = view.layout ?: continue
                for (line in 0 until layout.lineCount) assertEquals("Truncated: ${view.text}", 0, layout.getEllipsisCount(line))
            }
            val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(apply.height, cancel.height)
            assertTrue(apply.height > 0 && dialog.listView.height > 0)
            if (profile == "rtl") assertTrue("RTL actions mirror", apply.left < cancel.left)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(12, 17, 26))
            canvas.translate((1920 - decor.width) / 2f, (720 - decor.height) / 2f)
            decor.draw(canvas)
            val directory = File("../agent_docs/screenshots/interface-size-picker-test-renders").apply { mkdirs() }
            File(directory, "$profile-font150.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(directory, "$profile-font150.json").writeText(org.json.JSONObject().apply {
                put("source", "robolectric-dialog-test-render")
                put("densityDpi", activity.resources.displayMetrics.densityDpi)
                put("fontScale", 1.5)
                put("interfaceSize", InterfaceSize.preference(activity))
            }.toString(2))
            bitmap.recycle()
        } finally {
            dialog?.dismiss()
            controller.pause().stop().destroy()
            app.applicationInfo.flags = flags
            InterfaceSize.save(app, InterfaceSize.PREFACE_12_3)
            AppAppearanceRuntime.resetForTest()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
