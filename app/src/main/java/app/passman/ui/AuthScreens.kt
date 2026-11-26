package app.passman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.passman.data.BiometricUnlockResult
import app.passman.data.UnlockResult
import app.passman.data.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun AuthLayout(title: String, subtitle: String, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            content()
        }
    }
}

/** FR-1: first launch. */
@Composable
fun SetupScreen(vault: VaultRepository) {
    val scope = rememberCoroutineScope()
    val notify = LocalNotify.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val restore = rememberRestoreFlow(vault, currentEntryCount = null)

    val tooShort = password.length < MIN_MASTER_PASSWORD
    val mismatch = confirm != password
    val valid = !tooShort && !mismatch

    fun submit() {
        attempted = true
        if (!valid || busy) return
        busy = true
        val chars = password.toPasswordChars()
        // Drop the typed text now; create() zeroes chars when done.
        password = ""
        confirm = ""
        scope.launch {
            try {
                vault.create(chars)
            } catch (e: Exception) {
                busy = false
                notify("Couldn't create the vault")
            }
        }
    }

    AuthLayout("Create your vault", "Choose a master password. It's the only key to everything you store in PassMan.") {
        SecretField(
            value = password,
            onValueChange = { password = it },
            label = "Master password",
            enabled = !busy,
            isError = attempted && tooShort,
            supportingText = "At least $MIN_MASTER_PASSWORD characters · ${password.length} so far",
        )
        Spacer(Modifier.height(8.dp))
        SecretField(
            value = confirm,
            onValueChange = { confirm = it },
            label = "Enter it again",
            enabled = !busy,
            isError = (attempted || confirm.length >= password.length) && confirm.isNotEmpty() && mismatch,
            supportingText = if (confirm.isNotEmpty() && mismatch && (attempted || confirm.length >= password.length)) "Passwords don't match" else null,
            imeAction = ImeAction.Done,
            onImeAction = ::submit,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                Text(
                    "If you forget your master password, your vault cannot be recovered. Nobody can reset it — not even PassMan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        BusyButton(if (busy) "Creating vault…" else "Create vault", busy = busy, enabled = valid, onClick = ::submit)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = restore, enabled = !busy) { Text("Restore from a backup instead") }
    }
}

/** FR-2 and SEC-8. */
@Composable
fun UnlockScreen(vault: VaultRepository, onCloseApp: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var damaged by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableLongStateOf(vault.unlockWaitMillis()) }
    val focus = remember { FocusRequester() }
    val restore = rememberRestoreFlow(vault, currentEntryCount = null)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bioEnabled by vault.biometricEnabled.collectAsState()
    val bioUsable = bioEnabled && remember(bioEnabled) { vault.biometricAvailability() == BiometricManager.BIOMETRIC_SUCCESS }
    var bioMessage by remember { mutableStateOf<String?>(null) }
    var bioBusy by remember { mutableStateOf(false) }

    fun useFingerprint() {
        if (busy || bioBusy) return
        bioBusy = true
        bioMessage = null
        scope.launch {
            try {
                val cipher = vault.prepareBiometricUnlock()
                if (cipher == null) {
                    bioMessage = "Fingerprint unlock was turned off because the fingerprints on this phone changed. " +
                        "Unlock with your master password, then turn it on again in Settings."
                    return@launch
                }
                when (val outcome = context.findFragmentActivity().authenticate(cipher, "Unlock PassMan", null, "Use master password")) {
                    is BiometricOutcome.Success -> {
                        busy = true
                        if (vault.unlockWithBiometric(outcome.cipher) == BiometricUnlockResult.TurnedOff) {
                            bioMessage = "Fingerprint unlock was turned off because it no longer matches this vault. " +
                                "Unlock with your master password, then turn it on again in Settings."
                        }
                        busy = false
                    }
                    BiometricOutcome.Cancelled -> runCatching { focus.requestFocus() }
                    is BiometricOutcome.Error -> bioMessage = outcome.message
                }
            } finally {
                bioBusy = false
            }
        }
    }

    BackHandler(onBack = onCloseApp)
    LaunchedEffect(Unit) {
        if (bioUsable) {
            // Once per lock, and only once PassMan is really on screen (not while the phone is
            // asleep or the app is in the background). Cancelling doesn't bring it back.
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            useFingerprint()
        } else {
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(waitMillis > 0) {
        while (waitMillis > 0) {
            delay(250)
            waitMillis = vault.unlockWaitMillis()
        }
    }

    fun submit() {
        if (password.isEmpty() || busy || waitMillis > 0) return
        busy = true
        error = null
        val chars = password.toPasswordChars()
        // Drop the typed text now; unlock() zeroes chars when done.
        password = ""
        scope.launch {
            when (val r = vault.unlock(chars)) {
                UnlockResult.Success -> Unit // The root switches screens.
                UnlockResult.WrongPassword -> {
                    error = "Wrong master password"
                    waitMillis = vault.unlockWaitMillis()
                }
                is UnlockResult.Damaged -> {
                    damaged = true
                    error = "The vault file is damaged or was changed outside PassMan, so it can't be opened. It has not been modified."
                }
                is UnlockResult.Throttled -> waitMillis = r.waitMillis
            }
            busy = false
        }
    }

    AuthLayout("PassMan is locked", "Enter your master password to unlock.") {
        SecretField(
            value = password,
            onValueChange = { password = it; error = null },
            label = "Master password",
            enabled = !busy,
            isError = error != null,
            supportingText = error,
            imeAction = ImeAction.Done,
            onImeAction = ::submit,
            modifier = Modifier.focusRequester(focus),
        )
        if (waitMillis > 0) {
            Text(
                "Too many attempts. Try again in ${(waitMillis + 999) / 1000} s.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        BusyButton(
            if (busy) "Unlocking…" else "Unlock",
            busy = busy,
            enabled = password.isNotEmpty() && waitMillis <= 0,
            onClick = ::submit,
        )
        if (bioUsable && !damaged) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = ::useFingerprint,
                enabled = !busy && !bioBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Use fingerprint")
            }
        }
        bioMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (damaged) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = restore) { Text("Restore from a backup") }
        }
    }
}
