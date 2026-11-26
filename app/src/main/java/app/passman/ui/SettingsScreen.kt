package app.passman.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.passman.data.UnlockResult
import app.passman.data.VaultRepository
import app.passman.data.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vault: VaultRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val state by vault.state.collectAsState()
    val count = (state as? VaultState.Unlocked)?.entries?.size ?: 0
    var changePassword by remember { mutableStateOf(false) }
    val restore = rememberRestoreFlow(vault, currentEntryCount = count, onRestored = onBack)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { vault.exportBackup(it) }
                }
            }.isSuccess
            notify(if (ok) "Encrypted backup saved" else "Couldn't save the backup")
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Vault")
            SettingRow(Icons.Outlined.Lock, "Lock now", "Also locks after 5 minutes idle, 1 minute in the background, or when the screen turns off") {
                vault.lock()
            }
            SettingRow(Icons.Outlined.Password, "Change master password", "You'll need your current one") { changePassword = true }

            Section("Backup")
            SettingRow(Icons.Outlined.Download, "Export encrypted backup", "Saves a file protected by your current master password") {
                exporter.launch("passman-backup-${LocalDate.now()}.pmv")
            }
            SettingRow(Icons.Outlined.Restore, "Restore from backup", "Replaces this vault with a backup's entries") { restore() }

            Section("Good to know")
            Column(Modifier.padding(horizontal = 20.dp)) {
                listOf(
                    "If you forget your master password, the vault cannot be recovered.",
                    "Copied passwords and 2FA codes are cleared from the clipboard after 30 seconds. If you copy something else in another app during that time, Android may not let PassMan tell the difference, and it may be cleared too.",
                    "PassMan can't protect an unlocked vault on a compromised phone.",
                    "Unlock delays slow guessing on this phone only. Someone with a copy of your vault or a backup file can guess offline, so a long master password matters.",
                    "PassMan has no internet access. Opening a website hands its address to your browser.",
                ).forEach {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text("•  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (changePassword) ChangePasswordDialog(vault, onDone = { changePassword = false })
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** FR-13. */
@Composable
private fun ChangePasswordDialog(vault: VaultRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notify = LocalNotify.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var currentError by remember { mutableStateOf<String?>(null) }

    val tooShort = new.length < MIN_MASTER_PASSWORD
    val mismatch = new != confirm
    val valid = current.isNotEmpty() && !tooShort && !mismatch

    fun submit() {
        attempted = true
        if (!valid || busy) return
        busy = true
        val currentChars = current.toPasswordChars()
        // Drop the typed current password now; changePassword() zeroes both arrays when done.
        current = ""
        scope.launch {
            val result = try {
                vault.changePassword(currentChars, new.toPasswordChars())
            } catch (e: Exception) {
                busy = false
                notify("Couldn't change the master password")
                return@launch
            }
            busy = false
            when (result) {
                UnlockResult.Success -> {
                    notify("Master password changed")
                    onDone()
                }
                UnlockResult.WrongPassword -> currentError = "Wrong current password"
                is UnlockResult.Throttled -> currentError = "Too many attempts. Try again in ${(result.waitMillis + 999) / 1000} s."
                is UnlockResult.Damaged -> currentError = result.reason
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Change master password") },
        text = {
            Column {
                SecretField(
                    value = current, onValueChange = { current = it; currentError = null }, label = "Current password",
                    enabled = !busy, isError = currentError != null, supportingText = currentError,
                )
                SecretField(
                    value = new, onValueChange = { new = it }, label = "New password", enabled = !busy,
                    isError = attempted && tooShort, supportingText = "At least $MIN_MASTER_PASSWORD characters",
                )
                SecretField(
                    value = confirm, onValueChange = { confirm = it }, label = "New password again", enabled = !busy,
                    isError = attempted && mismatch, supportingText = if (attempted && mismatch) "Passwords don't match" else null,
                    imeAction = ImeAction.Done, onImeAction = ::submit,
                )
                Text(
                    "Backups you exported earlier still open with the password that protected them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text(if (busy) "Changing…" else "Change") } },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}
