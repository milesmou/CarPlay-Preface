package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/** Codes only: never save keyboard text, device names, or transient input-device IDs. */
internal object WheelMediaBindings {
    const val PREFS = "diplay_media_wheel"
    enum class Action(val defaultCode: Int) { PREVIOUS(88), NEXT(87), SIRI(231) }
    fun canonical(code: Int): Int = when (code) { 304 -> 88; 305 -> 87; 298, 219 -> 231; else -> code }
    fun key(context: Context, action: Action): Int? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { preferences.getInt(action.name, action.defaultCode) }.getOrDefault(-1)
            .takeIf { it > 0 && allowed(it) }?.let(::canonical)
    }
    fun resolve(context: Context, code: Int): Action? {
        if (key(context, Action.SIRI) == canonical(code)) return Action.SIRI
        return Action.entries.firstOrNull { key(context, it) == canonical(code) }
    }
    fun known(context: Context, code: Int): Boolean = Action.entries.any {
        it.defaultCode == canonical(code) || key(context, it) == canonical(code)
    }
    fun allowed(code: Int): Boolean = code in 1..1000 && !WheelKeyLog.writesText(code) &&
        code !in setOf(3, 4, 5, 6, 24, 25, 26, 79, 85, 89, 90, 91, 126, 127, 164, 291, 292, 299, 301, 302, 307, 308)
    fun conflict(context: Context, action: Action, code: Int): Action? =
        Action.entries.firstOrNull { it != action && key(context, it) == canonical(code) }
    fun assign(context: Context, action: Action, code: Int?): Boolean {
        if (code != null && (!allowed(code) || conflict(context, action, code) != null)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(action.name, code?.let(::canonical) ?: -1).apply()
        CarPlayMediaKeys.mediaBindingsChanged()
        return true
    }
    fun requestedCodes(context: Context): IntArray = Action.entries.mapNotNull { key(context, it) }
        .filter { it !in setOf(87, 88, 231) }.distinct().toIntArray()
}

/** Explicit, short-lived learning. It observes the existing input API and media broadcasts. */
internal object WheelMediaLearning {
    private const val TIMEOUT_MILLIS = 10_000L
    private val handler = Handler(Looper.getMainLooper())
    private data class Pending(val owner: Any, val context: Context, val action: WheelMediaBindings.Action,
        val done: (Result) -> Unit)
    enum class Result { SAVED, CONFLICT, RESERVED, CANCELLED, TIMED_OUT }
    private var pending: Pending? = null
    private var backend: PrefaceTrackKeyInterception.Backend? = null
    private var receiver: BroadcastReceiver? = null
    private var suppressCode: Int? = null
    private var suppressUntil = 0L
    private val clearSuppression = Runnable { suppressCode = null }
    private val timeout = Runnable { finish(Result.TIMED_OUT) }
    fun active(owner: Any): Boolean = pending?.owner === owner
    fun start(owner: Any, context: Context, action: WheelMediaBindings.Action, done: (Result) -> Unit) {
        finish(Result.CANCELLED)
        handler.removeCallbacks(clearSuppression)
        suppressCode = null
        val current = Pending(owner, context.applicationContext, action, done)
        pending = current
        val observer = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (pending !== current) return
                if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)?.let(::offer)
                } else if (intent?.action == PrefaceVoiceButtonReceiver.ACTION &&
                    intent.getIntExtra(PrefaceVoiceButtonReceiver.KEY_TYPE, -1) == 200231) {
                    val state = intent.getIntExtra(PrefaceVoiceButtonReceiver.KEY_ACTION, -1)
                    if (state in 0..1) offer(state == 0, 298)
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(current.context, observer, IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply {
                addCategory(Intent.CATEGORY_DEFAULT); addAction(PrefaceVoiceButtonReceiver.ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            receiver = observer
        }
        val input = PrefaceInputInterceptionBackend({ work -> handler.post { work() } },
            additionalKeys = { WheelMediaBindings.requestedCodes(current.context) })
        backend = input
        runCatching { input.request { down, code -> if (pending === current) offer(down, code) } }
            .onFailure { WheelKeyLog.note("Wheel learning input unavailable; foreground/broadcast learning retained") }
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
    }
    fun offer(event: KeyEvent): Boolean {
        if (event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        return offer(event.action == KeyEvent.ACTION_DOWN, event.keyCode, event.repeatCount == 0)
    }
    fun offer(down: Boolean, code: Int, first: Boolean = true): Boolean {
        val key = WheelMediaBindings.canonical(code)
        if (suppressCode == key && SystemClock.uptimeMillis() < suppressUntil) return true
        val current = pending ?: return false
        if (!down || !first) return false
        if (inCall(current.context)) {
            finish(Result.CANCELLED); return false
        }
        if (!WheelMediaBindings.allowed(code)) { finish(Result.RESERVED); return false }
        val joystickConflict = WheelZoomSettings.anyEnabled(current.context) && WheelZoomSettings.Role.entries.any {
            WheelZoomSettings.key(current.context, it)?.code?.let(WheelMediaBindings::canonical) == key
        }
        if (joystickConflict || !WheelMediaBindings.assign(current.context, current.action, code)) {
            finish(Result.CONFLICT); return true
        }
        suppressCode = key
        suppressUntil = SystemClock.uptimeMillis() + 750L
        handler.postDelayed(clearSuppression, 750L)
        finish(Result.SAVED)
        return true
    }
    fun cancel(owner: Any) { if (pending?.owner === owner) finish(Result.CANCELLED) }
    internal fun resetForTest() {
        finish(Result.CANCELLED)
        handler.removeCallbacks(clearSuppression)
        suppressCode = null
        suppressUntil = 0L
    }
    private fun finish(result: Result) {
        val current = pending ?: return
        pending = null
        handler.removeCallbacks(timeout)
        receiver?.let { runCatching { current.context.unregisterReceiver(it) } }
        receiver = null
        runCatching { backend?.release() }
        backend = null
        current.done(result)
    }
}
