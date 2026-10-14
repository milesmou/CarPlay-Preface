package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class PrefaceMediaKeyEchoesTest {
    private var time = 0L
    private val sent = mutableListOf<Int>()
    private val echoes = PrefaceMediaKeyEchoes { time }
    private fun press(index: Int, intercepted: Boolean) = echoes.dispatch(index, intercepted) { sent.add(index) }

    @Test fun reportedPhysicalAndBroadcastPairsSendOnlyOneCommandPerTap() {
        press(4, true)
        time += 146
        assertTrue(press(4, false))
        time += 8316
        press(4, true)
        time += 153
        press(4, false)
        assertEquals(listOf(4, 4), sent)
    }

    @Test fun reverseDeliveryAndTwoQuickPhysicalPressesKeepTwoCommands() {
        press(4, false)
        time += 50
        press(4, true)
        time += 50
        press(4, true)
        time += 50
        press(4, true)
        press(4, false)
        press(4, false)
        assertEquals(listOf(4, 4, 4), sent)
    }

    @Test fun independentCommandsAndExpiredOrClearedEchoesAreNeverSuppressed() {
        press(4, true)
        press(5, false)
        press(4, true)
        time += 751
        press(4, false)
        echoes.clear()
        press(4, true)
        assertEquals(listOf(4, 5, 4, 4, 4), sent)
    }

    @Test fun failedSendLeavesTheOtherDeliveryAvailable() {
        assertFalse(echoes.dispatch(4, true) { false })
        assertTrue(press(4, false))
        assertEquals(listOf(4), sent)
    }
}
