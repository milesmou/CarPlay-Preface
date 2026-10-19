package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** Audio page controls; Activity owns rendering, dialogs and the consent to reconnect. */
internal class AudioSettingsControls(
    private val ui: SettingsCardUi,
    private val markReconnectNeeded: () -> Unit,
    private val render: () -> Unit,
) {
    private fun getString(id: Int): String = ui.context.getString(id)
    private fun getString(id: Int, vararg args: Any): String = ui.context.getString(id, *args)
    fun audioOutputControls(card: LinearLayout) = with(ui) {
        val modes = MusicWheelMode.entries
        val mode = MusicWheelMode.load(context)
        val labels = listOf(R.string.settings_music_wheel_carplay,
            R.string.settings_music_wheel_bluetooth).map(::getString)
        choice(card, getString(R.string.settings_music_wheel_mode), labels, modes.indexOf(mode), reconnects = false) {
            MusicWheelMode.save(context, modes[it])
            markReconnectNeeded()
            render()
        }
        (card.getChildAt(card.childCount - 2) as? SettingButton)?.wrapContentHeight = true
        card.addView(label(getString(when (mode) {
            MusicWheelMode.CARPLAY -> R.string.settings_music_wheel_system_description
            MusicWheelMode.CAR_BLUETOOTH -> R.string.settings_car_bluetooth_audio_description
        }), 14, muted))
        card.addView(label(getString(R.string.settings_music_wheel_next_connection), 14, muted))
    }

    fun audioFocusControls(parent: LinearLayout) = with(ui) {
        toggle(parent, getString(R.string.settings_disable_media_key_audio_focus),
            getString(R.string.settings_disable_media_key_audio_focus_description),
            AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)) {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, it)
            markReconnectNeeded()
        }
        val enabled = AirPlayPersistence.loadAudioFocusEnabled(context)
        val dependent = column().apply { visibility = if (enabled) View.VISIBLE else View.GONE }
        toggle(parent, getString(R.string.contrib_audio_home_toggle_audio_focus),
            getString(R.string.contrib_audio_home_toggle_audio_focus_desc), enabled) {
            AirPlayPersistence.saveAudioFocusEnabled(context, it)
            markReconnectNeeded()
            dependent.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle(dependent, getString(R.string.audio_focus_auto_yield), getString(R.string.audio_focus_auto_yield_desc),
            AirPlayPersistence.loadAudioFocusAutoYield(context)) {
            AirPlayPersistence.saveAudioFocusAutoYield(context, it)
            markReconnectNeeded()
        }
        parent.addView(dependent)
    }

    fun musicBufferChoice(card: LinearLayout, wireless: Boolean) = with(ui) {
        val buffer = com.shilapi.xcertplay.media.MediaAudioBuffer
        val presets = if (wireless) buffer.wirelessPresets else buffer.wiredPresets
        val labels = presets.map { millis ->
            when (millis) {
                buffer.DEFAULT_MILLIS -> getString(R.string.s_300_ms_default)
                buffer.MOST_STABLE_MILLIS -> getString(R.string.s_1000_ms_most_stable)
                else -> getString(R.string.settings_music_buffer_ms, millis)
            }
        }
        val title = getString(if (wireless) R.string.settings_music_buffer_wireless else R.string.settings_music_buffer_wired)
        choice(card, title, labels,
            presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(context, wireless)).coerceAtLeast(0),
            reconnects = false) {
            AirPlayPersistence.saveMediaBufferMillis(context, wireless, presets[it])
            markReconnectNeeded()
        }
    }
}
