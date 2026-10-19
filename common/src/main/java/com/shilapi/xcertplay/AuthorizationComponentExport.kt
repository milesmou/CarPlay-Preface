package com.shilapi.xcertplay

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.Files

/** Publishes a verified component to public Downloads. All intents open folders, never APKs. */
internal object AuthorizationComponentExport {
    data class Saved(val path: String, val directory: File, val alreadySaved: Boolean = false)

    fun save(context: Context, verified: File): Saved {
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Public Downloads storage permission is required"
        }
        @Suppress("DEPRECATION")
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return saveToDirectory(verified, directory)
    }

    internal fun saveToDirectory(verified: File, directory: File): Saved {
        check(directory.isDirectory || directory.mkdirs()) { "Public Downloads is unavailable" }
        val expected = AuthorizationComponentInstaller.sha256(verified.readBytes())
        var destination = File(directory, verified.name)
        var index = 1
        while (destination.exists()) {
            if (destination.isFile && AuthorizationComponentInstaller.sha256(destination.readBytes()) == expected)
                return Saved(destination.absolutePath, directory, alreadySaved = true)
            destination = File(directory, "${verified.nameWithoutExtension}-${index++}.apk")
        }
        val temporary = File.createTempFile(".preface-authorization-", ".part", directory)
        try {
            verified.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(AuthorizationComponentInstaller.sha256(temporary.readBytes()) == expected)
            // No REPLACE_EXISTING: another file or concurrent export is never overwritten.
            Files.move(temporary.toPath(), destination.toPath())
            return Saved(destination.absolutePath, directory)
        } finally { temporary.delete() }
    }

    fun folderIntents(): List<Intent> = listOf(
        // Use the Downloads browser first. A generic directory VIEW can be accepted and
        // immediately closed by a firmware handler, which cannot trigger a launch fallback.
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
        Intent(Intent.ACTION_VIEW).setDataAndType(
            DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
            DocumentsContract.Root.MIME_TYPE_ITEM),
        Intent(Intent.ACTION_VIEW).setDataAndType(
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}"),
            DocumentsContract.Document.MIME_TYPE_DIR),
    ).onEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun openFolder(saved: Saved, open: (Intent) -> Unit): Boolean {
        if (!saved.directory.isDirectory) return false
        return folderIntents().any { intent -> runCatching { open(intent); true }.getOrDefault(false) }
    }

    /** Available even before exporting a component; opening a directory needs no storage grant. */
    fun openDownloads(open: (Intent) -> Unit): Boolean =
        folderIntents().any { runCatching { open(it); true }.getOrDefault(false) }
}
