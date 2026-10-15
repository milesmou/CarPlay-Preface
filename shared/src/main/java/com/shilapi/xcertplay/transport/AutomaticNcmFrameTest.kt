package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a diagnostic-only NCM bridge after the normal system path has failed. */
internal class AutomaticNcmFrameTest(
    hostMac: ByteArray,
    hostAddress: ByteArray,
    send: (ByteArray) -> Boolean,
    private val receive: () -> ByteArray?,
    private val release: () -> Unit,
    private val diagnostic: (String) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
    private val launch: (() -> Unit) -> Unit = { work ->
        Thread(work, "usb-ncm-frame-test").apply { isDaemon = true; start() }
    },
    private val transportSummary: () -> String = { "" },
) : Closeable {
    constructor(bridge: NcmUsbBridge, hostMac: ByteArray, hostAddress: ByteArray,
        diagnostic: (String) -> Unit) : this(hostMac, hostAddress,
        { bridge.sendAccepted(it, 100) }, { bridge.recv(100) }, bridge::close, diagnostic,
        transportSummary = bridge::readDiagnosticSummary)

    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val probe = NcmFrameProbe(hostMac, hostAddress, send)
    private val traffic = NcmFrameTraffic()
    @Volatile var result: String? = null; private set

    fun startSessionSent() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        launch {
            var outcome = "window_complete"
            try {
                diagnostic("NCM frame test started durationMs=15000 startSessionSent=true")
                val start = clockNanos()
                var nextProbe = start
                var nextSummary = start + 5_000_000_000L
                while (!closed.get() && clockNanos() - start < 15_000_000_000L) {
                    val now = clockNanos()
                    if (now >= nextProbe) { probe.probe(); nextProbe = now + 5_000_000_000L }
                    receive()?.let { frame -> traffic.receive(frame); probe.receive(frame) }
                    if (now >= nextSummary) {
                        emit("sample")
                        nextSummary = now + 5_000_000_000L
                    }
                }
                if (closed.get()) outcome = "cancelled"
            } catch (error: Exception) {
                outcome = if (closed.get()) "cancelled" else "io_failed_${error.javaClass.simpleName}"
            } finally {
                val summary = "outcome=$outcome ${summary()}"
                try { emit("result", "outcome=$outcome ") }
                finally { try { close() } finally { result = summary } }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            release()
            if (!started.get()) {
                result = "outcome=no_start_session ${summary()}"
                emit("result", "outcome=no_start_session ")
            }
        }
    }

    private fun summary(): String = "${probe.summary()}\n${traffic.summary()}\n${transportSummary()}".trimEnd()

    private fun emit(stage: String, prefix: String = "") {
        diagnostic("NCM frame test $stage $prefix${probe.summary()}")
        traffic.summary().lineSequence().forEach { diagnostic("NCM frame test $stage $it") }
        transportSummary().lineSequence().filter { it.isNotBlank() }.forEach {
            diagnostic("NCM frame test $stage $it")
        }
    }
}
