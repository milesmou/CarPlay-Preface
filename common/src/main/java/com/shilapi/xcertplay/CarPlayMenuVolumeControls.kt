package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayAudioOutputs
import com.shilapi.xcertplay.media.CarPlayOutputRoute

/** Explicit touch volume controls do not depend on the optional wheel volume policy. */
internal class CarPlayMenuVolume(
    private val audio: AudioManager?,
    private val configuredStream: (Boolean) -> Int,
    val bluetoothAudio: () -> Boolean,
    val suppressed: () -> Boolean,
    private val liveRoute: (Boolean) -> CarPlayOutputRoute? = CarPlayAudioOutputs::route,
    private val report: (String) -> Unit = {},
) {
    data class Level(val stream: Int, val minimum: Int, val maximum: Int, val current: Int)

    fun read(navigation: Boolean): Level? = try {
        val manager = audio
        if (manager == null || manager.isVolumeFixed || navigation && bluetoothAudio()) null
        else {
            val route = if (bluetoothAudio()) null else liveRoute(navigation)
            val stream = if (bluetoothAudio()) AudioManager.STREAM_MUSIC
                else if (route != null) route.legacyStreamType ?: AudioManager.STREAM_MUSIC
                else configuredStream(navigation).takeIf { it != 0 } ?: AudioManager.STREAM_MUSIC
            if (stream !in 1..20) null
            else {
                val minimum = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(stream) else 0
                val maximum = manager.getStreamMaxVolume(stream)
                val current = manager.getStreamVolume(stream)
                if (minimum < 0 || maximum <= minimum || current !in minimum..maximum) null
                else Level(stream, minimum, maximum, current)
            }
        }
    } catch (_: Exception) { null }

    fun apply(navigation: Boolean, displayed: Level, target: Int): Boolean {
        if (suppressed()) return false
        val current = read(navigation) ?: return false
        // A route may have changed since the slider was displayed or since the drag started.
        if (current.stream != displayed.stream || current.minimum != displayed.minimum ||
            current.maximum != displayed.maximum) return false
        val value = target.coerceIn(current.minimum, current.maximum)
        if (value == current.current) return true
        if (suppressed()) return false
        return try {
            // The menu supplies feedback; no system volume popup or preview sound is needed.
            audio?.setStreamVolume(current.stream, value, 0)
            val actual = read(navigation)?.current
            report("Quick menu volume navigation=$navigation stream=${current.stream} requested=$value actual=$actual")
            actual == value
        } catch (error: Exception) {
            report("Quick menu volume navigation=$navigation stream=${current.stream} unavailable=${error.javaClass.simpleName}")
            false
        }
    }
}

internal class CarPlayMenuVolumeControls(
    context: Context,
    private val palette: DiPlayPalette,
    private val volume: CarPlayMenuVolume,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private var tracking = false
    private var adjustmentFailed = false
    private inner class Control(val navigation: Boolean, title: Int) {
        var displayed: CarPlayMenuVolume.Level? = null
        val label = TextView(context).apply {
            text = context.getString(title)
            textSize = 16f
            setTextColor(palette.primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START
        }
        val value = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val slider = SeekBar(context).apply {
            contentDescription = context.getString(title)
            fun track(color: Int) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(VOLUME_TRACK_THICKNESS_DP) / 2f
            }
            progressDrawable = LayerDrawable(arrayOf(
                track(palette.trackOff),
                ClipDrawable(track(palette.accent), Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (index in 0 until numberOfLayers) {
                    setLayerHeight(index, dp(VOLUME_TRACK_THICKNESS_DP))
                    setLayerGravity(index, Gravity.CENTER_VERTICAL)
                }
            }
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
        }
        val view = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LayoutParams(0, -2, 1f))
                addView(value, LayoutParams(-2, -2).apply { marginStart = dp(VOLUME_BLOCK_GAP_DP) })
            }, LayoutParams(-1, -2))
            addView(slider, LayoutParams(-1, dp(VOLUME_SLIDER_HEIGHT_DP)))
        }
        private val titleText = context.getString(title)
        init {
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    displayed?.let { adjustmentFailed = !volume.apply(navigation, it, progress + it.minimum) }
                    refresh(force = true)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) { tracking = false; refresh() }
            })
        }
        fun update(blocked: Boolean) {
            displayed = volume.read(navigation)
            val level = displayed
            val description = if (level == null) context.getString(R.string.settings_volume_unavailable_label, titleText)
                else context.getString(R.string.settings_volume_level, titleText, level.current, level.maximum)
            value.text = if (level == null) context.getString(R.string.settings_volume_unavailable_value)
                else "${level.current}/${level.maximum}"
            value.setTextColor(if (level == null || blocked) palette.secondaryText else palette.accent)
            slider.contentDescription = description
            slider.isEnabled = level != null && !blocked
            slider.max = level?.let { it.maximum - it.minimum } ?: 0
            slider.progress = level?.let { it.current - it.minimum } ?: 0
        }
    }
    private val navigation = Control(true, R.string.settings_navigation_volume)
    private val media = Control(false, R.string.settings_media_volume)
    private val hint = TextView(context).apply {
        textSize = 14f
        setTextColor(palette.secondaryText)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    init {
        orientation = VERTICAL
        addView(navigation.view, LayoutParams(-1, -2))
        addView(media.view, LayoutParams(-1, -2).apply { topMargin = dp(VOLUME_BLOCK_GAP_DP) })
        addView(hint, LayoutParams(-1, -2).apply { topMargin = dp(VOLUME_BLOCK_GAP_DP) })
        refresh()
    }

    fun refresh(force: Boolean = false) {
        if (tracking && !force) return
        val blocked = volume.suppressed()
        navigation.update(blocked)
        media.update(blocked)
        val message = when {
            blocked -> R.string.settings_volume_busy
            volume.bluetoothAudio() -> R.string.settings_volume_bluetooth_navigation
            navigation.displayed == null || media.displayed == null -> R.string.settings_volume_unavailable_hint
            adjustmentFailed -> R.string.settings_volume_adjustment_failed
            navigation.displayed?.stream == media.displayed?.stream -> R.string.settings_volume_shared
            else -> null
        }
        hint.visibility = if (message == null) View.GONE else View.VISIBLE
        hint.text = message?.let(context::getString) ?: ""
    }

    companion object {
        private const val VOLUME_TRACK_THICKNESS_DP = 4
        private const val VOLUME_SLIDER_HEIGHT_DP = 56
        private const val VOLUME_BLOCK_GAP_DP = 8
    }
}
