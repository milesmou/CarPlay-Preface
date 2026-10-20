package com.shilapi.xcertplay.settings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Synchronous notifications on the saving thread. UI subscribers must dispatch to their UI thread.
 * Close the subscription when its owner stops; closing it never removes another owner's listener.
 * A callback already running may finish. Callbacks run without holding registry locks.
 */
internal object OverlaySettingsChanges {
    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    fun subscribe(listener: () -> Unit): AutoCloseable = Subscription(listener).also(subscriptions::add)

    fun notifyChanged() { subscriptions.forEach { it.notifyChanged() } }

    private class Subscription(private val listener: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        fun notifyChanged() { if (!closed.get()) listener() }
        override fun close() {
            closed.set(true)
            subscriptions.remove(this)
        }
    }
}
