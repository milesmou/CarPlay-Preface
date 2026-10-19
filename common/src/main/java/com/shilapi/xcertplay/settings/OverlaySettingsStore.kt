package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.PREFS
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_MAP_SCALE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_CONTENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_X
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_Y
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MODE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_X
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_Y
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_X
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_Y
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_OPACITY
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_X_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_MARKER_Y_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_TURN_CARD_THEME
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_THEME
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_X
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_Y
import com.shilapi.xcertplay.settings.AirPlayPreferenceKeys.KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY

/** Overlay persistence. Retains existing keys, defaults and validation. */
internal object OverlaySettingsStore {
    fun loadClusterSpeedometerEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cluster_speedometer_enabled", false)

    fun saveClusterSpeedometerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cluster_speedometer_enabled", enabled).apply()
    }

    internal fun observeClusterSpeedometer(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("cluster_speedometer_") == true) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun loadClusterSpeedometerX(context: Context): Int =
        ClusterSpeedometerOverlay.snap(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("cluster_speedometer_x", ClusterSpeedometerOverlay.DEFAULT_X_PERCENT), ClusterSpeedometerOverlay.xPercents)

    fun saveClusterSpeedometerX(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("cluster_speedometer_x", ClusterSpeedometerOverlay.snap(value, ClusterSpeedometerOverlay.xPercents)).apply()
    }

    fun loadClusterSpeedometerY(context: Context): Int =
        ClusterSpeedometerOverlay.snap(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("cluster_speedometer_y", ClusterSpeedometerOverlay.DEFAULT_Y_PERCENT), ClusterSpeedometerOverlay.yPercents)

    fun saveClusterSpeedometerY(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("cluster_speedometer_y", ClusterSpeedometerOverlay.snap(value, ClusterSpeedometerOverlay.yPercents)).apply()
    }

    fun loadClusterSpeedometerSize(context: Context): Int =
        ClusterSpeedometerOverlay.snap(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("cluster_speedometer_size", ClusterSpeedometerOverlay.DEFAULT_SIZE_PERCENT), ClusterSpeedometerOverlay.sizePercents)

    fun saveClusterSpeedometerSize(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("cluster_speedometer_size", ClusterSpeedometerOverlay.snap(value, ClusterSpeedometerOverlay.sizePercents)).apply()
    }

    fun loadClusterSpeedometerOpacity(context: Context): Int =
        ClusterSpeedometerOverlay.snap(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("cluster_speedometer_opacity", ClusterSpeedometerOverlay.DEFAULT_OPACITY_PERCENT), ClusterSpeedometerOverlay.opacityPercents)

    fun saveClusterSpeedometerOpacity(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("cluster_speedometer_opacity", ClusterSpeedometerOverlay.snap(value, ClusterSpeedometerOverlay.opacityPercents)).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT),
                ClusterTurnCardOverlay.sizePercents,
            )
        }
        return when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)) {
            "SMALL" -> 40
            "LARGE" -> 70
            else -> ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        }
    }

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents),
            ).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)
            .coerceIn(20, 100)

    /**
     * Full-screen marker on the same 1 % grid as the small-window one. The old 10 % steps migrate
     * around the centre the sliders show (50 / 45), so an untouched marker reads as the default.
     */
    fun loadClusterMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_X_PERCENT, 50), CarPlayClusterDisplay.markerXPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_X, 0)
        return ClusterTurnCardOverlay.snap(
            50 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT,
            CarPlayClusterDisplay.markerXPercents,
        )
    }

    fun saveClusterMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_Y_PERCENT, 45), CarPlayClusterDisplay.markerYPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_Y, 0)
        return ClusterTurnCardOverlay.snap(
            45 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT,
            CarPlayClusterDisplay.markerYPercents,
        )
    }

    fun saveClusterMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }

    /**
     * Right of centre by default: the small navi window sits on the right half of the panel.
     * The old step values migrate onto the 1 % grid.
     */
    fun loadClusterSmallWindowMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT, 80),
                CarPlayClusterDisplay.markerXPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X, 3)
        val legacyPercent = (Math.round((49.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerXPercents)
    }

    fun saveClusterSmallWindowMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterSmallWindowMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT, 45),
                CarPlayClusterDisplay.markerYPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y, 0)
        val legacyPercent = (Math.round((45.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerYPercents)
    }

    fun saveClusterSmallWindowMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }

    /** Small-window card theme: 0 follow the full-screen card theme, 1 always day, 2 always night. */
    fun loadClusterSmallWindowCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterSmallWindowCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, theme.coerceIn(0, 2)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    /**
     * The custom turn card keeps a second rect for the small window: x/y/size, panel percents. Its
     * defaults sit right of centre and smaller, where the small navi window is. Until the driver sets
     * the small-window card, a full-screen card value the driver chose still applies, as it did before.
     */
    fun loadClusterSmallWindowCardSizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val size = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, 40)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT) || prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE) ->
                loadClusterTurnCardOverlaySizePercent(context)
            else -> 40
        }
        return ClusterTurnCardOverlay.snap(size, ClusterTurnCardOverlay.sizePercents)
    }

    fun saveClusterSmallWindowCardSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_X) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, 80)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X) || prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION) ->
                loadClusterTurnCardOverlayXPercent(context)
            else -> 80
        }
        return ClusterTurnCardOverlay.snap(x, ClusterTurnCardOverlay.smallWindowXPercents)
    }

    fun saveClusterSmallWindowCardXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowXPercents)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val y = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_Y) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, 25)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y) -> loadClusterTurnCardOverlayYPercent(context)
            else -> 25
        }
        return ClusterTurnCardOverlay.snap(y, ClusterTurnCardOverlay.smallWindowYPercents)
    }

    fun saveClusterSmallWindowCardYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowYPercents)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    /** The small-window card falls back to the shared opacity until it gets its own value. */
    fun loadClusterSmallWindowCardOpacityPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY)) {
            return prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, 85).coerceIn(20, 100)
        }
        return loadClusterTurnCardOpacityPercent(context)
    }

    fun saveClusterSmallWindowCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    /** Turn-card glass theme: 0 follow the head unit, 1 always day, 2 always night. */
    fun loadClusterTurnCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterTurnCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_THEME, theme.coerceIn(0, 2)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X)) {
            prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_X, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
        } else when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION, null)) {
            "LEFT" -> 20
            "CENTER" -> 50
            else -> ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        }
        return ClusterTurnCardOverlay.snap(raw, ClusterTurnCardOverlay.xPercents)
    }

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_X,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.xPercents),
            ).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_Y, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT),
                ClusterTurnCardOverlay.yPercents,
            )
        }
        return ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    }

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.yPercents),
            ).apply()
        AirPlayPersistence.overlaySettingsListener?.invoke()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    /** 0 off, 1 always small-window positions, 2 auto from the cluster. Default off. */
    fun loadClusterSmallWindowMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_MODE, 0).coerceIn(0, 2)

    fun saveClusterSmallWindowMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MODE, mode.coerceIn(0, 2)).apply()
    }

    fun loadClusterSmallWindowMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X, 3)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterSmallWindowMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterSmallWindowMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterSmallWindowMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    // Cluster mapping has its own key; never reuse the main display mapping at the same resolution.
    fun loadClusterSafeAreaRect(context: Context): SafeAreaRect? =
        SafeAreaCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cluster_safe_area_1920x720", null))?.clampTo(1920, 720)

    fun saveClusterSafeAreaRect(context: Context, rect: SafeAreaRect) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cluster_safe_area_1920x720", SafeAreaCodec.encode(rect.clampTo(1920, 720))).apply()
    }

    fun clearClusterSafeAreaRect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cluster_safe_area_1920x720").apply()
    }
}
