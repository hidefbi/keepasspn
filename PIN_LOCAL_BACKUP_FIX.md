# PIN + local KDBX recovery copy

## Behavior

1. The first open still requires the real master credential.
2. After each successful load or save, an app-private copy is kept under `filesDir/database_backups/`, together with a sync record holding the SHA-256 of the content both sides last agreed on.
3. The original document URI stays the logical identity. The PIN record is keyed to it.
4. When the original is unreadable, the private copy is loaded.
5. When both are readable, the decision uses content digests, not modification times (providers report those unreliably):
   - Only the original changed: the original is loaded and the copy refreshed.
   - Only the copy changed (offline edits not yet pushed): the copy is loaded and pushed back to the original on the next successful save or load, if the original is still unchanged.
   - Both changed: the original is loaded. The copy is archived as a conflict copy under `<id>.conflict-<millis>.kdbx`; the three most recent are kept.
   - Copy without sync record and different content: archived, never silently overwritten.
6. Deleting a database from the list removes its copy, sync record and conflict copies.
7. The copy is still a KDBX file encrypted by the master key, and never leaves app-private storage. Android backup is disabled for the whole app (`android:allowBackup="false"`), so this file is never copied to cloud backup or another device.

## PIN wrap (format v2)

See `database/.../pin/PinEnvelope.kt` for the binary layout.

- Inner layer: AES-256-GCM with the non-exportable AndroidKeyStore key. AAD = label + database URI. On API 28+ the key is usable only while the device is unlocked (applies when the key is created).
- Outer layer: AES-256-GCM with Argon2id(PIN, random 32-byte salt), 64 MiB, 3 iterations, 1 lane. AAD = label + database URI + full header.
- Parameters are stored in the entry, so they can be raised later without breaking existing entries. Read-side bounds prevent a tampered entry from forcing huge allocations.
- Entries from the previous revision (no `v2.` prefix) still unlock and are re-wrapped to v2 on success.

## Limits

A PIN has little entropy. The attempt counter is enforced by this app and lives in its database, so a rooted device can reset it. Use PIN unlock as a convenience, not a replacement for the master password.

## Verification

- JVM unit tests: `database/src/test/.../tests/pin/PinEnvelopeTest.kt`, `tests/backup/BackupSyncPolicyTest.kt`.
- Sync scenarios against the real `LocalDatabaseBackupManager` with Android stubs: `verification/sync-harness/run.sh`.
