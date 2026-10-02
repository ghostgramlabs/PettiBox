package com.ghostgramlabs.pettibox.data.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createBackupFile(): File {
        val dir = backupDir().also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "pettibox-auto-backup-$stamp.zip")
    }

    // 3 rolling copies is enough recovery headroom for a daily worker;
    // 7 (the prior default) could pile up gigabytes on heavy libraries
    // because each zip embeds every attachment.
    fun pruneOldBackups(keep: Int = 3) {
        backupDir()
            .listFiles { file -> file.isFile && file.name.startsWith("pettibox-auto-backup-") && file.extension == "zip" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    fun latestBackupFile(): File? =
        backupDir()
            .listFiles { file ->
                file.isFile &&
                    file.extension.equals("zip", ignoreCase = true) &&
                    file.name.startsWith("pettibox-")
            }
            .orEmpty()
            .maxByOrNull { it.lastModified() }

    /** The automatic copies kept on this phone, newest first. */
    fun listBackups(): List<File> =
        backupDir()
            .listFiles { file -> file.isFile && file.name.startsWith("pettibox-auto-backup-") && file.extension == "zip" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    fun backupLocationLabel(): String = "Device storage / PettiBox backups"

    fun backupPath(): String = backupDir().absolutePath

    fun copyToPickedFolder(source: File, folderUri: String): Boolean {
        if (folderUri.isBlank()) return false
        return runCatching {
            val treeUri = Uri.parse(folderUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val targetUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/zip",
                source.name
            ) ?: return false
            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun backupDir(): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(external ?: context.filesDir, "pettibox-backups")
    }
}
