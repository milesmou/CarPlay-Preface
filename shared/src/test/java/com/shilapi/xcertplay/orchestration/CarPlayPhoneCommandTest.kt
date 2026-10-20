package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase
import com.shilapi.xcertplay.hud.CarPlayCallControl
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CarPlayPhoneCommandTest {
    private val work = ArrayDeque<Runnable>()
    private val executor = Executor { work.add(it) }
    private var call: CarPlayCallControl? = CarPlayCallControl(Any(), Phase.RINGING)
    private var eligible = true
    private val sent = mutableListOf<Int>()
    private fun enqueue(action: CarPlayPhoneCommand) = enqueuePhoneCommand(executor, action, { call }, { eligible }, { sent.add(it) })
    private fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }

    @Test fun answeringWritesHookSwitchOnlyAfterTheQueueRuns() {
        assertTrue(enqueue(CarPlayPhoneCommand.ANSWER)); assertTrue(sent.isEmpty())
        drain(); assertEquals(listOf(1), sent)
    }
    @Test fun endWritesDropForActiveDialingAndRejectedIncomingCalls() {
        for (phase in Phase.entries) {
            call = CarPlayCallControl(Any(), phase)
            assertTrue(enqueue(CarPlayPhoneCommand.END)); drain()
        }
        assertEquals(listOf(3, 3, 3), sent)
    }
    @Test fun answerCannotToggleAnAlreadyActiveOrOutgoingCall() {
        for (phase in listOf(Phase.ACTIVE, Phase.DIALING)) {
            call = CarPlayCallControl(Any(), phase)
            assertFalse(enqueue(CarPlayPhoneCommand.ANSWER))
        }
        assertTrue(work.isEmpty())
    }
    @Test fun nativeAnswerBeforeDeliveryCancelsTheQueuedAnswer() {
        enqueue(CarPlayPhoneCommand.ANSWER)
        call = call!!.copy(phase = Phase.ACTIVE)
        drain(); assertTrue(sent.isEmpty())
    }
    @Test fun replacementEndedOrAmbiguousCallCannotReceiveTheQueuedCommand() {
        for (next in listOf(null, CarPlayCallControl(Any(), Phase.RINGING))) {
            call = CarPlayCallControl(Any(), Phase.RINGING)
            enqueue(CarPlayPhoneCommand.END); call = next
            drain(); assertTrue(sent.isEmpty())
        }
    }
    @Test fun ownershipLossOrHfpReconnectionCancelsQueuedWork() {
        enqueue(CarPlayPhoneCommand.ANSWER); eligible = false
        drain(); assertTrue(sent.isEmpty())
        assertFalse(enqueue(CarPlayPhoneCommand.END))
    }
    @Test fun aRejectedOrClosedTransportReturnsFailure() {
        val rejected = Executor { throw java.util.concurrent.RejectedExecutionException() }
        assertFalse(enqueuePhoneCommand(rejected, CarPlayPhoneCommand.ANSWER, { call }, { true }, {}))
        call = null; assertFalse(enqueue(CarPlayPhoneCommand.END))
    }
}
