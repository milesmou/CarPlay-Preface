package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** CarPlay navigation overlays and call state for the head-unit screen. */
object NavigationOutputs {
    private val lock = Any()
    private val overlayRoute = CarPlayRouteState(staleRouteNs = 120_000_000_000L, emptyListHideNs = 8_000_000_000L, keepAcrossNoRoute = true)
    private val calls = CarPlayCallState()
    @Volatile private var listener: ((ClusterTurnGuidance?) -> Unit)? = null
    private var published: ClusterTurnGuidance? = null

    fun onFrame(frame: Iap2Frame, usesCarBluetoothAudio: Boolean) {
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            val active = synchronized(lock) { calls.accept(frame); calls.current() != null }
            PhoneAudioRoute.carPlayCall(active, usesCarBluetoothAudio)
        }
        synchronized(lock) { overlayRoute.accept(frame.messageId, frame.payload) }
        refreshTurnOverlay()
    }

    fun setTurnOverlayListener(value: ((ClusterTurnGuidance?) -> Unit)?) {
        val next = synchronized(lock) {
            listener = value
            currentOverlay().also { published = it }
        }
        value?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = overlayRoute.currentApple()?.let { maneuver ->
        ClusterTurnGuidance.from(ClusterGuidanceFrame.from(maneuver)).copy(
            arrivalEpochSeconds = maneuver.arrivalEpochSeconds,
            remainingSeconds = maneuver.remainingSeconds,
            remainingMeters = maneuver.remainingMeters,
        )
    }

    fun refreshTurnOverlay() {
        val next = synchronized(lock) {
            val next = currentOverlay()
            if (next == published) return
            published = next
            next
        }
        listener?.invoke(next)
    }

    fun carPlayCall(): CarPlayCallCard? = synchronized(lock) { calls.current() }

    fun endNow(preserveTurnOverlay: Boolean = false) {
        synchronized(lock) {
            calls.clear()
            if (preserveTurnOverlay) overlayRoute.holdAcrossDrop() else overlayRoute.clear()
        }
        PhoneAudioRoute.carPlayCall(false, usesCarBluetoothAudio = false)
        refreshTurnOverlay()
    }
}
