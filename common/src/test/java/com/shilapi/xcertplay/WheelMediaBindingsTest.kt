package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelMediaBindingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val owner = Any()
    @Before fun reset() {
        WheelMediaLearning.resetForTest()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        app.getSharedPreferences(WheelMediaBindings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun release() {
        WheelMediaLearning.cancel(owner)
        app.getSharedPreferences(WheelMediaBindings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
    @Test fun physicalAndStandardAliasesHaveOneBindingAndCannotBeAssignedTwice() {
        assertEquals(WheelMediaBindings.Action.NEXT, WheelMediaBindings.resolve(app, 305))
        assertFalse(WheelMediaBindings.assign(app, WheelMediaBindings.Action.PREVIOUS, 305))
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, null))
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.PREVIOUS, 305))
        assertEquals(WheelMediaBindings.Action.PREVIOUS, WheelMediaBindings.resolve(app, 87))
        assertEquals(WheelMediaBindings.Action.PREVIOUS, WheelMediaBindings.resolve(app, 305))
        assertFalse(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, 87))
    }
    @Test fun protectedAndTypingKeysCannotBecomeMediaBindingsIncludingImportedValues() {
        for (code in listOf(3, 4, 5, 6, 24, 25, 26, 29, 66, 164, 299, 301, 302)) {
            assertFalse("code=$code", WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, code))
        }
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().putInt("NEXT", 24).commit()
        assertNull(WheelMediaBindings.key(app, WheelMediaBindings.Action.NEXT))
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().putString("NEXT", "invalid").commit()
        assertNull(WheelMediaBindings.key(app, WheelMediaBindings.Action.NEXT))
    }
    @Test fun learningSavesOnceAndSuppressesParallelEchoWithoutForwardingIt() {
        val results = mutableListOf<WheelMediaLearning.Result>()
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.NEXT, results::add)
        assertTrue(WheelMediaLearning.offer(KeyEvent(10, 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 0)))
        assertTrue(WheelMediaLearning.offer(KeyEvent(10, 11, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1, 0)))
        assertEquals(listOf(WheelMediaLearning.Result.SAVED), results)
        assertEquals(KeyEvent.KEYCODE_F1, WheelMediaBindings.key(app, WheelMediaBindings.Action.NEXT))
        assertFalse(WheelMediaLearning.active(owner))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(1, results.size)
    }
    @Test fun cancellationAndTimeoutCannotLeaveLearningActiveOrChangeTheBinding() {
        val results = mutableListOf<WheelMediaLearning.Result>()
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.NEXT, results::add)
        WheelMediaLearning.cancel(Any())
        assertTrue(WheelMediaLearning.active(owner))
        WheelMediaLearning.cancel(owner)
        assertEquals(listOf(WheelMediaLearning.Result.CANCELLED), results)
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.NEXT, results::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf(WheelMediaLearning.Result.CANCELLED, WheelMediaLearning.Result.TIMED_OUT), results)
        assertFalse(WheelMediaLearning.offer(true, KeyEvent.KEYCODE_F2))
        assertEquals(87, WheelMediaBindings.key(app, WheelMediaBindings.Action.NEXT))
    }
    @Test fun conflictingOrReservedLearningPreservesTheExistingBinding() {
        val results = mutableListOf<WheelMediaLearning.Result>()
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.PREVIOUS, results::add)
        assertTrue(WheelMediaLearning.offer(true, 305))
        assertEquals(listOf(WheelMediaLearning.Result.CONFLICT), results)
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.PREVIOUS, results::add)
        assertFalse(WheelMediaLearning.offer(true, 24))
        assertEquals(WheelMediaLearning.Result.RESERVED, results.last())
        assertEquals(88, WheelMediaBindings.key(app, WheelMediaBindings.Action.PREVIOUS))
    }

    @Test fun ringingCancelsLearningWithoutConsumingTheCallKeyOrSavingIt() {
        val results = mutableListOf<WheelMediaLearning.Result>()
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        WheelMediaLearning.start(owner, app, WheelMediaBindings.Action.NEXT, results::add)
        audio.mode = android.media.AudioManager.MODE_RINGTONE
        try {
            assertFalse(WheelMediaLearning.offer(true, KeyEvent.KEYCODE_F2))
            assertEquals(listOf(WheelMediaLearning.Result.CANCELLED), results)
            assertEquals(87, WheelMediaBindings.key(app, WheelMediaBindings.Action.NEXT))
        } finally { audio.mode = android.media.AudioManager.MODE_NORMAL }
    }

    @Test fun obsoleteSiriPreferencesCannotOverrideOrReviveTheNewBinding() {
        app.getSharedPreferences("diplay_wheel_map_zoom", 0).edit()
            .putBoolean("siri_key", true).putString("key_SIRI", WheelKey(87, 0, "?").encode()).commit()
        assertEquals(231, WheelMediaBindings.key(app, WheelMediaBindings.Action.SIRI))
        assertEquals(WheelMediaBindings.Action.NEXT, WheelMediaBindings.resolve(app, 305))
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, null))
        assertNull(WheelMediaBindings.resolve(app, 231))
    }
}
