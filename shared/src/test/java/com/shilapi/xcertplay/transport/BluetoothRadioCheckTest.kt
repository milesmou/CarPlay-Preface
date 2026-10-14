package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRadioCheckTest {
    @Test fun aRadioThatIsOnLetsTheAttemptGoOn() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = true))
    }

    @Test fun aRadioThatIsOffStopsTheAttemptBeforeTheHotspot() {
        assertEquals(
            BluetoothRadioCheck.RADIO_OFF,
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false),
        )
    }

    @Test fun aHeadUnitWithoutAnAdapterStopsTheAttemptWhateverElseIsKnown() {
            assertEquals(
                BluetoothRadioCheck.NO_ADAPTER,
                BluetoothRadioCheck.blocker(adapterPresent = false, enabled = false),
            )
    }

    // CarPlayHostActivity shows the request in the app's language by finding these words.
    @Test fun theRequestCarriesTheWordsTheHostScreenLooksFor() {
        assertTrue(BluetoothRadioCheck.RADIO_OFF.contains(BluetoothRadioCheck.RADIO_OFF_MARK))
    }
}
