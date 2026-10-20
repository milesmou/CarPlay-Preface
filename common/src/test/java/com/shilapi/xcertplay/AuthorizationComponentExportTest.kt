package com.shilapi.xcertplay

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthorizationComponentExportTest {
    private fun withFiles(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("authorization-export-test").toFile()
        try {
            val source = File(root, "Preface-Android9-Authorization.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            test(source, File(root, "Download"))
        } finally { root.deleteRecursively() }
    }
    @Test fun publishesVerifiedBytesToThePublicDirectory() = withFiles { source, downloads ->
        val saved = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(saved.alreadySaved)
        assertEquals(downloads, saved.directory)
        assertArrayEquals(source.readBytes(), File(saved.path).readBytes())
        assertFalse(downloads.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun savingUsesPublicDownloadsRootWithoutAnAppSubdirectory() = withFiles { source, _ ->
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val saved = AuthorizationComponentExport.save(context, source)
        try {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            assertEquals(downloads, saved.directory)
            assertEquals(downloads, File(saved.path).parentFile)
            assertArrayEquals(source.readBytes(), File(saved.path).readBytes())
        } finally { File(saved.path).delete() }
    }
    @Test fun repeatedSaveReusesMatchingFileWithoutAccumulatingCopies() = withFiles { source, downloads ->
        val first = AuthorizationComponentExport.saveToDirectory(source, downloads)
        val modified = File(first.path).lastModified()
        val second = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(first.alreadySaved)
        assertTrue(second.alreadySaved)
        assertEquals(first.path, second.path)
        assertEquals(modified, File(second.path).lastModified())
        assertEquals(1, downloads.listFiles()!!.size)
    }
    @Test fun authorizationExportKeepsItsStableNameAcrossUpdates() = withFiles { source, downloads ->
        val first = AuthorizationComponentExport.saveToDirectory(source, downloads)
        source.writeBytes(source.readBytes() + byteArrayOf(7))
        val updated = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(updated.alreadySaved)
        assertEquals(first.path, updated.path)
        assertArrayEquals(source.readBytes(), File(updated.path).readBytes())
        assertTrue(AuthorizationComponentExport.saveToDirectory(source, downloads).alreadySaved)
        assertEquals(listOf(source.name), downloads.listFiles()!!.map { it.name })
    }
    @Test fun aChangedSavedApkIsNotMistakenForTheVerifiedCopy() = withFiles { source, downloads ->
        val first = AuthorizationComponentExport.saveToDirectory(source, downloads)
        File(first.path).writeText("changed bytes")
        val next = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(next.alreadySaved)
        assertEquals(first.path, next.path)
        assertArrayEquals(source.readBytes(), File(next.path).readBytes())
        assertEquals(1, downloads.listFiles()!!.size)
    }
    @Test fun overwritesOnlyTheSameNamedFile() = withFiles { source, downloads ->
        downloads.mkdirs()
        val existing = File(downloads, source.name).apply { writeText("keep this") }
        val other = File(downloads, "other.apk").apply { writeText("keep other") }
        val saved = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(saved.alreadySaved)
        assertEquals(existing.absolutePath, saved.path)
        assertArrayEquals(source.readBytes(), existing.readBytes())
        assertEquals("keep other", other.readText())
        assertEquals(2, downloads.listFiles()!!.size)
    }
    @Test fun aDirectoryNamedLikeTheApkIsNotReplaced() = withFiles { source, downloads ->
        val existing = File(downloads, source.name).apply { mkdirs() }
        assertThrows(IllegalStateException::class.java) { AuthorizationComponentExport.saveToDirectory(source, downloads) }
        assertTrue(existing.isDirectory)
        assertEquals(listOf(existing), downloads.listFiles()!!.toList())
    }
    @Test fun unavailableDirectoryDoesNotChangeItsContents() = withFiles { source, downloads ->
        downloads.parentFile!!.mkdirs(); downloads.writeText("not a directory")
        assertThrows(IllegalStateException::class.java) { AuthorizationComponentExport.saveToDirectory(source, downloads) }
        assertEquals("not a directory", downloads.readText())
    }
    @Test fun downloadsBrowserFallsBackToADownloadsRootWithoutTargetingTheApk() = withFiles { source, downloads ->
        val saved = AuthorizationComponentExport.saveToDirectory(source, downloads)
        val attempts = mutableListOf<Intent>()
        assertTrue(AuthorizationComponentExport.openFolder(saved) {
            attempts += it
            if (attempts.size == 1) throw ActivityNotFoundException()
        })
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, attempts[0].action)
        assertNull(attempts[0].data)
        assertEquals(DocumentsContract.Root.MIME_TYPE_ITEM, attempts[1].type)
        assertEquals("downloads", DocumentsContract.getRootId(attempts[1].data!!))
        assertEquals("com.android.providers.downloads.documents", attempts[1].data!!.authority)
        assertTrue(attempts.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        assertTrue(attempts.none { it.type == "application/vnd.android.package-archive" || it.dataString?.endsWith(".apk") == true })
    }
    @Test fun missingDownloadsBrowserAndRootFallBackToPublicDownloadDocument() {
        val attempts = mutableListOf<Intent>()
        assertTrue(AuthorizationComponentExport.openDownloads {
            attempts += it
            if (attempts.size < 3) throw ActivityNotFoundException()
        })
        assertEquals(3, attempts.size)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, attempts.last().type)
        assertEquals("primary:Download", DocumentsContract.getDocumentId(attempts.last().data!!))
    }
    @Test fun successfulDownloadsRequestDoesNotLaunchAdditionalFileManagers() {
        val attempts = mutableListOf<Intent>()
        assertTrue(AuthorizationComponentExport.openDownloads { attempts += it })
        assertEquals(1, attempts.size)
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, attempts.single().action)
    }
    @Test fun missingFileManagerLeavesTheDownloadedApkAvailable() = withFiles { source, downloads ->
        val saved = AuthorizationComponentExport.saveToDirectory(source, downloads)
        assertFalse(AuthorizationComponentExport.openFolder(saved) { throw ActivityNotFoundException() })
        assertArrayEquals(source.readBytes(), File(saved.path).readBytes())
    }
    @Test fun deniedStorageCannotFallBackToAPrivateCopy() = withFiles { source, _ ->
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertThrows(IllegalStateException::class.java) { AuthorizationComponentExport.save(context, source) }
    }
    @Test fun standaloneDownloadsOpensWithoutExportOrStoragePermission() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val intents = mutableListOf<Intent>()
        assertTrue(AuthorizationComponentExport.openDownloads { intents += it })
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, intents.single().action)
        assertNull(intents.single().data)
        assertNull(intents.single().type)
    }
}
