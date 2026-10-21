package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build

/** Numeric/public state only: never includes device names, addresses, other apps or audio content. */
internal data class MicrophoneRouteState(
    val mode: Int? = null,
    val micMuted: Boolean? = null,
    val scoOn: Boolean? = null,
    val speakerOn: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val recorderState: Int? = null,
    val recordingState: Int? = null,
    val source: Int? = null,
    val recorderRate: Int? = null,
    val channels: Int? = null,
    val routedType: Int? = null,
    val routedId: Int? = null,
    val preferredType: Int? = null,
    val configuration: Configuration = Configuration.UNAVAILABLE,
    val deviceRate: Int? = null,
    val deviceType: Int? = null,
    val clientSilenced: Boolean? = null,
    val softwareAec: Boolean = false,
    val platformAec: Effect = Effect.NOT_ATTACHED,
    val platformNs: Effect = Effect.NOT_ATTACHED,
) {
    enum class Configuration { UNAVAILABLE, NOT_EXPOSED, MATCHED }
    enum class Effect { NOT_ATTACHED, ENABLED, DISABLED, UNKNOWN }
}

/** Samples on the existing capture thread, at most once a second; logs only changes/start/end. */
internal class MicrophoneRouteDiagnostics(
    audioType: String,
    private val readState: () -> MicrophoneRouteState,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    enum class Stage { START, POLL, END }
    private val type = audioType.takeIf { it == "telephony" || it == "speechrecognition" } ?: "other"
    private var lastSample: Long? = null
    private var previous: MicrophoneRouteState? = null

    fun sample(stage: Stage) {
        val now = nowNs()
        if (stage == Stage.POLL && lastSample?.let { now - it < 1_000_000_000L } == true) return
        lastSample = now
        val state = runCatching { readState() }.getOrElse { MicrophoneRouteState() }
        if (stage == Stage.POLL && state == previous) return
        previous = state
        val line = with(state) {
            "Microphone: route type=$type stage=${stage.name} mode=${mode ?: "unknown"} " +
                "micMuted=${micMuted ?: "unknown"} scoOn=${scoOn ?: "unknown"} speakerOn=${speakerOn ?: "unknown"} " +
                "permissionGranted=${permissionGranted ?: "unknown"} recorderState=${recorderState ?: "unknown"} " +
                "recordingState=${recordingState ?: "unknown"} source=${source ?: "unknown"} " +
                "recorderRate=${recorderRate ?: "unknown"} channels=${channels ?: "unknown"} " +
                "routedType=${routedType ?: "unknown"} routedId=${routedId ?: "unknown"} preferredType=${preferredType ?: "unknown"} " +
                "configuration=${configuration.name} deviceRate=${deviceRate ?: "unknown"} deviceType=${deviceType ?: "unknown"} " +
                "clientSilenced=${clientSilenced ?: "unknown"} softwareAec=$softwareAec " +
                "platformAec=${platformAec.name} platformNs=${platformNs.name}"
        }
        runCatching { report(line) }
    }
}

internal fun readMicrophoneRouteState(
    recorder: AudioRecord?,
    context: Context?,
    manager: AudioManager?,
    softwareAec: Boolean,
    effects: List<AudioEffect>,
): MicrophoneRouteState {
    val session = runCatching { recorder?.audioSessionId }.getOrNull()
    val configurations = runCatching { manager?.activeRecordingConfigurations }.getOrNull()
    // Some ROMs anonymize configurations. Never attribute a zero/foreign session to our recorder.
    val own = if (session != null && session > 0) configurations?.firstOrNull {
        runCatching { it.clientAudioSessionId == session }.getOrDefault(false)
    } else null
    val route = runCatching { recorder?.routedDevice }.getOrNull()
    fun effectState(effect: AudioEffect?) = if (effect == null) MicrophoneRouteState.Effect.NOT_ATTACHED else {
        runCatching { if (effect.enabled) MicrophoneRouteState.Effect.ENABLED else MicrophoneRouteState.Effect.DISABLED }
            .getOrDefault(MicrophoneRouteState.Effect.UNKNOWN)
    }
    return MicrophoneRouteState(
        mode = runCatching { manager?.mode }.getOrNull(),
        micMuted = runCatching { manager?.isMicrophoneMute }.getOrNull(),
        scoOn = runCatching { manager?.isBluetoothScoOn }.getOrNull(),
        speakerOn = runCatching { manager?.isSpeakerphoneOn }.getOrNull(),
        permissionGranted = context?.let {
            runCatching { it.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }.getOrNull()
        },
        recorderState = runCatching { recorder?.state }.getOrNull(),
        recordingState = runCatching { recorder?.recordingState }.getOrNull(),
        source = runCatching { recorder?.audioSource }.getOrNull(),
        recorderRate = runCatching { recorder?.sampleRate }.getOrNull(),
        channels = runCatching { recorder?.channelCount }.getOrNull(),
        routedType = runCatching { route?.type }.getOrNull(),
        routedId = runCatching { route?.id }.getOrNull(),
        preferredType = runCatching { recorder?.preferredDevice?.type }.getOrNull(),
        configuration = when {
            configurations == null -> MicrophoneRouteState.Configuration.UNAVAILABLE
            own == null -> MicrophoneRouteState.Configuration.NOT_EXPOSED
            else -> MicrophoneRouteState.Configuration.MATCHED
        },
        deviceRate = runCatching { own?.format?.sampleRate }.getOrNull(),
        deviceType = runCatching { own?.audioDevice?.type }.getOrNull(),
        clientSilenced = if (Build.VERSION.SDK_INT >= 29) runCatching { own?.isClientSilenced }.getOrNull() else null,
        softwareAec = softwareAec,
        platformAec = effectState(effects.filterIsInstance<AcousticEchoCanceler>().firstOrNull()),
        platformNs = effectState(effects.filterIsInstance<NoiseSuppressor>().firstOrNull()),
    )
}
