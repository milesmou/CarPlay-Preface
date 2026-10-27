package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.shared.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The music renderer trims its track's playback rate from the queued level (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioClockTrimTest.RateTrack::class])
class AudioClockTrimTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack

    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.result = AudioTrack.SUCCESS
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(96_000)
            .build()
        renderer = type.declaredConstructors.single { it.parameterCount == 12 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 500, { message: String -> reports.add(message) }, false, null, false)
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("playbackStarted", true)
        set("startThresholdBytes", 57_600)
        set("bytesPerSecond", 48_000 * 4)
        // 300 ms of music is the target; createTrack sets this up for music only.
        set("clockTrim", MediaClockTrim(48_000, 14_400))
    }

    @After fun tearDown() {
        track.release()
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(bytes: Int) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(renderer, ByteArray(bytes), 0, bytes)

    @Test fun aLowLevelSlowsTheTrackAndTheStatsShowTheRate() {
        // 150 ms queued against a 300 ms target.
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.toString(), reports.any { it.startsWith("audio stats") && it.contains(" playbackRate=47280 ended=true") })
    }

    @Test fun aTrackThatRefusesTheRateKeepsPlayingWithoutTheTrim() {
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        write(28_800)
        assertNull(get("clockTrim"))
        assertTrue(reports.toString(), reports.contains("Audio: clock trim off audioType=media rate=47280 result=-3"))
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
    }

    @Test fun healthyPeriodicDetailsFollowTheBuildAndStillResetTheirWindow() {
        set("statsWindowStartNs", 1L)
        set("writtenFramesThisWindow", 100L)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, false)
        assertEquals(BuildConfig.DEBUG, reports.any { it.startsWith("audio stats") })
        assertEquals(0L, get("writtenFramesThisWindow"))
        assertTrue(get("clockTrim") != null)
        reports.clear()
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.single().contains("writtenFrames=0"))
        assertTrue(reports.single().contains("ended=true"))
    }

    @Test fun writeErrorsAreRetainedInBothVariantsAndTheirWindowStillResets() {
        set("statsWindowStartNs", 1L)
        set("writeErrorsThisWindow", 1)
        set("lastWriteErrorCode", AudioTrack.ERROR_INVALID_OPERATION)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, false)
        assertTrue(reports.single().contains("writeErrors=1"))
        assertEquals(0, get("writeErrorsThisWindow"))
        assertTrue(get("clockTrim") != null)
    }

    @Test fun nothingIsTrimmedBeforeMusicPlays() {
        set("playbackStarted", false)
        write(2_048)
        assertTrue(RateTrack.rates.isEmpty())
    }

    @Test fun mobileCompatibleRoutingNeverTrimsVoiceEvenWhenItUsesTheMediaChannel() {
        for (audioType in listOf("media", "telephony", "speech_recognition", "alert")) {
            val candidate = type.declaredConstructors.single { it.parameterCount == 12 }
                .apply { isAccessible = true }
                .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, audioType),
                    false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                    0, 300, { _: String -> }, false, null, false)
            try {
                type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(candidate)
                val actualTrack = type.getDeclaredField("track").apply { isAccessible = true }.get(candidate)
                assertTrue("$audioType track must exist", actualTrack is AudioTrack)
                val trim = type.getDeclaredField("clockTrim").apply { isAccessible = true }.get(candidate)
                assertEquals("$audioType", audioType == "media", trim != null)
                val lagGuard = type.getDeclaredField("mediaLagGuard").apply { isAccessible = true }.get(candidate)
                assertEquals("Only music can discard stale packets: $audioType", audioType == "media", lagGuard != null)
            } finally {
                (candidate as java.io.Closeable).close()
            }
        }
    }

    @Test fun undecodedMusicIsCountedByTheActualRendererBeforeTrimmingItsClock() {
        // A 2-second compressed queue and only 100 ms in AudioTrack must not slow playback.
        set("started", true)
        val submit = type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        submit.invoke(renderer, ByteArray(12), 0)
        submit.invoke(renderer, ByteArray(12), 96_000)
        write(19_200)
        assertTrue("queued compressed music must not be mistaken for a starving track", RateTrack.rates.last() >= 48_000)
        assertEquals("decoder backpressure must not become learned clock drift", 0.0, MediaClockTrim.learnedTrim, 1e-8)
    }

    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun play() = Unit
        @Implementation fun pause() = Unit
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int = size
        @Implementation fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        companion object {
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, encoding: Int) = 4096
            val rates = mutableListOf<Int>()
            var result = AudioTrack.SUCCESS
        }
    }
}
