package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioChannelPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun freshInstallUsesPrefaceDefaults() {
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
        org.junit.Assert.assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
    }

    @Test fun audioFocusAutoYieldCanBeSavedAndRestored() {
        AirPlayPersistence.saveAudioFocusAutoYield(context, false)
        org.junit.Assert.assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
        AirPlayPersistence.saveAudioFocusAutoYield(context, true)
        org.junit.Assert.assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
    }




    @Test fun extendedChannelsCanBeSavedForMediaAndNavigation() {
        for (channel in listOf(11, 14, 15, 20)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(channel, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(channel, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun existingExtendedChannelsRemainAvailable() {
        prefs.edit().putInt("media_audio_channel", 15).putInt("navigation_audio_channel", 15).apply()
        assertEquals(15, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun invalidSavedChannelsUseAutomaticRouting() {
        for (channel in listOf(-1, 21)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun invalidStoredChannelsUseAutomaticRouting() {
        for (channel in listOf(-1, 21)) {
            prefs.edit().putInt("media_audio_channel", channel).putInt("navigation_audio_channel", channel).apply()
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

}
