package com.shilapi.xcertplay.update

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 35])
class UpdateAvailabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(context)

    @Test fun persistsAReleaseThatIsNewerThanTheInstalledBuild() {
        val release = release("v0.2.16")
        UpdateAvailability.save(context, release)

        assertEquals(release, UpdateAvailability.available(context, "0.2.15"))
    }

    @Test fun removesAReleaseAfterTheAppCatchesUp() {
        UpdateAvailability.save(context, release("v0.2.16"))

        assertNull(UpdateAvailability.available(context, "0.2.16"))
        assertNull(UpdateAvailability.available(context, "0.2.15"))
    }

    @Test fun backgroundChecksDefaultOnAndPersistTheChoice() {
        assertTrue(UpdateAvailability.backgroundChecksEnabled(context))
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
    }

    @Test fun reportShowsStateWithRelativeAges() {
        assertEquals(
            "Update check: background=on; lastSuccess=never; cachedRelease=none; lastResult=none (never); lastInstall=none (never)",
            UpdateAvailability.report(context, 10_000_000L),
        )
        UpdateAvailability.save(context, release("v0.2.16"))
        UpdateAvailability.recordAttempt(context, 10_000_000L - 5 * 60_000)
        UpdateAvailability.recordResult(context, 10_000_000L - 60_000, "no validated network (attempt 2)")
        UpdateAvailability.recordInstall(context, 10_000_000L - 2 * 60_000, "chooser uri=public installers=2")
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertEquals(
            "Update check: background=off; lastSuccess=5 min ago; cachedRelease=v0.2.16; " +
                "lastResult=no validated network (attempt 2) (1 min ago); " +
                "lastInstall=chooser uri=public installers=2 (2 min ago)",
            UpdateAvailability.report(context, 10_000_000L),
        )
    }

    @Test fun checksAtMostOncePerDayButRecoversFromAClockReset() {
        val now = 10L * UpdateAvailability.CHECK_INTERVAL_MILLIS
        assertTrue(UpdateAvailability.shouldCheck(context, now))
        UpdateAvailability.recordAttempt(context, now)

        assertFalse(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS - 1))
        assertTrue(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS))
        assertTrue(UpdateAvailability.shouldCheck(context, now - 1))
    }

    @Test fun repositoryMigrationClearsUpstreamMetadataAndAllowsAnImmediateCheck() {
        val values = context.getSharedPreferences("diplay_update_availability", Context.MODE_PRIVATE)
        values.edit()
            .putString("repository", "shihabal3amri/DiPlay")
            .putString("tag", "v0.2.18")
            .putString("apk_name", "DiPlay-0.2.18.apk")
            .putString("apk_url", "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.18/DiPlay-0.2.18.apk")
            .putString("checksums_url", "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.18/SHA256SUMS.txt")
            .putLong("last_attempt_millis", 10_000_000L)
            .putString("last_result", "found v0.2.18")
            .putBoolean("background_checks", false)
            .commit()

        assertNull(UpdateAvailability.available(context, "0.1.0"))
        assertTrue(UpdateAvailability.shouldCheck(context, 10_000_001L))
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
        assertFalse(values.contains("apk_url"))
        assertFalse(values.contains("last_result"))
        assertEquals("milesmou/CarPlay-Preface", values.getString("repository", null))
    }

    @Test fun unscopedLegacyMetadataIsNotUsedForTheFork() {
        UpdateAvailability.save(context, release("v0.2.18"))
        context.getSharedPreferences("diplay_update_availability", Context.MODE_PRIVATE)
            .edit().remove("repository").commit()

        assertNull(UpdateAvailability.available(context, "0.1.0"))
    }

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://example.com/DiPlay-$tag.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )
}
