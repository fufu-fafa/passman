package app.passman.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.passman.core.Entry
import app.passman.core.Totp
import app.passman.core.WebsiteUrl
import app.passman.data.SecureClipboard
import app.passman.data.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    entry: Entry,
    vault: VaultRepository,
    clipboard: SecureClipboard,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    // FR-9: hidden by default, and hidden again whenever this screen is left (state is dropped).
    var showPassword by remember(entry.id) { mutableStateOf(false) }
    var showCode by remember(entry.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Monogram(entry.title, size = 52)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(entry.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Modified ${formatDate(entry.modifiedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            FieldCard {
                FieldRow("User name", entry.userName.ifEmpty { null }) {
                    if (entry.userName.isNotEmpty()) {
                        CopyButton {
                            clipboard.copyPlain("User name", entry.userName)
                            notify("User name copied")
                        }
                    }
                }
                Divider()
                FieldRow(
                    "Password",
                    when {
                        entry.password.isEmpty() -> null
                        showPassword -> entry.password
                        else -> MASK
                    },
                    secret = true,
                ) {
                    if (entry.password.isNotEmpty()) {
                        RevealButton(showPassword) { showPassword = !showPassword }
                        CopyButton {
                            clipboard.copySecret("Password", entry.password)
                            notify("Password copied · clears in 30 s")
                        }
                    }
                }
                if (entry.code.isNotEmpty()) {
                    Divider()
                    TotpRow(entry.code, showCode, onToggle = { showCode = !showCode }) { code ->
                        clipboard.copySecret("2FA code", code)
                        notify("Code copied · clears in 30 s")
                    }
                }
                Divider()
                FieldRow("Website", entry.website.ifEmpty { null }, singleLine = true) {
                    if (entry.website.isNotEmpty()) {
                        IconButton(onClick = {
                            val url = WebsiteUrl.toOpenable(entry.website)
                            if (url == null) {
                                notify("Only http and https links can be opened")
                            } else {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addCategory(Intent.CATEGORY_BROWSABLE)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (e: ActivityNotFoundException) {
                                    notify("No browser found")
                                }
                            }
                        }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open website") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (entry.notes.isEmpty()) {
                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        SelectionContainer { Text(entry.notes, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete “${entry.title}”?") },
            text = { Text("This entry will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            try {
                                vault.delete(entry.id)
                                notify("Deleted")
                                onDeleted()
                            } catch (e: Exception) {
                                notify("Couldn't delete the entry")
                            } finally {
                                deleting = false
                                confirmDelete = false
                            }
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text("Cancel") } },
        )
    }
}

private const val MASK = "••••••••••"

@Composable
private fun TotpRow(setupKey: String, visible: Boolean, onToggle: () -> Unit, onCopy: (String) -> Unit) {
    val config = remember(setupKey) { Totp.parse(setupKey) }
    if (config == null) {
        FieldRow("2FA code", "Invalid setup key — edit the entry to fix it")
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(config) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val code = Totp.code(config, now)
    val remaining = Totp.secondsRemaining(config, now)
    val grouped = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
    FieldRow("2FA code", if (visible) grouped else "••• •••", secret = true) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
            CircularProgressIndicator(
                progress = { remaining / config.periodSeconds.toFloat() },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = if (remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("$remaining", style = MaterialTheme.typography.labelSmall)
        }
        RevealButton(visible, onToggle)
        CopyButton { onCopy(Totp.code(config, System.currentTimeMillis())) }
    }
}

@Composable
private fun FieldCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun FieldRow(
    label: String,
    value: String?,
    secret: Boolean = false,
    singleLine: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value ?: "—",
                style = MaterialTheme.typography.bodyLarge.let { if (secret && value != null) it.copy(fontFamily = SecretFont) else it },
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun CopyButton(onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }

@Composable
private fun RevealButton(visible: Boolean, onClick: () -> Unit) =
    IconButton(onClick = onClick) {
        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Hide" else "Show")
    }

fun formatDate(utcMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(utcMillis))
