package com.shilapi.xcertplay

import com.shilapi.xcertplay.settings.OverlaySettingsChanges
import org.junit.Assert.*
import org.junit.Test

class OverlaySettingsChangesTest {
    @Test fun ownersUnsubscribeIndependentlyAndClosingIsIdempotent() {
        var first = 0
        var second = 0
        val a = OverlaySettingsChanges.subscribe { first++ }
        val b = OverlaySettingsChanges.subscribe { second++ }
        try {
            OverlaySettingsChanges.notifyChanged()
            a.close(); a.close()
            OverlaySettingsChanges.notifyChanged()
            assertEquals(1, first)
            assertEquals(2, second)
        } finally { a.close(); b.close() }
    }

    @Test fun selfRemovalDoesNotSkipAnotherListener() {
        var calls = 0
        lateinit var first: AutoCloseable
        first = OverlaySettingsChanges.subscribe { first.close() }
        val second = OverlaySettingsChanges.subscribe { calls++ }
        try {
            OverlaySettingsChanges.notifyChanged()
            OverlaySettingsChanges.notifyChanged()
            assertEquals(2, calls)
        } finally { first.close(); second.close() }
    }
}
