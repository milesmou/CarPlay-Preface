package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.AmbientLightThreshold
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.CarPlayNightMode
import com.shilapi.xcertplay.CarPlayNightSchedule
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.PREFS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_DISPLAY_SCALE_TENTHS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_UI_SCALE_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_HEVC_ENABLED
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_HEVC_SOFTWARE_DECODER
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_NAVIGATION_STREAM_TYPE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_APP_APPEARANCE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CARPLAY_NIGHT_MODE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CARPLAY_NIGHT_START
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CARPLAY_NIGHT_END
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_AMBIENT_LUX_THRESHOLD
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_FPS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_SMOOTH_VIDEO
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MTK_DECODER_TUNING
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_DIRECT_VIDEO_OUTPUT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_LOW_LATENCY_DECODER
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_FPS_COUNTER
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_SETTINGS_GESTURE_FINGERS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_WIDTH_PHYSICAL_MM
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_PHYSICAL_SIZE_BASIS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MAX_DETECTED_WIDTH
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MAX_DETECTED_HEIGHT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_RIGHT_HAND_DRIVE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_HIDE_TOP_BAR
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_HIDE_BOTTOM_BAR
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_HARMAN_FULLSCREEN
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_SAFE_AREA_DRAW_OUTSIDE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.SAFE_AREA_KEY_PREFIX

/** Display persistence. Retains existing keys, defaults and validation. */
internal object DisplaySettingsStore {
    fun loadAmbientDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("ambient_delay_seconds", 2).coerceIn(0, 60)

    fun saveAmbientDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ambient_delay_seconds", seconds.coerceIn(0, 60)).apply()
    }

    fun loadDisplayScalePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("display_scale_percent", loadDisplayScaleTenths(context) * 10)
            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)

    fun saveDisplayScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                "display_scale_percent",
                percent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT),
            ).apply()
    }

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int =
        AudioSettingsStore.loadNavigationAudioChannel(context)

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    fun loadAmbientLightThreshold(context: Context): AmbientLightThreshold = AmbientLightThreshold.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AMBIENT_LUX_THRESHOLD, AmbientLightThreshold.DEFAULT_LUX),
    )

    fun saveAmbientLightThreshold(context: Context, threshold: AmbientLightThreshold) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AMBIENT_LUX_THRESHOLD, threshold.lux).apply()
    }

    fun loadAppAppearance(context: Context): AppAppearance = AppAppearance.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_APPEARANCE, null),
    )

    fun saveAppAppearance(context: Context, appearance: AppAppearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_APPEARANCE, appearance.key).apply()
    }

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode = CarPlayNightMode.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CARPLAY_NIGHT_MODE, null),
    )

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadCarPlayNightSchedule(context: Context): CarPlayNightSchedule {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = CarPlayNightSchedule()
        val start = preferences.getInt(KEY_CARPLAY_NIGHT_START, defaults.startMinute)
        val end = preferences.getInt(KEY_CARPLAY_NIGHT_END, defaults.endMinute)
        return CarPlayNightSchedule(
            start.takeIf { it in 0 until 24 * 60 } ?: defaults.startMinute,
            end.takeIf { it in 0 until 24 * 60 } ?: defaults.endMinute,
        )
    }

    fun saveCarPlayNightSchedule(context: Context, schedule: CarPlayNightSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CARPLAY_NIGHT_START, schedule.startMinute)
            .putInt(KEY_CARPLAY_NIGHT_END, schedule.endMinute)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, AirPlayDisplaySettings.DEFAULT_FPS),
    )

    fun loadSmoothVideo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMOOTH_VIDEO, false)

    fun saveSmoothVideo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMOOTH_VIDEO, enabled).apply()
    }

    /** MediaTek's legacy low-latency/no-reorder decoder path; opt-in and applied at reconnect. */
    fun loadMtkDecoderTuning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MTK_DECODER_TUNING, false)

    fun saveMtkDecoderTuning(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MTK_DECODER_TUNING, enabled).apply()
    }

    fun loadDirectVideoOutput(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DIRECT_VIDEO_OUTPUT, false)

    fun saveDirectVideoOutput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRECT_VIDEO_OUTPUT, enabled).apply()
    }

    fun loadFpsCounter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FPS_COUNTER, false)

    fun saveFpsCounter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FPS_COUNTER, enabled).apply()
    }

    fun loadLowLatencyDecoder(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOW_LATENCY_DECODER, false)

    fun saveLowLatencyDecoder(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOW_LATENCY_DECODER, enabled).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    /** Fingers for the swipe-down that opens settings; some head units reserve three. */
    fun loadSettingsGestureFingers(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_GESTURE_FINGERS, 3).let { if (it == 0) 0 else it.coerceIn(2, 4) }

    fun saveSettingsGestureFingers(context: Context, fingers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_GESTURE_FINGERS, if (fingers == 0) 0 else fingers.coerceIn(2, 4)).apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadHarmanFullscreen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HARMAN_FULLSCREEN, false)

    fun saveHarmanFullscreen(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HARMAN_FULLSCREEN, enabled)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    /** Off by default: square full-screen video is for screens that show a gap or wallpaper corners (#578, #586). */
    fun loadMainDisplayDisableSafeArea(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA, false)

    fun saveMainDisplayDisableSafeArea(context: Context, disable: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA, disable)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
