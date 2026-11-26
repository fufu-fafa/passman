# Handoff: biometric unlock for PassMan

**Goal:** let the user unlock PassMan with a fingerprint instead of typing the master password.

**Status:** done (2026-11-26), built as described below with `androidx.biometric` 1.4.0-rc01.
Tested on the S21 FE: enable, fingerprint unlock, cancel → password, change password and restore
turn it off, a key that no longer matches the vault turns it off (never "damaged"), screen off
during the prompt locks, and the R8-minified build works. Not tested yet: test plan steps 3
(Android lockout after 5 wrong fingers) and 4 (adding a fingerprint invalidates the key).

PassMan v1 is built, tested on a Galaxy S21 FE, and documented in
`README.md`. Read the README's security table first: this change works inside that design.

**Scope note:** the PRD (Claude Doc, "PassMan: Product Requirements Document") answered
"Is biometric unlock needed at launch?" with *no*. This is follow-on work. If you change scope,
update that doc's open-questions list.

---

## 1. The one decision to get right: biometrics *alongside* the master password, not replacing it

"Instead of master password" should mean **instead of typing it every time**. The master password
can't go away:

| Why the master password must stay | Where |
|---|---|
| Backups are protected by it. A backup is a copy of `vault.pmv`, whose vault key is wrapped only by the Argon2id key. A fingerprint can't open a backup on another phone or after a reinstall. | `VaultCrypto`, FR-14 |
| Fingerprint keys are destroyed when the user adds a fingerprint, removes all fingerprints, or resets the phone's lock screen. Without the password, the vault would be lost. | Android Keystore behaviour |
| Change password (FR-13) and restore re-verify with the password. | `VaultRepository.changePassword`, `RestoreFlow` |

So: **fingerprint unlock is an optional shortcut that stores a second, hardware-protected wrap of
the same vault key.** The master password stays the root of trust and is always offered as a
fallback.

---

## 2. Facts about the test phone (checked)

- **Fingerprint:** Class 3, `BIOMETRIC_STRONG`. Can release a Keystore key, so it works.
- **Face unlock:** Class 2 (weak). It **can't** unlock a Keystore key. Don't advertise face unlock.
- **StrongBox:** present (`android.hardware.strongbox_keystore`).
- **`androidx.biometric` is not in the Gradle cache.** The first build needs network, so drop
  `--offline`. `androidx.fragment` in the cache is old (1.1.0); let Gradle resolve what biometric
  pulls in.
- **No fingerprint over adb:** a real phone can't take an injected fingerprint. Test on the phone
  by hand, or use an emulator: `adb -e emu finger touch 1`.

---

## 3. How it should work

```
Enable (Settings, while unlocked)
  Keystore: generate AES-256-GCM key "passman_bio"
            userAuthenticationRequired = true, auth per use (timeout 0), BIOMETRIC_STRONG only
            invalidatedByBiometricEnrollment = true, StrongBox if available (fall back if not)
  BiometricPrompt(CryptoObject(cipher in ENCRYPT mode))  -> user touches sensor
  cipher.doFinal(vaultKey)  -> files/biometric.bin  (version, iv, ciphertext)

Unlock (Unlock screen)
  read biometric.bin -> cipher.init(DECRYPT, key, iv)  (KeyPermanentlyInvalidatedException -> disable)
  BiometricPrompt(CryptoObject(cipher))  -> user touches sensor
  vaultKey = cipher.doFinal(ciphertext)
  VaultCrypto.openWithKey(vault.pmv, vaultKey)   // skips Argon2id: unlock gets *faster*
```

The fingerprint never derives anything. It authorizes the Keystore to decrypt one 32-byte blob.
The plaintext vault key exists only in app memory after unlock, exactly as today.

---

## 4. Code changes, file by file

### `gradle/libs.versions.toml` and `app/build.gradle.kts`
Add `androidx.biometric:biometric` (latest stable; 1.1.0 at the time of writing, or the newest
1.2.x if stable by then). Keep the project **Kotlin-only**. Don't enable `buildConfig`: on this
Mac, JDK 27's `jlink` breaks AGP's javac step (README, "Build and install").

### `core/VaultCrypto.kt`
- Add `fun openWithKey(file: ByteArray, vaultKey: ByteArray): OpenedVault`. It's the second half
  of `open()`: parse the header, decrypt the data with the given key, and map
  `AEADBadTagException` to `CorruptVaultException`. Refactor so `open()` and `openWithKey()` share
  that path.
- `VaultKeys.vaultKey` is private. Add an `internal fun exportKeyCopy(): ByteArray` (inside
  `useKey`, under the same lock) for enrollment. The caller must zero the copy.
- Unit-test it in `CoreTest.kt`: round-trip, a wrong key gives `CorruptVaultException`, a tampered
  file gives `CorruptVaultException`.

### New `data/BiometricKeyStore.kt` (Android)
- `isAvailable(context)`: `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`.
- `createEncryptCipher()`: create a new Keystore key (delete any old one first) and init the cipher
  for ENCRYPT.
- `createDecryptCipher(iv)`: init the cipher for DECRYPT. On `KeyPermanentlyInvalidatedException`
  or `UnrecoverableKeyException`, call `disable()` and report "fingerprints changed".
- Blob file `files/biometric.bin`, written atomically like `VaultFileStore` (temp, fsync, rename).
  Format: `"PMB1"`, u8 version, u8 iv length (12), iv, then ciphertext of the 32-byte key plus the
  16-byte tag. Authenticate the header as GCM AAD.
- `disable()`: delete the blob **and** the Keystore entry.
- Never log the key or the blob.

### `data/VaultRepository.kt`
- `suspend fun enableBiometric(cipher: Cipher)`: wrap `keys.exportKeyCopy()`, write the blob, zero
  the copy.
- `suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult`: decrypt the blob, then
  `openWithKey`. **If it fails with `CorruptVaultException`, the blob is stale** (the vault key
  changed). Disable biometrics and ask for the password; don't show "vault damaged".
- **Keep the blob valid when the vault key changes.** Two places create a new vault key:
  - `changePassword()` calls `VaultCrypto.create()`, which makes a **new vault key**. Either
    re-wrap it (needs a fresh fingerprint touch, so the UI has to prompt) or disable biometrics and
    tell the user to turn it back on. **Recommended: disable and tell them.** It's simpler, and a
    password change is a natural moment to re-confirm.
  - `restore()` brings in the backup's vault key. Always disable biometrics here.
- Biometric failures don't count toward the SEC-8 password throttle. Android has its own lockout
  (`ERROR_LOCKOUT` after 5 tries, `ERROR_LOCKOUT_PERMANENT` later), so surface those messages.
- A successful fingerprint unlock may reset the password-failure counter, as a password unlock
  does today (only the owner's enrolled finger can do it). Keep that reset in one place in the
  repository.

### `MainActivity.kt`
`BiometricPrompt` needs a `FragmentActivity`. Change `ComponentActivity` to `FragmentActivity`
(from `androidx.fragment`); `setContent` still works. Check that the existing behaviour stays the
same: `onSaveInstanceState` clearing, the `FLAG_SECURE` resource flag, and autofill exclusion.

### `ui/AuthScreens.kt`: Unlock screen
- If biometrics are enabled and available, show the prompt automatically once on first display, and
  offer a "Use fingerprint" button after that. The password field stays visible.
- Prompt text: title "Unlock PassMan", negative button "Use master password". Use
  `setAllowedAuthenticators(BIOMETRIC_STRONG)` only. **Don't allow `DEVICE_CREDENTIAL`**: that
  would let the phone PIN open the vault, a big downgrade, and it doesn't work with an auth-per-use
  CryptoObject on API < 30 anyway.
- Don't auto-prompt again after the user cancels, and not after a damaged-vault result.

### `ui/SettingsScreen.kt`
Add a "Unlock with fingerprint" switch to the Vault section:
- **On:** check availability (none enrolled: "Add a fingerprint in Android settings first"), run the
  ENCRYPT prompt, then `enableBiometric`.
- **Off:** `disable()`.
- Subtitle: "Your master password still works and is needed for backups."

### `PassManApp.kt` / `AutoLock`
No change needed. The prompt is an in-app dialog on API 29+, so the activity doesn't stop and the
1-minute background timer doesn't start. **Verify this on the phone.** If the prompt does trigger
`onStop` on One UI, an unlock that takes over a minute would just lock again, which is harmless.

### `README.md`
Add a row to the security table and a bullet to "Platform limits": fingerprint unlock relies on
Android Keystore and TEE/StrongBox security; adding a fingerprint turns it off; face unlock isn't
supported.

---

## 5. Security checklist (must hold after the change)

- [ ] SEC-3 still holds: the master password is never stored, and neither is the plaintext vault key.
- [ ] Only `BIOMETRIC_STRONG`; no device-credential fallback.
- [ ] Key is auth-per-use (timeout 0) and `setInvalidatedByBiometricEnrollment(true)`.
- [ ] `biometric.bin` stays out of backups. `data_extraction_rules.xml` already excludes all files
      and `allowBackup="false"`; confirm nothing loosened.
- [ ] A stale or invalidated blob never shows "vault damaged" and never overwrites `vault.pmv`.
- [ ] Locking still zeroes the key (SEC-6); the blob on disk is useless without the Keystore key
      plus a fingerprint.
- [ ] Still no `INTERNET` permission. The biometric library doesn't need it; check the merged
      manifest (`app/build/intermediates/merged_manifest/release/.../AndroidManifest.xml`).

---

## 6. Test plan

**Unit (JVM):** the `openWithKey` tests above. The current 20 tests must still pass:
`./gradlew :app:testDebugUnitTest`.

**On the phone, by hand (fingerprint can't be scripted):**
1. Enable in Settings, lock, unlock with fingerprint. Entries and fields match. Unlock is noticeably
   faster than with the password (no Argon2id; the password takes about 1.3–1.6 s today).
2. Cancel the prompt and unlock with the password.
3. Five wrong fingers give the system lockout message; the password still works.
4. Add a new fingerprint in Android settings. PassMan says fingerprint unlock was turned off, and
   the password works.
5. Change the master password. Fingerprint unlock is turned off with a message; re-enable it, and it
   works.
6. Restore a backup. Fingerprint unlock is turned off.
7. Uninstall and reinstall, then restore the backup with its password. Biometrics start disabled.
8. Screen off during the prompt still locks. Background longer than 1 minute still locks.
9. Release build: `./gradlew :app:assembleRelease`, then check R8 kept everything the biometric
   path needs (install and repeat step 1).

**Emulator option:** create a fingerprint in emulator settings, then use
`adb -e emu finger touch 1` to automate steps 1–6.

**After any Gradle build on this Mac:** run `./gradlew --stop` (8 GB RAM).

---

## 7. Where things are

| What | File |
|---|---|
| Vault format, Argon2id, AES-GCM, `VaultKeys` | `app/src/main/java/app/passman/core/VaultCrypto.kt` |
| Unlock, save, change password, restore | `app/src/main/java/app/passman/data/VaultRepository.kt` |
| Atomic file write pattern | `app/src/main/java/app/passman/data/VaultFileStore.kt` |
| Setup and Unlock screens | `app/src/main/java/app/passman/ui/AuthScreens.kt` |
| Settings | `app/src/main/java/app/passman/ui/SettingsScreen.kt` |
| Restore flow | `app/src/main/java/app/passman/ui/RestoreFlow.kt` |
| Auto-lock timers, screen-off receiver | `app/src/main/java/app/passman/PassManApp.kt` |
| Activity, window flags | `app/src/main/java/app/passman/MainActivity.kt` |
| Unit tests | `app/src/test/java/app/passman/CoreTest.kt` |

Test vault on the phone: two entries, master password `test master pw 1` (restored from
`Download/passman-backup-2026-11-25.pmv`). `Download/tampered.pmv` is a deliberately corrupted
copy for negative tests.
