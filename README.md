# KeePassPN

KeePassPN lets you open and edit KeePass databases in the KDBX format. It is a fork of [KeePassDX](https://github.com/Kunzisoft/KeePassDX), which is itself a fork of KeePassDroid by bpellin. KeePassPN keeps the same database management and autofill workflow. Your database stays wherever you choose to store it, either on your device or in a folder provided by a document provider.

## What KeePassPN adds to KeePassDX

### 1. Quick unlock with a PIN

After you unlock a database with the master password for the first time, the app asks whether you want to set a PIN of at least six digits. With a PIN, you can unlock the database without typing the master password each time.

- The PIN is never stored in plain text.
- The PIN protects an encrypted copy of your master password. A key stored in the Android Keystore is also required to decrypt it.
- Checking the PIN takes a moment by design, because the PIN is stretched with Argon2id. While it is checked, the dialog shows a progress bar and the input is locked.
- After three incorrect PIN attempts, the PIN is deleted and you must enter the master password again. If you then enter the master password incorrectly twice more, the database is removed from the app.
- You can choose how long the PIN stays valid: 7, 14, 30 or 60 days, or indefinitely.
- If you do not open the app within the selected period, the PIN and the app's copy of the database are deleted.
- You can change or disable the PIN under *Settings > Device unlocking*.

Note: Your original `.kdbx` file is not modified when the app's data is deleted, formatted or cleared.

Even if your `.kdbx` file is stored outside your device, you can still unlock the database quickly with your PIN. Back up the latest copy of your database regularly.

### 2. Verify your identity before copying or editing

Some actions ask you to verify your identity first:

- **Copying protected fields.** Controlled by *App settings > User Verification > Verify before copying*.
- **Editing entries and folders.** Controlled by *App settings > User Verification > Verify before editing*. It is on by default. Turn it off to edit entries and folders without verification. It applies to databases that allow user verification.

The verification method is chosen in this order:

1. The database PIN, if you have set one.
2. The device screen lock, if no PIN is set and the device has one.
3. The first four characters of the master password, if neither option is available.

### 3. Database recovery copies

Each time a database opens successfully, the app saves a private copy in its own storage.

- If the original file is replaced with an older version, the app keeps the newer changes from its private copy.
- If both copies have changed, the app loads the original file and saves the private copy as a separate conflict file.
- Saving writes to a temporary file first, so an interrupted save does not replace your database with a partial file.

This helps preserve your newest changes and reduces the risk of data loss when a database file is overwritten by an older version.

### 4. Trusted browsers for autofill

Autofill can only offer entries for a website when it knows which website you are on. Browsers tell it this, and KeePassPN only believes browsers you trust.

- Choose trusted browsers under *Settings > Form filling > Trusted browsers*.
- Built-in browsers are verified by their signature. Additional browsers can be added from the installed apps.
- Trust is tied to the browser's signature. If the signature changes, for example after an update that changes the signing key, the browser must be trusted again.
- Trusted browsers require Android 9 or later.
- Other apps never get a website from autofill, so an app cannot borrow another site's credentials.

### 5. Copy TOTP codes after autofill (optional)

When an autofilled entry contains a time-based one-time password (TOTP), the app can also copy the current code to your clipboard.

This setting is optional and does not change how autofill works. You can turn it on under *Settings > Form filling*.

### 6. One-time codes (2FA)

Open *Verification codes* to see the current TOTP codes of entries from all groups in one list.

- Tap a code to copy it to the clipboard.
- To import, tap the scan button in a group, or *Import codes* on the Verification codes screen. Then scan:
  - the batch QR code that Google Authenticator generates when you export accounts, which can span several QR codes, or
  - a single `otpauth://` QR code for one account.
- Imported accounts are saved to a group named **2FA**, which you can rename. It sits second at the root, right after the TOTP Summary folder.
- Accounts that already exist, with the same issuer, name and secret, are skipped.
- The **TOTP Summary** folder at the top of the root group lists the entries of your database that already have a one-time password, with their live codes and the group each one belongs to. Codes you scan are not listed there; they stay in the 2FA group. Long-press the folder to rename it. Codes inside it have no actions on long press.

All QR code decoding happens on your device. The camera is used only for this. The app does not save camera images and does not request internet access.

### 7. Organising entries and folders

- Long-press an entry or folder to open its actions: edit it, move it to another folder, or duplicate it.
- Entries can be reordered by hand when the group allows reordering.
- Edits follow the verification setting described in section 2.

### 8. Check for updates

*Settings > About > Check for updates* shows the installed version and opens the release page in your browser, where you can compare it with the latest release. KeePassPN does not connect to the internet itself.

### 9. Privacy

Android cloud backup is disabled for the app, so its data is not included in Android cloud backups.

## Features inherited from KeePassDX

KeePassPN keeps these KeePassDX features:

- Opening KDBX databases from local storage or through a document provider.
- The Android Autofill service.
- Passkey provider support for Android Credential Manager.

## Passkeys and privileged apps

Passkeys are offered only to privileged callers: browsers and apps on a privileged allow list. KeePassPN checks these lists in this order:

1. A custom list stored on the device, if one is set.
2. The bundled lists: Google's list, then the community list.

A browser that is missing from every list will not show passkeys.

KeePassPN declares no `INTERNET` permission. Native apps that call Credential Manager directly are refused, because verifying them would require network access. See `SECURITY_HARDENING.md` for the reasoning.

## Things to know before enabling PIN unlock

A PIN is much less secure than a sufficiently long master password.

The failed-attempt counter is stored by the app itself, so an attacker with root access to the device may be able to reset it.

Enable PIN unlock only if you accept these risks.

## Building

Build the debug app from the repository root:

```
./gradlew assembleDebug
```

There is a single build variant. ## Project information

- Project name: KeePassPN
- Source code: https://github.com/hidefbi/keepasspn
- Upstream: KeePassDX by Kunzisoft (GPL-3.0), itself a fork of KeePassDroid by bpellin
- License: GNU General Public License v3.0 or later (GPL-3.0-or-later). See [LICENSE](LICENSE).
