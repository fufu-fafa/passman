package app.passman.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.passman.core.CorruptVaultException
import app.passman.core.WrongPasswordException
import app.passman.data.PendingRestore
import app.passman.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private sealed interface RestoreStep {
    data object Idle : RestoreStep
    class AskPassword(val bytes: ByteArray, val error: String? = null, val busy: Boolean = false) : RestoreStep
    class Confirm(val pending: PendingRestore, val busy: Boolean = false) : RestoreStep
}

/**
 * FR-14 restore: pick a backup file, enter the master password that protected it, confirm, and
 * replace the vault. Nothing on disk changes until the user confirms a backup that verified.
 *
 * @param currentEntryCount entries in the current vault, or null on a fresh install
 * @return a function that starts the flow
 */
@Composable
fun rememberRestoreFlow(vault: VaultRepository, currentEntryCount: Int?, onRestored: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notify = LocalNotify.current
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.Idle) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > VaultRepository.MAX_BACKUP_BYTES) throw IOException("Too large")
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) notify("Couldn't read that file") else step = RestoreStep.AskPassword(bytes)
        }
    }

    // Drop a verified-but-unconfirmed backup's key if this screen goes away (for example on lock).
    DisposableEffect(Unit) {
        // A busy Confirm means restore() is running and the vault is adopting these keys.
        onDispose { (step as? RestoreStep.Confirm)?.takeIf { !it.busy }?.pending?.discard() }
    }

    when (val s = step) {
        RestoreStep.Idle -> Unit
        is RestoreStep.AskPassword -> {
            var password by remember(s.bytes) { mutableStateOf("") }
            fun submit() {
                if (password.isEmpty() || s.busy) return
                step = RestoreStep.AskPassword(s.bytes, busy = true)
                scope.launch {
                    step = try {
                        RestoreStep.Confirm(vault.openBackup(s.bytes, password.toPasswordChars()))
                    } catch (e: WrongPasswordException) {
                        RestoreStep.AskPassword(s.bytes, error = "Wrong master password for this backup")
                    } catch (e: CorruptVaultException) {
                        RestoreStep.AskPassword(s.bytes, error = "This file isn't a PassMan backup, or it is damaged")
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { if (!s.busy) step = RestoreStep.Idle },
                title = { Text("Unlock backup") },
                text = {
                    Column {
                        Text("Enter the master password that protected this backup.")
                        SecretField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Backup's master password",
                            isError = s.error != null,
                            supportingText = s.error,
                            enabled = !s.busy,
                            imeAction = ImeAction.Done,
                            onImeAction = ::submit,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = ::submit, enabled = password.isNotEmpty() && !s.busy) {
                        Text(if (s.busy) "Checking…" else "Continue")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = RestoreStep.Idle }, enabled = !s.busy) { Text("Cancel") }
                },
            )
        }
        is RestoreStep.Confirm -> {
            val count = s.pending.entryCount
            AlertDialog(
                onDismissRequest = {
                    if (!s.busy) {
                        s.pending.discard()
                        step = RestoreStep.Idle
                    }
                },
                title = { Text(if (currentEntryCount == null) "Restore backup?" else "Replace your vault?") },
                text = {
                    Text(
                        buildString {
                            if (currentEntryCount == null) {
                                append("Restore ${entries(count)} from this backup?")
                            } else {
                                append("Your current vault (${entries(currentEntryCount)}) will be permanently replaced by this backup (${entries(count)}).")
                            }
                            append("\n\nFrom now on, unlock PassMan with the backup's master password.")
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            step = RestoreStep.Confirm(s.pending, busy = true)
                            scope.launch {
                                try {
                                    vault.restore(s.pending)
                                    step = RestoreStep.Idle
                                    notify("Backup restored")
                                    onRestored()
                                } catch (e: IOException) {
                                    s.pending.discard()
                                    step = RestoreStep.Idle
                                    notify("Restore failed; your vault was not changed")
                                }
                            }
                        },
                        enabled = !s.busy,
                    ) {
                        Text(
                            if (currentEntryCount == null) "Restore" else "Replace",
                            color = if (currentEntryCount == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            s.pending.discard()
                            step = RestoreStep.Idle
                        },
                        enabled = !s.busy,
                    ) { Text("Cancel") }
                },
            )
        }
    }

    return { picker.launch(arrayOf("*/*")) }
}

fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
