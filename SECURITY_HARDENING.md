# Security hardening (audit items 1–10)

This document records the fixes from the second security audit, the reasons for each
decision, and the limits that remain. It is meant for maintainers and for anyone
reviewing the code.

## Summary

| # | Finding | Fix | Where |
|---|---------|-----|-------|
| 1 | Passkey RP ID was trusted from any caller | Passkeys are offered only to *privileged* callers (verified browsers and allow-listed apps). Their origin is computed by the platform, and browsers validate the RP ID against it. Other apps get no passkeys (fail closed). The RP ID is also checked for DNS-name syntax before use. | `PasskeyProviderService.kt`, `PrivilegedAllowLists.kt` |
| 2 | Autofill trusted any `webDomain` set by an app | A web domain (and web scheme) is used only when the window belongs to an installed browser on the privileged allow list. A non-browser window can no longer borrow another site's credentials. | `StructureParser.kt`, `AppUtil.trustedBrowserPackages` |
| 3 | Exported VIEW activity accepted a password and auto-unlock flags | The `password` and `launchImmediately` extras are removed. No caller can pre-fill or unlock. | `MainCredentialActivity.kt` |
| 4 | Clipboard "sensitive" flag depended on the field's protection flag | Every copy is marked sensitive by default (hidden in Android 13+ clipboard previews). | `ClipboardHelper.kt`, `EntryActivity.kt` |
| 5 | `DexModeReceiver` exported with no permission | `android:exported="false"`. | `AndroidManifest.xml` |
| 6 | Legacy device-credential key stays usable 5 s after unlock | Documented as an accepted platform limitation (see below). | `DeviceUnlockManager.kt` |
| 7 | Temp attachment files used Blowfish-CBC with a 64-bit block | AES-256-GCM, a fresh random 96-bit nonce per file stored in the file header, authenticated. | `LoadedKey.kt`, `BinaryFile.kt` |
| 8 | Custom JNI AES cipher handled every KDBX payload | All user-data AES goes through the platform JCA provider. The native AES cipher was removed. The native KDF (ECB rounds) is kept, but only used after a self-test against JCA in the same process. | `CipherFactory.kt`, `AESTransformer.kt`, `aes_jni.c`, `AESProvider.kt` and `NativeAESCipherSpi.java` removed |
| 9 | Implicit broadcast PendingIntents | Each implicit broadcast intent is scoped to this package (`setPackage`). The credential and autofill PendingIntents already use explicit components. | `TimeoutHelper.kt`, `BroadcastAction.kt`, notification services |
| 10 | Binary de-duplication used a 32-bit prefix of MD5 | Full SHA-256 digest compared byte by byte. A 32-bit hash can collide, and two different attachments could be merged on save (data loss). MD5 collisions are also easy to craft. | `BinaryData.kt`, `BinaryPool.kt` |

## Decisions and limits

### Passkeys and native apps (item 1)
This app declares **no `INTERNET` permission**, on purpose. Verifying a native app's RP ID
requires Digital Asset Links, i.e. fetching `https://<rpId>/.well-known/assetlinks.json`,
which needs network access. Adding it would change the privacy model, so native apps
that call Credential Manager directly are refused.

- Browsers on the privileged allow list keep working.
- A browser missing from the allow list will not show passkeys. The custom privileged
  list (`PrivilegedAllowLists.saveCustomPrivilegedApps`) is the existing way to fix that.
- If native-app passkeys must work, the trade-off is an opt-in network permission plus a
  Digital Asset Links check. That is a product decision, not a silent change.

### Camera for OTP import
The camera is used only to read QR codes exported from Google Authenticator and single
`otpauth://` codes. Frames are decoded on the device and never stored or sent anywhere.
The app declares the `CAMERA` permission for this. It still declares no `INTERNET` permission.

### Autofill web domains (item 2)
Web domains from WebViews and custom views inside non-browser apps are ignored. Matching
then falls back to the application package, which is the existing behavior for apps.

### Autofill suggestions
Suggestions show only when there is something to fill: an open database with a matching entry,
or, while locked, a site or app in the saved sites index. The index keeps HMAC-SHA256 values of
the registrable domain or the package name, keyed with a non-exportable Keystore key, so the
stored set does not show the sites. It is rebuilt after each load and save, recycle bin entries
are not counted. Sign up and change password forms get no suggestion, the save prompt stays.

### Legacy device-credential window (item 6)
On Android 6.0 to 10 (API 23–29), the keyguard-based flow only offers time-based
authorization for device credentials. The key therefore remains usable for 5 seconds after
a successful PIN, pattern or password unlock. Per-use authorization needs the BiometricPrompt
crypto flow, which is not available for device credentials on these API levels. The key is
app-private, and an attacker who can run code in this process can already read the unlocked
database, so the window adds no new exposure. Accepted and documented.

### Native KDF (item 8)
The AES key-derivation rounds (KDBX AES-KDF) are performance sensitive, so they stay in
native code. Before first use, the native result is compared with the JCA result on a fixed
vector. A mismatch or a load failure falls back to JCA. Output is unchanged, and
`AESTest.testAESKDF` still compares the two implementations.

### Binary temp files (item 7)
Temp files are written only during a session and are never read after the process ends,
because the session key does not survive a restart. Changing their format is therefore safe.
`BinaryData` parcels now carry the digest as a byte array (item 10). This only affects
in-process state.

### Secret buffers
Passwords and PINs are kept in `CharArray` or `ByteArray` and wiped with `wipe()`, or with
`useAndWipe { }` when one block is the only user of the buffer. The wipes sit in `finally`
blocks, so an exception does not skip them. A Kotlin `String` cannot be wiped, so keep secrets
out of strings where possible. Not every buffer in the app has been moved to these helpers yet.

Task secrets go through `TaskSecrets`, the Intent only has a token. Read them with
`hasSecretExtra` / `takeSecretExtra`. A plain `hasExtra` is always false for them, and the task
just gets dropped with no message. That is what broke the master password change.

### PGP keys
Keys live in the database as entries, in the PGP Safe folder. The armored secret key and its passphrase are protected
custom fields, and the public key is a plain field. A kept passphrase is wrapped with an AES-GCM key from the Android Keystore that does not leave the device (StrongBox when the phone has it). If the Keystore can't open it, the app asks for the passphrase. Nothing is written to disk outside the
database, and the keys go with its backups.

- Default key is version 4 with Curve25519, and messages use AES-256 with the integrity packet
  (SEIPD v1). Those are what gpg and most OpenPGP tools read without fuss. Version 6 keys and
  AEAD are not created, to keep compatibility wide. RSA 3072 and 4096 are offered for older tools.
- Signing and decrypting ask for user verification before the key is used.
- Keys are checked before use. A revoked or expired key, or a subkey whose binding signature does not check out, is refused for encrypt and sign. A signature that matches two different keys by key ID is not checked at all.
- Files and results are streamed through `cacheDir/pgp`, which is app-private. Limits are 50 MiB for
  files and for decrypted output, and 1 MB for typed text. Decrypted output over the limit is refused and
  the partial file is deleted.
- Messages without an MDC or AEAD tag are refused before any plain data is read. Compression can be
  nested 2 levels at most. Public keys, pasted or loaded from a file, are limited to 2 MiB and signatures to 64 KiB, the length is
  checked before the parser runs. A key file is read in pieces and stops at the same limit.
- Decrypted data goes to a staging file first. It is moved to the output only after the integrity check of the
  whole message passes, so unchecked plain data is never shown or saved.
- The PGP cache is removed on exit, when the screen is left (another app, the lock screen), on a fresh start of the
  screen and by Clear PGP cache. A run in progress is cancelled when the screen is left. A system file picker does
  not count as leaving. Clear on the
  page removes the files of that page only. Removal is a plain delete, flash storage does not give a
  reliable overwrite, so the files are not wiped securely.
- Keys are generated on the device with SecureRandom. There is no key server, and the app has no
  internet permission, so public keys have to be imported by hand.
- The OpenPGP library is BouncyCastle bcpg 1.87. It is the main size cost of the feature.

### Build and supply chain
- Gradle wrapper 8.14.7, pinned by `distributionSha256Sum`.
- `gradle/verification-metadata.xml` holds the SHA-256 of every artifact the build resolves.
  After a dependency change, regenerate it with
  `./gradlew --write-verification-metadata sha256 :app:assembleRelease` and commit the result.
  Test-only dependencies need the test tasks in the same command.
- SBOM: CycloneDX plugin 3.5.1. `./gradlew cyclonedxBom` writes
  `app/build/reports/cyclonedx/bom.json`. It is build output, so it is not committed and it is
  not in the APK. Test-only dependencies are left out.
- Dependency info is not put in the APK or the bundle (`dependenciesInfo` is off), so the APK
  does not carry a list of its libraries.
- Release builds should be reproducible: two clean builds from the same commit give the same
  APK hash.
- The APK does carry `META-INF/version-control-info.textproto` (commit hash and a dirty flag).
  That is kept on purpose. Releases are built from clean tagged commits, so the file is the
  same for every build of a release.
- R8 is off. The source is public, so obfuscation hides nothing, and turning it on would need
  keep rules and a full device pass for every release. Revisit only if APK size becomes a
  problem.

## Verification status

- Pure JVM checks (Kotlin 2.0.21): NIST SP 800-38A AES-CBC vector, AES-GCM stream layout
  (12-byte nonce header, tag verification, tamper detection, empty payload).
- C: `aes_jni.c` compiles cleanly with `gcc -Wall -Wextra`.
- Mac: `assembleRelease` passes on Gradle 8.14.7, and two clean builds give the same APK hash.
- `ArrayWipeTest` passes. `AppUtilTest` has two failures (`testIsMemorySufficientWithType`,
  `testGetSafeMemoryLimitWithType`). They also fail on f4ec9d6, before the wrapper and passkey
  changes, and they depend on the memory of the machine running them. Not fixed yet.
- The device checklist below has not been run yet. **Run it before release.**

## Device checklist (please run on a real device)

1. Open a `.kdbx` database and unlock it (AES-CBC payload, KDF) — confirms item 8.
2. Open an entry that has an attachment, view it, and save — confirms items 7 and 10.
3. Save two different attachments with the same size and check both survive a save.
4. Copy a username and a password. The username copies straight away. The password asks for
   user verification first (PIN, device credential, then master password) while the
   setting is on, which it is by default. Check the Android 13+ clipboard preview is hidden.
5. From Chrome (or another allow-listed browser), sign in with a passkey — confirms item 1.
6. From a non-browser app, start passkey creation and sign-in. No KeePassPN entry should show
   up in the sheet (expected, item 1).
7. In a non-browser app (e.g. a WebView login page), autofill should fall back to the app
   match rather than a web domain (expected, item 2).
8. Send a `VIEW` intent carrying `password=...` to the app: the password field must stay empty.
9. Autofill lock notification and the "lock on screen off" timeout still work (item 9).
10. PIN: set a PIN, unlock with it, enter a wrong PIN, change the PIN, turn it off. Each step
    should behave as the README describes. (Covers the buffer wipe changes in the PIN code.)
11. Passkey with the PRF extension, from Chrome on a test page that asks for it: sign in twice,
    the PRF output must be the same both times. (Covers the change in `PasskeyHelper`.)
12. Install the signed release APK over the previous version. The database must still open, an
    existing PIN must still unlock, and Android must accept the update (same signing key).
13. Change the master password, lock the database and unlock it with the new password. It must
    open. The old password must not open it any more.
14. Open a login page of a site with no saved entry, database open or locked: no suggestion in
    the keyboard strip. Submit a login, the save prompt must still come.
15. Lock the database and open a saved site: the unlock prompt shows. Open a site that is not
    saved: nothing shows. With "No suggestions when locked" on, nothing shows while locked.
16. The same as 14 and 15 for an app with a saved entry (its AndroidApp field).
17. Open PGP from the + button on the database screen. Generate a key with a passphrase, then check
    the key shows in the list with its fingerprint.
18. Encrypt a text to the key and decrypt it again. A wrong passphrase must show an error and no text.
19. Encrypt and decrypt a file of about 50 MB. A file over 50 MB must be refused, and so must decrypted output
    over 50 MB. Cancel a long run: the progress stops and no output file is left.
20. Sign a text and verify it. Change one character and verify again: it must show a bad signature.
21. gpg interop, both ways: encrypt to the app's public key and decrypt with gpg; encrypt with gpg
    to the exported public key and decrypt in the app. Same for a signature and its verification.
22. With "Verify before editing" on, sign and decrypt must ask for verification first.
23. The first key creates PGP Safe under TOTP Summary. A second key goes into the same folder. Move
    PGP Safe somewhere else, make another key: it still goes into PGP Safe.
24. Delete PGP Safe: its keys go to the recycle bin and leave the PGP page. Making a key then creates
    a new PGP Safe.
25. Type more than 1 MB of text: it must be refused. Clear empties the boxes and result. Clear PGP cache
    and leaving the screen leave no file in the app's cache under pgp/.
26. Decrypt a message made with no MDC (old style): it must be refused and no output file is left.
27. Decrypt a message with three nested compression layers: it must be refused.
28. Import a key text or key file over 2 MiB, or a signature over 64 KiB: all must be refused with a message.
29. Import a binary .gpg public key file (gpg --export, no --armor): it must work and show the same fingerprint as gpg --show-keys.
30. Make a key with Remember passphrase on, then sign a text: no passphrase prompt. Make a key with it off: signing asks for the passphrase, and a wrong one shows an error.
31. Restore a backup with a kept passphrase on another phone (or clear the app data): the app asks for the passphrase and does not fail.
32. Import a key that was revoked (gpg --gen-revoke, then import the certificate) and try to encrypt to it: it must be refused with the can't be used message. Verify a signature made before the revocation: it must show the key message.
33. Decrypt a message with a changed byte: it must be refused and no output is shown, and no staging or output file is left in cache under pgp/.
34. Start a long decrypt, then switch to another app: when you come back the run is stopped, the result is gone and the text boxes still hold their text. Pick a file, then go to the picker and back: the file is still there.
