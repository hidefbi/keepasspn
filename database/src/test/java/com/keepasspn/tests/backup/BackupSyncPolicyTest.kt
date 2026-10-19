package com.keepasspn.tests.backup

import com.keepasspn.database.backup.BackupDecision
import com.keepasspn.database.backup.BackupSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSyncPolicyTest {

    private val synced = "aaa"
    private val changedA = "bbb"
    private val changedB = "ccc"

    @Test
    fun nothingReadable_isUnavailable() {
        assertEquals(BackupDecision.UNAVAILABLE, BackupSyncPolicy.decide(null, null, null))
        assertEquals(BackupDecision.UNAVAILABLE, BackupSyncPolicy.decide(null, null, synced))
    }

    @Test
    fun originalMissing_fallsBackToCopy() {
        assertEquals(BackupDecision.USE_BACKUP, BackupSyncPolicy.decide(null, synced, synced))
    }

    @Test
    fun noCopyYet_usesOriginal() {
        assertEquals(BackupDecision.USE_ORIGINAL, BackupSyncPolicy.decide(synced, null, null))
    }

    @Test
    fun identicalContent_usesOriginal() {
        assertEquals(BackupDecision.USE_ORIGINAL, BackupSyncPolicy.decide(synced, synced, synced))
    }

    @Test
    fun unknownProvenance_neverSilentlyOverwritesCopy() {
        assertEquals(
            BackupDecision.USE_ORIGINAL_ARCHIVE_BACKUP,
            BackupSyncPolicy.decide(changedA, changedB, null)
        )
    }

    /**
     * Regression test for the data-loss bug: the original is still the stale
     * version from the last sync, while the offline private copy holds newer
     * edits. The copy must win and must not be overwritten by the stale original.
     */
    @Test
    fun staleOriginal_withOfflineEdits_keepsCopy() {
        assertEquals(BackupDecision.USE_BACKUP, BackupSyncPolicy.decide(synced, changedA, synced))
    }

    @Test
    fun originalChangedElsewhere_usesOriginal() {
        assertEquals(BackupDecision.USE_ORIGINAL, BackupSyncPolicy.decide(changedA, synced, synced))
    }

    @Test
    fun bothChanged_archivesCopyAndUsesOriginal() {
        assertEquals(
            BackupDecision.USE_ORIGINAL_ARCHIVE_BACKUP,
            BackupSyncPolicy.decide(changedA, changedB, synced)
        )
    }

    @Test
    fun pushAllowed_onlyWhenOriginalUnchangedOrAlreadyEqual() {
        assertFalse(BackupSyncPolicy.canPushBackupToOriginal(null, changedA, synced))
        assertFalse(BackupSyncPolicy.canPushBackupToOriginal(synced, null, synced))
        assertTrue(BackupSyncPolicy.canPushBackupToOriginal(synced, changedA, synced))
        assertTrue(BackupSyncPolicy.canPushBackupToOriginal(changedA, changedA, synced))
        assertFalse(BackupSyncPolicy.canPushBackupToOriginal(changedA, changedB, synced))
        assertFalse(BackupSyncPolicy.canPushBackupToOriginal(synced, changedA, null))
    }
}
