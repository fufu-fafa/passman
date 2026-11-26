package app.passman.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Keyboard settings for secrets: no suggestions, autocorrect or learning. */
val SecretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/** A masked text field with a show/hide toggle. */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    maxLength: Int = Int.MAX_VALUE,
    trailingExtra: @Composable (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        textStyle = TextStyle(fontFamily = SecretFont),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboard.copy(imeAction = imeAction),
        // Without a handler the keyboard's default (Next moves focus) applies.
        keyboardActions = onImeAction?.let { action -> KeyboardActions(onAny = { action() }) } ?: KeyboardActions.Default,
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = {
            Row {
                trailingExtra?.invoke()
                // Out of keyboard focus order so Next moves to the following field.
                IconButton(onClick = { visible = !visible }, modifier = Modifier.focusProperties { canFocus = false }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** A full-width button that shows a spinner while [busy]. */
@Composable
fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(12.dp))
            }
            Text(text)
        }
    }
}

/**
 * Copies the characters into a fresh array for key derivation; the VaultRepository call it is
 * passed to zeroes it. The String itself can't be wiped, so callers clear the field's state
 * right after (it then lingers in memory only until garbage collection).
 */
fun String.toPasswordChars(): CharArray = toCharArray()

/**
 * Asks every keyboard to treat every PassMan text field as incognito
 * ([EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]): no learning words from, or suggesting,
 * user names, notes or anything else typed here. Password fields are also typed as passwords.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val incognito = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttrs: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttrs)
                    outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            }
            nextHandler.startInputMethod(incognito)
        },
        content = content,
    )
}

const val MIN_MASTER_PASSWORD = 12
