package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase
import com.shilapi.xcertplay.hud.CarPlayCallControl
import com.shilapi.xcertplay.orchestration.CarPlayController
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrefacePhoneSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private var call = CarPlayCallControl(Any(), Phase.RINGING)
    private val guards = mutableListOf<() -> Boolean>()

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH, true)
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(com.shilapi.xcertplay.media.CarPlayNowPlaying())
        `when`(controller.phoneCallControl()).thenAnswer { call }
        `when`(controller.answerCall(any<() -> Boolean>() ?: { false })).thenAnswer {
            @Suppress("UNCHECKED_CAST") guards.add(it.arguments[0] as () -> Boolean); true
        }
        `when`(controller.endCall(any<() -> Boolean>() ?: { false })).thenAnswer {
            @Suppress("UNCHECKED_CAST") guards.add(it.arguments[0] as () -> Boolean); true
        }
        CarPlayMediaKeys.attach(app, controller) { false }
        CarPlayBackgroundSession.active = true
        idle()
    }
    @After fun cleanup() {
        CarPlayMediaKeys.detach(controller); CarPlayBackgroundSession.clear(); idle()
        shadowOf(app).denyPermissions("android.permission.INJECT_EVENTS")
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun receiver() = ReflectionHelpers.getField<PrefacePhoneButtonReceiver>(CarPlayMediaKeys, "prefacePhoneReceiver")
    private fun event() = Intent(PrefacePhoneButtonReceiver.ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra(PrefacePhoneButtonReceiver.KEY_TYPE, 200005).putExtra(PrefacePhoneButtonReceiver.KEY_ACTION, 0)

    @Test fun phoneBroadcastAnswersInTheBackgroundWithoutMusicFocusOrAccessibility() {
        receiver().onReceive(app, event())
        assertEquals(1, guards.size); assertTrue(guards.single()())
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun activePhoneBroadcastUsesDropAndNeverRequestsSiri() {
        call = call.copy(phase = Phase.ACTIVE)
        receiver().onReceive(app, event())
        verify(controller).endCall(any<() -> Boolean>() ?: { false })
        verify(controller, never()).answerCall(any<() -> Boolean>() ?: { false })
        verify(controller, never()).requestSiri()
    }
    @Test fun disconnectRetiresTheOldReceiverAndQueuedGuard() {
        val retired = receiver(); retired.onReceive(app, event())
        CarPlayMediaKeys.detach(controller)
        assertFalse(guards.single()())
        retired.onReceive(app, event())
        assertEquals(1, guards.size)
        assertFalse(CarPlayMediaKeys.report().contains("phoneBroadcastRegistered=true"))
    }
    @Test fun reattachingTheSameControllerStillInvalidatesItsOldReceiver() {
        val retired = receiver()
        CarPlayMediaKeys.detach(controller)
        CarPlayMediaKeys.attach(app, controller) { false }; CarPlayBackgroundSession.active = true; idle()
        retired.onReceive(app, event()); assertTrue(guards.isEmpty())
        receiver().onReceive(app, event()); assertEquals(1, guards.size)
    }
    @Test fun inactiveProjectionDoesNotAnswerAnOldRingingSnapshot() {
        CarPlayBackgroundSession.active = false
        receiver().onReceive(app, event()); assertTrue(guards.isEmpty())
    }
    @Test fun bluetoothMusicModeKeepsAnIndependentPhoneReceiver() {
        CarPlayMediaKeys.detach(controller)
        `when`(controller.usesCarBluetoothAudio).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller) { false }; CarPlayBackgroundSession.active = true; idle()
        receiver().onReceive(app, event())
        assertEquals(1, guards.size)
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "trackInterception"))
    }

    @Test fun registeredReceiverRequiresTheSendersPlatformInputPermission() {
        val registration = shadowOf(app).registeredReceivers.single { it.broadcastReceiver is PrefacePhoneButtonReceiver }
        assertEquals("android.permission.INJECT_EVENTS", registration.broadcastPermission)
        app.sendBroadcast(event()); idle(); assertTrue(guards.isEmpty())
        shadowOf(app).grantPermissions("android.permission.INJECT_EVENTS")
        app.sendBroadcast(event()); idle(); assertEquals(1, guards.size)
    }

    @Test fun connectedTransitioningOrUnknownHfpKeepsPhoneInputWithTheVehicle() {
        val adapter = shadowOf(BluetoothAdapter.getDefaultAdapter())
        adapter.setEnabled(true)
        for (state in listOf(1, 2, 3, 99)) {
            adapter.setProfileConnectionState(16, state)
            receiver().onReceive(app, event())
        }
        assertTrue(guards.isEmpty())
        adapter.setProfileConnectionState(16, 0)
        receiver().onReceive(app, event()); assertEquals(1, guards.size)
    }
}
