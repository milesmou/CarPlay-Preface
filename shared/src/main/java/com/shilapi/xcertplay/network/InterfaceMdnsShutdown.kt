package com.shilapi.xcertplay.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.impl.JmDNSImpl

/**
 * JmDNS 3.6.3 closes its socket only after goodbye/cancellation waits, and then waits
 * indefinitely for its receiver. Release that instance's transport once cancellation
 * has started, so its receiver/finalizer cannot hold up the next CarPlay connection.
 */
internal object InterfaceMdnsShutdown {
    fun close(dns: JmDNS, waitMillis: Long = 1_000, log: (String) -> Unit = {}) {
        require(waitMillis > 0)
        // The pinned factory returns JmDNSImpl. Keep ordinary Closeable semantics for
        // any other implementation; do not claim its transport has been released.
        if (dns !is JmDNSImpl) {
            dns.close()
            return
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        var interrupted = false
        fun await(millis: Long): Boolean = try {
            done.await(millis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        Thread({
            try {
                dns.close()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }, "diplay-mdns-finalize").apply { isDaemon = true; start() }
        try {
            // Closing a live registry's socket before cancelState() can trigger its
            // recovery path and open another socket. Observe cancellation first.
            while (done.count != 0L && !dns.isCanceling && !dns.isCanceled &&
                !dns.isClosing && !dns.isClosed && System.nanoTime() < deadline) {
                await(10)
            }
            var transportReleased = false
            if ((done.count != 0L || failure.get() != null) &&
                (dns.isCanceling || dns.isCanceled || dns.isClosing || dns.isClosed)) {
                try {
                    val socket = dns.socket
                    socket?.close()
                    transportReleased = socket == null || socket.isClosed
                } catch (error: Exception) {
                    log("socketCloseFailed=${error.javaClass.simpleName}")
                }
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            if (!await(remaining) && !transportReleased) {
                // No proof that the old registry is inert: retain the ownership barrier.
                log("transportReleased=false waitingForFinalizer=true")
                while (!await(1_000)) { /* Preserve the barrier even if interrupted. */ }
            }
            failure.get()?.let { throw it }
            log("transportReleased=${transportReleased || done.count == 0L} finalizerCompleted=${done.count == 0L}")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
