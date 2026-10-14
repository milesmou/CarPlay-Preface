package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioOutputsTest {
    @Test fun actualRouteSurvivesSilenceAndFallbackIsDistinctFromMissingOutput() {
        val token = Any()
        try {
            assertNull(CarPlayAudioOutputs.route(true))
            CarPlayAudioOutputs.register(token, true, 12)
            assertEquals(CarPlayOutputRoute(12), CarPlayAudioOutputs.route(true))
            CarPlayAudioOutputs.register(token, true, null)
            assertEquals(CarPlayOutputRoute(null), CarPlayAudioOutputs.route(true))
        } finally {
            CarPlayAudioOutputs.unregister(token)
        }
        assertNull(CarPlayAudioOutputs.route(true))
    }
}
