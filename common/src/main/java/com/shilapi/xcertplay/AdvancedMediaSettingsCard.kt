package com.shilapi.xcertplay

import android.media.MediaFormat
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.telecom.PhoneAudioSettings
import com.shilapi.xcertplay.host.R
import android.widget.LinearLayout

/** Experimental media controls reuse the same save and reconnect actions as the Activity. */
internal class AdvancedMediaSettingsCard(
    private val ui: SettingsCardUi,
    private val selectedMtkDecoderName: (String) -> String?,
    private val audioFocusControls: (LinearLayout) -> Unit,
    private val markReconnectNeeded: () -> Unit,
    private val reconnectIfRunning: () -> Unit,
    private val render: () -> Unit,
) {
    private fun getString(id: Int): String = ui.context.getString(id)

    fun render(card: LinearLayout) = with(ui) {
        toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(context)) {
            AirPlayPersistence.saveHevcEnabled(context, it)
            markReconnectNeeded()
            render()
        }
        val hevc = AirPlayPersistence.loadHevcEnabled(context)
        val selectedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val mtkDecoder = if (hevc && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context)) null
            else selectedMtkDecoderName(selectedMime)
        if (mtkDecoder != null) {
            toggle(card, getString(R.string.settings_mtk_decoder_tuning),
                getString(R.string.settings_mtk_decoder_tuning_description),
                AirPlayPersistence.loadMtkDecoderTuning(context)) {
                AirPlayPersistence.saveMtkDecoderTuning(context, it)
                markReconnectNeeded()
            }
        }
        toggle(card, getString(R.string.smooth_video), getString(R.string.smooth_video_description),
            AirPlayPersistence.loadSmoothVideo(context)) {
            AirPlayPersistence.saveSmoothVideo(context, it)
            reconnectIfRunning()
        }
        toggle(card, getString(R.string.settings_direct_video_output),
            getString(R.string.settings_direct_video_output_description),
            AirPlayPersistence.loadDirectVideoOutput(context)) {
            AirPlayPersistence.saveDirectVideoOutput(context, it)
            reconnectIfRunning()
        }
        toggle(card, getString(R.string.settings_low_latency_decoder),
            getString(R.string.settings_low_latency_decoder_description),
            AirPlayPersistence.loadLowLatencyDecoder(context)) {
            AirPlayPersistence.saveLowLatencyDecoder(context, it)
            markReconnectNeeded()
        }
        toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
            AirPlayPersistence.loadCallEchoCancellation(context)) {
            AirPlayPersistence.saveCallEchoCancellation(context, it)
            markReconnectNeeded()
        }
        toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
            AirPlayPersistence.loadCallVoiceFilter(context)) {
            AirPlayPersistence.saveCallVoiceFilter(context, it)
            markReconnectNeeded()
        }
        toggle(card, getString(R.string.phone_audio_route), getString(R.string.phone_audio_route_description),
            PhoneAudioSettings.phoneAudioRoute(context)) {
            PhoneAudioSettings.setPhoneAudioRoute(context, it)
            PhoneAudioRoute.settingsChanged(context)
        }
        toggle(card, getString(R.string.phone_audio_route_apps), getString(R.string.phone_audio_route_apps_description),
            PhoneAudioSettings.phoneAudioRouteApps(context)) {
            PhoneAudioSettings.setPhoneAudioRouteApps(context, it)
            PhoneAudioRoute.settingsChanged(context)
            // The watcher for other apps' calls needs DiPlay running outside a CarPlay session too, which
            // the accessibility service provides.
            if (it && !WheelKeyService.connected()) {
                Thread({ WheelKeyService.enableOverAdb(context) }, "diplay-phone-audio-enable").start()
            }
        }
        audioFocusControls(card)
        if (context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
            toggle(card, getString(R.string.advanced_audio_channel_mapping),
                getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                AirPlayPersistence.loadAdvancedAudioChannelMapping(context)) {
                AirPlayPersistence.saveAdvancedAudioChannelMapping(context, it)
                markReconnectNeeded()
            }
        }
        toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
            AirPlayPersistence.loadMainBufferedAudio(context)) {
            AirPlayPersistence.saveMainBufferedAudio(context, it)
            reconnectIfRunning()
        }
        toggle(card, getString(R.string.settings_speech_without_low_rates), getString(R.string.settings_speech_without_low_rates_description),
            AirPlayPersistence.loadSpeechWithout8And16Khz(context)) {
            AirPlayPersistence.saveSpeechWithout8And16Khz(context, it)
            reconnectIfRunning()
        }
    }
}
