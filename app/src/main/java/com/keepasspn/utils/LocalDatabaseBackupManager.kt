/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN. If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.utils

import android.content.Context
import android.net.Uri
import com.keepasspn.database.backup.BackupDecision
import com.keepasspn.database.backup.BackupSyncPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Maintains a private, encrypted-KDBX recovery copy for each database opened
 * at least once. The copy is a normal KDBX file, still protected by the master
 * key, stored only in app-private storage.
 *
 * Sync state is tracked by content digest, not by modification time, because
 * storage providers report times unreliably. For every database the manager
 * keeps the digest of the content both sides last agreed on. From that:
 *  - a copy with edits not yet pushed to the original is never overwritten by
 *    a stale original (the reported data-loss case);
 *  - if both sides changed, the private copy is archived as a conflict copy
 *    instead of being silently replaced.
 */
object LocalDatabaseBackupManager {
    private const val TAG = "LocalDatabaseBackup"
    private const val DIRECTORY = "database_backups"
    private const val TEMP_SUFFIX = ".tmp"
    private const val META_SUFFIX = ".meta"
    private const val META_KEY = "lastSyncedSha256="
    private const val CONFLICT_INFIX = ".conflict-"
    private const val MAX_CONFLICT_COPIES = 3
    private const val COPY_BUFFER_SIZE = 64 * 1024

    // Serialises all operations on the private copies in this process
    private val lock = Any()

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private fun digestName(logicalUri: Uri): String =
        sha256Hex(logicalUri.toString().toByteArray(Charsets.UTF_8))

    private fun backupFile(context: Context, logicalUri: Uri): File =
        File(directory(context), "${digestName(logicalUri)}.kdbx")

    private fun metaFile(context: Context, logicalUri: Uri): File =
        File(directory(context), "${digestName(logicalUri)}$META_SUFFIX")

    fun backupUri(context: Context, logicalUri: Uri): Uri =
        Uri.fromFile(backupFile(context, logicalUri))

    fun hasBackup(context: Context, logicalUri: Uri): Boolean =
        backupFile(context, logicalUri).let {
            it.isFile && it.canRead() && it.length() > 0L
        }

    /** Deletes the private recovery copy, its sync state and its conflict copies. */
    fun deleteBackup(context: Context, logicalUri: Uri): Boolean = synchronized(lock) {
        val target = backupFile(context, logicalUri)
        val deleted = !target.exists() || target.delete()
        File(target.parentFile, target.name + TEMP_SUFFIX).delete()
        metaFile(context, logicalUri).delete()
        conflictFiles(context, logicalUri).forEach { it.delete() }
        deleted
    }

    /**
     * Returns the URI to load: the original, the private copy, or null when
     * neither is readable. May archive the private copy as a conflict copy.
     */
    fun resolveLoadUri(context: Context, logicalUri: Uri): Uri? = synchronized(lock) {
        val originalHash = hashUri(context, logicalUri)
        val backupHash = if (hasBackup(context, logicalUri)) hashFile(backupFile(context, logicalUri)) else null
        val decision = BackupSyncPolicy.decide(
            originalHash,
            backupHash,
            readLastSyncedHash(context, logicalUri)
        )
        when (decision) {
            BackupDecision.UNAVAILABLE -> null
            BackupDecision.USE_BACKUP -> backupUri(context, logicalUri)
            BackupDecision.USE_ORIGINAL -> logicalUri
            BackupDecision.USE_ORIGINAL_ARCHIVE_BACKUP -> {
                // Preserve the offline copy before the original replaces it on load
                archiveBackup(context, logicalUri)
                logicalUri
            }
        }
    }

    /** Returns true when [candidate] is the private backup for [logicalUri]. */
    fun isBackupUri(context: Context, logicalUri: Uri, candidate: Uri?): Boolean =
        candidate != null && candidate == backupUri(context, logicalUri)

    fun isReadable(context: Context, uri: Uri): Boolean {
        return try {
            openInput(context, uri)?.use { it.read() >= 0 } == true
        } catch (e: Exception) {
            NoOpLog.d(TAG, "Database URI is not readable: $uri", e)
            false
        }
    }

    /**
     * Synchronises the private copy after a successful load or save.
     *  - [sourceUri] is the private copy: its content is pushed back to the
     *    original if the original has not changed since the last sync.
     *  - otherwise [sourceUri] is the original: it is copied over the private copy,
     *    after archiving the copy if it still holds unsynced edits.
     */
    fun syncFromUri(context: Context, logicalUri: Uri, sourceUri: Uri? = logicalUri): Boolean =
        synchronized(lock) {
            when {
                sourceUri == null -> false
                isBackupUri(context, logicalUri, sourceUri) -> pushBackupToOriginal(context, logicalUri)
                else -> copyOriginalToBackup(context, logicalUri, sourceUri)
            }
        }

    private fun copyOriginalToBackup(context: Context, logicalUri: Uri, source: Uri): Boolean {
        val target = backupFile(context, logicalUri)
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            temp.delete()
            val copied = openInput(context, source)?.use { input ->
                FileOutputStream(temp).use { output ->
                    copyAndDigest(input, output).also { output.fd.sync() }
                }
            }
            if (copied == null || copied.second <= 0L) {
                temp.delete()
                return false
            }
            val sourceHash = copied.first

            val backupHash = hashFile(target)
            val lastSynced = readLastSyncedHash(context, logicalUri)
            if (backupHash != null && backupHash != lastSynced && backupHash != sourceHash) {
                // Unsynced offline edits: archive them rather than overwrite them
                if (!archiveBackup(context, logicalUri)) {
                    temp.delete()
                    return false
                }
            }

            // Atomic replace on Linux/Android: rename overwrites the existing file
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            writeLastSyncedHash(context, logicalUri, sourceHash)
            NoOpLog.d(TAG, "Private KDBX backup synchronized for $logicalUri")
            return true
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to synchronize private KDBX backup for $logicalUri", e)
            temp.delete()
            return false
        }
    }

    private fun pushBackupToOriginal(context: Context, logicalUri: Uri): Boolean {
        val backup = backupFile(context, logicalUri)
        val backupHash = hashFile(backup) ?: return false
        val originalHash = hashUri(context, logicalUri)
        val lastSynced = readLastSyncedHash(context, logicalUri)
        if (!BackupSyncPolicy.canPushBackupToOriginal(originalHash, backupHash, lastSynced)) {
            // Original unavailable or changed elsewhere: keep the copy and let the next load decide
            return false
        }
        if (originalHash != backupHash) {
            val written = try {
                context.contentResolver.openOutputStream(logicalUri, "wt")?.use { output ->
                    FileInputStream(backup).use { input -> input.copyTo(output) }
                    output.flush()
                    true
                } == true
            } catch (e: Exception) {
                NoOpLog.e(TAG, "Unable to write private copy back to $logicalUri", e)
                false
            }
            if (!written) return false
            // Verify the write before trusting it
            if (hashUri(context, logicalUri) != backupHash) return false
        }
        writeLastSyncedHash(context, logicalUri, backupHash)
        return true
    }

    /** Moves the private copy aside as a conflict copy. Keeps only the most recent ones. */
    private fun archiveBackup(context: Context, logicalUri: Uri): Boolean {
        val backup = backupFile(context, logicalUri)
        if (!backup.isFile) return true
        val conflict = File(
            backup.parentFile,
            "${digestName(logicalUri)}$CONFLICT_INFIX${System.currentTimeMillis()}.kdbx"
        )
        return try {
            if (!backup.renameTo(conflict)) {
                backup.copyTo(conflict, overwrite = true)
                backup.delete()
            }
            conflictFiles(context, logicalUri)
                .sortedByDescending { conflictTimestamp(it) }
                .drop(MAX_CONFLICT_COPIES)
                .forEach { it.delete() }
            NoOpLog.e(TAG, "Private copy archived as conflict copy for $logicalUri")
            true
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to archive private copy for $logicalUri", e)
            false
        }
    }

    private fun conflictFiles(context: Context, logicalUri: Uri): List<File> {
        val prefix = "${digestName(logicalUri)}$CONFLICT_INFIX"
        return directory(context).listFiles { file -> file.name.startsWith(prefix) }?.toList().orEmpty()
    }

    private fun conflictTimestamp(file: File): Long =
        file.name.substringAfter(CONFLICT_INFIX).removeSuffix(".kdbx").toLongOrNull() ?: 0L

    private fun readLastSyncedHash(context: Context, logicalUri: Uri): String? = try {
        metaFile(context, logicalUri)
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith(META_KEY) }
            ?.removePrefix(META_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun writeLastSyncedHash(context: Context, logicalUri: Uri, hash: String) {
        val target = metaFile(context, logicalUri)
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            temp.writeText("$META_KEY$hash\n")
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: Exception) {
            temp.delete()
            NoOpLog.e(TAG, "Unable to record sync state for $logicalUri", e)
        }
    }

    private fun openInput(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    /** SHA-256 of the whole document, or null when missing, unreadable or empty. */
    private fun hashUri(context: Context, uri: Uri): String? = try {
        openInput(context, uri)?.use { copyAndDigest(it, null) }
            ?.takeIf { it.second > 0L }
            ?.first
    } catch (e: Exception) {
        NoOpLog.d(TAG, "Database URI is not readable: $uri", e)
        null
    }

    private fun hashFile(file: File): String? = try {
        if (!file.isFile) null
        else FileInputStream(file).use { copyAndDigest(it, null) }
            .takeIf { it.second > 0L }
            ?.first
    } catch (e: Exception) {
        null
    }

    /** Streams [input] (optionally into [output]) and returns (sha256 hex, byte count). */
    private fun copyAndDigest(input: InputStream, output: OutputStream?): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            total += count
        }
        return Pair(digest.digest().toHex(), total)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
