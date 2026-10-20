# Security hardening (audit items 1–10)

This document records the fixes from the second security audit, the reasons for each
decision, and the limits that remain. It is meant for maintainers and for anyone
reviewing the code.

## Summary

| # | Finding | Fix | Where |
|---|---------|-----|-------|
| 1 | Passkey RP ID was trusted from any caller | Passkeys are offered only to *privileged* callers (verified browsers and allow-listed apps). Their origin is computed by the platform, and browsers validate the RP ID against it. Other apps get no passkeys (fail closed). The RP ID is also checked for DNS-name syntax before use. | `PasskeyProviderService.kt` |
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

### Autofill web domains (item 2)
Web domains from WebViews and custom views inside non-browser apps are ignored. Matching
then falls back to the application package, which is the existing behavior for apps.

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

## Verification status

- Pure JVM checks (run with Kotlin 2.0.21): NIST SP 800-38A AES-CBC vector, AES-GCM stream
  layout (12-byte nonce header, tag verification, tamper detection, empty payload).
- C: `aes_jni.c` compiles cleanly with `gcc -Wall -Wextra` against the bundled headers.
- Android-dependent Kotlin was checked for syntax only, because the Android SDK is not in
  the build sandbox. **Run the device checks below before release.**

## Device checklist (please run on a real device)

1. Open a `.kdbx` database and unlock it (AES-CBC payload, KDF) — confirms item 8.
2. Open an entry that has an attachment, view it, and save — confirms items 7 and 10.
3. Save two different attachments with the same size and check both survive a save.
4. Copy a password and a username; check the Android 13+ clipboard preview is hidden.
5. From Chrome (or another allow-listed browser), sign in with a passkey — confirms item 1.
6. From a non-browser app, passkey creation/sign-in is refused (expected).
7. In a non-browser app (e.g. a WebView login page), autofill should fall back to the app
   match rather than a web domain (expected, item 2).
8. Send a `VIEW` intent carrying `password=...` to the app: the password field must stay empty.
9. Autofill lock notification and the "lock on screen off" timeout still work (item 9).
