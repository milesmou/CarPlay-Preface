package com.shilapi.xcertplay.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateReleaseLookupTest {
    private val releaseJson = """
        [{"tag_name":"v0.1.1","draft":false,"assets":[
          {"name":"CarPlay-0.1.1.apk","browser_download_url":"https://github.com/milesmou/CarPlay-Preface/releases/download/v0.1.1/CarPlay-0.1.1.apk"},
          {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/milesmou/CarPlay-Preface/releases/download/v0.1.1/SHA256SUMS.txt"}
        ]}]
    """.trimIndent()

    @Test fun sharedLookupUsesTheCurrentRepositoryAndFindsANewerRelease() {
        val requests = mutableListOf<String>()
        val release = UpdateReleaseLookup.latest("0.1.0", "CarPlay/test") { url ->
            requests += url.toExternalForm()
            connection(url)
        }
        assertEquals(listOf("https://api.github.com/repos/milesmou/CarPlay-Preface/releases?per_page=3"), requests)
        assertEquals("v0.1.1", release?.tagName)
        assertEquals("CarPlay-0.1.1.apk", release?.apkName)
        assertEquals("https://github.com/milesmou/CarPlay-Preface/releases/download/v0.1.1/CarPlay-0.1.1.apk", release?.apkUrl)
    }

    @Test fun theInstalledForkReleaseDoesNotOfferAnUpdate() {
        assertNull(UpdateReleaseLookup.latest("0.1.1", "CarPlay/test", ::connection))
    }

    private fun connection(url: URL) = object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(releaseJson.toByteArray(Charsets.UTF_8))
    }
}
