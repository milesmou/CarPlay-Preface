package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayPhoneTransportTest {
    private lateinit var controller: CarPlayController
    private lateinit var listener: AirPlaySessionListener
    private lateinit var session: AirPlaySession
    private val work = QueuedExecutor()

    @Before fun setup() {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "1", AirPlayDisplayConfig(1920, 720)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        ReflectionHelpers.getField<ExecutorService>(controller, "touchExecutor").shutdownNow()
        ReflectionHelpers.setField(controller, "touchExecutor", work)
        listener = ReflectionHelpers.getField(controller, "sessionListener")
        session = mock(AirPlaySession::class.java); listener.onSessionActive(session)
    }
    @After fun cleanup() { controller.close(); shadowOf(Looper.getMainLooper()).idle() }
    private fun state(id: String = "one", status: Int) {
        val frame = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) { string(4, id); u8(2, status) }
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onRouteFrame",
            ReflectionHelpers.ClassParameter.from(com.shilapi.xcertplay.iap2.wire.Iap2Frame::class.java, frame))
    }

    @Test fun controllerUsesItsOwnRealCallStateAndWritesHookThenDrop() {
        assertFalse(controller.answerCall())
        state(status = 2); assertTrue(controller.answerCall()); work.drain()
        verify(session).sendTelephony(1)
        state(status = 4); assertTrue(controller.endCall()); work.drain()
        verify(session).sendTelephony(3)
    }
    @Test fun nativeAnswerBeforeTheExecutorRunsCancelsTheOldHook() {
        state(status = 2); controller.answerCall(); state(status = 4); work.drain()
        verify(session, never()).sendTelephony(anyInt())
    }
    @Test fun replacedAirPlaySessionCannotReceiveOldOrInheritedPhoneCommands() {
        state(status = 2); controller.answerCall()
        val replacement = mock(AirPlaySession::class.java)
        listener.onSessionActive(replacement); listener.onSessionEnded(session)
        assertNull(controller.phoneCallControl()); work.drain()
        verify(session, never()).sendTelephony(anyInt()); verify(replacement, never()).sendTelephony(anyInt())
    }
    @Test fun waitingAndEndedCallsDoNotAcceptUntargetedCommands() {
        state(status = 4); state("two", 2)
        assertFalse(controller.answerCall()); assertFalse(controller.endCall())
        state("two", 0); state(status = 0)
        assertFalse(controller.endCall())
    }
    @Test fun routeEligibilityIsRecheckedAtActualDelivery() {
        var nativeOwnsPhone = false
        state(status = 2); assertTrue(controller.answerCall { !nativeOwnsPhone })
        nativeOwnsPhone = true; work.drain()
        verify(session, never()).sendTelephony(anyInt())
    }
    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
