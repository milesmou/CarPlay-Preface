package com.shilapi.xcertplay.telecom

import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationPhoneAudioModeTest {
    private val controllerField = PhoneAudioRoute::class.java.getDeclaredField("controller").apply { isAccessible = true }
    private var previous: Any? = null
    private var starts = 0
    private var stops = 0
    private val releases = mutableListOf<() -> Unit>()

    @Before fun prepare() {
        previous = controllerField.get(null)
        NavigationOutputs.endNow()
        controllerField.set(null, PhoneAudioRouteController(
            backend = object : PhoneAudioBackend {
                private var active = false
                override fun start(): Boolean { starts++; active = true; return true }
                override fun stop() { stops++; active = false }
                override fun isActive() = active
            },
            scheduler = object : RouteScheduler {
                override fun execute(block: () -> Unit) = block()
                override fun schedule(delayMillis: Long, block: () -> Unit): () -> Unit {
                    releases += block
                    return { releases.remove(block) }
                }
            },
            enabled = { it == PhoneAudioReason.CARPLAY_CALL },
        ))
    }

    @After fun release() {
        NavigationOutputs.endNow()
        releases.toList().forEach { it() }
        controllerField.set(null, previous)
    }

    private fun update(status: Int, bluetooth: Boolean) {
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, status)
            string(4, "test-call")
        }, usesCarBluetoothAudio = bluetooth)
    }

    @Test fun bluetoothCallStillUpdatesCallStateWithoutRequestingPhoneAudio() {
        for (status in listOf(1, 3, 4, 2)) {
            update(status, bluetooth = true)
            assertNotNull(NavigationOutputs.carPlayCall())
            assertTrue(PhoneAudioRoute.ownCarPlayCallActive())
            assertFalse(PhoneAudioRoute.carPlayCallsUseCarAudio())
        }
        assertEquals(0, starts)
        update(0, bluetooth = true)
        assertNull(NavigationOutputs.carPlayCall())
        assertFalse(PhoneAudioRoute.ownCarPlayCallActive())
        assertEquals(0, starts)
    }

    @Test fun carplayAudioCallStartsOnceAndSessionEndClearsTheRequest() {
        for (status in listOf(1, 3, 4)) update(status, bluetooth = false)
        assertEquals(1, starts)
        assertTrue(PhoneAudioRoute.carPlayCallsUseCarAudio())
        NavigationOutputs.endNow()
        releases.toList().forEach { it() }
        assertEquals(1, stops)
        assertNull(NavigationOutputs.carPlayCall())
        assertFalse(PhoneAudioRoute.ownCarPlayCallActive())
    }
}
