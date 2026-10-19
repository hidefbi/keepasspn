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
package com.keepasspn.database.backup

enum class BackupDecision {
    /** Neither the original document nor the private copy can be read. */
    UNAVAILABLE,
    /** Load the original document. The private copy is refreshed from it afterwards. */
    USE_ORIGINAL,
    /** Load the private copy: it holds edits that have not been pushed to the original yet. */
    USE_BACKUP,
    /**
     * Both sides changed since the last sync. Load the original, but keep the
     * private copy as an archived conflict copy instead of overwriting it.
     */
    USE_ORIGINAL_ARCHIVE_BACKUP
}

/**
 * Pure decision logic for the private recovery copy. Hashes are lowercase hex
 * SHA-256 digests of the complete KDBX bytes; null means absent or unreadable.
 *
 * [lastSyncedHash] is the digest of the content both sides were known to share
 * at the last successful sync. Modification times are deliberately not used:
 * many storage providers report them unreliably.
 */
object BackupSyncPolicy {

    fun decide(
        originalHash: String?,
        backupHash: String?,
        lastSyncedHash: String?
    ): BackupDecision {
        if (originalHash == null) {
            return if (backupHash == null) BackupDecision.UNAVAILABLE else BackupDecision.USE_BACKUP
        }
        if (backupHash == null) return BackupDecision.USE_ORIGINAL
        if (originalHash == backupHash) return BackupDecision.USE_ORIGINAL
        // Provenance unknown (e.g. copy made before sync tracking existed): never overwrite it silently
        if (lastSyncedHash == null) return BackupDecision.USE_ORIGINAL_ARCHIVE_BACKUP

        val originalChanged = originalHash != lastSyncedHash
        val backupChanged = backupHash != lastSyncedHash
        return when {
            originalChanged && backupChanged -> BackupDecision.USE_ORIGINAL_ARCHIVE_BACKUP
            backupChanged -> BackupDecision.USE_BACKUP
            else -> BackupDecision.USE_ORIGINAL
        }
    }

    /**
     * True when the private copy can safely be written back to the original:
     * the original is readable and has not changed since the last sync (or
     * already holds the same content).
     */
    fun canPushBackupToOriginal(
        originalHash: String?,
        backupHash: String?,
        lastSyncedHash: String?
    ): Boolean {
        if (originalHash == null || backupHash == null) return false
        if (originalHash == backupHash) return true
        return lastSyncedHash != null && originalHash == lastSyncedHash
    }
}
