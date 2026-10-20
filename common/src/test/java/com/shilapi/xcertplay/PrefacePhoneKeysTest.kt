package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent
import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase
import com.shilapi.xcertplay.hud.CarPlayCallControl
import com.shilapi.xcertplay.orchestration.CarPlayPhoneCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrefacePhoneKeysTest {
    private var call: CarPlayCallControl? = CarPlayCallControl(Any(), Phase.RINGING)
    private var active = true
    private var route = PhoneKeyRoute.CARPLAY
    private var now = 1000L
    private var accepted = true
    private val sent = mutableListOf<CarPlayPhoneCommand>()
    private val pending = mutableListOf<() -> Boolean>()
    private val keys = PrefacePhoneKeys({ call }, { active }, { route }, { action, guard ->
        pending.add(guard); if (accepted) sent.add(action); accepted
    }, clock = { now })
    private fun event(down: Boolean, code: Int = 299, repeats: Int = 0, duration: Long = 100,
        flags: Int = 0) = KeyEvent(now, now + if (down) 0 else duration,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeats, 0, 1, 0, flags)
    private fun press(code: Int = 299) { keys.window(event(true, code)); keys.window(event(false, code)) }
    private fun broadcast(action: Int = 0, type: Int = 200005) = Intent(PrefacePhoneButtonReceiver.ACTION)
        .putExtra(PrefacePhoneButtonReceiver.KEY_TYPE, type).putExtra(PrefacePhoneButtonReceiver.KEY_ACTION, action)

    @Test fun shortPhonePressAnswersThenHangsUpOnTheNextDistinctPress() {
        press(); assertEquals(listOf(CarPlayPhoneCommand.ANSWER), sent)
        call = call!!.copy(phase = Phase.ACTIVE); now += 1000
        press(); assertEquals(listOf(CarPlayPhoneCommand.ANSWER, CarPlayPhoneCommand.END), sent)
    }
    @Test fun physicalBroadcastAndWindowEchoCannotHangUpTheJustAnsweredCall() {
        press(); call = call!!.copy(phase = Phase.ACTIVE)
        assertTrue(keys.shortPress()); assertEquals(listOf(CarPlayPhoneCommand.ANSWER), sent)
    }
    @Test fun aBroadcastAnswerBeforeWindowReleaseDoesNotSendASecondCommand() {
        keys.window(event(true)); keys.shortPress(); call = call!!.copy(phase = Phase.ACTIVE)
        assertTrue(keys.window(event(false)) == true)
        assertEquals(listOf(CarPlayPhoneCommand.ANSWER), sent)
    }
    @Test fun explicitCallAndEndKeysKeepTheirOwnMeaning() {
        press(KeyEvent.KEYCODE_ENDCALL)
        assertEquals(listOf(CarPlayPhoneCommand.END), sent)
        now += 1000; call = call!!.copy(phase = Phase.ACTIVE)
        assertFalse(keys.window(event(true, KeyEvent.KEYCODE_CALL)) == true)
        assertNull(keys.window(event(true, KeyEvent.KEYCODE_MEDIA_NEXT)))
    }
    @Test fun repeatsLongPressAndCanceledKeysDoNotSendPhoneCommands() {
        keys.window(event(true, repeats = 1)); keys.window(event(false))
        keys.window(event(true)); keys.window(event(false, duration = 1000))
        keys.window(event(true)); keys.window(event(false, flags = KeyEvent.FLAG_CANCELED))
        assertTrue(sent.isEmpty())
    }
    @Test fun releaseWithoutPressIdleOrReplacedCallIsIgnored() {
        keys.window(event(false)); assertTrue(sent.isEmpty())
        keys.window(event(true)); call = CarPlayCallControl(Any(), Phase.RINGING)
        keys.window(event(false)); assertTrue(sent.isEmpty())
        call = null; assertFalse(keys.shortPress())
    }
    @Test fun hfpOwnershipOrUnknownStatusNeverCreatesAnAdditionalCarPlayCommand() {
        for (owner in listOf(PhoneKeyRoute.VEHICLE, PhoneKeyRoute.UNKNOWN)) {
            route = owner; assertFalse(keys.shortPress()); assertFalse(keys.window(event(true)) == true)
        }
        assertTrue(sent.isEmpty())
    }
    @Test fun closeDisconnectCallChangeAndHfpReconnectInvalidatePendingCommands() {
        assertTrue(keys.shortPress()); val valid = pending.last(); assertTrue(valid())
        route = PhoneKeyRoute.VEHICLE; assertFalse(valid()); route = PhoneKeyRoute.CARPLAY
        active = false; assertFalse(valid()); active = true
        call = call!!.copy(phase = Phase.ACTIVE); assertFalse(valid())
        keys.close(); assertFalse(valid()); assertFalse(keys.shortPress())
    }
    @Test fun failedSendCanBeRetriedWithoutAFalseEchoSuccess() {
        accepted = false; assertFalse(keys.shortPress())
        accepted = true; assertTrue(keys.shortPress()); assertEquals(1, sent.size)
    }
    @Test fun broadcastRequiresTheExactShortPhoneEventAndNeverAbortsAnOrderedBroadcast() {
        var attempts = 0
        val receiver = PrefacePhoneButtonReceiver(shortPress = { attempts++; keys.shortPress() })
        receiver.onReceive(null, broadcast(1)); receiver.onReceive(null, broadcast(2))
        receiver.onReceive(null, broadcast(type = 200231))
        receiver.onReceive(null, broadcast().setAction("other"))
        receiver.onReceive(null, Intent(PrefacePhoneButtonReceiver.ACTION))
        assertEquals(0, attempts)
        receiver.onReceive(null, broadcast())
        assertEquals(1, attempts); assertFalse(receiver.abortBroadcast)
    }

    @Test fun aHeldRepeatNeverTurnsIntoAShortPhonePressOnRelease() {
        keys.window(event(true)); keys.window(event(true, repeats = 1)); keys.window(event(false))
        assertTrue(sent.isEmpty())
    }

    @Test fun theOemLongClickCancelsAPendingWindowShortClick() {
        keys.window(event(true))
        val receiver = PrefacePhoneButtonReceiver(keys::shortPress, keys::longPress)
        receiver.onReceive(null, broadcast(action = 1))
        assertTrue(keys.window(event(false)) == true)
        assertTrue(sent.isEmpty())
    }
}
