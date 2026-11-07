# KeePassPN

KeePassPN is a KeePass app for Android. It opens and edits KDBX databases. It's a fork of [KeePassDX](https://github.com/Kunzisoft/KeePassDX), so the database handling and autofill work the same way. Your database stays wherever you keep it, on the device or in a folder from a document provider.

## PIN unlock

Unlock once with your master password, then the app can offer a PIN (at least 6 digits) for next time.

- The PIN is never stored as is. It protects an encrypted copy of your master password, and the key lives in the Android Keystore.
- Checking the PIN is slow on purpose, so expect a short wait.
- Three wrong PINs and the PIN gets deleted. You need the master password again. Two more wrong ones and the database is removed from the app.
- Without a PIN, three wrong master passwords also remove the database from the app.
- The PIN can be valid for 7, 14, 30 or 60 days, or forever (the default). If you don't open the app in that time, the PIN and the app's copy of the database are deleted.
- Change or turn off the PIN under *Settings > Device unlocking*.

None of this touches your `.kdbx` file.

**Read before you turn PIN on.** A PIN has little entropy, and the attempt counter lives in the app, so someone with root can reset it. Use it for convenience, not in place of a strong master password.

## Verification before copying or editing

- Copying protected fields: *App settings > User Verification > Verify before copying*.
- Editing entries and folders: *App settings > User Verification > Verify before editing*. It's on by default.

The app uses the first option it finds: your PIN, then the device screen lock, then the master password.

## Recovery copies

Every time a database opens or saves, the app keeps a private copy in its own storage.

- If the original gets replaced by an older version, your newer changes are kept.
- If both the original and the copy changed, the original loads and the copy is saved as a conflict file. The three newest are kept.
- Saves go through a temp file first, so an interrupted save won't leave a half-written database.

The copy never leaves the app, and Android backup is turned off.

## Autofill

- Trusted browsers live under *Settings > Form filling > Trusted browsers*. The built-in ones are checked by signature. You can add more from your installed apps. Trusted browsers need Android 9 or later.
- A trust is tied to the browser's signature, so after a signing key change you have to trust it again.
- Other apps never get a website from autofill, so one app can't use another site's login.
- Optional: *Copy TOTP after autofill* copies the current code when the entry has one. It's under *Settings > Form filling*.

## One-time codes (2FA)

Open *Verification codes* to see the live codes of your entries in one list. Tap a code to copy it.

- To add codes, use the scan button in a group or *Import codes* on the Verification codes screen. You can scan Google Authenticator's export (it can be several QR codes) or a single `otpauth://` code.
- Imported accounts go to a group called **2FA Import**. Rename it if you like. Duplicates (same issuer, name and secret) are skipped.
- The **TOTP Summary** folder at the top lists the entries in your database that already have a code. Scanned codes stay in the 2FA group and don't show up there. Long-press the folder to rename it.
- In an entry's one-time password dialog, tap the scan icon in the top right. The camera reads one code, fills in the form and closes. Save as usual. If the entry already has a code, you're asked before it gets replaced.

Scanning happens on the device. The camera is only used for this, and no images are saved.

## Importing

*Import from a file* is at the bottom of the database settings. It takes CSV, JSON or ZIP. Nothing is written until you confirm, and you can change the group name first.

- Everything in the file comes in as it is, including repeated entries and long values.
- CSV needs a password column and a title or URL column. KeePass and Chrome column names are recognised. Commas, semicolons and tabs all work.
- JSON can be a list, or an object holding `items`, `entries`, `logins` or `passwords`. Bitwarden and Proton Pass exports are read properly, one-time codes included.
- A ZIP works if it holds exactly one JSON file.
- Encrypted exports aren't supported. Files over 10 MB or with more than 10,000 entries are refused.
- Your original file is never changed or deleted. If an import fails, whatever it created is removed.

## Brand icons

In the icon picker, the *Brand* tab shows the logo for the entry's site, for example GitHub for `github.com`. The logos come from Simple Icons (CC0) and are stored in the app, so it works offline. Matching goes by the domain name. Nothing changes until you pick a logo, and then a copy is saved as a custom icon of the database.

Logos belong to their owners. Check the brand's guidelines before you use one anywhere else.

## Privacy and permissions

- The app has no internet permission. It can't connect anywhere.
- The camera is only used to scan codes.
- *Check for updates* just opens the releases page in your browser.
- Passkeys are only offered to browsers and apps on the privileged allow lists. A custom list on the device is checked first, then Google's and the community list. Native apps that call Credential Manager directly are refused, since checking them would need internet. See `SECURITY_HARDENING.md` for the reasoning.

## Features from KeePassDX

- Opening KDBX databases from local storage or a document provider.
- The Android Autofill service.
- Passkey provider support for Credential Manager.

## Building

```
./gradlew :app:installDebug   # build and install on a connected device
./gradlew assembleDebug       # build the APK only
```

## Project information

- Source: https://github.com/hidefbi/keepasspn
- Upstream: KeePassDX by Kunzisoft (GPL-3.0), itself a fork of KeePassDroid by bpellin
- License: GPL-3.0-or-later. See [LICENSE](LICENSE).
