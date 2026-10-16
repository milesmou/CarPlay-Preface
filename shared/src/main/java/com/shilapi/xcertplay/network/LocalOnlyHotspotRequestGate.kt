package com.shilapi.xcertplay.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Serializes this process's reservations, including callbacks arriving after cancellation. */
internal class LocalOnlyHotspotRequestGate {
    private val lock = Object()
    private var owner: Lease? = null

    fun acquire(deadlineNanos: Long, cancelled: () -> Boolean): Lease = synchronized(lock) {
        while (owner != null) {
            if (cancelled()) throw InterruptedIOException("Temporary hotspot startup cancelled")
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException("Previous temporary hotspot request is still releasing")
            try {
                TimeUnit.NANOSECONDS.timedWait(lock, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted waiting for previous temporary hotspot release")
            }
        }
        if (cancelled()) throw InterruptedIOException("Temporary hotspot startup cancelled")
        Lease().also { owner = it }
    }

    inner class Lease internal constructor() {
        private var callbackFinished = false
        private var retired = false

        // Call only after any rejected reservation has been closed, never before that close.
        fun callbackFinished() = synchronized(lock) {
            callbackFinished = true
            releaseIfFinished()
        }

        // Call only after closing the application's acquired reservation.
        fun retire() = synchronized(lock) {
            retired = true
            releaseIfFinished()
        }

        private fun releaseIfFinished() {
            if (retired && callbackFinished && owner === this) {
                owner = null
                lock.notifyAll()
            }
        }
    }
}
