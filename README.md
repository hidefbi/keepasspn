# KeePassPN

KeePassPN is an Android password manager for KeePass databases in the KDBX format. It opens and edits databases stored on the device or exposed through the Storage Access Framework, and offers optional PIN quick unlock.

## Features

- **KDBX support**: open and edit KeePass databases from local storage or any Storage Access Framework document provider.
- **PIN quick unlock** (optional): unlock a database with a PIN of at least six digits instead of the master password.
  - The PIN protects a copy of the master credential with two AES-GCM layers. The inner layer uses a non-exportable key in the Android Keystore. The outer layer uses a key derived from the PIN with Argon2id (64 MiB, 3 iterations, 1 lane).
  - Each layer is authenticated against the database URI, and the outer layer also against the database header, so an entry cannot be reused for another database.
  - Three consecutive wrong PINs delete the PIN. The master password is then required.
- **PIN validity**: 7, 14, 30 or 60 days, or never. If the app has not been opened within the configured period, the PIN, the app's copy of the database, its recovery copy and its history entry are removed. The original `.kdbx` file is not modified.
- **Verify before copying**: copying a protected field requires user verification. The method is chosen in this order: the database PIN (if one is set), the device credential (if available), and the first four characters of the master password. This can be turned off under *Settings > User Verification*.
- **Recovery copies**: each database that has been opened successfully keeps a private copy in app-private storage, still protected by the master credential. A copy with unsynced edits is never overwritten by a stale original, and conflicting changes are archived instead of replaced.

## Security notes

- The master password is the primary credential. PIN unlock is a convenience feature.
- A numeric PIN has far less entropy than a master password. Argon2id increases the cost of offline guessing, but it cannot remove the risk of a short secret.
- The PIN attempt counter is stored in the app's database and enforced by the app, not by Android's hardware-backed authentication. On a rooted or otherwise compromised device, the counter can be reset.
- The Android Keystore key cannot be exported. Whether it is hardware-backed depends on the device.
- Recovery copies help prevent data loss from stale synchronization. They are not a replacement for independent backups.
- Use PIN unlock only if you accept these trade-offs.

## Requirements

- Android 4.4 (API 19) or later.

## Building

Build the debug variants from the repository root:

```
./gradlew assembleFreeDebug
./gradlew assembleLibreDebug
```

- `free`: for closed-store distribution. Passkey privilege checks use Google's allow list only.
- `libre`: for open-source distribution. Passkey privilege checks fall back to the community allow list.

## License

KeePassPN is free software licensed under the GNU General Public License v3.0 (GPL-3.0). See the [LICENSE](LICENSE) file for the full text.
