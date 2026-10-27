package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.logging.LogPolicy

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val type = typeAndSource(config.audioType).first
    private val format = format(config)
    private var metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private val captureLevels = PcmLevelWindow()
    private val uplinkLevels = PcmLevelWindow()
    private var routedDeviceType: Int? = null

    fun started(routeType: Int?) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}")
    }

    /** Call before the capture thread starts; [metadata] is not volatile. */
    fun useVoiceRecognitionSource() { metadata = "type=$type source=VOICE_RECOGNITION $format" }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        reads++
        readMaxNs = maxOf(readMaxNs, (nowNs() - readStart).coerceAtLeast(0))
        when {
            count > 0 -> capturedBytes += count
            count == 0 -> zeroReads++
            else -> readErrors++
        }
    }

    /**
     * The loudest 16-bit little-endian sample sent in this window. Without it, a microphone that
     * delivers silence and an uplink the iPhone discards looked the same in a report (#647).
     */
    fun level(pcm: ByteArray) = uplinkLevels.observe(pcm)

    /** AudioRecord PCM before this application's optional processing; the HAL may already process it. */
    fun capturedLevel(pcm: ByteArray) = captureLevels.observe(pcm)

    fun encoded(frameCount: Int, emptyCount: Int) {
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
    }

    fun sendFailed() { sendErrors++ }

    fun restarted(attempt: Int, reason: String, routeType: Int?) {
        emit("Microphone: recording restarted $metadata attempt=$attempt reason=$reason " +
            "routedDeviceType=${routeType ?: "unknown"}")
    }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null) {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        if (LogPolicy.detailedEnabled && (captureLevels.samples > 0 || uplinkLevels.samples > 0)) {
            emit("Microphone: levels $metadata windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
                captureLevels.describe("capture") + " " + uplinkLevels.describe("uplink") + " ended=$ended")
        }
        if (LogPolicy.detailedEnabled || ended || readErrors > 0 || sendErrors > 0) {
            emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
                "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
                "readMaxMs=${readMaxNs / 1_000_000} encodedFrames=$encodedFrames " +
                "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors " +
                "sendGapMaxMs=${sendGapMaxNs / 1_000_000} peakDbfs=${peakDbfs(uplinkLevels.peak)} ended=$ended")
        }
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        sendGapMaxNs = 0
        captureLevels.reset()
        uplinkLevels.reset()
    }

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        /** Whole dBFS of a 16-bit peak; "silent" when every sample was zero. */
        internal fun peakDbfs(peak: Int): String =
            if (peak <= 0) "silent" else Math.round(20 * kotlin.math.log10(peak / 32768.0)).toString()

        private fun typeAndSource(audioType: String): Pair<String, String> = when (audioType) {
            "telephony" -> "telephony" to "VOICE_COMMUNICATION"
            "speechrecognition" -> "speechrecognition" to "VOICE_COMMUNICATION"
            else -> "other" to "MIC"
        }

        private fun format(config: MicrophoneConfig): String = "codec=${config.codec.name} " +
            "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = typeAndSource(config.audioType)
            return "type=$type source=$source ${format(config)}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}

/** Retains numeric aggregates only. The threshold is signal energy, not speech recognition. */
private class PcmLevelWindow {
    var samples = 0L
        private set
    var peak = 0
        private set
    private var squareSum = 0L
    private var nonzero = 0L
    private var aboveMinus50 = 0L

    fun observe(pcm: ByteArray) {
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            val magnitude = kotlin.math.abs(sample)
            samples++
            peak = maxOf(peak, magnitude)
            squareSum += sample.toLong() * sample
            if (magnitude > 0) nonzero++
            if (magnitude >= 104) aboveMinus50++ // ceil(32768 * 10^(-50/20))
            index += 2
        }
    }

    fun describe(prefix: String): String {
        val rms = when {
            samples == 0L -> "unavailable"
            squareSum == 0L -> "silent"
            else -> Math.round(10 * kotlin.math.log10(squareSum.toDouble() / samples / (32768.0 * 32768.0))).toString()
        }
        fun permille(value: Long) = if (samples == 0L) "unavailable" else (value * 1000 / samples).toString()
        val peakDbfs = if (samples == 0L) "unavailable" else MicrophoneCaptureStats.peakDbfs(peak)
        return "${prefix}Samples=$samples ${prefix}PeakDbfs=$peakDbfs ${prefix}RmsDbfs=$rms " +
            "${prefix}NonzeroPermille=${permille(nonzero)} ${prefix}AboveMinus50Permille=${permille(aboveMinus50)}"
    }

    fun reset() {
        samples = 0
        peak = 0
        squareSum = 0
        nonzero = 0
        aboveMinus50 = 0
    }
}
