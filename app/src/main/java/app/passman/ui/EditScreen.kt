package app.passman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.passman.core.Entry
import app.passman.core.Limits
import app.passman.core.PasswordGenerator
import app.passman.core.Totp
import app.passman.data.VaultRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    original: Entry?,
    vault: VaultRepository,
    onClose: () -> Unit,
    onSaved: (Entry) -> Unit,
) {
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val base = remember { original ?: Entry(Entry.newId(), "", "", "", "", "", "", 0L) }
    // Drafts live only in memory (never in saved instance state) and vanish on lock (SEC-6).
    var title by remember { mutableStateOf(base.title) }
    var userName by remember { mutableStateOf(base.userName) }
    var password by remember { mutableStateOf(base.password) }
    var code by remember { mutableStateOf(base.code) }
    var website by remember { mutableStateOf(base.website) }
    var notes by remember { mutableStateOf(base.notes) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var generator by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    val draft = base.copy(title = title, userName = userName, password = password, code = code, website = website, notes = notes)
    val dirty = !draft.sameContentAs(base)
    val titleError = title.isBlank()
    val codeError = code.isNotBlank() && !Totp.isValid(code)

    if (original == null) LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }

    fun cancel() {
        if (dirty) confirmDiscard = true else onClose()
    }

    fun save() {
        attempted = true
        if (titleError || codeError || saving) return
        if (!dirty && original != null) {
            onClose() // FR: saving without changes leaves Modified date alone.
            return
        }
        saving = true
        scope.launch {
            try {
                val saved = vault.save(draft.copy(code = code.trim()))
                notify("Saved")
                onSaved(saved)
            } catch (e: Exception) {
                saving = false
                notify("Couldn't save the entry")
            }
        }
    }

    BackHandler(onBack = ::cancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "New entry" else "Edit entry") },
                navigationIcon = { IconButton(onClick = ::cancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") } },
                actions = {
                    TextButton(onClick = ::save, enabled = !saving) { Text("Save", fontWeight = FontWeight.SemiBold) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            LimitedField(
                value = title, onValueChange = { title = it }, label = "Title *", limit = Limits.TITLE,
                isError = attempted && titleError, error = "Title is required",
                capitalization = KeyboardCapitalization.Sentences,
                modifier = Modifier.focusRequester(titleFocus),
            )
            LimitedField(
                value = userName, onValueChange = { userName = it }, label = "User name", limit = Limits.USER_NAME,
                keyboardType = KeyboardType.Email,
            )
            SecretField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                maxLength = Limits.PASSWORD,
                supportingText = counter(password, Limits.PASSWORD),
                trailingExtra = {
                    IconButton(onClick = { generator = true }, modifier = Modifier.focusProperties { canFocus = false }) { Icon(Icons.Outlined.Casino, contentDescription = "Generate password") }
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            SecretField(
                value = code,
                onValueChange = { code = it },
                label = "2FA setup key",
                maxLength = Limits.CODE,
                isError = codeError,
                supportingText = when {
                    codeError -> "Not a valid setup key. Paste the secret (letters A–Z, 2–7) or an otpauth:// link."
                    code.isBlank() -> "Optional. The secret shown when you set up two-factor sign-in; PassMan shows the rotating code."
                    else -> "Valid setup key · the entry shows the rotating code"
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            LimitedField(
                value = website, onValueChange = { website = it }, label = "Website", limit = Limits.WEBSITE,
                keyboardType = KeyboardType.Uri, hint = "https:// is added if you leave it out",
            )
            LimitedField(
                value = notes, onValueChange = { notes = it }, label = "Notes", limit = Limits.NOTES,
                singleLine = false, capitalization = KeyboardCapitalization.Sentences,
                modifier = Modifier.heightIn(min = 140.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved changes to this entry will be lost.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (generator) {
        GeneratorDialog(
            onDismiss = { generator = false },
            onUse = {
                password = it
                generator = false
            },
        )
    }
}

private fun counter(value: String, limit: Int): String? =
    if (value.length >= limit * 8 / 10) "${value.length} / $limit" else null

@Composable
private fun LimitedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    limit: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    error: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val support = when {
        isError && error != null -> error
        else -> counter(value, limit) ?: hint
    }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(limit)) },
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = support?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            autoCorrectEnabled = false,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** FR-11. */
@Composable
private fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var memorable by remember { mutableStateOf(true) }
    var length by remember { mutableIntStateOf(PasswordGenerator.DEFAULT_LENGTH) }
    var letters by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    val generated = remember(memorable, length, letters, digits, symbols, nonce) {
        if (memorable) PasswordGenerator.generateMemorable()
        else PasswordGenerator.generate(length, letters, digits, symbols)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate password") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    SegmentedButton(
                        selected = memorable,
                        onClick = { memorable = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Strong") }
                    SegmentedButton(
                        selected = !memorable,
                        onClick = { memorable = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Custom") }
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            generated,
                            style = TextStyle(fontFamily = SecretFont, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        IconButton(onClick = { nonce++ }) { Icon(Icons.Outlined.Refresh, contentDescription = "New password") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (memorable) {
                    Text(
                        "20 characters in three easy-to-read groups, with one capital letter and one number. Works on almost every site.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Length: $length", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = length.toFloat(),
                        onValueChange = { length = Math.round(it) },
                        valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat(),
                    )
                    // At least one character set stays on.
                    CheckRow("Letters (A–Z, a–z)", letters) { if (it || digits || symbols) letters = it }
                    CheckRow("Numbers (0–9)", digits) { if (it || letters || symbols) digits = it }
                    CheckRow("Symbols (!@#…)", symbols) { if (it || letters || digits) symbols = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(generated) }) { Text("Use password") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
