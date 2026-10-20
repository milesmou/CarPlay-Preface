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
import android.widget.LinearLayout
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
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        context.applicationInfo.flags = previousFlags
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun componentDescriptionIsUnderTheSystemHeadingWithoutASeparateNoticeCardOrAutomaticVpnRequest() {
        val screen = openAuthorization()
        assertMergedDescription(screen)
        assertEquals(0, AuthorizationVpnShadow.calls)
        assertNull(shadowOf(screen).nextStartedActivity)
        controller!!.pause().resume()
        assertMergedDescription(screen)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    private fun assertMergedDescription(screen: DiPlayActivity) {
        val content = texts(screen).toList()
        assertFalse(content.any { it.text == screen.getString(R.string.settings_authorization_native_title) })
        val description = content.single { it.text == screen.getString(R.string.settings_authorization_native_description) }
        val heading = content.single { it.text == screen.getString(R.string.settings_authorization_system) }
        val card = description.parent as ViewGroup
        assertSame(card, heading.parent.parent)
        assertEquals("Description immediately follows the section heading", 1, card.indexOfChild(description))
        assertSame(card, row(screen, R.string.settings_authorization_write).parent)
        assertTrue(card.indexOfChild(description) < card.indexOfChild(row(screen, R.string.settings_authorization_write)))
    }

    @Test fun bundledComponentSavesThenOpensDownloadsAfterExplicitTapWithoutInstallingOrGrantingVpn() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.INSTALLABLE }
        screen.authorizationExportWorker = { it.run() }
        var preparations = 0
        screen.authorizationPrepareExport = {
            preparations++
            AuthorizationComponentExport.Saved("Download/test.apk", context.cacheDir)
        }
        screen.authorizationOpenFolder = { screen.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); true }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertEquals(0, preparations)
        assertNull(shadowOf(screen).nextStartedActivity)
        descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick()
        assertEquals(1, preparations)
        val opened = shadowOf(screen).nextStartedActivity
        assertEquals(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS, opened.action)
        assertNull(opened.type)
        assertTrue(texts(screen).any { it.text.contains("Download/test.apk") })
        assertEquals(0, AuthorizationVpnShadow.calls)
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.READY }
        controller!!.pause().resume()
        assertTrue(descendants(screen.window.decorView).any { it.tag == "authorization-component-export" })
        assertTrue(descendants(screen.window.decorView).any { it.tag == "authorization-open-downloads" })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_component_ready) })
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun failedComponentPreparationShowsRetryWithoutOpeningVpnOrInstaller() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.INSTALLABLE }
        screen.authorizationExportWorker = { it.run() }
        screen.authorizationPrepareExport = { throw IllegalStateException("invalid archive") }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_component_install_failed) })
        assertTrue(descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.isEnabled)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun bothActionsRemainAvailableInEveryComponentStateAndOpeningTheFolderDoesNotExport() {
        val screen = openAuthorization()
        var folderOpens = 0
        var exports = 0
        screen.authorizationOpenDownloads = { folderOpens++; true }
        screen.authorizationPrepareExport = { exports++; error("opening the folder must not export") }
        for (state in AuthorizationComponentInstaller.State.values()) {
            screen.authorizationComponentState = { state }
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            assertTrue(descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.isEnabled)
            descendants(screen.window.decorView).single { it.tag == "authorization-open-downloads" }.performClick()
        }
        assertEquals(AuthorizationComponentInstaller.State.values().size, folderOpens)
        assertEquals(0, exports)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun savingAnAlreadySavedApkShowsTheNoticeWithoutReopeningTheFolder() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.READY }
        screen.authorizationExportWorker = { it.run() }
        var saves = 0
        var folderOpens = 0
        val path = "Download/Preface-Android9-Authorization.apk"
        screen.authorizationPrepareExport = {
            AuthorizationComponentExport.Saved(path, context.cacheDir, alreadySaved = saves++ > 0)
        }
        screen.authorizationOpenFolder = { folderOpens++; true }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        repeat(2) { descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick() }
        assertEquals(2, saves)
        assertEquals(1, folderOpens)
        val notice = screen.getString(R.string.settings_authorization_component_already_saved, path)
        assertEquals(notice, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue(texts(screen).any { it.text.toString() == notice })
        assertNull(shadowOf(screen).nextStartedActivity)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun incompatibleComponentSaveShowsItsReasonWithoutRequestingStorageOrExporting() {
        val screen = openAuthorization()
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        var exports = 0
        screen.authorizationPrepareExport = { exports++; error("incompatible component must not export") }
        for (state in listOf(AuthorizationComponentInstaller.State.EXISTING,
                AuthorizationComponentInstaller.State.UNAVAILABLE, AuthorizationComponentInstaller.State.PERMISSION_MISSING)) {
            screen.authorizationComponentState = { state }
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick()
            val reason = when (state) {
                AuthorizationComponentInstaller.State.EXISTING -> R.string.settings_authorization_component_existing
                AuthorizationComponentInstaller.State.UNAVAILABLE -> R.string.settings_authorization_component_unsupported
                else -> R.string.settings_authorization_component_permission_missing
            }
            assertEquals(screen.getString(reason), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(screen).nextStartedActivityForResult)
        }
        assertEquals(0, exports)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun systemIdentityMigrationShowsManualReinstallNoticeAndOnlyExportsAfterTap() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.REINSTALL_REQUIRED }
        screen.authorizationExportWorker = { it.run() }
        var exports = 0
        screen.authorizationPrepareExport = {
            exports++
            AuthorizationComponentExport.Saved("Download/Preface-Android9-Authorization.apk", context.cacheDir)
        }
        screen.authorizationOpenFolder = { screen.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); true }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_component_reinstall_required) })
        assertEquals(0, exports)
        assertNull(shadowOf(screen).nextStartedActivity)
        descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick()
        assertEquals(1, exports)
        assertEquals(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS, shadowOf(screen).nextStartedActivity.action)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun folderOpenFailureKeepsTheSavedPathAndDoesNotClaimSavingFailed() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.UPDATE_AVAILABLE }
        screen.authorizationExportWorker = { it.run() }
        screen.authorizationPrepareExport = { AuthorizationComponentExport.Saved("Download/v2.apk", context.cacheDir) }
        screen.authorizationOpenFolder = { false }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        descendants(screen.window.decorView).single { it.tag == "authorization-component-export" }.performClick()
        assertTrue(texts(screen).any { it.text.contains("Download/v2.apk") })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_component_folder_unavailable) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_authorization_component_install_failed) })
        assertNull(shadowOf(screen).nextStartedActivity)
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
    @Test fun downloadsButtonWorksWhenNoComponentExportIsAvailable() {
        val screen = openAuthorization()
        screen.authorizationComponentState = { AuthorizationComponentInstaller.State.EXISTING }
        var exports = 0
        screen.authorizationPrepareExport = { exports++; error("must not export") }
        screen.authorizationOpenDownloads = { screen.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); true }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        descendants(screen.window.decorView).single { it.tag == "authorization-open-downloads" }.performClick()
        assertEquals(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS, shadowOf(screen).nextStartedActivity.action)
        assertEquals(0, exports)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun additionalPermissionsOnlyOpenTheirOwnConfirmationAfterTap() {
        val screen = openAuthorization()
        assertNull(shadowOf(screen).nextStartedActivity)
        row(screen, R.string.settings_authorization_overlay).performClick()
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(screen).nextStartedActivity.action)
        assertFalse(texts(screen).any { it.text.contains("忽略电池优化") })
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


    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun migrationNoticeFullLargeFontScreenshot() = capture("full", reinstall = true)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun migrationNoticeCompactLargeFontScreenshot() = capture("compact", reinstall = true)


    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun installedComponentFullLargeFontScreenshot() = capture("full", installed = true)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun installedComponentCompactLargeFontScreenshot() = capture("compact", installed = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun existingComponentFullLargeFontScreenshot() = capture("full", existing = true)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun existingComponentCompactLargeFontScreenshot() = capture("compact", existing = true)


    private fun capture(size: String, fontScale: Float = 1.5f, reinstall: Boolean = false, installed: Boolean = false,
        existing: Boolean = false) {
        context.resources.updateConfiguration(Configuration(context.resources.configuration).apply { this.fontScale = fontScale }, context.resources.displayMetrics)
        // Explicit system-size choice exercises the short available dp window while keeping 1920x720.
        InterfaceSize.save(context, if (size == "compact") 100 else InterfaceSize.PREFACE_12_3)

        val screen = openAuthorization()
        screen.authorizationComponentState = {
            if (reinstall) AuthorizationComponentInstaller.State.REINSTALL_REQUIRED
            else if (installed) AuthorizationComponentInstaller.State.READY
            else if (existing) AuthorizationComponentInstaller.State.EXISTING else AuthorizationComponentInstaller.State.INSTALLABLE
        }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertMergedDescription(screen)
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        val write = row(screen, R.string.settings_authorization_write)
        val vpn = row(screen, R.string.settings_authorization_vpn)
        val overlay = row(screen, R.string.settings_authorization_overlay)
        val save = descendants(frame).single { it.tag == "authorization-component-export" } as TextView
        val downloads = descendants(frame).single { it.tag == "authorization-open-downloads" } as TextView
        val migrationNotice = if (reinstall) texts(screen).single {
            it.text.toString() == screen.getString(R.string.settings_authorization_component_reinstall_required)
        } else null
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
        assertSame("Save and open Downloads share a row", save.parent, downloads.parent)
        assertEquals(LinearLayout.HORIZONTAL, (save.parent as LinearLayout).orientation)
        assertEquals("Both actions have the same height", save.height, downloads.height)
        assertEquals("Both actions align at the top", save.top, downloads.top)
        assertTrue("Both actions share the available width", kotlin.math.abs(save.width - downloads.width) <= 1)
        assertTrue("Action height accommodates the complete label", save.height >= save.layout.height + save.compoundPaddingTop + save.compoundPaddingBottom)
        assertTrue("Action height accommodates the complete label", downloads.height >= downloads.layout.height + downloads.compoundPaddingTop + downloads.compoundPaddingBottom)
        if (size == "preface-default") {
            assertEquals(60, write.height)
            assertEquals(240, ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll").width)
        }
        assertTrue("Actions follow reading order", downloads.left >= save.right)

        for (view in texts(screen)) {
            val layout = view.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals("Truncated: ${view.text}", 0, layout.getEllipsisCount(line))
        }
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        // These are unit-test renders, not installed-APK screenshots for user previews.
        val directory = File("../agent_docs/screenshots/authorization-merged-description-20261020-test-renders").apply { mkdirs() }
        for (part in listOf("top", "system-authorization") + (if (reinstall) listOf("migration-notice") else emptyList()) + listOf("component-actions", "vpn", "actions")) {
            // Descendant rects are already in the scroll view's content coordinates.
            val vpnOffset = android.graphics.Rect().also { vpn.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(vpn, it) }.top
            val componentOffset = android.graphics.Rect().also { save.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(save, it) }.top
            val systemCard = texts(screen).single {
                it.text == screen.getString(R.string.settings_authorization_native_description)
            }.parent as View
            val systemOffset = android.graphics.Rect().also {
                systemCard.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(systemCard, it)
            }.top
            val migrationOffset = migrationNotice?.let { note ->
                android.graphics.Rect().also { note.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(note, it) }.top
            } ?: 0
            scroll.scrollTo(0, when (part) { "top" -> 0; "system-authorization" -> systemOffset; "migration-notice" -> migrationOffset; "component-actions" -> componentOffset; "vpn" -> vpnOffset; else -> scroll.getChildAt(0).height })
            shadowOf(Looper.getMainLooper()).idle()
            if (part == "migration-notice") {
                val rect = android.graphics.Rect()
                assertTrue("Manual reinstall notice is reachable", migrationNotice!!.getGlobalVisibleRect(rect))
                assertEquals("Manual reinstall notice is fully visible", migrationNotice.height, rect.height())
            }
            if (part == "component-actions") {
                for (action in listOf(save, downloads)) {
                    val rect = android.graphics.Rect()
                    assertTrue("Authorization APK action is reachable", action.getGlobalVisibleRect(rect))
                    assertEquals("Authorization APK action is fully visible", action.height, rect.height())
                }
            }
            if (part == "actions") {
                val rect = android.graphics.Rect()
                assertTrue("Overlay action is reachable", overlay.getGlobalVisibleRect(rect))
                assertEquals("Overlay action is fully visible", overlay.height, rect.height())
            }
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            val filename = "${if (reinstall) "migration-" else if (installed) "installed-" else if (existing) "existing-" else ""}$size-$part-dpi${screen.resources.displayMetrics.densityDpi}-font${Math.round(fontScale * 100)}"
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
                put("componentState", screen.authorizationComponentState().name)
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
