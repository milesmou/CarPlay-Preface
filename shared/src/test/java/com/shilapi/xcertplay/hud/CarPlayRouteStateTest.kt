package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayRouteStateTest {
    @Test
    fun `combines maneuver and route updates`() {
        val state = CarPlayRouteState()
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 7), tlv(0x03, 1), tlv(0x08, 0)),
        )

        val change = state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 150), tlv(0x0d, 0, 7)),
        )

        assertEquals(CarPlayRouteChange.GUIDANCE, change)
        assertEquals(CarPlayManeuver(distanceMeters = 150, type = 1, drivingSide = 0), state.currentApple())
    }

    @Test
    fun `maps right-hand u-turn`() {
        val state = CarPlayRouteState()
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 3), tlv(0x03, 4), tlv(0x08, 1)),
        )
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 50), tlv(0x0d, 0, 3)),
        )

        assertEquals(CarPlayManeuver(distanceMeters = 50, type = 4, drivingSide = 1), state.currentApple())
    }

    @Test
    fun `route end clears guidance`() {
        val state = populatedState()

        val change = state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(CarPlayRouteChange.CLEAR, change)
        assertNull(state.currentApple())
    }

    @Test
    fun `overlay keeps the last instruction across a NoRouteSet handoff`() {
        val state = populatedState(keepAcrossNoRoute = true)
        val before = state.currentApple()

        val change = state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))

        assertEquals(CarPlayRouteChange.NONE, change)
        assertEquals(before, state.currentApple())
    }

    @Test
    fun `overlay hides the card shortly after the driver ends guidance`() {
        // #511: cancelling a route sends NoRouteSet while the session stays up.
        var now = 0L
        val state = populatedState({ now }, keepAcrossNoRoute = true)
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))
        now = 4_000_000_000L
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))
        assertEquals(25, state.currentApple()!!.distanceMeters)
        now = 5_000_000_000L
        assertNull(state.currentApple())
    }

    @Test
    fun `guidance after NoRouteSet keeps the card`() {
        var now = 0L
        val state = populatedState({ now }, keepAcrossNoRoute = true)
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))
        now = 1_000_000_000L
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 20)))
        now = 10_000_000_000L
        assertEquals(20, state.currentApple()!!.distanceMeters)
    }

    @Test
    fun `a wireless drop keeps the card through NoRouteSet before and after it`() {
        var now = 0L
        val state = populatedState({ now }, keepAcrossNoRoute = true)
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))
        state.holdAcrossDrop()
        now = 10_000_000_000L
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))
        now = 20_000_000_000L
        assertEquals(25, state.currentApple()!!.distanceMeters)
    }

    @Test
    fun `overlay still clears on arrival even when NoRouteSet is ignored`() {
        val state = populatedState(keepAcrossNoRoute = true)

        val change = state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(CarPlayRouteChange.CLEAR, change)
        assertNull(state.currentApple())
    }

    @Test
    fun `malformed frame does not alter active guidance`() {
        val state = populatedState()
        val before = state.currentApple()

        val change = state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            byteArrayOf(0, 8, 0, 1, 1),
        )

        assertEquals(CarPlayRouteChange.NONE, change)
        assertEquals(before, state.currentApple())
    }


    @Test
    fun `carries after-maneuver road and arrival time`() {
        val state = CarPlayRouteState()
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 2), tlv(0x03, 2), tlv(0x04, *utf8z("Am Wehr")), tlv(0x08, 0)),
        )
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x05, 0, 0, 0, 0, 0x68, 0xd5, 0x2a, 0x40),
                tlv(0x0a, 0, 0, 1, 44), tlv(0x0d, 0, 2),
            ),
        )

        val guidance = state.currentApple()!!
        assertEquals("Am Wehr", guidance.road)
        assertEquals(0x68d52a40L, guidance.arrivalEpochSeconds)
        assertEquals(300, guidance.distanceMeters)
    }

    @Test
    fun `brief empty maneuver list keeps guidance and a lasting one hides it without losing maneuvers`() {
        var now = 0L
        val state = populatedState(nanoTime = { now })

        assertEquals(CarPlayRouteChange.NONE, state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 5), tlv(0x0d))))
        now = 2_000_000_000L
        assertEquals(2, state.currentApple()!!.type) // a blip does not blank the outputs
        now = 3_100_000_000L
        assertNull(state.currentApple())

        // The iPhone lists the same maneuver again without resending its 0x5202 details.
        val resumed = state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 20), tlv(0x0d, 0, 1)),
        )
        assertEquals(CarPlayRouteChange.GUIDANCE, resumed)
        assertEquals(2, state.currentApple()!!.type)
    }

    @Test
    fun `falls back to current road`() {
        val state = CarPlayRouteState()
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 1)))
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x0d, 0, 0)),
        )
        assertEquals("Hauptstraße", state.currentApple()!!.road)
    }

    @Test
    fun `roundabout exit has blank arrow and exit icon`() {
        val state = CarPlayRouteState()
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 29)))
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0d, 0, 0)))
        val guidance = state.currentApple()!!
        assertEquals(29, guidance.type)
    }

    @Test
    fun `silent route expires and fresh update restores cached maneuver`() {
        var now = 0L
        val state = populatedState(nanoTime = { now })
        now = 30_000_000_000L
        assertNull(state.currentApple())
        state.accept(CarPlayRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0a, 0, 0, 0, 20)))
        assertEquals(20, state.currentApple()!!.distanceMeters)
    }

    @Test
    fun `clear prevents a keepalive from restoring guidance`() {
        val state = populatedState()
        state.clear()
        assertNull(state.currentApple())
        assertNull(state.currentApple())
    }

    private fun utf8z(value: String): IntArray =
        (value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff } + 0).toIntArray()

    private fun populatedState(
        nanoTime: () -> Long = System::nanoTime,
        keepAcrossNoRoute: Boolean = false,
    ): CarPlayRouteState =
        CarPlayRouteState(nanoTime, keepAcrossNoRoute = keepAcrossNoRoute).also { state ->
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 1), tlv(0x03, 2), tlv(0x08, 0)),
        )
        state.accept(
            CarPlayRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 25), tlv(0x0d, 0, 1)),
        )
    }

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}
