package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.hud.CarPlayManeuver
import com.shilapi.xcertplay.hud.CarPlayRouteState
import com.shilapi.xcertplay.hud.ClusterGuidanceFrame
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.logging.LogPolicy

/** Only the eight base arrows confirmed by build327's NAVTurn2IPK mapping. */
internal data class PrefaceInstrumentGuidance(
    val turnId: Int,
    val distanceMeters: Int,
    val road: String,
    val remainingMeters: Long,
    val remainingSeconds: Long,
) {
    companion object {
        fun from(maneuver: CarPlayManeuver): PrefaceInstrumentGuidance? {
            val icon = ClusterGuidanceFrame.from(maneuver).icon
            if (icon !in 2..9) return null
            // NAV F3 contains a five-bit UTF-16 road-name length. Keep surrogate pairs intact.
            val road = maneuver.road.filter { !it.isISOControl() }.take(31)
                .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            return PrefaceInstrumentGuidance(100 + icon, maneuver.distanceMeters.coerceAtLeast(0),
                road, maneuver.remainingMeters?.coerceAtLeast(0) ?: 0,
                maneuver.remainingSeconds?.coerceAtLeast(0) ?: 0)
        }
    }
}

internal interface PrefaceInstrumentBackend : AutoCloseable {
    fun connect()
    fun ready(): Boolean
    /** Native SDK readiness; the installed SDK owns its phone/navigation state handling. */
    fun available(): Boolean
    fun publish(guidance: PrefaceInstrumentGuidance)
    fun clear()
    override fun close()
}

/** Worker-confined state, independent of the longer-lived middle-screen overlay. */
internal class PrefaceInstrumentGuidanceSession(
    private val backend: PrefaceInstrumentBackend,
    private val clockNs: () -> Long = System::nanoTime,
    private val diagnostic: (String) -> Unit = {},
) : AutoCloseable {
    private val route = CarPlayRouteState(nanoTime = clockNs)
    private var connectingSince: Long? = null
    private var failed = false
    private var closed = false
    private var owned = false
    private var last: PrefaceInstrumentGuidance? = null
    private var lastSentNs: Long? = null
    private var lastAvailability: Boolean? = null
    private var reportedData = false

    fun accept(frame: Iap2Frame) {
        if (!closed && !failed) route.accept(frame.messageId, frame.payload)
    }

    fun tick() {
        if (closed || failed) return
        try {
            val guidance = route.currentApple()?.let(PrefaceInstrumentGuidance::from)
            if (guidance == null) {
                clearOwned()
                return
            }
            if (!reportedData) {
                reportedData = true
                diagnostic("instrument navigation route-data received; supported-arrow=true")
            }
            if (connectingSince == null) {
                connectingSince = clockNs()
                backend.connect()
            }
            if (!backend.ready()) {
                if (clockNs() - connectingSince!! >= 5_000_000_000L) error("instrument-sdk-connect-timeout")
                return
            }
            connectingSince = clockNs()
            val available = backend.available()
            if (lastAvailability != available) {
                diagnostic("instrument navigation available=$available; state-management=OEM-SDK")
                lastAvailability = available
            }
            if (!available) {
                // A native connection or ownership change invalidates our old publication.
                owned = false
                last = null
                return
            }
            val elapsed = lastSentNs?.let { clockNs() - it } ?: Long.MAX_VALUE
            if (elapsed < 500_000_000L || (last == guidance && elapsed < 2_000_000_000L)) return
            backend.publish(guidance)
            owned = true
            last = guidance
            lastSentNs = clockNs()
            if (LogPolicy.detailedEnabled) {
                diagnostic("instrument navigation native SDK update submitted; output=aggregate+fields; display-confirmed=false")
            }
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        failed = true
        var cause = error
        repeat(8) { cause = cause.cause ?: cause }
        val reason = error.message?.takeIf { it in setOf("instrument-sdk-connect-timeout",
            "unconfirmed-instrument-arrow", "instrument-session-ended",
            "instrument-sdk-interface-mismatch", "instrument-sdk-navigation-info-mismatch",
            "instrument-sdk-guidance-method-mismatch",
            "instrument-sdk-create-unavailable", "instrument-sdk-connectable-unavailable",
            "instrument-sdk-owned-by-another-session", "instrument-sdk-navigation-unavailable",
            "instrument-sdk-navigation-type-mismatch", "instrument-sdk-main-unavailable",
            "instrument-sdk-main-timeout", "instrument-sdk-main-failure") } ?: "api-failure"
        diagnostic("instrument navigation stopped type=${cause.javaClass.simpleName} reason=$reason; retry=next-session")
        runCatching { clearOwned() }.onFailure {
            diagnostic("instrument navigation clear failed type=${it.javaClass.simpleName}")
        }
        runCatching { backend.close() }
    }

    private fun clearOwned() {
        if (owned) {
            owned = false
            if (backend.ready() && backend.available()) {
                backend.clear()
                diagnostic("instrument navigation native SDK stop submitted; display-confirmed=false")
            }
        }
        last = null
        lastSentNs = null
        connectingSince = null
    }

    override fun close() {
        if (closed) return
        closed = true
        route.clear()
        try { clearOwned() }
        catch (error: Exception) { diagnostic("instrument navigation close-clear failed type=${error.javaClass.simpleName}") }
        catch (error: LinkageError) { diagnostic("instrument navigation close-clear failed type=${error.javaClass.simpleName}") }
        finally { backend.close() }
    }
}
