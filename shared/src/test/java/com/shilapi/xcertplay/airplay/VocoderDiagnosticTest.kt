package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class VocoderDiagnosticTest {
    @Test fun onlyNumericCodecFieldsAreExported() {
        assertEquals("{codec=4, sampleRate=16000}", vocoderDiagnostic(linkedMapOf(
            "codec" to 4, "sampleRate" to 16000, "channels" to "private text",
            "deviceId" to 1234, "payload" to byteArrayOf(1, 2))))
        assertEquals("present_non_numeric", vocoderDiagnostic("private payload"))
        assertEquals("present_non_numeric", vocoderDiagnostic(byteArrayOf(1)))
    }
}
