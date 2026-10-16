package com.shilapi.xcertplay.network

/** Owns only this process's idle LocalOnlyHotspot reservation between explicit connections. */
object TemporaryHotspotReservationStore {
    class Reservation(val manager: LocalOnlyHotspotManager, val info: WirelessHotspotInfo)

    private var idle: Reservation? = null

    @Synchronized
    fun retain(manager: LocalOnlyHotspotManager, info: WirelessHotspotInfo): Boolean {
        if (idle != null) return false
        // An idle reservation must not retain a retired controller, Activity or diagnostic file.
        manager.updateDiagnosticListener {}
        idle = Reservation(manager, info)
        return true
    }

    @Synchronized
    fun take(): Reservation? = idle.also { idle = null }

    fun release() {
        val retained = take() ?: return
        runCatching { retained.manager.close() }.onFailure {
            android.util.Log.w("DiPlay-Hotspot", "Idle reservation release failed failureClass=${it.javaClass.simpleName}")
        }
    }
}
