package com.shilapi.xcertplay.telecom

import com.shilapi.xcertplay.logging.CarPlayLog as Log
import java.util.ArrayDeque

/** Process-local, bounded evidence for report export; contains no call identities or audio. */
internal object PhoneAudioDiagnostics {
    enum class Event { SETTINGS_CHANGED, IPHONE_CALL_STATE, REQUEST, REGISTERED, ADMISSION_QUERY, PLACE_RETURNED, RESULT, CREATED, CREATE_FAILED, RELEASED }
    enum class Result { ACTIVE, CALLBACK_FAILED, TIMEOUT, API_UNAVAILABLE, SERVICE_UNAVAILABLE, SECURITY_ERROR, RUNTIME_ERROR }
    private val entries = ArrayDeque<String>()

    fun record(
        event: Event,
        carplay: Boolean? = null,
        otherApps: Boolean? = null,
        active: Boolean? = null,
        permissionGranted: Boolean? = null,
        outgoingPermitted: Boolean? = null,
        elapsedMs: Long? = null,
        result: Result? = null,
        error: Throwable? = null,
    ) {
        runCatching {
            val line = buildString {
                append("Phone audio: atEpochMs=${System.currentTimeMillis()} event=${event.name}")
                carplay?.let { append(" carplaySetting=$it") }
                otherApps?.let { append(" otherAppsSetting=$it") }
                active?.let { append(" active=$it") }
                permissionGranted?.let { append(" manageOwnCallsGranted=$it") }
                // A diagnostic query only; it never decides whether the existing request runs.
                if (event == Event.ADMISSION_QUERY) append(" outgoingPermitted=${outgoingPermitted ?: "unknown"}")
                elapsedMs?.let { append(" elapsedMs=${it.coerceAtLeast(0)}") }
                result?.let { append(" result=${it.name}") }
                error?.let { append(" error=${it.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(64)}") }
            }
            synchronized(entries) {
                while (entries.size >= 48) entries.removeFirst()
                entries.addLast(line)
            }
            Log.i("DiPlay-PhoneAudio", line)
        }
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }
}
