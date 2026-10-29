package com.shilapi.xcertplay

import android.content.Intent
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothAutoConnectActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val phone = "12:34:56:78:9A:BC"
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        app.getSharedPreferences("diplay", 0).edit().clear().putBoolean("notification_asked", true).commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        ConnectionAutoStart.resetForTest()
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        DiPlayPreferences.saveAutoConnect(app, true)
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).create().get()
        ReflectionHelpers.setField(activity, "setupError", null)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<android.os.Handler>(activity, "handler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
        ConnectionAutoStart.resetForTest()
    }

    private fun wake(address: String = phone, at: Long = SystemClock.elapsedRealtime()) = Intent()
        .putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, PhoneBluetoothReceiver.launchToken)
        .putExtra(PhoneBluetoothReceiver.EXTRA_PHONE_ADDRESS, address)
        .putExtra(PhoneBluetoothReceiver.EXTRA_WAKE_ELAPSED, at)

    private fun accept(intent: Intent) = ReflectionHelpers.callInstanceMethod<Boolean>(activity,
        "consumeBluetoothAutoConnectIntent", from(Intent::class.java, intent))

    private fun connect() = ReflectionHelpers.callInstanceMethod<Unit>(activity,
        "connectFromBluetooth", from(String::class.java, phone))

    @Test fun returningHomeRejectsQueuedWakeEvenAfterManualConnectionIsAllowedAgain() {
        val queued = ConnectionAutoStart.stamp(wake())
        assertTrue(accept(ConnectionAutoStart.stamp(wake())))
        ConnectionAutoStart.cancel()
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
        ConnectionAutoStart.resume()
        assertFalse(accept(queued))
        assertTrue(accept(ConnectionAutoStart.stamp(wake())))
        connect()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test fun wakeSelectsWirelessEvenWhenDefaultTransportIsUsbAndConsumesOnlyOnce() {
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val intent = wake()
        assertTrue(accept(intent))
        assertFalse(accept(intent))
        connect()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun staleAndDifferentPhoneIntentsAreRejected() {
        assertFalse(accept(wake("AA:BB:CC:DD:EE:FF")))
        assertFalse(accept(wake(at = SystemClock.elapsedRealtime() - 60_001)))
        assertFalse(accept(Intent().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)))
    }

    @Test fun forgedBooleanOrWrongTokenCannotStartABluetoothConnection() {
        assertFalse(accept(wake().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)))
        assertFalse(accept(wake().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, "untrusted")))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "initialLaunch"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(accept(wake()))
    }

    @Test fun selectionOrOptInChangesBeforeQueuedLaunchCancelTheRequest() {
        assertTrue(accept(wake()))
        DiPlayPreferences.saveAutoConnect(app, false)
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
        DiPlayPreferences.saveAutoConnect(app, true)
        assertTrue(accept(wake()))
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Other")
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aSessionStartedWhileActivityWasOpeningIsNeverStoppedOrReplaced() {
        assertTrue(accept(wake()))
        var stopped = false
        val stop: ((() -> Unit) -> Unit) = { stopped = true }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        connect()
        assertFalse(stopped)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=session-present"))
    }

    @Test fun aSupersededWakeDoesNotReconnectFromItsOldHandlerOrPermissionCallback() {
        assertTrue(accept(wake()))
        // Manual connection paths cancel this pending request before requesting their transport.
        ReflectionHelpers.setField(activity, "bluetoothAutoConnectPhone", null)
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=activity-request-cancelled"))
    }

    @Test fun oldPhoneCallbackDoesNotEraseNewPhoneRequest() {
        assertTrue(accept(wake()))
        val other = "AA:BB:CC:DD:EE:FF"
        DiPlayPreferences.savePhone(app, other, "Other")
        assertTrue(accept(wake(other)))
        connect()
        assertEquals(other, ReflectionHelpers.getField<String>(activity, "bluetoothAutoConnectPhone"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connectFromBluetooth", from(String::class.java, other))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
    }
}
