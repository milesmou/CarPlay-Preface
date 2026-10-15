package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
class HotspotCredentialsDialogTest {
    private lateinit var owner: Activity
    private var dialog: HotspotCredentialsDialog? = null
    private val saved = mutableListOf<Triple<String, String, ManualHotspotSecurity>>()
    private var originalFlags = 0

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        originalFlags = app.applicationInfo.flags
        app.applicationInfo.flags = originalFlags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        owner = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
    }
    @After fun cleanup() {
        dialog?.dismiss(); owner.finish()
        RuntimeEnvironment.getApplication().applicationInfo.flags = originalFlags
        AppAppearanceRuntime.resetForTest()
    }

    private fun show(secret: String = "example123", palette: DiPlayPalette = DiPlayPalette.DARK) =
        HotspotCredentialsDialog(owner, palette, " Xingrui-CarPlay ", secret, ManualHotspotSecurity.WPA3_TRANSITION) { name, password, security ->
            saved.add(Triple(name, password, security))
        }.also { dialog = it; it.show() }
    private fun tagged(tag: Any) = views(dialog!!.window!!.decorView).single { it.tag == tag }
    private fun button(id: Int) = views(dialog!!.window!!.decorView).filterIsInstance<Button>().single { it.text == owner.getString(id) }

    @Test fun labelsAndHiddenPasswordRemainClearAndVisibilityPreservesSelectionAndExactText() {
        val picker = show(" password123 ")
        val name = tagged("hotspot-name") as EditText
        val password = tagged("hotspot-password") as EditText
        assertEquals(" Xingrui-CarPlay ", name.text.toString())
        assertTrue(password.transformationMethod is PasswordTransformationMethod)
        assertTrue(views(picker.window!!.decorView).filterIsInstance<TextView>().any { it.labelFor == name.id && it.text.isNotEmpty() })
        password.setSelection(2, 6)
        val check = views(picker.window!!.decorView).filterIsInstance<CheckBox>().single()
        check.isChecked = true
        assertNull(password.transformationMethod); assertEquals(2, password.selectionStart); assertEquals(6, password.selectionEnd)
        check.isChecked = false
        assertTrue(password.transformationMethod is PasswordTransformationMethod)
        assertEquals(" password123 ", password.text.toString())
    }

    @Test fun invalidSaveStaysOpenAndValidSaveReturnsExactCredentialsWithChosenSecurity() {
        val picker = show("short")
        tagged("hotspot-save").performClick()
        assertTrue(picker.isShowing); assertTrue(saved.isEmpty())
        assertTrue(views(picker.window!!.decorView).filterIsInstance<TextView>().any {
            it.visibility == View.VISIBLE && it.text == owner.getString(R.string.settings_hotspot_password_length) })
        (tagged("hotspot-password") as EditText).setText(" valid passphrase ")
        tagged("hotspot-security").performClick(); tagged(ManualHotspotSecurity.WPA3).performClick()
        tagged("hotspot-save").performClick()
        assertFalse(picker.isShowing)
        assertEquals(listOf(Triple(" Xingrui-CarPlay ", " valid passphrase ", ManualHotspotSecurity.WPA3)), saved)
    }

    @Test fun openHotspotAndCancelHaveNoAccidentalPersistence() {
        val picker = show("")
        assertFalse(tagged("hotspot-security").isEnabled)
        button(R.string.cancel).performClick()
        assertFalse(picker.isShowing); assertTrue(saved.isEmpty())
        show(""); tagged("hotspot-save").performClick()
        assertEquals(ManualHotspotSecurity.OPEN, saved.single().third)
    }

    @Test fun keyboardNextAndDoneNavigateWithoutSaving() {
        show()
        val name = tagged("hotspot-name") as EditText
        val password = tagged("hotspot-password") as EditText
        name.requestFocus(); name.onEditorAction(EditorInfo.IME_ACTION_NEXT)
        assertTrue(password.hasFocus())
        password.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertFalse(password.hasFocus()); assertTrue(saved.isEmpty()); assertTrue(dialog!!.isShowing)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullLandscapeLargeFontScreenshot() = screenshot("full", DiPlayPalette.DARK)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLandscapeLargeFontScreenshot() = screenshot("compact", DiPlayPalette.DARK)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lightLandscapeLargeFontScreenshot() = screenshot("light", DiPlayPalette.LIGHT)

    @Test @Config(qualifiers = "ar-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun rtlLandscapeLargeFontScreenshot() = screenshot("rtl", DiPlayPalette.DARK)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keyboardVisibleAreaKeepsActionsReachableScreenshot() = screenshot("keyboard", DiPlayPalette.DARK)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactKeyboardVisibleAreaKeepsActionsReachableScreenshot() = screenshot("compact-keyboard", DiPlayPalette.DARK)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowAvailableWindowLargeFontScreenshot() = screenshot("narrow", DiPlayPalette.DARK)

    private fun screenshot(name: String, palette: DiPlayPalette) {
        owner.resources.updateConfiguration(Configuration(owner.resources.configuration).apply { fontScale = 1.5f }, owner.resources.displayMetrics)
        val picker = show(palette = palette)
        val decor = picker.window!!.decorView
        if (name == "rtl") views(decor).forEach {
            it.layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (it is TextView && it !is EditText) it.text = when (it.text.toString()) {
                owner.getString(R.string.car_hotspot_details) -> "إعداد نقطة الاتصال"
                owner.getString(R.string.settings_hotspot_dialog_hint) -> "أدخل اسم الشبكة وكلمة المرور كما في إعدادات السيارة. يسري التغيير عند الاتصال التالي."
                owner.getString(R.string.hotspot_name) -> "اسم الشبكة"
                owner.getString(R.string.settings_hotspot_password) -> "كلمة المرور"
                owner.getString(R.string.cancel) -> "إلغاء"
                owner.getString(R.string.save_details) -> "حفظ التفاصيل"
                owner.getString(R.string.hide_keyboard) -> "إخفاء لوحة المفاتيح"
                owner.getString(R.string.show_password) -> "إظهار كلمة المرور"
                else -> it.text
            }
        }
        val usableHeight = if (name.contains("keyboard")) 420 else 720
        if (name.contains("keyboard")) {
            picker.fitVisibleArea(1920, usableHeight)
        }
        if (name == "narrow") picker.fitVisibleArea(620, usableHeight)
        if (name.contains("keyboard")) tagged("hotspot-password").requestFocus()
        repeat(4) {
            views(decor).forEach { it.forceLayout() }
            decor.measure(View.MeasureSpec.makeMeasureSpec(picker.window!!.attributes.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(usableHeight, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, owner.resources.configuration.orientation)
        assertTrue(decor.width < 1920); assertTrue(decor.height <= usableHeight)
        val save = tagged("hotspot-save")
        val footer = save.parent as ViewGroup
        assertEquals(footer.getChildAt(0).height, save.height)
        assertTrue(save.height > 0)
        val rectangle = Rect(0, 0, save.width, save.height)
        (decor as ViewGroup).offsetDescendantRectToMyCoords(save, rectangle)
        assertTrue("Actions must stay above keyboard", rectangle.bottom <= usableHeight)
        if (name == "rtl") assertTrue("RTL footer must mirror", footer.getChildAt(0).left > save.left)
        val scroll = views(decor).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.height > 0)
        if (name in listOf("compact", "keyboard", "compact-keyboard")) assertTrue(scroll.getChildAt(0).height > scroll.height)
        if (name.contains("keyboard")) {
            val input = tagged("hotspot-password") as EditText
            val fieldBounds = Rect(0, 0, input.width, input.height)
            decor.offsetDescendantRectToMyCoords(input, fieldBounds)
            val viewport = Rect(0, scroll.scrollY, scroll.width, scroll.scrollY + scroll.height)
            decor.offsetDescendantRectToMyCoords(scroll, viewport)
            assertTrue("Focused field must remain visible: field=$fieldBounds viewport=$viewport",
                fieldBounds.top >= viewport.top && fieldBounds.bottom <= viewport.bottom)
        }
        if (name == "narrow") assertTrue(decor.width <= 620)
        assertEquals(palette.primaryText, views(decor).filterIsInstance<TextView>().first().currentTextColor)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(palette.background)
        canvas.translate((1920 - decor.width) / 2f, (usableHeight - decor.height) / 2f)
        decor.draw(canvas)
        val directory = File("../agent_docs/screenshots/hotspot-dialog").apply { mkdirs() }
        File(directory, "1920x720-$name-font150.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
