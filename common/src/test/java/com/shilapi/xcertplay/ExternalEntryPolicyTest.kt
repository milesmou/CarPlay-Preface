package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalEntryPolicyTest {
    @Test fun externalPageCannotStartTheBackupServer() {
        assertNull(DiPlayActivity.requestedPage(Intent().putExtra("page", "backup")))
        assertNull(DiPlayActivity.requestedPage(Intent().putExtra("page", "arbitrary")))
        for (page in listOf("home", "settings", "wireless-recovery")) {
            assertEquals(page, DiPlayActivity.requestedPage(Intent().putExtra("page", page)))
        }
    }
}
