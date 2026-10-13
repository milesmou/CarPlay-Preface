package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Vendor-independent navigation overlays and the optional FCE output. */
object NavigationOutputs {
    private val lock = Any()
    private val overlayRoute = CarPlayRouteState(staleRouteNs = 120_000_000_000L, emptyListHideNs = 8_000_000_000L, keepAcrossNoRoute = true)
    private val calls = CarPlayCallState()
    private val fce = NavigationOutputWorker("diplay-fce-cluster-output", FceClusterBridge::clear)
    @Volatile private var listener: ((ClusterTurnGuidance?) -> Unit)? = null
    private var published: ClusterTurnGuidance? = null

    fun start(context: Context) {
        if (FceClusterBridge.available()) fce.start { FceClusterBridge.initialize(context.applicationContext) }
    }

    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            val active = synchronized(lock) { calls.accept(frame); calls.current() != null }
            PhoneAudioRoute.carPlayCall(active)
        }
        synchronized(lock) { overlayRoute.accept(frame.messageId, frame.payload) }
        refreshTurnOverlay()
        fce.submit { FceClusterBridge.onFrame(frame) }
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
        fce.clear()
        synchronized(lock) {
            calls.clear()
            if (preserveTurnOverlay) overlayRoute.holdAcrossDrop() else overlayRoute.clear()
        }
        PhoneAudioRoute.carPlayCall(false)
        refreshTurnOverlay()
    }
}
