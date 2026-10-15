package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** Full-canvas areas allow the fixed dock to move without reconnecting. */
class CarPlayViewAreas private constructor(val areas: List<AirPlayViewArea>, initial: Int) {
    @Volatile var current: Int = initial
        private set

    fun withDock(dockEdge: Int): Int? = areas.indices.firstOrNull { areas[it].dockEdge == dockEdge }

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        fun build(width: Int, height: Int, dock: CarPlayDock): CarPlayViewAreas? {
            val edge = dock.edge ?: return null
            val edges = listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM)
            return CarPlayViewAreas(edges.map { AirPlayViewArea(width, height, dockEdge = it) },
                edges.indexOf(edge))
        }
    }
}
