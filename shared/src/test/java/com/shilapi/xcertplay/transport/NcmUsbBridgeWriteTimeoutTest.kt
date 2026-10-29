package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A NAKed NCM bulk OUT must not hold a USB transfer for the full timeout before the session (#634). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [NcmWriteConnectionShadow::class])
class NcmUsbBridgeWriteTimeoutTest {
    @Before fun reset() { NcmWriteReplay.reset() }

    @Test fun framesBeforeThePhoneTakesOneUseTheShortTimeoutThenTheCallers() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL, NcmWriteReplay.ALL))
        val diagnostics = withBridge { ncm -> repeat(4) { ncm.send(frame, 2_000) } }
        val short = NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS
        assertEquals(listOf(short, short, short, 2_000), NcmWriteReplay.timeouts)
        assertEquals(listOf("NCM bulk-out ready after 2 dropped frames"), diagnostics)
    }

    @Test fun aTimeoutAfterThePipeWasReadyKeepsTheCallersTimeout() {
        NcmWriteReplay.results.addAll(listOf(NcmWriteReplay.ALL, -1, NcmWriteReplay.ALL))
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000) } }
        assertEquals(listOf(NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS, 2_000, 2_000), NcmWriteReplay.timeouts)
    }

    @Test fun theFirstAcceptedFullBlockMarksLinkTimeAndLaterWritesCannotMoveIt() {
        NcmWriteReplay.results.addAll(listOf(-1, 0, NcmWriteReplay.ALL, NcmWriteReplay.ALL))
        withBridge { ncm ->
            repeat(2) { ncm.send(frame, 100); org.junit.Assert.assertNull(ncm.firstBulkOutAcceptedAtNanos) }
            ncm.send(frame, 100)
            val first = ncm.firstBulkOutAcceptedAtNanos
            org.junit.Assert.assertNotNull(first)
            ncm.send(frame, 100)
            assertEquals(first, ncm.firstBulkOutAcceptedAtNanos)
        }
    }

    @Test fun aShorterCallerTimeoutIsKept() {
        NcmWriteReplay.results.add(-1)
        withBridge { ncm -> ncm.send(frame, 5) }
        assertEquals(listOf(5), NcmWriteReplay.timeouts)
    }

    @Test fun diagnosticWritesDistinguishNakFromAnEntireAcceptedBlock() {
        NcmWriteReplay.results.addAll(listOf(-1, 0, NcmWriteReplay.ALL, -1))
        withBridge {
            assertFalse(it.sendAccepted(frame, 100))
            assertFalse(it.sendAccepted(frame, 100))
            assertTrue(it.sendAccepted(frame, 100))
            assertFalse(it.sendAccepted(frame, 100))
        }
    }

    @Test fun partialWriteIsAnErrorAndDoesNotAnnounceReadiness() {
        NcmWriteReplay.results.add(1)
        val diagnostics = withBridge {
            try { it.sendAccepted(frame, 100); throw AssertionError("partial write accepted") }
            catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun diagnosticSetupReportsKnownFormatAndEnablesReceptionOnTheNcmInterfaceOnly() {
        NcmWriteReplay.controlWords[0x83] = 0
        NcmWriteReplay.controlWords[0x89] = 0
        val diagnostics = withBridge { it.prepareFrameDiagnostics(2) }
        assertTrue(diagnostics.any { it.contains("ntbFormat=0 crcMode=0") })
        assertEquals(listOf(Triple(0xa1, 0x83, 2), Triple(0xa1, 0x89, 2), Triple(0x21, 0x43, 2)),
            NcmWriteReplay.controlCalls)
        assertEquals(0x0e, NcmWriteReplay.filter)
    }

    @Test fun unsupportedNtb32StopsDiagnosticsWithoutChangingItsFormat() {
        NcmWriteReplay.controlWords[0x83] = 1
        NcmWriteReplay.controlWords[0x89] = 0
        withBridge {
            try { it.prepareFrameDiagnostics(2); throw AssertionError("unsupported format accepted") }
            catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
        assertEquals(listOf(0x83, 0x89), NcmWriteReplay.controlCalls.map { it.second })
    }

    @Test fun diagnosticOutResultsKeepApiFailureZeroPartialAndSuccessDistinctAndBoundDetailLines() {
        NcmWriteReplay.results.addAll(listOf(-1, 0, 1, NcmWriteReplay.ALL))
        val diagnostics = withBridge { ncm ->
            ncm.prepareFrameDiagnostics(2)
            assertFalse(ncm.sendAccepted(frame, 100))
            assertFalse(ncm.sendAccepted(frame, 100))
            try { ncm.sendAccepted(frame, 100); throw AssertionError("partial accepted") }
            catch (_: IphoneUsbException.DeviceUnavailable) { }
            assertTrue(ncm.sendAccepted(frame, 100))
            repeat(20) { ncm.sendAccepted(frame, 100) }
            val summary = ncm.readDiagnosticSummary()
            assertTrue(summary, summary.contains("usbOutCalls=24 full=21 negative=1 zero=1 partial=1"))
        }
        val writes = diagnostics.filter { it.startsWith("NCM frame test write ") }
        assertEquals(12, writes.size)
        assertTrue(writes[0].contains("return=-1 outcome=negative_api_result"))
        assertTrue(writes[0].contains("requestedTimeoutMs=100 effectiveTimeoutMs=100"))
        assertTrue(writes[1].contains("outcome=zero"))
        assertTrue(writes[2].contains("outcome=partial"))
        assertTrue(writes[3].contains("outcome=full"))
        assertTrue(writes[4].contains("effectiveTimeoutMs=100"))
        assertTrue(writes.all { it.contains("elapsedUs=") && it.length < 500 })
        assertTrue(diagnostics.any { it.contains("request=0x83 return=-1 expectedBytes=2") })
        assertTrue(diagnostics.any { it.contains("endpoints in=0x88 out=0x6 inPacket=512 outPacket=512") })
    }

    @Test fun diagnosticExceptionIsRecordedWithoutPrivateErrorMessagesAndStillPropagates() {
        NcmWriteReplay.failure = SecurityException("private USB identity")
        val diagnostics = withBridge { ncm ->
            ncm.prepareFrameDiagnostics(2)
            try { ncm.sendAccepted(frame, 100); throw AssertionError("failure swallowed") }
            catch (_: SecurityException) { }
            assertTrue(ncm.readDiagnosticSummary().contains("exceptions=1"))
        }
        assertTrue(diagnostics.any { it.contains("outcome=exception failureClass=SecurityException") })
        assertFalse(diagnostics.any { it.contains("private USB identity") })
    }

    @Test fun diagnosticProbesKeepTheirBoundedBudgetEvenWhenFirstWritesFail() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL))
        withBridge { ncm ->
            ncm.prepareFrameDiagnostics(2)
            repeat(3) { ncm.sendAccepted(frame, 100) }
        }
        assertEquals(listOf(100, 100, 100), NcmWriteReplay.timeouts)
    }

    private fun withBridge(block: (NcmUsbBridge) -> Unit): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x88), null, emptyList(), null, diagnostics::add)
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    private companion object {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmWriteReplay {
    const val ALL = Int.MAX_VALUE
    /** One result per bulkTransfer: a byte count, or [ALL] for the whole frame. */
    val results = ArrayDeque<Int>()
    val timeouts = mutableListOf<Int>()
    val controlWords = mutableMapOf<Int, Int>()
    val controlCalls = mutableListOf<Triple<Int, Int, Int>>()
    var filter: Int? = null
    var failure: RuntimeException? = null
    fun reset() { results.clear(); timeouts.clear(); controlWords.clear(); controlCalls.clear(); filter = null; failure = null }
}

@Implements(UsbDeviceConnection::class)
class NcmWriteConnectionShadow {
    @Implementation fun controlTransfer(type: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeout: Int): Int {
        NcmWriteReplay.controlCalls.add(Triple(type, request, index))
        if (request == 0x43) { NcmWriteReplay.filter = value; return 0 }
        val word = NcmWriteReplay.controlWords[request] ?: return -1
        buffer!![0] = word.toByte(); buffer[1] = (word ushr 8).toByte()
        return 2
    }
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        NcmWriteReplay.failure?.let { throw it }
        NcmWriteReplay.timeouts += timeout
        return NcmWriteReplay.results.pollFirst()?.takeIf { it != NcmWriteReplay.ALL } ?: length
    }
    @Implementation fun close() = Unit
}
