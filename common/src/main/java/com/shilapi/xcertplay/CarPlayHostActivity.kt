package com.shilapi.xcertplay

import android.app.AlertDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import com.shilapi.xcertplay.logging.CarPlayLog as Log
import com.shilapi.xcertplay.logging.DiagnosticLevel
import com.shilapi.xcertplay.logging.LogPolicy
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaRects: MutableMap<DisplaySize, SafeAreaRect?>,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var connectionInstructionsView: TextView? = null
    private var connectionHelpView: TextView? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private val startupRetryBudget = WirelessStartupRetryBudget()
    private var startupRetryStopped = false
    private var bluetoothRecoveryAllowed = false
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "CarPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleSpeedEnabled = locationReportingEnabled && false,
        ),
        label = "CarPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
        availableCurrentMilliAmps = AirPlayPersistence.loadUsbChargingCurrent(this).milliAmps,
    )

    private val vpnConsent = activityResultRegistry.register(
        "usb_vpn_authorization", this, ActivityResultContracts.StartActivityForResult(),
    ) { result -> onVpnConsentResult(result.resultCode) }

    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (shuttingDown.get()) return@registerForActivityResult
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (shuttingDown.get()) return@registerForActivityResult
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (shuttingDown.get()) return@registerForActivityResult
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent(), ::cropSelectedImage)
    private val imageDocumentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropSelectedImage)
    private fun cropSelectedImage(uri: Uri?) {
        if (uri == null) {
            externalActivityInProgress = false
            return
        }
        imageCrop.launch(
            Intent(this, ImageCropActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    // The full-window viewport stays independent of the letterboxed SurfaceView dimensions.
    private var videoView: View? = null
    private var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    // The last picture of a SurfaceView video output, kept when its surface goes (DiPlay leaves the screen) and
    // shown over the new surface until a new frame is rendered there: a still CarPlay screen sends no frame, so
    // the new surface would otherwise stay black until the next change (a touch).
    private var lastPicture: Bitmap? = null
    private var pictureHold: ImageView? = null
    private var pictureHoldBaseline = -1L
    private var pictureHoldStartMillis = 0L
    private var pictureCopyThreadStarted = false
    private val pictureCopyThread by lazy {
        pictureCopyThreadStarted = true
        android.os.HandlerThread("diplay-picture-copy").apply { start() }
    }
    private val pictureHoldTick = object : Runnable {
        override fun run() {
            val hold = pictureHold ?: return
            val rendered = sink?.liveVideoCounters()?.rendered ?: -1L
            val elapsed = SystemClock.uptimeMillis() - pictureHoldStartMillis
            if (lastPictureDone(rendered, pictureHoldBaseline, elapsed, PICTURE_HOLD_MAX_MILLIS)) {
                appendLog("Last picture hidden after ${elapsed} ms (${if (elapsed > PICTURE_HOLD_MAX_MILLIS) "timeout" else "new frame"})")
                hold.animate().alpha(0f).setDuration(150L).withEndAction {
                    hold.visibility = View.GONE
                    hold.setImageDrawable(null)
                }.start()
                return
            }
            mainHandler.postDelayed(this, 50L)
        }
    }
    // Asks the iPhone for a picture again while the new surface has shown none.
    private val pictureRetry = Runnable {
        if (pictureHold?.visibility == View.VISIBLE) {
            appendLog("No frame yet after returning; asking for a keyframe again")
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
        }
    }
    // Smooth video (a setting): SurfaceView output with frames released at the iPhone's frame time.
    private var smoothVideo = false
    // Direct video output (a setting): SurfaceView output with frames shown as soon as they are decoded.
    private var directVideoOutput = false
    // Diagnostics setting: a game-style FPS counter over the picture.
    private var fpsCounter: FpsCounterOverlay? = null
    // Sinks whose sessions are being torn down; their decoders may still render to the current surface
    // until they have released their codecs, so a destroyed surface is detached from them too. A restart
    // and a shutdown can overlap, so this is a set.
    private val retiringSinks = java.util.concurrent.CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var menuVolumeControls: CarPlayMenuVolumeControls? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    // The staged menu settings as they stood when it opened, so leaving can tell whether any
    // edit is pending. A hash, not the values: one of them is the hotspot passphrase.
    private var menuSettingsSignature: Int? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        },
        release = { it.release() },
    )
    private val currentSurface: Surface? get() = videoSurfaceOwner.current
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private val hevcDecoders by lazy { HevcSupport.decoders() }
    private val sessionHevc: Boolean
        get() = HevcSupport.usable(hevcEnabled, hevcSoftwareDecoderEnabled, hevcDecoders)
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = com.shilapi.xcertplay.network.PrefaceE02HeadUnit.NAVIGATION_STREAM
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    /** The view areas the next session declares (see createAirPlayConfig). */
    private var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var mainDisplayDisableSafeArea = false
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var vpnReady = false
    private var awaitingVpnConsent = false
    private var usbAttemptGeneration = UsbAutoConnect.generation
    private var wirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    /** English strings for [friendlyStage], whose keywords are English whatever the UI language. */
    private val englishResources: Resources by lazy {
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) })
            .resources
    }
    private var darkMode = false
    private var appNight = true
    private var hostAppearanceResumed = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                if (hostAppearanceResumed) AppAppearanceRuntime.publishHost(this, night)
                refreshAppAppearance()
                paintWaitingScreen()
                appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var reconnectTicket = 0L
    private var sessionLog: SessionLogFile? = null
    internal val touchController = CarPlayTouchController(
        menuOpen = { menuOpen }, gestureFingerCount = { gestureFingerCount },
        swipeDistancePixels = { dp(SETTINGS_SWIPE_DISTANCE_DP) },
        swipeDirectionRatio = SETTINGS_SWIPE_DIRECTION_RATIO,
        contentRect = ::contentRect, sendTouch = { controller?.sendTouch(it) ?: false },
        openSettingsMenu = ::openSettingsMenu, appendLog = ::appendLog,
    )
    private var gestureFingerCount = 3
    private var settingsGestureHint: TextView? = null
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            if (menuOpen) menuVolumeControls?.refresh()
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    videoSurfaceOwner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let(videoSurfaceOwner::clear)
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val fallbackSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isDestroyed || holder !== fallbackVideoView?.holder) return
            val surface = holder.surface
            videoSurfaceOwner.replace(surface, releaseOnDetach = false)
            appendLog("SurfaceView video surface created valid=${surface.isValid}")
            attachSurface(surface)
            videoView?.let { updateVideoLayout(it.width, it.height) }
            showLastPicture()
            // A still CarPlay screen sends no frames, so ask for one instead of showing the parked gap, in every
            // SurfaceView output (smooth video, direct output), and again while none has come.
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
            mainHandler.removeCallbacks(pictureRetry)
            mainHandler.postDelayed(pictureRetry, 1_000L)
            mainHandler.postDelayed(pictureRetry, 2_000L)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== fallbackVideoView?.holder) return
            // Holder dimensions describe the fitted video, not the host window/CarPlay canvas.
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = holder.surface
            captureLastPicture()
            // The SurfaceHolder contract: nothing may render to the surface once this returns. Every decoder
            // is asked at once, then waited for against one deadline; each confirms once it has moved,
            // parked or released its codec, and closing decoders count once their workers exit. With
            // smooth video the live session's main decoder parks off screen and keeps its state.
            val live = sink
            val detaches = (listOfNotNull(live) + retiringSinks).distinct()
                .map { owner -> owner.beginSurfaceDetach(surface, parkMain = smoothVideo && owner === live) }
            val confirmed = detaches.map { it.await() }.all { it }
            videoSurfaceOwner.clear(surface)
            appendLog("SurfaceView video surface destroyed detachConfirmed=$confirmed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ConnectionAutoStart.accepts(intent)) { finish(); return }
        if (UsbAutoConnect.automaticRequestDisabled(this, intent)) {
            startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "home"))
            finish(); return
        }
        awaitingVpnConsent = savedInstanceState?.getBoolean("usb_vpn_pending") ?: false
        usbAttemptGeneration = savedInstanceState?.getLong("usb_attempt_generation", UsbAutoConnect.generation)
            ?: UsbAutoConnect.generation
        NavigationWidgetUpdater.attach(applicationContext)
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        appNight = resolveAppNightNow(darkMode)
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        registerCarPlayBackHandler()

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun registerCarPlayBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    returnHomeFromConnection()
                }
            },
        )
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
            nightSchedule,
        )
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        mainDisplayDisableSafeArea = AirPlayPersistence.loadMainDisplayDisableSafeArea(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (shuttingDown.get() || UsbAutoConnect.automaticRequestDisabled(this, intent) ||
            (!wirelessEnabled && UsbAutoConnect.wirelessSelected(this))) return
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            if (controller == null) requestVpnConsent() else maybeStartCarPlay()
        }
    }

    private fun requestVpnConsent() {
        if (awaitingVpnConsent || shuttingDown.get() || wirelessEnabled || UsbAutoConnect.wirelessSelected(this) ||
            UsbAutoConnect.automaticRequestDisabled(this, intent)) return
        usbAttemptGeneration = UsbAutoConnect.beginWiredAttempt(this)
        val consent = try {
            com.shilapi.xcertplay.network.CarPlayVpnService.prepare(this)
        } catch (error: RuntimeException) {
            onVpnConsentUnavailable("prepare", null, error)
            return
        }
        vpnReady = consent == null
        if (consent == null) {
            maybeStartCarPlay()
            return
        }
        awaitingVpnConsent = true
        try {
            vpnConsent.launch(consent)
        } catch (error: RuntimeException) {
            onVpnConsentUnavailable("launch", consent, error)
        }
    }

    private fun onVpnConsentResult(resultCode: Int) {
        if (!awaitingVpnConsent) return
        awaitingVpnConsent = false
        vpnReady = false
        if (shuttingDown.get() || UsbAutoConnect.automaticRequestDisabled(this, intent) || wirelessEnabled || UsbAutoConnect.wirelessSelected(this) ||
            usbAttemptGeneration != UsbAutoConnect.generation) return
        if (resultCode == RESULT_OK) {
            // A result alone does not prove that this package is still the prepared VPN owner.
            requestVpnConsentAfterResult()
        } else {
            stopVpnStartup(R.string.vpn_consent_was_denied)
        }
    }

    private fun requestVpnConsentAfterResult() {
        try {
            vpnReady = com.shilapi.xcertplay.network.CarPlayVpnService.prepare(this) == null
            if (vpnReady) maybeStartCarPlay() else stopVpnStartup(R.string.vpn_consent_was_denied)
        } catch (error: RuntimeException) {
            onVpnConsentUnavailable("result", null, error)
        }
    }

    private fun onVpnConsentUnavailable(operation: String, consent: Intent?, error: RuntimeException) {
        awaitingVpnConsent = false
        vpnReady = false
        appendLog("VPN consent unavailable operation=$operation failureClass=${error.javaClass.simpleName} " +
            "component=${consent?.component?.flattenToString() ?: "none"}")
        stopVpnStartup(R.string.vpn_authorization_unavailable)
    }

    private fun stopVpnStartup(@androidx.annotation.StringRes message: Int) {
        if (!wirelessEnabled) UsbAutoConnect.wiredFailed(this, usbAttemptGeneration)
        startupRetryStopped = true
        reconnectScheduled = false
        ++reconnectTicket
        startupRetryButton?.visibility = View.VISIBLE
        setStatus(message)
    }

    private fun requestLocationPermission() {
        if (shuttingDown.get()) return
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestWirelessPermissions() {
        if (shuttingDown.get()) return
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> =
        WirelessPermissions.required(wirelessHotspotMode, Build.VERSION.SDK_INT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shuttingDown.get() || !ConnectionAutoStart.accepts(intent) ||
            UsbAutoConnect.automaticRequestDisabled(this, intent)) return
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            if (wirelessEnabled) {
                if (menuOpen) cancelSettingsEdits()
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            } else if (startupRetryStopped) {
                startupRetryBudget.manualRetry()
                startupRetryStopped = false
                reconnectAttempts = 0
                restartCarPlay("USB reattached; retrying wired connection")
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        if (!UsbAutoConnect.isCurrentRequest(intent) || UsbAutoConnect.wirelessSelected(this)) return false
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        // Read here so a change made in Settings applies when the projection comes back.
        if (AirPlayPersistence.loadFpsCounter(this)) fpsCounter?.start() else fpsCounter?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (shuttingDown.get() || isFinishing) return
        AppLocale.enforce(this)
        WheelKeyService.restoreIfNeeded(this)
        hostAppearanceResumed = true
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold ||
            savedDelay != ambientDelaySeconds || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds,
                nightSchedule)
        }
        nightModeController.resume(systemNight)
        AppAppearanceRuntime.publishHost(this, darkMode)
        refreshAppAppearance()
        if (!menuOpen) {
                    updateResolutionMenu()
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        // The settings screen returns here with FLAG_ACTIVITY_REORDER_TO_FRONT, so this screen is
        // resumed, not recreated: refresh what that screen can change before it is used again.
        if (!menuOpen) loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (!menuOpen) gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        settingsGestureHint?.text = settingsGestureHintText()
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            systemBarsChanged = hideTopBar != savedHideTopBar ||
                hideBottomBar != savedHideBottomBar
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }


    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    private val windowMediaPresses = mutableSetOf<Triple<Int, Int, Int>>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CarPlayMediaKeys.onWindowPhoneKey(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) returnHomeFromConnection()
            return true
        }
        if (WheelMediaLearning.offer(event)) return true
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in windowMediaPresses) {
            CarPlayMediaKeys.onWindowMediaKey(event)
            if (event.action == KeyEvent.ACTION_UP) windowMediaPresses.remove(physicalKey)
            return true
        }
        if (activeAirPlaySession != null && controller?.usesCarBluetoothAudio == true &&
            CarPlayMediaButton.belongsToCarBluetooth(event.keyCode)) return super.dispatchKeyEvent(event)
        if (!menuOpen && activeAirPlaySession != null && CarPlayMediaButton.forKeyCode(event.keyCode) == null &&
            WheelMediaBindings.known(this, event.keyCode)) {
            return if (CarPlayMediaKeys.onWindowMediaKey(event) == true) true else super.dispatchKeyEvent(event)
        }
        if (!menuOpen &&
            AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }


        // Standard wheel media keys can reach this window without an accessibility service.
        val mediaPress = CarPlayMediaButton.forCarPlayWindow(
            event.keyCode,
            sessionActive = activeAirPlaySession != null,
            inCall = inCall(this),
            bluetoothAudio = controller?.usesCarBluetoothAudio == true,
        )
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && mediaPress != null) {
            val sent = CarPlayMediaKeys.onWindowMediaKey(event) ?: (controller?.sendMediaButton(mediaPress) == true)
            appendLog("Media: wheel key ${event.keyCode} -> CarPlay $mediaPress sent=$sent")
            if (sent) {
                windowMediaPresses.add(physicalKey)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        outState.putBoolean("usb_vpn_pending", awaitingVpnConsent)
        outState.putLong("usb_attempt_generation", usbAttemptGeneration)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        fpsCounter?.stop()
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        logHostVisibility("stop")
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        logHostVisibility("user-leave-hint")
        super.onUserLeaveHint()
    }

    private fun logHostVisibility(event: String) {
        appendLog("Host visibility event=$event sessionActive=${activeAirPlaySession != null} " +
            "backgroundSessionActive=${CarPlayBackgroundSession.active} " +
            "finishing=$isFinishing changingConfigurations=$isChangingConfigurations " +
            "windowFocus=${hasWindowFocus()} surfaceValid=${currentSurface?.isValid == true}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.enforce(this)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        connectionHelpView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        discardLastPicture()
        if (pictureCopyThreadStarted) pictureCopyThread.quitSafely()
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        fallbackVideoView = null
        fallbackVideoBounds = null
        sessionLog?.append("Activity destroyed")
        // Disconnect finishes the Activity before the controller's asynchronous teardown ends.
        // Keep its log alive until the same teardown barrier that admits the next connection.
        if (!shuttingDown.get() || shutdownComplete) {
            sessionLog?.close()
            sessionLog = null
        }
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Measure the preparation content naturally, then fit it inside the safe viewport.
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                getChildAt(0)?.measure(
                    View.MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
        }.apply {
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = getString(R.string.diplay)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        val stage = TextView(this).apply {
            text = getString(R.string.getting_carplay_ready)
            gravity = Gravity.CENTER
        }
        panel.addView(stage)
        val instructions = TextView(this).apply {
            text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            gravity = Gravity.CENTER
        }
        panel.addView(instructions)
        val help = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        panel.addView(help)
        val recovery = DiPlayButton(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }
        panel.addView(recovery, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val retry = DiPlayButton(this).apply {
            text = getString(R.string.retry_carplay_connection)
            visibility = View.GONE
            setOnClickListener { retryConnectionFromButton() }
            startupRetryButton = this
        }
        panel.addView(retry, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val back = DiPlayButton(this).apply {
            text = getString(R.string.back_to_diplay)
            setOnClickListener { showDiPlayHome() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = settingsGestureHintText()
            gravity = Gravity.CENTER
        }
        panel.addView(gestureHint)
        paintWaitingScreen = {
            val colors = WaitingScreenColors.of(appNight)
            viewport.setBackgroundColor(colors.background)
            title.setTextColor(colors.text)
            stage.setTextColor(colors.text)
            instructions.setTextColor(colors.secondary)
            help.setTextColor(colors.secondary)
            gestureHint.setTextColor(colors.secondary)
            val buttonPalette = DiPlayPalette.of(appNight)
            back.style(DiPlayButtonKind.PRIMARY, buttonPalette, overlay = false)
            retry.style(DiPlayButtonKind.SECONDARY, buttonPalette, overlay = false)
            recovery.style(DiPlayButtonKind.SECONDARY, buttonPalette, overlay = false)
        }
        paintWaitingScreen()
        viewport.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        var preparationHeight = -1
        fun updatePreparationLayout() {
            val height = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (height <= 0) return
            // Interpolate within the short viewport range; keep regular screens at their existing size.
            val fraction = ((height.toFloat() / resources.displayMetrics.density - 240f) / 240f).coerceIn(0f, 1f)
            fun size(short: Float, regular: Float) = short + (regular - short) * fraction
            fun spacing(short: Float, regular: Float) = dp(size(short, regular).toInt())
            val availableWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(48)
            val buttonWidth = minOf(dp(300), availableWidth.coerceAtLeast(dp(48)))
            for (button in listOf(back, recovery, retry)) {
                if (button.layoutParams.width != buttonWidth) {
                    button.layoutParams = button.layoutParams.apply { width = buttonWidth }
                }
            }
            if (preparationHeight == height) return
            preparationHeight = height
            panel.setPadding(dp(24), spacing(16f, 32f), dp(24), spacing(16f, 32f))
            val iconSize = spacing(54f, 88f)
            icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            title.textSize = size(26f, 34f)
            title.setPadding(0, spacing(8f, 18f), 0, spacing(6f, 14f))
            stage.textSize = size(19f, 22f)
            instructions.textSize = size(15f, 17f)
            instructions.setPadding(0, spacing(8f, 14f), 0, spacing(12f, 24f))
            help.textSize = size(15f, 17f)
            help.setPadding(0, spacing(8f, 14f), 0, spacing(12f, 24f))
            for (button in listOf(back, recovery, retry)) {
                button.textSize = size(17f, 18f)
                button.layoutParams = button.layoutParams.apply { this.height = spacing(50f, 64f) }
            }
            gestureHint.textSize = size(12.5f, 13f)
            gestureHint.setPadding(0, spacing(10f, 20f), 0, 0)
        }
        fun fitPreparationContent() {
            val availableHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (panel.height <= 0 || availableHeight <= 0) return
            val landscape = viewport.width - viewport.paddingLeft - viewport.paddingRight > availableHeight
            val scale = if (landscape) minOf(1f, availableHeight.toFloat() / panel.height) else 1f
            panel.scaleX = scale
            panel.scaleY = scale
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreparationContent() }
        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreparationLayout()
            fitPreparationContent()
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            viewport.post { updatePreparationLayout() }
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        // Over the picture and the connection panel, under the settings menu.
        fpsCounter = FpsCounterOverlay(this, mainHandler) { sink?.liveVideoCounters() }.also { counter ->
            // CarPlay's picture is never mirrored, so the counter stays top-right in right-to-left locales too.
            root.addView(counter.view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(12), dp(12), 0)
            })
        }
        settingsMenu = buildSettingsMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))
        safeAreaEditor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(safeAreaEditor, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        directVideoOutput = AirPlayPersistence.loadDirectVideoOutput(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        settingsGestureHint = gestureHint
        stageStatusView = stage
        connectionInstructionsView = instructions
        connectionHelpView = help
        connectionPanel = viewport
        updateDebugOverlays()
        return root
    }

    private fun retryConnectionFromButton() {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        // VPN denial happens before a controller owns the background session. That initial
        // attempt still needs a working Retry button, without disturbing another owner's session.
        if (!CarPlayBackgroundSession.isOwner(this) && (controller != null || CarPlayBackgroundSession.hasSession())) return
        UsbAutoConnect.markManualRequest(intent)
        if (!wirelessEnabled) usbAttemptGeneration = UsbAutoConnect.beginWiredAttempt(this)
        startupRetryBudget.manualRetry()
        startupRetryStopped = false
        reconnectAttempts = 0
        startupRetryButton?.visibility = View.GONE
        if (controller == null) requestStartupPrerequisites()
        else retryStartupOrRestart(getString(R.string.connecting_to_your_iphone))
    }

    private val overlayPalette: DiPlayPalette get() = DiPlayPalette.of(appNight)
    private val settingsOverlayTheme: SettingsTheme get() = SettingsTheme.overlay(overlayPalette)
    private val MENU_BACKGROUND: Int get() = overlayPalette.overlayBackground
    private val MENU_PRIMARY: Int get() = overlayPalette.overlayPrimaryText
    private val MENU_SECONDARY: Int get() = overlayPalette.overlaySecondaryText
    private val MENU_ACCENT: Int get() = overlayPalette.overlayAccent
    private val MENU_ACCENT_TRACK: Int get() = overlayPalette.overlayAccentTrack
    private val MENU_TRACK_OFF: Int get() = overlayPalette.overlayTrackOff
    private val MENU_BUTTON_TEXT: Int get() = overlayPalette.overlayOnAccent
    private val MENU_DANGER: Int get() = overlayPalette.overlayDanger

    private fun refreshAppAppearance() {
        val nextNight = resolveAppNightNow(darkMode)
        if (nextNight == appNight) return
        appNight = nextNight
        paintWaitingScreen()

        settingsMenu?.let { previous ->
            val parent = previous.parent as? ViewGroup ?: return@let
            val index = parent.indexOfChild(previous)
            val visible = previous.visibility
            val scrollY = findDescendant<ScrollView>(previous)?.scrollY ?: 0
            val focusDescription = previous.findFocus()?.contentDescription?.toString()
            parent.removeView(previous)
            val replacement = buildSettingsMenu().apply { visibility = visible }
            settingsMenu = replacement
            parent.addView(replacement, index, FrameLayout.LayoutParams(-1, -1))
            replacement.post {
                findDescendant<ScrollView>(replacement)?.scrollTo(0, scrollY)
                if (focusDescription != null) {
                    findDescendants(replacement).firstOrNull {
                        it.contentDescription?.toString() == focusDescription
                    }?.requestFocus()
                }
            }
        }

        safeAreaEditor?.let { overlay ->
            overlay.setBackgroundColor(MENU_BACKGROUND)
            safeAreaEditorView?.applyPalette(overlayPalette)
            (overlay as? ViewGroup)?.let { group ->
                (group.getChildAt(1) as? TextView)?.setTextColor(MENU_PRIMARY)
            }
        }
        (picturePanel as? CarPlayPicturePanel)?.applyPalette(overlayPalette)
    }

    private inline fun <reified T : View> findDescendant(root: View): T? =
        findDescendants(root).filterIsInstance<T>().firstOrNull()

    private fun findDescendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(findDescendants(root.getChildAt(index)))
        }
    }

    private fun settingsGestureHintText(): String =
        if (gestureFingerCount == 0) getString(R.string.settings_gesture_disabled_hint)
        else getString(R.string.open_diplay_settings_hint, gestureFingerCount)

    private fun settingsGestureButtonText(): String =
        if (gestureFingerCount == 0) getString(R.string.settings_gesture_disabled_action)
        else getString(R.string.settings_gesture_fingers, gestureFingerCount)
    /** Quick actions only. Connection-time options live in the full Settings screen. */
    private fun buildSettingsMenu(): View = CarPlayQuickMenu(
        context = this, palette = overlayPalette,
        dismiss = { leaveSettingsMenu() },
        home = { leaveSettingsMenu { showDiPlayHome() } },
        settings = { leaveSettingsMenu { showDiPlayHome("settings") } },
        picture = { leaveSettingsMenu { openPicturePanel() } },
        exit = ::exitApplication,
        volume = CarPlayMenuVolume(
            audio = getSystemService(android.media.AudioManager::class.java),
            configuredStream = { navigation ->
                sink?.configuredVolumeStream(navigation) ?: if (navigation)
                    AirPlayPersistence.loadNavigationAudioChannel(this) else AirPlayPersistence.loadMediaAudioChannel(this)
            },
            bluetoothAudio = { controller?.usesCarBluetoothAudio ?: AirPlayPersistence.loadCarBluetoothAudio(this) },
            suppressed = { runCatching {
                inCall(this) || com.shilapi.xcertplay.media.NavigationPlayback.snapshot().priorityVoiceActive
            }.getOrDefault(true) },
            report = ::appendLog,
        ),
        onVolumeControls = { menuVolumeControls = it },
    ).build()

    private fun persistMenuSettings() {
        AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingerCount)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        CarPlayDock.save(this, carPlayDock)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
        AirPlayPersistence.saveMainDisplayDisableSafeArea(this, mainDisplayDisableSafeArea)
    }

    /** Mirrors [persistMenuSettings]: a field staged there belongs here too. */
    private fun menuSettingsSignature(): Int = listOf(
        gestureFingerCount, wirelessEnabled, mfiTarget, mfiI2cPath, remoteMfiServer, remoteMfiToken,
        wirelessHotspotMode, manualHotspotSsid,
        manualHotspotPassphrase, manualHotspotBand, manualHotspotChannel, manualHotspotSecurity,
        locationReportingEnabled, autoStartOnBoot, advancedAudioChannelMapping,
        fps, widthPhysicalMm, physicalSizeBasis, hevcEnabled,
        hevcSoftwareDecoderEnabled, manufacturer, model, oemLabel, debugLogsEnabled, rightHandDrive,
        carPlayDock, hideTopBar, hideBottomBar, safeAreaDrawOutside, mainDisplayDisableSafeArea,
    ).joinToString("|").hashCode()

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaRects = mutableMapOf<DisplaySize, SafeAreaRect?>().apply {
                safeAreaSize?.let { put(it, AirPlayPersistence.loadSafeAreaRect(this@CarPlayHostActivity, it.width, it.height)) }
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        val previewChangedSystemBars = hideTopBar != AirPlayPersistence.loadHideTopBar(this) ||
            hideBottomBar != AirPlayPersistence.loadHideBottomBar(this)
        loadPersistedSettings()
        baseline.safeAreaRects.forEach { (size, savedRect) ->
            savedRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        // Closing an unchanged menu must not schedule a display renegotiation. Real bar
        // previews still need to restore the window and re-measure after cancellation.
        if (previewChangedSystemBars) {
            applyFullscreenMode()
            refreshDisplaySizeAfterLayout()
        }
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = ConnectionSettingsSection.createMfiTargetChoice(
            context = this,
            selected = mfiTarget,
            theme = settingsOverlayTheme,
        ) { target ->
            if (mfiTarget == target) return@createMfiTargetChoice
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = targetChoice.radioGroup
        section.addView(
            targetChoice.container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.linux_device_path_for_example_dev_i2c_1), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.settings_mfi_address_hint),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        SettingsWidgets.createCategoryHeader(this, title, settingsOverlayTheme)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location_to_iphone), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                SettingsWidgets.applyLargeSwitchStyle(this, MENU_ACCENT, MENU_SECONDARY, MENU_ACCENT_TRACK, MENU_TRACK_OFF)
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.show_on_screen_debug_logs),
        ) { checked ->
            debugLogsEnabled = checked
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.choose_image)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener {
                    externalActivityInProgress = true
                    launchCarButtonImagePicker(
                        openDocument = { imageDocumentPicker.launch(arrayOf("image/*")) },
                        getContent = { imagePicker.launch("image/*") },
                        documentPickerIsSystem = documentPickerIsSystem(),
                    ).onFailure {
                        externalActivityInProgress = false
                        appendLog("No image picker: ${it.javaClass.simpleName}")
                        android.widget.Toast.makeText(this@CarPlayHostActivity,
                            getString(R.string.this_head_unit_has_no_image_picker),
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.default_icon)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    // Between the driver's side and the bottom the dock moves at once (and is saved at once) when the
    // running session declared both; to or from automatic waits for Save, which reconnects.
    private fun buildDockSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.carplay_dock), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val labels = mapOf(
            CarPlayDock.AUTOMATIC to R.string.carplay_dock_automatic,
            CarPlayDock.DRIVER_SIDE to R.string.carplay_dock_driver_side,
            CarPlayDock.BOTTOM to R.string.carplay_dock_bottom,
        )
        val buttons = CarPlayDock.entries.associateWith { dock ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labels.getValue(dock))
                setTextColor(MENU_PRIMARY)
                isChecked = carPlayDock == dock
            }.also { group.addView(it) }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val next = buttons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            carPlayDock = next
            val from = sessionDock
            val areas = sessionDisplay?.viewAreas
            val target = next.edge?.let { areas?.withDock(it) }
            if (from != null && areas != null && target != null && CarPlayDock.movesLive(from, next) &&
                controller?.showViewArea(target) == true
            ) {
                areas.use(target)
                CarPlayDock.save(this, next)
            }
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        section.addView(menuText(getString(R.string.carplay_dock_hint), 16f, MENU_SECONDARY))
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.left_hand_drive)
            setTextColor(MENU_PRIMARY)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.right_hand_drive)
            setTextColor(MENU_PRIMARY)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addSystemBarControls(
            hideTopBar = hideTopBar,
            hideBottomBar = hideBottomBar,
            onHideTopBarChanged = { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            onHideBottomBarChanged = { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
        ) { label, checked, onChanged ->
            section.addView(
                SettingsWidgets.createSwitchRow(
                    context = this,
                    label = getString(label),
                    checked = checked,
                    description = getString(if (label == R.string.hide_the_navigation_bar)
                        R.string.settings_fixed_resolution_hint else label),
                    theme = settingsOverlayTheme,
                    onChanged = onChanged,
                ).rowView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
        }
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.set)
                style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.reset)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.rect_fill_disable_safe_area),
                checked = mainDisplayDisableSafeArea,
                description = getString(R.string.rect_fill_disable_safe_area_description),
            ) { checked ->
                mainDisplayDisableSafeArea = checked
                updateSafeAreaSummary()
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(MENU_BACKGROUND)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this, overlayPalette)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, MENU_PRIMARY, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.cancel)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.save)
                style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(MENU_PRIMARY)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = SettingsWidgets.createSwitchRow(
        context = this,
        label = label,
        description = description,
        checked = checked,
        theme = settingsOverlayTheme,
        contentDescription = description,
        onChanged = onChanged,
    ).rowView

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(menuText("${getString(R.string.settings_hotspot_mode)} · ${hotspotModeLabel(wirelessHotspotMode)}", 20f, MENU_SECONDARY))
        addView(menuText(getString(R.string.settings_hotspot_next_connection), 14f, MENU_SECONDARY))
        addView(DiPlayButton(this@CarPlayHostActivity).apply {
            text = getString(R.string.settings_wireless_hotspot)
            setOnClickListener { leaveSettingsMenu { showDiPlayHome("settings") } }
        }, LinearLayout.LayoutParams(-1, dp(SETTINGS_EXPERIMENTAL_MENU_CONTROL_HEIGHT_DP)))
    }
    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.LOCAL && runCatching { DiPlayBootstrap.ensure(this, mfiTarget) }.isFailure ->
                getString(R.string.setup_error_auth)
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.i2c_device_path_is_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in mfiI2cPath -> getString(R.string.i2c_device_path_contains_u_0000)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (!wirelessEnabled || wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.settings_hotspot_empty_name)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.settings_hotspot_long_name)
            '\u0000' in manualHotspotSsid -> getString(R.string.settings_hotspot_invalid_character)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.settings_hotspot_invalid_character)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.settings_temporary_hotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.settings_manual_hotspot_title)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = SettingsWidgets.createChoiceRow(
        context = this,
        label = label,
        options = options,
        selected = selected,
        theme = settingsOverlayTheme,
        onSelected = onSelected,
    ).container

    private fun updateResolutionMenu() {
        val native = nativeCarPlaySize()
        val resolution = "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height}"
        val transport = if (!sessionHevc) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        // Only the saved navigation-bar choice selects the E02 native canvas.
        // Transient window measurements must not change the resolution offered to the phone.
        val alignedSize = nativeCarPlaySize()
        if (hevcEnabled && !sessionHevc) appendLog("HEVC requested but no usable hardware decoder; using H.264")
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val display = baseDisplay.copy(
            safeArea = if (mainDisplayDisableSafeArea) null else AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = baseDisplay.widthPixels,
                displayHeightPixels = baseDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
            mainDisplayCornerMasks = if (mainDisplayDisableSafeArea) true else null,
            mainDisplayDisableSafeArea = mainDisplayDisableSafeArea,
        )
        // Only the fixed dock needs alternative areas, each covering the whole canvas.
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        val viewAreas = CarPlayViewAreas.build(display.widthPixels, display.heightPixels, dock)
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) display else display.copy(
            viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        val requestSummary = "Display request fixed=true hideNavigationBar=$hideBottomBar " +
            "surface=${size.width}x${size.height} canvas=${display.widthPixels}x${display.heightPixels} fps=$fps " +
            "codec=${if (sessionHevc) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective canvas=${display.widthPixels}x${display.heightPixels} " +
            "decision=fixed_navigation_bar physical=${physical.widthMm}x${physical.heightMm}mm " +
            "safeArea=${display.safeArea} drawOutside=${display.safeAreaDrawOutside}"
        val policySummary = "E02 fixed canvas selected by navigation-bar preference; resolution and UI canvas scaling disabled"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, policySummary, effectiveSummary)
        appendLog(requestSummary)
        appendLog(policySummary)
        appendLog(effectiveSummary)
        val carBluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(this)
        if (carBluetoothAudio) logCarBluetoothAudio()
        return AirPlayConfig(
            deviceName = "CarPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = null, // E02 no longer offers an independent dashboard/launcher map stream.
            rightHandDrive = rightHandDrive,
            hevc = sessionHevc,
            microphone = microphoneAvailable,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = false,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
            speechWithout8And16Khz = AirPlayPersistence.loadSpeechWithout8And16Khz(this),
            disableAudioOutput = carBluetoothAudio,
        )
    }

    /** Observe the head-unit receiver roles; never log phone names or change Bluetooth profiles. */
    private fun logCarBluetoothAudio() {
        appendLog("Car Bluetooth audio: enabled; CarPlay audio not offered; music and wheel media keys vehicle-owned")
        CarAudioDiagnostics(applicationContext, ::appendLog).sample("bluetooth_route_start")
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val (size, bytes) = AirPlayIconSizing.fit(encoded) ?: return null
        if (bytes !== encoded) appendLog("CarPlay car-button icon resized bytes=${encoded.size}->${bytes.size} pixels=$size")
        return AirPlayIcon(size, size, bytes)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_xingrui).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.ic_xingrui)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun nativeCarPlaySize(): DisplaySize = DisplaySize(
        if (hideBottomBar) PREFACE_CANVAS_WIDTH else PREFACE_CANVAS_WIDTH_WITH_NAVIGATION_BAR,
        PREFACE_CANVAS_HEIGHT,
    )

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = PREFACE_CANVAS_WIDTH,
            maximumHeightPixels = PREFACE_CANVAS_HEIGHT,
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        if (mainDisplayDisableSafeArea) {
            return getString(R.string.safe_area_disabled_rect_fill_summary)
        }
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        menuVolumeControls?.refresh()
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun rememberSafeAreaBeforeEdit(size: DisplaySize) {
        val baseline = settingsBaseline ?: return
        if (!baseline.safeAreaRects.containsKey(size)) {
            baseline.safeAreaRects[size] = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        }
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
        mediaOwner: Any,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this, wireless = wirelessEnabled),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = { active -> CarPlayMediaKeys.onMediaAudioChanged(active, mediaOwner) },
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
            mainVideoFrameRate = fps,
            vendorLowLatencyDecoder = AirPlayPersistence.loadLowLatencyDecoder(this),
            mtkDecoderTuningEnabled = AirPlayPersistence.loadMtkDecoderTuning(this),
            bluetoothOutput = AirPlayPersistence.loadCarBluetoothAudio(this),
            disableMediaAudioFocus = AirPlayPersistence.loadDisableMediaKeyAudioFocus(this),
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    if (!wirelessEnabled) UsbAutoConnect.wiredConnected(usbAttemptGeneration)
                    // A late successful handshake supersedes pending recovery, including a stopped
                    // startup budget. Keep the budget until stable video, but allow future loss recovery.
                    ++reconnectTicket
                    reconnectScheduled = false
                    startupRetryStopped = false
                    bluetoothRecoveryAllowed = false
                    startupRetryButton?.visibility = View.GONE
                    showConnectionHelp(null)
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRetryBudget.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(R.string.carplay_session_ended_reconnecting)
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(R.string.transport_error_reconnecting)
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                receiveDiagnostic(DiagnosticLevel.DEBUG, message)
            }

            override fun onDiagnosticLog(level: DiagnosticLevel, message: String) {
                receiveDiagnostic(level, message)
            }

            private fun receiveDiagnostic(level: DiagnosticLevel, message: String) {
                if (!LogPolicy.enabled(level)) return
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message, level = level)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen && level == DiagnosticLevel.DEBUG) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message, level)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        if (!wirelessEnabled && status is CarPlayStatus.Failed) UsbAutoConnect.wiredFailed(this, usbAttemptGeneration)
        if (status is CarPlayStatus.Failed && !startupRetryStopped) {
            bluetoothRecoveryAllowed = status.retryable && !status.wifiResetRequired &&
                status.startupFailure?.retryable != false
        }
        if (status == CarPlayStatus.TestingUsbNcm) {
            // Detaching the failed listener may still deliver a queued transport-error callback.
            // It must not replace this diagnostic run with another complete USB attempt.
            startupRetryStopped = true
            reconnectScheduled = false
            startupRetryButton?.visibility = View.GONE
        }
        if (status is CarPlayStatus.Failed && !status.retryable) {
            if (status.usbVpnUnavailable) vpnReady = false
            startupRetryStopped = true
            reconnectScheduled = false
            startupRetryButton?.visibility = View.VISIBLE
            appendLog("Connection recovery stopped retryable=false usbDirectUnavailable=${status.usbDirectUnavailable} usbNcmTested=${status.usbNcmTested}")
        }
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        updateHotspotStatus(status)
        val description = status.describe()
        val englishDescription = status.describe(englishResources)
        setConnectionStage(description, englishDescription)
        if (status == CarPlayStatus.TestingUsbNcm) {
            stageStatusView?.text = getString(R.string.status_testing_usb_ncm)
        }
        if (status is CarPlayStatus.Failed && !status.retryable) {
            stageStatusView?.text = getString(when {
                status.usbVpnUnavailable -> R.string.usb_vpn_transport_unavailable
                status.usbNcmTested -> R.string.status_usb_ncm_test_finished
                else -> R.string.wireless_startup_retries_exhausted
            })
        }
        // Keep the actionable tip readable while automatic retries are in progress.
        // Only a working AirPlay session, or a new failure, replaces it.
        if (status is CarPlayStatus.Failed) showConnectionHelp(status)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                if (!status.retryable) return@report
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure, englishDescription)
            }
            else -> Unit
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        val expected = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED
        if (snapshot.controller.connectionTransport() != expected) {
            CarPlayBackgroundSession.stop {
                mainHandler.post { if (!isDestroyed && !shuttingDown.get()) requestStartupPrerequisites() }
            }
            return false
        }
        // The sink's pacing is fixed when it is built; a session from before a Smooth video change (for
        // example one whose reconnect stopped at a prerequisite) is stopped instead of adopted. Callers
        // retry, and the next start matches this view.
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video=${snapshot.sink.videoPacingEnabled} differs from this view; " +
                "stopping it instead of adopting it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStartCarPlay() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        val adoptedMediaLog = sessionLog
        CarPlayMediaKeys.updateForeground(snapshot.controller, diagnostic = { AsyncDiagnosticLog.append(adoptedMediaLog, it) }) {
            hasWindowFocus() && activeAirPlaySession != null && !menuOpen
        }
        sink = snapshot.sink
        val retainedController = snapshot.controller
        snapshot.sink.setAudioOwnershipChangedListener { owned ->
            retainedController.setBluetoothAudioOwnership(owned)
            CarPlayMediaKeys.onPlaybackFocusChanged(retainedController, owned)
        }
        sessionDisplay = snapshot.display
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                R.string.carplay_service_already_running
            } else {
                R.string.carplay_session_already_running
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}" +
                if (config.transport == CarPlayTransport.WIRED) " usbCharging=${config.availableCurrentMilliAmps}mA" else "",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val mediaOwner = Any()
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
            mediaOwner = mediaOwner,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            onNativeBluetoothPlaying = renderer::setNativeBluetoothPlaying,
            managesPlaybackAudioFocus = renderer.managesPlaybackAudioFocus,
            onBluetoothHandoffReady = renderer::setBluetoothHandoffReady,
        )
        controller = next
        renderer.setAudioOwnershipChangedListener { owned ->
            next.setBluetoothAudioOwnership(owned)
            CarPlayMediaKeys.onPlaybackFocusChanged(next, owned)
        }
        val mediaDiagnosticLog = sessionLog
        CarPlayMediaKeys.attach(this, next, diagnostic = { AsyncDiagnosticLog.append(mediaDiagnosticLog, it) }, mediaOwner = mediaOwner) {
            hasWindowFocus() && activeAirPlaySession != null && !menuOpen
        }
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, size.width, size.height,
            viewAreas = pendingViewAreas,
            statusBarShown = statusBarShown(),
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DiPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per)
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
    }


    private fun statusBarShown(): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged()
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged) {
            // Both growth and shrink only refit the fixed E02 canvas. In particular,
            // restoring 1920x720 must not release a working temporary hotspot.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (fallbackVideoBounds != bounds) {
                fallbackVideoBounds = bounds
                fallback.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || UsbAutoConnect.automaticRequestDisabled(this, intent)) return
        if (!wirelessEnabled && UsbAutoConnect.wirelessSelected(this)) return
        if (wirelessEnabled && UsbAutoConnect.priorityDevice(this) != null) {
            wirelessEnabled = false
            AirPlayPersistence.saveWirelessEnabled(this, false)
            if (controller != null) restartCarPlay("USB attached; switching from wireless")
            else requestStartupPrerequisites()
            return
        }
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        // The video view is chosen once per activity; a changed Smooth video or Direct video output
        // setting needs a new one.
        if (controller == null && (AirPlayPersistence.loadSmoothVideo(this) != smoothVideo ||
                AirPlayPersistence.loadDirectVideoOutput(this) != directVideoOutput)) {
            appendLog("Video output setting changed; rebuilding the video view")
            // No session runs here, but a restart keeps this host as the session owner; the new instance
            // must be able to start its own.
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(
        reason: String,
        startupFailure: WirelessStartupFailure? = null,
        englishReason: String = reason,
    ) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        val retainedStartup = startupFailure?.retryable != false && controller?.canRetryWirelessStartup() == true
        val startupDelay = if ((startupFailure != null && startupFailure.retryable) || retainedStartup)
            startupRetryBudget.nextDelayMillis() else null
        if ((startupFailure != null || retainedStartup) && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            if (startupFailure?.retryable == false) {
                setConnectionStage(reason, englishReason)
            } else {
                setConnectionStage("$reason\n${getString(R.string.wireless_startup_retries_exhausted)}",
                    "$englishReason\n${englishResources.getString(R.string.wireless_startup_retries_exhausted)}")
            }
            appendLog("wireless startup recovery stopped generation=$restartGeneration reason=$startupFailure retries=${startupRetryBudget.retries}")
            return
        }
        reconnectScheduled = true
        val ticket = ++reconnectTicket
        val sessionAtSchedule = activeAirPlaySession
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${startupRetryBudget.retries}")
        mainHandler.postDelayed(
            {
                if (ticket != reconnectTicket || generation != restartGeneration) return@postDelayed
                reconnectScheduled = false
                if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress || startupRetryStopped ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                if (activeAirPlaySession != null &&
                    (retainedStartup || startupFailure != null || activeAirPlaySession !== sessionAtSchedule)) return@postDelayed
                retryStartupOrRestart("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** A connected Bluetooth profile may arrive after the initial wireless retry budget ran out. */
    internal fun onPhoneBluetoothConnected(address: String): String {
        if (UsbAutoConnect.priorityDevice(this) != null) return "usb-priority"
        if (!CarPlayBackgroundSession.isOwner(this) || shuttingDown.get()) return "session-stopping"
        if (controller?.matchesWirelessPhone(address) != true) return "session-other-transport-or-phone"
        if (activeAirPlaySession != null || CarPlayBackgroundSession.active) return "session-active"
        if (menuOpen || handshakeResetInProgress) return "session-busy"
        if (!startupRetryStopped) return "connection-in-progress"
        if (!bluetoothRecoveryAllowed) return "requires-user-action"
        startupRetryBudget.manualRetry()
        startupRetryStopped = false
        reconnectAttempts = 0
        retryStartupOrRestart("Selected iPhone Bluetooth connected; resuming wireless startup")
        return "wireless-retry-resumed"
    }

    private fun retryStartupOrRestart(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this) || shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        ++reconnectTicket
        reconnectScheduled = false
        if (controller?.retryWirelessStartup() == true) {
            startupFailureGeneration = -1
            startupRetryButton?.visibility = View.GONE
            setConnectionStage(reason)
            appendLog("$reason; retrying wireless startup with the same temporary hotspot")
        } else restartCarPlay(reason)
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        ++reconnectTicket
        reconnectScheduled = false
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        activeAirPlaySession = null
        CarPlayBackgroundSession.active = false
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        discardLastPicture()
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.prepareTemporaryHotspotHandoff()
            oldController?.close()
            var completed: Boolean
            // Shutdown is queued on this executor too. It must inherit a completed retirement,
            // rather than treating cancellation of the replacement as completion of the old one.
            do {
                completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
                AsyncDiagnosticLog.append(
                    diagnosticLog,
                    "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                        "restart teardownWaitCompleted=$completed " +
                        "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
                )
            } while (!completed && !Thread.currentThread().isInterrupted)
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (completed && !shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    requestStartupPrerequisites()
                }
            }
        }
    }

    private fun connectionAttemptPending(): Boolean =
        (activeAirPlaySession == null && !CarPlayBackgroundSession.active) ||
            reconnectScheduled || handshakeResetInProgress || startupRetryStopped

    private fun returnHomeFromConnection() {
        if (!connectionAttemptPending() || shuttingDown.get()) return
        if (menuOpen) leaveSettingsMenu { showDiPlayHome() } else showDiPlayHome()
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        // Leaving the connection screen is an explicit cancellation, including late permissions
        // and a stack replacement already waiting for the previous controller to close.
        if (page != "settings" && connectionAttemptPending() && !shuttingDown.get()) {
            ConnectionAutoStart.cancel()
            UsbAutoConnect.cancelForHome(this)
            startupRetryStopped = true
            bluetoothRecoveryAllowed = false
            reconnectScheduled = false
            ++reconnectTicket
            recoveryPendingAfterMenu = false
            failurePendingAfterMenu = null
            startAfterHandshakeReset = false
            shutdown(false, "connection return home")
            finish()
        }
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page)
            .putExtra(DiPlayActivity.EXTRA_SETTINGS_RETURN_TO_CARPLAY, page == "settings")
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Keep a timed-out sink visible to surface teardown until its codecs have actually been released. */
    private fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        // Preserve bounded teardown sequencing without blocking the UI or forgetting a live worker.
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    private fun openSettingsMenu() {
        if (menuOpen) return
        closePicturePanel()
        controller?.sendTouch(emptyList())
        loadPersistedSettings()
        settingsBaseline = captureSettingsBaseline()
        menuSettingsSignature = menuSettingsSignature()
        // Rebuild controls from saved values so a cancelled edit cannot reappear on reopening.
        settingsMenu?.let { previous ->
            val parent = previous.parent as ViewGroup
            val index = parent.indexOfChild(previous)
            parent.removeView(previous)
            settingsMenu = buildSettingsMenu()
            parent.addView(settingsMenu, index, FrameLayout.LayoutParams(-1, -1))
        }
        menuOpen = true
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        updateDebugOverlays()
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val session = activeAirPlaySession ?: return
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        controller?.sendTouch(emptyList())
        root.post {
            if (generation != picturePanelGeneration || activeAirPlaySession !== session || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(
                this,
                adjustmentsAvailable = fallbackVideoView == null,
                initialPalette = overlayPalette,
                close = ::closePicturePanel,
            )
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved", reconnect = true)
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded", reconnect = false)
    }

    /**
     * The exits that do not announce a discard: the links to Home and the full settings screen.
     * The close control's own description says it discards, and a USB attachment is not the
     * driver leaving, so both keep calling [cancelSettingsEdits] without a question.
     *
     * [onLeft] runs only once the menu has actually closed, so nothing navigates behind the dialog.
     */
    private fun leaveSettingsMenu(onLeft: () -> Unit = {}) {
        if (!menuOpen) return
        if (menuSettingsSignature?.let { it != menuSettingsSignature() } == true) {
            // Follow the menu's light or dark palette, like DiPlay's other dialogs.
            AlertDialog.Builder(this, if (appNight) R.style.Theme_Xcertplay_Dialog_Dark else R.style.Theme_Xcertplay_Dialog_Light)
                .setTitle(getString(R.string.settings_discard_pending_title))
                .setMessage(getString(R.string.settings_discard_pending_message))
                .setPositiveButton(getString(R.string.save_and_reconnect)) { _, _ -> saveSettingsAndReconnect() }
                .setNegativeButton(getString(R.string.settings_discard_pending_confirm)) { _, _ ->
                    cancelSettingsEdits()
                    onLeft()
                }
                .show()
            return
        }
        cancelSettingsEdits()
        onLeft()
    }

    private fun finishSettingsMenu(prefix: String, reconnect: Boolean) {
        if (!menuOpen) return
        menuOpen = false
        menuSettingsSignature = null
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        settingsGestureHint?.text = settingsGestureHintText()
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; fixed resolution ${nativeCarPlaySize().width}x${nativeCarPlaySize().height} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        val failure = failurePendingAfterMenu
        failurePendingAfterMenu = null
        val recoveryPending = recoveryPendingAfterMenu || failure != null
        recoveryPendingAfterMenu = false
        if (reconnect) {
            startupRetryBudget.manualRetry()
            startupRetryStopped = false
        }
        if (failure != null && (!failure.retryable || failure.startupFailure != null) && !reconnect) {
            createStatusReporter(restartGeneration)(failure)
        } else if (failure?.wifiResetRequired == true && (!reconnect || wirelessEnabled)) {
            createStatusReporter(restartGeneration)(failure)
        } else if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else if ((reconnect || recoveryPending) && controller != null) {
            if (reconnect) restartCarPlay("Settings saved; reconnecting")
            else retryStartupOrRestart("Connection lost while settings were open; reconnecting")
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private var shutdownComplete = false
    private val shutdownWaiters = mutableListOf<() -> Unit>()

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (shutdownComplete) { completion(); return }
        shutdownWaiters.add(completion)
        if (!shuttingDown.compareAndSet(false, true)) return
        awaitingVpnConsent = false
        vpnReady = false
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        // Retain the owner/stop action while clearing the adoptable snapshot. Manual reconnect
        // and Bluetooth wake must wait for old sockets and the hotspot reservation to retire.
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        discardLastPicture()
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        val diagnosticLog = sessionLog
        AsyncDiagnosticLog.append(diagnosticLog, "Disconnect requested reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            val started = System.nanoTime()
            if (!terminateProcess && reason in setOf("DiPlay disconnect", "connection return home")) oldController?.prepareTemporaryHotspotHandoff()
            oldController?.close()
            var clean: Boolean
            do {
                clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
                AsyncDiagnosticLog.append(diagnosticLog,
                    "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} shutdown teardownWaitCompleted=$clean " +
                        "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}")
            } while (!clean && !Thread.currentThread().isInterrupted)
            if (!clean) return@execute
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                com.shilapi.xcertplay.network.TemporaryHotspotReservationStore.release()
                applicationContext.stopService(Intent(applicationContext, com.shilapi.xcertplay.network.CarPlaySocketService::class.java))
                applicationContext.stopService(Intent(applicationContext, com.shilapi.xcertplay.network.CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            AsyncDiagnosticLog.append(diagnosticLog, "Disconnect cleanup complete; next connection may start")
            AsyncDiagnosticLog.awaitIdle(1_000)
            teardownExecutor.shutdown()
            mainHandler.post {
                if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
                shutdownComplete = true
                if (isDestroyed) {
                    sessionLog?.close()
                    sessionLog = null
                }
                val callbacks = shutdownWaiters.toList()
                shutdownWaiters.clear()
                callbacks.forEach { it() }
            }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!texture.isAttachedToWindow) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val mode = carPlayVideoSurfaceMode(texture.isHardwareAccelerated, smoothVideo, directVideoOutput)
                appendLog("Video output mode=$mode windowHardwareAccelerated=${texture.isHardwareAccelerated} " +
                    "smoothVideo=$smoothVideo directVideoOutput=$directVideoOutput")
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture)
                return false // Measure the replacement before drawing the software window.
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    private fun useFallbackVideoSurface(texture: TextureView) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply { clipChildren = true }
        val surfaceView = SurfaceView(this)
        pictureBinding?.close()
        pictureBinding = null
        // Detach our wrapper before removing its TextureView; a late destruction callback
        // must not clear the framework-owned replacement surface.
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        appendLog(if (smoothVideo) {
            "Using SurfaceView video output: smooth video, frames shown at the iPhone's frame time + a delay " +
                "starting at ${smoothVideoDelayMillis(fps)} ms; picture adjustments unavailable"
        } else if (directVideoOutput) {
            "Using SurfaceView video output: direct video output, frames shown as soon as they are decoded; " +
                "picture adjustments unavailable"
        } else {
            "Using SurfaceView video output: window has no hardware acceleration; picture adjustments unavailable"
        })
    }

    // Copies the SurfaceView's current picture (at half size) before its surface goes, waiting briefly for it.
    private fun captureLastPicture() {
        val surfaceView = fallbackVideoView ?: return
        val bounds = fallbackVideoBounds ?: return
        if (!surfaceView.holder.surface.isValid) return
        val picture = Bitmap.createBitmap((bounds.width / 2).coerceAtLeast(1), (bounds.height / 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        val done = java.util.concurrent.CountDownLatch(1)
        val copied = java.util.concurrent.atomic.AtomicBoolean(false)
        val requested = runCatching {
            PixelCopy.request(surfaceView, picture, { result ->
                copied.set(result == PixelCopy.SUCCESS)
                done.countDown()
            }, Handler(pictureCopyThread.looper))
        }.isSuccess
        if (requested) done.await(PICTURE_COPY_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Not recycled by hand: the picture shown last may still be on screen when DiPlay leaves again.
        if (copied.get()) lastPicture = picture
        appendLog("Last picture kept=${copied.get()}")
    }

    private fun showLastPicture() {
        if (sink == null) return
        val picture = lastPicture ?: return
        val surfaceView = fallbackVideoView ?: return
        val viewport = surfaceView.parent as? FrameLayout ?: return
        val hold = pictureHold?.takeIf { it.parent === viewport } ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
        }.also {
            pictureHold?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
            pictureHold = it
            viewport.addView(it)
        }
        (surfaceView.layoutParams as? FrameLayout.LayoutParams)?.let { hold.layoutParams = FrameLayout.LayoutParams(it) }
        hold.animate().cancel()
        hold.setImageBitmap(picture)
        hold.alpha = 1f
        hold.visibility = View.VISIBLE
        pictureHoldBaseline = sink?.liveVideoCounters()?.rendered ?: -1L
        pictureHoldStartMillis = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.postDelayed(pictureHoldTick, 50L)
        appendLog("Last picture shown until a new frame")
    }

    // Forgets the last picture with its session, and stops showing or asking for one.
    private fun discardLastPicture() {
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.removeCallbacks(pictureRetry)
        pictureHold?.let { hold ->
            hold.animate().cancel()
            hold.visibility = View.GONE
            hold.setImageDrawable(null)
        }
        lastPicture = null
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean = touchController.onTouch(view, event)

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(stringId: Int) {
        val message = getString(stringId)
        val englishMessage = englishResources.getString(stringId)
        runOnUiThread {
            setConnectionStage(message, englishMessage)
            appendLog(message)
        }
    }

    private fun setConnectionStage(stringId: Int) =
        setConnectionStage(getString(stringId), englishResources.getString(stringId))

    private fun showConnectionHelp(failure: CarPlayStatus.Failed?) {
        connectionInstructionsView?.visibility = if (failure == null) View.VISIBLE else View.GONE
        connectionHelpView?.apply {
            visibility = if (failure == null) View.GONE else View.VISIBLE
            if (failure != null) text = getString(ConnectionFailureGuidance.message(failure, wirelessEnabled, wirelessHotspotMode))
        }
    }

    /** [englishMessage] is [message] in English, which [friendlyStage] reads for its keywords. */
    private fun setConnectionStage(message: String, englishMessage: String = message) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message, englishMessage)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    // The keywords are English; matching the English text keeps the result the same in every UI language.
    private fun friendlyStage(message: String, englishMessage: String = message): String = when {
        message == getString(R.string.vpn_authorization_unavailable) -> message
        message == getString(R.string.waiting_for_mfi_coprocessor) ||
            message == getString(R.string.requesting_mfi_usb_permission) -> message
        englishMessage.contains("CarPlay configuration request", true) -> getString(R.string.iphone_usb_mode_rejected)
        englishMessage.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        englishMessage.contains(BluetoothRadioCheck.RADIO_OFF_MARK, true) -> getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect)
        englishMessage.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        englishMessage.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        englishMessage.contains(BluetoothLinkCheck.UNREAL_MARK, true) -> getString(R.string.bluetooth_connections_not_real)
        englishMessage.contains("socket", true) || englishMessage.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        englishMessage.contains("unsupported", true) || englishMessage.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        englishMessage.contains("denied", true) || englishMessage.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        englishMessage.contains("Failed", true) || englishMessage.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        englishMessage.contains("Waiting for iPhone", true) || englishMessage.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        englishMessage.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        englishMessage.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        englishMessage.contains("reconnect", true) || englishMessage.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        englishMessage.contains("active", true) || englishMessage.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) = appendLog(message, DiagnosticLevel.EVENT)

    private fun appendLog(message: String, level: DiagnosticLevel) {
        if (!LogPolicy.enabled(level)) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()), level)
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath} logging=${if (LogPolicy.detailedEnabled) "debug-detailed" else "release-events"}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun CarPlayStatus.describe(res: Resources = resources): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> res.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> res.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> res.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> res.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> res.getString(R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady -> res.getString(R.string.status_hotspot_ready,
            backend, ssid, band, if (channel == 0) res.getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> res.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> res.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> res.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> res.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> res.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> res.getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> res.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> res.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> res.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> res.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> res.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> res.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) res.getString(R.string.starting_airplay_service) else res.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> res.getString(R.string.carplay_control_running)
        CarPlayStatus.TestingUsbNcm -> res.getString(R.string.status_testing_usb_ncm)
        CarPlayStatus.ControlEnded -> res.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> res.getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> res.getString(R.string.first_tcp_timeout)
            else -> res.getString(R.string.status_failed, message)
        }
    }

    private companion object {
        const val PREFACE_CANVAS_WIDTH = 1920
        const val PREFACE_CANVAS_WIDTH_WITH_NAVIGATION_BAR = 1760
        const val PREFACE_CANVAS_HEIGHT = 720
        private const val SETTINGS_EXPERIMENTAL_MENU_CONTROL_HEIGHT_DP = 52
        const val CLUSTER_SMALL_WINDOW_ON = 1
        const val CLUSTER_SMALL_WINDOW_AUTO = 2
        /** How long the last picture may stand in for the video after returning, and the wait to copy it. */
        const val PICTURE_HOLD_MAX_MILLIS = 30_000L
        const val PICTURE_COPY_WAIT_MILLIS = 150L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        // On the teardown thread: bounded sequencing wait. Release notification retains ownership beyond
        // this budget when a codec is still busy.
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_DP = CarPlayQuickMenu.MAX_SETTINGS_MENU_WIDTH_DP
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}
