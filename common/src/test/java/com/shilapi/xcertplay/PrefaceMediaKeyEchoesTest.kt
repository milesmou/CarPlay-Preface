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

    @Test fun threeParallelPathsSendOnceInAnyOrderAndKeepRapidPresses() {
        val sources = PrefaceMediaKeyEchoes.Source.entries
        for (first in sources) {
            echoes.clear(); sent.clear()
            val order = listOf(first) + sources.filter { it != first }
            repeat(2) { tap ->
                time += 50
                order.forEach { source ->
                    assertTrue(echoes.dispatch(4, source) { sent.add(4) })
                }
                assertEquals(tap + 1, sent.size)
            }
        }
    }

    @Test fun twoFastNativePressesAndTheirDelayedBroadcastsStillSendTwo() {
        val native = PrefaceMediaKeyEchoes.Source.MEDIA_CENTER
        val broadcast = PrefaceMediaKeyEchoes.Source.ANDROID_KEY
        val input = PrefaceMediaKeyEchoes.Source.INPUT
        echoes.dispatch(4, native) { sent.add(4) }
        time += 40
        echoes.dispatch(4, native) { sent.add(4) }
        repeat(2) {
            echoes.dispatch(4, broadcast) { sent.add(4) }
            echoes.dispatch(4, input) { sent.add(4) }
        }
        assertEquals(listOf(4, 4), sent)
    }

    @Test fun nativePlayAndPauseDoNotGetReversedByTheSamePhysicalToggle() {
        val native = PrefaceMediaKeyEchoes.Source.MEDIA_CENTER
        val input = PrefaceMediaKeyEchoes.Source.INPUT
        val broadcast = PrefaceMediaKeyEchoes.Source.ANDROID_KEY
        echoes.dispatch(1, native) { sent.add(1) }
        echoes.dispatch(3, input) { sent.add(3) }
        echoes.dispatch(3, broadcast) { sent.add(3) }
        echoes.dispatch(2, native) { sent.add(2) }
        echoes.dispatch(3, input) { sent.add(3) }
        echoes.dispatch(3, broadcast) { sent.add(3) }
        assertEquals(listOf(1, 2), sent)
        echoes.clear(); sent.clear()
        echoes.dispatch(3, input) { sent.add(3) }
        echoes.dispatch(1, native) { sent.add(1) }
        echoes.dispatch(3, broadcast) { sent.add(3) }
        assertEquals(listOf(3), sent)
    }

    @Test fun oppositeExplicitCommandsAreNeverMerged() {
        echoes.dispatch(1, PrefaceMediaKeyEchoes.Source.ANDROID_KEY) { sent.add(1) }
        echoes.dispatch(2, PrefaceMediaKeyEchoes.Source.MEDIA_CENTER) { sent.add(2) }
        assertEquals(listOf(1, 2), sent)
    }
}
