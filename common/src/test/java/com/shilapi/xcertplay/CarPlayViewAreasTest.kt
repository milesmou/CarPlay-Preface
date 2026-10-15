package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import org.junit.Assert.*
import org.junit.Test

class CarPlayViewAreasTest {
    @Test fun automaticDockNeedsNoAlternativeAreas() {
        assertNull(CarPlayViewAreas.build(1920, 720, CarPlayDock.AUTOMATIC))
    }

    @Test fun fixedDockMovesBetweenTwoFullCanvasAreas() {
        val areas = CarPlayViewAreas.build(1920, 720, CarPlayDock.DRIVER_SIDE)!!
        assertEquals(listOf(DOCK_EDGE_DRIVER_SIDE, DOCK_EDGE_BOTTOM), areas.areas.map { it.dockEdge })
        assertEquals(listOf(1920 to 720, 1920 to 720), areas.areas.map { it.width to it.height })
        assertTrue(areas.areas.all { it.originX == 0 && it.originY == 0 })
        assertEquals(0, areas.current)
        areas.use(areas.withDock(DOCK_EDGE_BOTTOM)!!)
        assertEquals(1, areas.current)
        assertNull(areas.withDock(-1))
        assertEquals(1, CarPlayViewAreas.build(1920, 720, CarPlayDock.BOTTOM)!!.current)
    }
}
