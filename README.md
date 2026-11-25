# PassMan

A basic, offline password manager for Android. One person, one encrypted vault on the phone,
one master password. Built to the PassMan PRD (Android first; Code = rotating 2FA code; no sync,
import or biometrics in version 1).

## Build and install

```sh
./gradlew :app:testDebugUnitTest          # JVM tests: crypto, tamper detection, TOTP vectors, search…
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew --stop
```

The release build is minified and signed with the local debug key (personal sideload). It sets
`FLAG_SECURE`, so screenshots and screen recordings are blocked. The debug build allows `adb`
screenshots for testing (`app/src/debug/res/values/bools.xml`); app-switcher previews are hidden
in both.

The project avoids Java sources (no `BuildConfig`): `jlink` from the Homebrew JDK 27 fails on
AGP's javac step, while Kotlin-only builds work.

## Screens

Setup → Unlock → Entry list (search, sort, empty and no-results states) → Entry detail →
Add/Edit (with password generator) → Settings (lock, change master password, export and restore
backup).

## Data

Each entry has Title (required, 100), User Name (254), Password (1,024), Code (256), Website
(2,048), Notes (10,000), and an automatic Modified date stored as UTC milliseconds and shown in
local time. Modified date changes only when a save actually changes a field.

**Code** holds a 2FA setup key: the Base32 secret, or an `otpauth://totp/…` link. The detail
screen shows the current 6–8 digit code (RFC 6238; SHA-1/256/512) with a countdown, masked until
revealed, and copies it in one tap. Search never looks at Password or Code.

## Security design

| | |
|---|---|
| Vault file | `files/vault.pmv` in app-private storage; nothing else holds entry data |
| Encryption | AES-256-GCM (platform `javax.crypto`), fresh random 96-bit nonce per save; the whole header is authenticated, so any changed byte is rejected |
| Key derivation | Argon2id (BouncyCastle), random 16-byte salt, 64 MiB / 3 iterations / parallelism 4 — about 1.3–1.6 s to unlock on a Galaxy S21 FE |
| Keys | A random 256-bit vault key, wrapped by the Argon2id key. The master password is never stored. Changing it creates a new salt and a new vault key |
| Saves | Written to a temp file, `fsync`ed, then renamed over the vault, so a crash leaves either the old or the new vault |
| Backups | A backup is a byte-for-byte copy of the encrypted vault, opened with the master password that protected it. Restore verifies the backup fully and asks before replacing anything |
| Lock | Manual; after 5 min idle; 1 min in the background; on screen off; when the app is closed with Back. Locking zeroes the vault key and drops all decrypted entries and unsaved edits |
| Unlock delays | 3 free attempts, then 5 s, 15 s, 30 s, 60 s, 2 min, capped at 5 min. Persisted across restarts. The vault is never erased |
| Clipboard | Passwords and codes are flagged sensitive (kept out of clipboard previews and history where Android honours it) and cleared after 30 s |
| Privacy | No `INTERNET` permission, no analytics, no logging of entry data, Android backup and device transfer disabled, autofill excluded, UI state never saved to the instance bundle |

File format (big-endian): `"PMV1"`, version, Argon2 memory/iterations/parallelism, salt, wrap
nonce, wrapped key (48 bytes), data nonce, then the encrypted entry list. See
`core/VaultCrypto.kt`.

## Platform limits

- **Memory:** Kotlin/Compose text fields use immutable `String`s, which can't be zeroed. PassMan
  zeroes the key and its own byte buffers and drops every reference on lock; the remaining copies
  are freed by the garbage collector.
- **Clipboard clearing:** Android only lets the focused app read the clipboard. With PassMan in
  front, it clears only its own copied value. In the background it can't check, so after 30 s it
  clears the clipboard anyway, even if you copied something else in another app since. If Android
  kills the app within those 30 s, the copied value stays. Keyboards with their own clipboard
  history may ignore the sensitive flag.
- **Idle timer:** the 5-minute idle lock runs while PassMan is on screen. Screen-off and the
  1-minute background lock cover the other cases.
- **Offline guessing:** unlock delays only apply in the app. Anyone with a copy of the vault or a
  backup file can guess offline, slowed only by Argon2id, so the master password's length matters.
- **Compromised device:** PassMan can't protect an unlocked vault on a rooted or infected phone.
