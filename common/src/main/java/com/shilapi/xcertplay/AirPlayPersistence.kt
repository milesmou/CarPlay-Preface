package com.shilapi.xcertplay

import com.shilapi.xcertplay.settings.AudioSettingsStore
import com.shilapi.xcertplay.settings.ConnectionSettingsStore
import com.shilapi.xcertplay.settings.DisplaySettingsStore
import com.shilapi.xcertplay.settings.OverlaySettingsStore
import com.shilapi.xcertplay.settings.IdentitySettingsStore
import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File

/** Stable callers' facade. Domain stores own defaults, validation and persistence. */
object AirPlayPersistence {
    /** 0 uses usage-based routing; 1–20 select head-unit stream types. */
    val AUDIO_CHANNELS = 0..20
    const val DEFAULT_MANUFACTURER = "吉利"
    const val DEFAULT_MODEL = "星瑞"
    const val DEFAULT_OEM_LABEL = "Preface"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    /** Live overlay changes are observed by the current projection host. */

    fun loadAmbientDelaySeconds(context: Context): Int =
        DisplaySettingsStore.loadAmbientDelaySeconds(context)

    fun saveAmbientDelaySeconds(context: Context, seconds: Int) =
        DisplaySettingsStore.saveAmbientDelaySeconds(context, seconds)

    fun loadDisplayScalePercent(context: Context): Int =
        DisplaySettingsStore.loadDisplayScalePercent(context)

    fun saveDisplayScalePercent(context: Context, percent: Int) =
        DisplaySettingsStore.saveDisplayScalePercent(context, percent)

    fun loadDisplayScaleTenths(context: Context): Int =
        DisplaySettingsStore.loadDisplayScaleTenths(context)

    fun saveDisplayScaleTenths(context: Context, tenths: Int) =
        DisplaySettingsStore.saveDisplayScaleTenths(context, tenths)

    fun loadHevcEnabled(context: Context): Boolean =
        DisplaySettingsStore.loadHevcEnabled(context)

    fun loadUiScalePercent(context: Context): Int =
        DisplaySettingsStore.loadUiScalePercent(context)

    fun saveUiScalePercent(context: Context, percent: Int) =
        DisplaySettingsStore.saveUiScalePercent(context, percent)

    fun saveHevcEnabled(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveHevcEnabled(context, enabled)

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        DisplaySettingsStore.loadHevcSoftwareDecoderEnabled(context)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveHevcSoftwareDecoderEnabled(context, enabled)

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        AudioSettingsStore.loadAdvancedAudioChannelMapping(context)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveAdvancedAudioChannelMapping(context, enabled)

    fun loadNavigationStreamType(context: Context): Int =
        DisplaySettingsStore.loadNavigationStreamType(context)

    fun saveNavigationStreamType(context: Context, streamType: Int) =
        DisplaySettingsStore.saveNavigationStreamType(context, streamType)

    fun loadAudioFocusEnabled(context: Context): Boolean =
        AudioSettingsStore.loadAudioFocusEnabled(context)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveAudioFocusEnabled(context, enabled)

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        AudioSettingsStore.loadAudioFocusAutoYield(context)

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveAudioFocusAutoYield(context, enabled)

    fun loadDisableMediaKeyAudioFocus(context: Context): Boolean =
        AudioSettingsStore.loadDisableMediaKeyAudioFocus(context)

    fun saveDisableMediaKeyAudioFocus(context: Context, disabled: Boolean) =
        AudioSettingsStore.saveDisableMediaKeyAudioFocus(context, disabled)

    fun loadMediaAudioChannel(context: Context): Int =
        AudioSettingsStore.loadMediaAudioChannel(context)

    fun saveMediaAudioChannel(context: Context, channel: Int) =
        AudioSettingsStore.saveMediaAudioChannel(context, channel)

    fun loadNavigationAudioChannel(context: Context): Int =
        AudioSettingsStore.loadNavigationAudioChannel(context)

    fun saveNavigationAudioChannel(context: Context, channel: Int) =
        AudioSettingsStore.saveNavigationAudioChannel(context, channel)

    fun loadWirelessEnabled(context: Context): Boolean =
        ConnectionSettingsStore.loadWirelessEnabled(context)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) =
        ConnectionSettingsStore.saveWirelessEnabled(context, enabled)

    fun loadMfiTarget(context: Context): MfiTarget =
        ConnectionSettingsStore.loadMfiTarget(context)

    fun saveMfiTarget(context: Context, target: MfiTarget) =
        ConnectionSettingsStore.saveMfiTarget(context, target)

    fun loadMfiI2cPath(context: Context): String =
        ConnectionSettingsStore.loadMfiI2cPath(context)

    fun saveMfiI2cPath(context: Context, path: String) =
        ConnectionSettingsStore.saveMfiI2cPath(context, path)

    fun loadRemoteMfiServer(context: Context): String =
        ConnectionSettingsStore.loadRemoteMfiServer(context)

    fun saveRemoteMfiServer(context: Context, server: String) =
        ConnectionSettingsStore.saveRemoteMfiServer(context, server)

    fun loadRemoteMfiToken(context: Context): String =
        ConnectionSettingsStore.loadRemoteMfiToken(context)

    fun saveRemoteMfiToken(context: Context, token: String) =
        ConnectionSettingsStore.saveRemoteMfiToken(context, token)

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode =
        ConnectionSettingsStore.loadWirelessHotspotMode(context)

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) =
        ConnectionSettingsStore.saveWirelessHotspotMode(context, mode)

    fun loadManualHotspotSsid(context: Context): String =
        ConnectionSettingsStore.loadManualHotspotSsid(context)

    fun saveManualHotspotSsid(context: Context, ssid: String) =
        ConnectionSettingsStore.saveManualHotspotSsid(context, ssid)

    fun loadManualHotspotPassphrase(context: Context): String =
        ConnectionSettingsStore.loadManualHotspotPassphrase(context)

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) =
        ConnectionSettingsStore.saveManualHotspotPassphrase(context, passphrase)

    fun loadManualHotspotBand(context: Context): ManualHotspotBand =
        ConnectionSettingsStore.loadManualHotspotBand(context)

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) =
        ConnectionSettingsStore.saveManualHotspotBand(context, band)

    fun loadManualHotspotChannel(context: Context): Int =
        ConnectionSettingsStore.loadManualHotspotChannel(context)

    fun saveManualHotspotChannel(context: Context, channel: Int) =
        ConnectionSettingsStore.saveManualHotspotChannel(context, channel)

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity =
        ConnectionSettingsStore.loadManualHotspotSecurity(context)

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) =
        ConnectionSettingsStore.saveManualHotspotSecurity(context, security)

    fun loadDebugLogsEnabled(context: Context): Boolean =
        ConnectionSettingsStore.loadDebugLogsEnabled(context)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) =
        ConnectionSettingsStore.saveDebugLogsEnabled(context, enabled)

    fun loadAutoStartOnBoot(context: Context): Boolean =
        ConnectionSettingsStore.loadAutoStartOnBoot(context)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) =
        ConnectionSettingsStore.saveAutoStartOnBoot(context, enabled)

    fun loadExternalController(context: Context): Boolean =
        ConnectionSettingsStore.loadExternalController(context)

    fun saveExternalController(context: Context, enabled: Boolean) =
        ConnectionSettingsStore.saveExternalController(context, enabled)

    fun loadUsbChargingCurrent(context: Context): UsbChargingCurrent =
        ConnectionSettingsStore.loadUsbChargingCurrent(context)

    fun saveUsbChargingCurrent(context: Context, current: UsbChargingCurrent) =
        ConnectionSettingsStore.saveUsbChargingCurrent(context, current)

    fun loadLocationReportingEnabled(context: Context): Boolean =
        ConnectionSettingsStore.loadLocationReportingEnabled(context)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) =
        ConnectionSettingsStore.saveLocationReportingEnabled(context, enabled)

    fun loadManufacturer(context: Context): String =
        IdentitySettingsStore.loadManufacturer(context)

    fun saveManufacturer(context: Context, manufacturer: String) =
        IdentitySettingsStore.saveManufacturer(context, manufacturer)

    fun loadModel(context: Context): String =
        IdentitySettingsStore.loadModel(context)

    fun saveModel(context: Context, model: String) =
        IdentitySettingsStore.saveModel(context, model)

    fun loadOemLabel(context: Context): String =
        IdentitySettingsStore.loadOemLabel(context)

    fun saveOemLabel(context: Context, oemLabel: String) =
        IdentitySettingsStore.saveOemLabel(context, oemLabel)

    fun loadAmbientLightThreshold(context: Context): AmbientLightThreshold =
        DisplaySettingsStore.loadAmbientLightThreshold(context)

    fun saveAmbientLightThreshold(context: Context, threshold: AmbientLightThreshold) =
        DisplaySettingsStore.saveAmbientLightThreshold(context, threshold)

    fun loadAppAppearance(context: Context): AppAppearance =
        DisplaySettingsStore.loadAppAppearance(context)

    fun saveAppAppearance(context: Context, appearance: AppAppearance) =
        DisplaySettingsStore.saveAppAppearance(context, appearance)

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode =
        DisplaySettingsStore.loadCarPlayNightMode(context)

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) =
        DisplaySettingsStore.saveCarPlayNightMode(context, mode)

    fun loadCarPlayNightSchedule(context: Context): CarPlayNightSchedule =
        DisplaySettingsStore.loadCarPlayNightSchedule(context)

    fun saveCarPlayNightSchedule(context: Context, schedule: CarPlayNightSchedule) =
        DisplaySettingsStore.saveCarPlayNightSchedule(context, schedule)

    fun loadFps(context: Context): Int =
        DisplaySettingsStore.loadFps(context)

    fun loadMediaBufferMillis(context: Context, wireless: Boolean = true): Int =
        AudioSettingsStore.loadMediaBufferMillis(context, wireless)

    fun loadMainBufferedAudio(context: Context): Boolean =
        AudioSettingsStore.loadMainBufferedAudio(context)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveMainBufferedAudio(context, enabled)

    fun loadSpeechWithout8And16Khz(context: Context): Boolean =
        AudioSettingsStore.loadSpeechWithout8And16Khz(context)

    fun saveSpeechWithout8And16Khz(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveSpeechWithout8And16Khz(context, enabled)

    fun loadCarBluetoothAudio(context: Context): Boolean =
        AudioSettingsStore.loadCarBluetoothAudio(context)

    fun saveCarBluetoothAudio(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveCarBluetoothAudio(context, enabled)

    fun loadSmoothVideo(context: Context): Boolean =
        DisplaySettingsStore.loadSmoothVideo(context)

    fun saveSmoothVideo(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveSmoothVideo(context, enabled)

    fun loadMtkDecoderTuning(context: Context): Boolean =
        DisplaySettingsStore.loadMtkDecoderTuning(context)

    fun saveMtkDecoderTuning(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveMtkDecoderTuning(context, enabled)

    fun loadDirectVideoOutput(context: Context): Boolean =
        DisplaySettingsStore.loadDirectVideoOutput(context)

    fun saveDirectVideoOutput(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveDirectVideoOutput(context, enabled)

    fun loadFpsCounter(context: Context): Boolean =
        DisplaySettingsStore.loadFpsCounter(context)

    fun saveFpsCounter(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveFpsCounter(context, enabled)

    fun loadLowLatencyDecoder(context: Context): Boolean =
        DisplaySettingsStore.loadLowLatencyDecoder(context)

    fun saveLowLatencyDecoder(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveLowLatencyDecoder(context, enabled)

    fun saveMediaBufferMillis(context: Context, millis: Int) =
        AudioSettingsStore.saveMediaBufferMillis(context, millis)

    fun saveMediaBufferMillis(context: Context, wireless: Boolean, millis: Int) =
        AudioSettingsStore.saveMediaBufferMillis(context, wireless, millis)

    fun loadCallEchoCancellation(context: Context): Boolean =
        AudioSettingsStore.loadCallEchoCancellation(context)

    fun saveCallEchoCancellation(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveCallEchoCancellation(context, enabled)

    fun loadCallVoiceFilter(context: Context): Boolean =
        AudioSettingsStore.loadCallVoiceFilter(context)

    fun saveCallVoiceFilter(context: Context, enabled: Boolean) =
        AudioSettingsStore.saveCallVoiceFilter(context, enabled)

    fun saveFps(context: Context, fps: Int) =
        DisplaySettingsStore.saveFps(context, fps)

    fun loadWidthPhysicalMm(context: Context): Int =
        DisplaySettingsStore.loadWidthPhysicalMm(context)

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) =
        DisplaySettingsStore.saveWidthPhysicalMm(context, widthPhysicalMm)

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis =
        DisplaySettingsStore.loadPhysicalSizeBasis(context)

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) =
        DisplaySettingsStore.savePhysicalSizeBasis(context, basis)

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> =
        DisplaySettingsStore.loadMaximumDetectedDisplay(context)

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) =
        DisplaySettingsStore.saveMaximumDetectedDisplay(context, widthPixels, heightPixels)

    fun loadClusterSpeedometerEnabled(context: Context): Boolean =
        OverlaySettingsStore.loadClusterSpeedometerEnabled(context)

    fun saveClusterSpeedometerEnabled(context: Context, enabled: Boolean) =
        OverlaySettingsStore.saveClusterSpeedometerEnabled(context, enabled)

    internal fun observeClusterSpeedometer(context: Context, changed: () -> Unit): () -> Unit =
        OverlaySettingsStore.observeClusterSpeedometer(context, changed)

    fun loadClusterSpeedometerX(context: Context): Int =
        OverlaySettingsStore.loadClusterSpeedometerX(context)

    fun saveClusterSpeedometerX(context: Context, value: Int) =
        OverlaySettingsStore.saveClusterSpeedometerX(context, value)

    fun loadClusterSpeedometerY(context: Context): Int =
        OverlaySettingsStore.loadClusterSpeedometerY(context)

    fun saveClusterSpeedometerY(context: Context, value: Int) =
        OverlaySettingsStore.saveClusterSpeedometerY(context, value)

    fun loadClusterSpeedometerSize(context: Context): Int =
        OverlaySettingsStore.loadClusterSpeedometerSize(context)

    fun saveClusterSpeedometerSize(context: Context, value: Int) =
        OverlaySettingsStore.saveClusterSpeedometerSize(context, value)

    fun loadClusterSpeedometerOpacity(context: Context): Int =
        OverlaySettingsStore.loadClusterSpeedometerOpacity(context)

    fun saveClusterSpeedometerOpacity(context: Context, value: Int) =
        OverlaySettingsStore.saveClusterSpeedometerOpacity(context, value)

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        OverlaySettingsStore.loadClusterContent(context)

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) =
        OverlaySettingsStore.saveClusterContent(context, content)

    fun loadSettingsGestureFingers(context: Context): Int =
        DisplaySettingsStore.loadSettingsGestureFingers(context)

    fun saveSettingsGestureFingers(context: Context, fingers: Int) =
        DisplaySettingsStore.saveSettingsGestureFingers(context, fingers)

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int =
        OverlaySettingsStore.loadClusterTurnCardOverlaySizePercent(context)

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterTurnCardOverlaySizePercent(context, percent)

    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterTurnCardOpacityPercent(context)

    fun loadClusterMarkerXPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterMarkerXPercent(context)

    fun saveClusterMarkerXPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterMarkerXPercent(context, percent)

    fun loadClusterMarkerYPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterMarkerYPercent(context)

    fun saveClusterMarkerYPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterMarkerYPercent(context, percent)

    fun loadClusterSmallWindowMarkerXPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowMarkerXPercent(context)

    fun saveClusterSmallWindowMarkerXPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowMarkerXPercent(context, percent)

    fun loadClusterSmallWindowMarkerYPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowMarkerYPercent(context)

    fun saveClusterSmallWindowMarkerYPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowMarkerYPercent(context, percent)

    fun loadClusterSmallWindowCardTheme(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowCardTheme(context)

    fun saveClusterSmallWindowCardTheme(context: Context, theme: Int) =
        OverlaySettingsStore.saveClusterSmallWindowCardTheme(context, theme)

    fun loadClusterSmallWindowCardSizePercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowCardSizePercent(context)

    fun saveClusterSmallWindowCardSizePercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowCardSizePercent(context, percent)

    fun loadClusterSmallWindowCardXPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowCardXPercent(context)

    fun saveClusterSmallWindowCardXPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowCardXPercent(context, percent)

    fun loadClusterSmallWindowCardYPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowCardYPercent(context)

    fun saveClusterSmallWindowCardYPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowCardYPercent(context, percent)

    fun loadClusterSmallWindowCardOpacityPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowCardOpacityPercent(context)

    fun saveClusterSmallWindowCardOpacityPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterSmallWindowCardOpacityPercent(context, percent)

    fun loadClusterTurnCardTheme(context: Context): Int =
        OverlaySettingsStore.loadClusterTurnCardTheme(context)

    fun saveClusterTurnCardTheme(context: Context, theme: Int) =
        OverlaySettingsStore.saveClusterTurnCardTheme(context, theme)

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterTurnCardOpacityPercent(context, percent)

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterTurnCardOverlayXPercent(context)

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterTurnCardOverlayXPercent(context, percent)

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int =
        OverlaySettingsStore.loadClusterTurnCardOverlayYPercent(context)

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterTurnCardOverlayYPercent(context, percent)

    fun loadClusterMapScalePercent(context: Context): Int =
        OverlaySettingsStore.loadClusterMapScalePercent(context)

    fun saveClusterMapScalePercent(context: Context, percent: Int) =
        OverlaySettingsStore.saveClusterMapScalePercent(context, percent)

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        OverlaySettingsStore.loadClusterMarkerHorizontalStep(context)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) =
        OverlaySettingsStore.saveClusterMarkerHorizontalStep(context, step)

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        OverlaySettingsStore.loadClusterMarkerVerticalStep(context)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) =
        OverlaySettingsStore.saveClusterMarkerVerticalStep(context, step)

    fun loadClusterSmallWindowMode(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowMode(context)

    fun saveClusterSmallWindowMode(context: Context, mode: Int) =
        OverlaySettingsStore.saveClusterSmallWindowMode(context, mode)

    fun loadClusterSmallWindowMarkerHorizontalStep(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowMarkerHorizontalStep(context)

    fun saveClusterSmallWindowMarkerHorizontalStep(context: Context, step: Int) =
        OverlaySettingsStore.saveClusterSmallWindowMarkerHorizontalStep(context, step)

    fun loadClusterSmallWindowMarkerVerticalStep(context: Context): Int =
        OverlaySettingsStore.loadClusterSmallWindowMarkerVerticalStep(context)

    fun saveClusterSmallWindowMarkerVerticalStep(context: Context, step: Int) =
        OverlaySettingsStore.saveClusterSmallWindowMarkerVerticalStep(context, step)

    fun loadClusterSafeAreaRect(context: Context): SafeAreaRect? =
        OverlaySettingsStore.loadClusterSafeAreaRect(context)

    fun saveClusterSafeAreaRect(context: Context, rect: SafeAreaRect) =
        OverlaySettingsStore.saveClusterSafeAreaRect(context, rect)

    fun clearClusterSafeAreaRect(context: Context) =
        OverlaySettingsStore.clearClusterSafeAreaRect(context)

    fun loadRightHandDrive(context: Context): Boolean =
        DisplaySettingsStore.loadRightHandDrive(context)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) =
        DisplaySettingsStore.saveRightHandDrive(context, rightHandDrive)

    fun loadHideTopBar(context: Context): Boolean =
        DisplaySettingsStore.loadHideTopBar(context)

    fun saveHideTopBar(context: Context, hide: Boolean) =
        DisplaySettingsStore.saveHideTopBar(context, hide)

    fun loadHideBottomBar(context: Context): Boolean =
        DisplaySettingsStore.loadHideBottomBar(context)

    fun saveHideBottomBar(context: Context, hide: Boolean) =
        DisplaySettingsStore.saveHideBottomBar(context, hide)

    fun loadHarmanFullscreen(context: Context): Boolean =
        DisplaySettingsStore.loadHarmanFullscreen(context)

    fun saveHarmanFullscreen(context: Context, enabled: Boolean) =
        DisplaySettingsStore.saveHarmanFullscreen(context, enabled)

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        DisplaySettingsStore.loadSafeAreaDrawOutside(context)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) =
        DisplaySettingsStore.saveSafeAreaDrawOutside(context, drawOutside)

    fun loadMainDisplayDisableSafeArea(context: Context): Boolean =
        DisplaySettingsStore.loadMainDisplayDisableSafeArea(context)

    fun saveMainDisplayDisableSafeArea(context: Context, disable: Boolean) =
        DisplaySettingsStore.saveMainDisplayDisableSafeArea(context, disable)

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? =
        DisplaySettingsStore.loadSafeAreaRect(context, widthPixels, heightPixels)

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) =
        DisplaySettingsStore.saveSafeAreaRect(context, activityWidthPixels, activityHeightPixels, rect, commit)

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) =
        DisplaySettingsStore.clearSafeAreaRect(context, activityWidthPixels, activityHeightPixels, commit)

    fun loadCustomAirPlayIconFile(context: Context): File? =
        IdentitySettingsStore.loadCustomAirPlayIconFile(context)

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) =
        IdentitySettingsStore.saveCustomAirPlayIcon(context, encodedImage)

    fun clearCustomAirPlayIcon(context: Context) =
        IdentitySettingsStore.clearCustomAirPlayIcon(context)

    fun loadIdentity(context: Context): AirPlayIdentity =
        IdentitySettingsStore.loadIdentity(context)

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore =
        IdentitySettingsStore.loadPairings(context, onSave)

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) =
        IdentitySettingsStore.savePairing(context, identifier, longTermPublicKey)

    fun loadLockdownRecord(context: Context): LockdownPairRecord? =
        IdentitySettingsStore.loadLockdownRecord(context)

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) =
        IdentitySettingsStore.saveLockdownRecord(context, record)

    fun clearLockdownRecord(context: Context) =
        IdentitySettingsStore.clearLockdownRecord(context)
}
