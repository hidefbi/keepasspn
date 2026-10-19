# KeePassPN

KeePassPN is an Android password manager for KeePass databases (KDBX), with optional PIN quick unlock.

## Features

- Open and edit KDBX databases stored on the device or through a document provider.
- **PIN quick unlock** (optional): unlock a database with a PIN of at least 6 digits instead of typing the master password.
  - The PIN wraps a copy of the master credential, encrypted with a device-bound key (AndroidKeyStore) and with Argon2id (64 MiB, 3 iterations).
  - Three wrong PINs delete the PIN and require the master password.
  - After the PIN is deleted, two wrong master passwords delete the database from the app. The original .kdbx file is never touched.
  - Optional validity: 7, 14, 30 or 60 days, or never. If the app is not opened within the period, the PIN and the app's copy of the database are removed, and the .kdbx file must be imported again.
- **Verify before copying**: copying a protected field asks for user verification. The verification uses the database PIN when one is set, otherwise the device credential, otherwise the first four characters of the master password. The option can be turned off in *Settings > User verification*.
- A private encrypted recovery copy of each database that has been opened successfully.

## Security note

PIN verification is checked by this app's own code, not by the Android system's security hardware. A PIN has far less entropy than the master password. Use PIN unlock only if you accept that trade-off.

## Building

```
./gradlew assembleFreeDebug
```

## License

KeePassPN is free software under the GNU General Public License version 3. See the `LICENSE` file.
