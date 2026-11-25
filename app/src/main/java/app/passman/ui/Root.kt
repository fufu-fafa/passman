package app.passman.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.passman.data.SecureClipboard
import app.passman.data.VaultRepository
import app.passman.data.VaultState
import kotlinx.coroutines.launch

/** Shows a short message in the app-wide snackbar. Never pass entry data to it. */
val LocalNotify = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun PassManRoot(vault: VaultRepository, clipboard: SecureClipboard, onCloseApp: () -> Unit) {
    val state by vault.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = remember {
        { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }
    }
    CompositionLocalProvider(LocalNotify provides notify) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                // Each branch owns its own UI state, so leaving Unlocked drops every draft and
                // revealed value (SEC-6).
                when (val s = state) {
                    VaultState.NoVault -> SetupScreen(vault)
                    VaultState.Locked -> UnlockScreen(vault, onCloseApp)
                    is VaultState.Unlocked -> UnlockedApp(vault, clipboard, s, onCloseApp)
                }
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 8.dp),
                )
            }
        }
    }
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val id: String) : Screen
    /** [id] null = new entry. */
    data class Edit(val id: String?) : Screen
    data object Settings : Screen
}

@Composable
private fun UnlockedApp(
    vault: VaultRepository,
    clipboard: SecureClipboard,
    state: VaultState.Unlocked,
    onCloseApp: () -> Unit,
) {
    val stack = remember { mutableStateListOf<Screen>(Screen.List) }
    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    when (val top = stack.last()) {
        Screen.List -> ListScreen(
            vault = vault,
            entries = state.entries,
            onOpen = { push(Screen.Detail(it)) },
            onAdd = { push(Screen.Edit(null)) },
            onSettings = { push(Screen.Settings) },
            onLock = vault::lock,
            onBack = onCloseApp,
        )
        is Screen.Detail -> {
            val entry = state.entries.firstOrNull { it.id == top.id }
            if (entry == null) {
                LaunchedEffect(top) { pop() }
            } else {
                DetailScreen(
                    entry = entry,
                    vault = vault,
                    clipboard = clipboard,
                    onBack = ::pop,
                    onEdit = { push(Screen.Edit(entry.id)) },
                    onDeleted = ::pop,
                )
            }
        }
        is Screen.Edit -> {
            val original = top.id?.let { id -> state.entries.firstOrNull { it.id == id } }
            if (top.id != null && original == null) {
                LaunchedEffect(top) { pop() }
            } else {
                EditScreen(
                    original = original,
                    vault = vault,
                    onClose = ::pop,
                    onSaved = { saved ->
                        pop()
                        if (original == null) push(Screen.Detail(saved.id))
                    },
                )
            }
        }
        Screen.Settings -> SettingsScreen(vault = vault, onBack = ::pop)
    }
}
