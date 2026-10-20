package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import java.io.IOException

/** Owns only this controller's hotspot reservation. Uses the transport's existing cleanup lock. */
internal class WirelessHotspotLease(
    private val appContext: Context,
    private val config: CarPlayRuntimeConfig,
    private val lock: Any,
    private val isCancelled: (Int) -> Boolean,
    private val bluetoothAvailable: () -> Boolean,
    private val bluetoothEnabled: () -> Boolean,
    private val debugLog: (String) -> Unit,
    private val closeBestEffort: (String, () -> Unit) -> Unit,
    private val managerFactory: ((Int) -> WirelessHotspotManager)? = null,
) {
    @Volatile var currentManager: WirelessHotspotManager? = null
        private set
    @Volatile var retainedInfo: WirelessHotspotInfo? = null
        private set

    fun start(generation: Int): WirelessHotspotInfo {
        BluetoothRadioCheck.blocker(bluetoothAvailable(), bluetoothEnabled())
            ?.let { throw IOException(it) }
        if (config.wirelessHotspotMode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "This Android version cannot create a temporary hotspot; use wired CarPlay")
        }
        synchronized(lock) {
            if (isCancelled(generation)) throw java.io.InterruptedIOException("Hotspot startup cancelled")
            if (currentManager == null && config.wirelessHotspotMode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) {
                TemporaryHotspotReservationStore.take()?.let { retained ->
                    retained.manager.updateDiagnosticListener(debugLog)
                    currentManager = retained.manager
                    retainedInfo = retained.info
                    debugLog("wireless manual reconnect acquired retained application hotspot reservation")
                }
            }
            val current = currentManager
            val info = retainedInfo
            if (current != null && info != null) {
                try {
                    current.validateReady()
                    debugLog("wireless startup reusing temporary hotspot reservation; credentials unchanged")
                    return info
                } catch (error: IOException) {
                    debugLog("wireless retained hotspot no longer ready failureClass=${error.javaClass.simpleName} reason=${error.message}; recreating reservation")
                    currentManager = null
                    retainedInfo = null
                    closeBestEffort("invalid temporary hotspot") { current.close() }
                }
            }
        }
        val manager = managerFactory?.invoke(generation) ?: createManager(generation)
        synchronized(lock) {
            if (isCancelled(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            currentManager = manager
        }
        return try {
            val info = manager.start(HOTSPOT_START_TIMEOUT_MILLIS)
            synchronized(lock) {
                if (isCancelled(generation)) throw java.io.InterruptedIOException("Hotspot startup cancelled")
                retainedInfo = info
            }
            info
        } catch (failure: Exception) {
            synchronized(lock) {
                if (currentManager === manager) { currentManager = null; retainedInfo = null }
            }
            closeBestEffort("wireless hotspot manager") { manager.close() }
            if (isCancelled(generation)) throw failure
            if (failure is WirelessStartupException) throw failure
            throw IOException("Could not create temporary hotspot: " + (failure.message ?: failure.javaClass.simpleName), failure)
        }
    }

    private fun createManager(generation: Int): WirelessHotspotManager =
        when (config.wirelessHotspotMode) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext, debugLog)
            WirelessHotspotMode.MANUAL -> com.shilapi.xcertplay.network.ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid.orEmpty(),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = debugLog,
                isCancelled = { isCancelled(generation) },
            )
        }

    fun release(keepHotspot: Boolean, closed: Boolean, retainTemporaryHotspotOnClose: Boolean) = synchronized(lock) {
        if (!keepHotspot || closed) {
            val activeHotspot = currentManager
            val activeInfo = retainedInfo
            currentManager = null
            retainedInfo = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") {
                val retained = closed && retainTemporaryHotspotOnClose && activeInfo != null &&
                    activeHotspot is LocalOnlyHotspotManager &&
                    runCatching { activeHotspot.validateReady() }.isSuccess &&
                    TemporaryHotspotReservationStore.retain(activeHotspot, activeInfo)
                if (retained) {
                    debugLog("wireless manual disconnect retained application hotspot reservation; credentials unchanged")
                } else {
                    activeHotspot.close()
                }
            }
        } else if (currentManager != null) {
            debugLog("wireless startup transport cleanup retained temporary hotspot reservation")
        }
    }

    companion object { private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L }
}
