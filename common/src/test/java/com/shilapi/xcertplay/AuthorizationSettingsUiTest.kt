package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi",
    shadows = [AuthorizationVpnShadow::class, AuthorizationWriteShadow::class])
class AuthorizationSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private var previousFlags = 0

    @Before fun reset() {
        AuthorizationVpnShadow.resetState()
        AuthorizationWriteShadow.allowed = false
        previousFlags = context.applicationInfo.flags
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        context.applicationInfo.flags = previousFlags
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun pageHasNativeSettingsNoticeBeforeActionsAndNoAutomaticVpnRequest() {
        val screen = openAuthorization()
        val content = texts(screen).map { it.text.toString() }.toList()
        val notice = content.indexOf(screen.getString(R.string.settings_authorization_native_title))
        val action = content.indexOfFirst { it.startsWith(screen.getString(R.string.settings_authorization_write) + " · ") }
        assertTrue(notice >= 0 && action > notice)
        assertEquals(0, AuthorizationVpnShadow.calls)
        assertNull(shadowOf(screen).nextStartedActivity)
        controller!!.pause().resume()
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun writeStatusRefreshesWhenReturningFromSystemSettings() {
        val screen = openAuthorization()
        row(screen, R.string.settings_authorization_write).performClick()
        val launched = shadowOf(screen).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, launched.action)
        assertEquals("package:${screen.packageName}", launched.dataString)
        controller!!.pause()
        AuthorizationWriteShadow.allowed = true
        controller!!.resume()
        assertTrue(row(screen, R.string.settings_authorization_write).text.endsWith(screen.getString(R.string.settings_authorization_allowed)))
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun vpnConsentCancellationShowsRetryAndDoesNotRecheck() {
        val screen = openAuthorization()
        row(screen, R.string.settings_authorization_vpn).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        assertEquals(1, AuthorizationVpnShadow.calls)
        screen.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_cancelled) })
        assertTrue(row(screen, R.string.settings_authorization_vpn).text.endsWith(screen.getString(R.string.settings_authorization_check)))
        assertEquals(1, AuthorizationVpnShadow.calls)
    }

    @Test fun vpnConsentSuccessIsCheckedAndLabeledAsLastManualResult() {
        val screen = openAuthorization()
        row(screen, R.string.settings_authorization_vpn).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        AuthorizationVpnShadow.consent = null
        screen.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        assertTrue(row(screen, R.string.settings_authorization_vpn).text.endsWith(screen.getString(R.string.settings_authorization_last_allowed)))
        assertEquals(2, AuthorizationVpnShadow.calls)
        controller!!.pause().resume()
        assertEquals(2, AuthorizationVpnShadow.calls)
    }

    @Test fun missingVpnDialogShowsNativeSettingsHintAndAllowsRetry() {
        val screen = openAuthorization()
        ReflectionHelpers.setField(screen, "vpnAuthorization", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) { throw ActivityNotFoundException() }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
        row(screen, R.string.settings_authorization_vpn).performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_unavailable) })
        assertTrue(row(screen, R.string.settings_authorization_vpn).text.endsWith(screen.getString(R.string.settings_authorization_check)))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun prefaceScreenAtSystem240Dpi() = capture("preface-default", fontScale = 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullLargeFontScreenshot() = capture("full")

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLargeFontScreenshot() = capture("compact")

    @Test @Config(qualifiers = "ar-w1280dp-h480dp-land-hdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun rtlLargeFontScreenshot() = capture("rtl", rtl = true)

    private fun capture(size: String, rtl: Boolean = false, fontScale: Float = 1.5f) {
        context.resources.updateConfiguration(Configuration(context.resources.configuration).apply { this.fontScale = fontScale }, context.resources.displayMetrics)
        // Explicit system-size choice exercises the short available dp window while keeping 1920x720.
        InterfaceSize.save(context, if (size == "compact") 100 else InterfaceSize.PREFACE_12_3)
        if (rtl) context.applicationInfo.flags = previousFlags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val screen = openAuthorization()
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        val write = row(screen, R.string.settings_authorization_write)
        val vpn = row(screen, R.string.settings_authorization_vpn)
        if (rtl) {
            // Production pins Chinese; Arabic fixtures verify actual RTL row and text geometry.
            descendants(frame).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val fixtures = mapOf(
                R.string.settings_authorization to "الأذونات",
                R.string.settings_authorization_summary to "إدارة أذونات النظام لهذا التطبيق.",
                R.string.settings_authorization_native_title to "يرجى تثبيت إعدادات النظام أولاً",
                R.string.settings_authorization_native_description to "ثبّت الإعدادات قبل منح الأذونات. يتطلب إذن VPN مكوّن النظام المناسب أيضاً.",
                R.string.settings_authorization_system to "أذونات النظام",
                R.string.settings_authorization_write_description to "افتح صفحة أذونات التطبيق للسماح بتعديل إعدادات النظام.",
                R.string.settings_authorization_vpn_description to "تحقق من إذن VPN واطلبه. يمنح الإذن فقط دون إنشاء اتصال.",
            ).mapKeys { screen.getString(it.key) }
            texts(screen).forEach { view -> fixtures[view.text.toString()]?.let { view.text = it } }
            write.text = "تعديل إعدادات النظام · غير مسموح"
            vpn.text = "إنشاء VPN · تحقق وامنح الإذن"
        }
        repeat(3) {
            descendants(frame).forEach { it.forceLayout() }
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
        assertEquals(1920, screen.resources.displayMetrics.widthPixels)
        assertEquals(720, screen.resources.displayMetrics.heightPixels)
        assertEquals(if (size == "compact") 320 else 160, screen.resources.displayMetrics.densityDpi)
        assertEquals(fontScale, screen.resources.configuration.fontScale, 0.001f)
        assertEquals(if (size == "compact") 100 else InterfaceSize.PREFACE_12_3, InterfaceSize.preference(screen))
        assertEquals(write.height, vpn.height)
        assertEquals(Math.round(60 * screen.resources.displayMetrics.density), write.height)
        if (size == "preface-default") {
            assertEquals(60, write.height)
            assertEquals(240, ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll").width)
        }
        if (rtl) {
            assertEquals(View.LAYOUT_DIRECTION_RTL, write.layoutDirection)
            assertNotNull("RTL row has its chevron on the left", write.compoundDrawables[0])
        }
        for (view in texts(screen)) {
            val layout = view.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals("Truncated: ${view.text}", 0, layout.getEllipsisCount(line))
        }
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        // These are unit-test renders, not installed-APK screenshots for user previews.
        val directory = File("../agent_docs/screenshots/authorization-test-renders").apply { mkdirs() }
        for (part in listOf("top", "actions")) {
            scroll.scrollTo(0, if (part == "top") 0 else scroll.getChildAt(0).height)
            shadowOf(Looper.getMainLooper()).idle()
            if (part == "actions") {
                val rect = android.graphics.Rect()
                assertTrue("VPN action is reachable", vpn.getGlobalVisibleRect(rect))
                assertEquals("VPN action is fully visible", vpn.height, rect.height())
            }
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            val filename = "$size-$part-dpi${screen.resources.displayMetrics.densityDpi}-font${Math.round(fontScale * 100)}"
            File(directory, "$filename.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(directory, "$filename.json").writeText(org.json.JSONObject().apply {
                put("source", "robolectric-test-render")
                put("androidApi", 28)
                put("widthPixels", 1920)
                put("heightPixels", 720)
                put("densityDpi", screen.resources.displayMetrics.densityDpi)
                put("fontScale", fontScale)
                put("interfaceSize", InterfaceSize.preference(screen))
                put("configuration", screen.resources.configuration.toString())
            }.toString(2))
            bitmap.recycle()
        }
    }

    private fun openAuthorization(): DiPlayActivity {
        val built = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        controller = built
        return built.get().also {
            ReflectionHelpers.callInstanceMethod<Unit>(it, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.AUTHORIZATION))
        }
    }

    private fun row(screen: DiPlayActivity, id: Int) = texts(screen).single { it.text.startsWith(screen.getString(id) + " · ") }
    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
