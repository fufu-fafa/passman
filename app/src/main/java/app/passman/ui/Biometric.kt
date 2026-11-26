package app.passman.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface BiometricOutcome {
    /** The fingerprint matched; [cipher] is now authorized for one operation. */
    class Success(val cipher: Cipher) : BiometricOutcome

    /** The user (or the system, for example on screen off) dismissed the prompt. */
    data object Cancelled : BiometricOutcome

    /** Lockout or another error; [message] is Android's own text, fit to show as is. */
    class Error(val code: Int, val message: String) : BiometricOutcome
}

/**
 * Shows the fingerprint prompt bound to [cipher] and waits for the result. Strong biometrics only:
 * no device PIN/pattern fallback, so the phone's lock screen can't open the vault. Cancelling the
 * coroutine dismisses the prompt.
 */
suspend fun FragmentActivity.authenticate(
    cipher: Cipher,
    title: String,
    subtitle: String?,
    negativeText: String,
): BiometricOutcome = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorized = result.cryptoObject?.cipher
                if (cont.isActive) {
                    cont.resume(
                        if (authorized != null) BiometricOutcome.Success(authorized)
                        else BiometricOutcome.Error(-1, "Fingerprint unlock failed"),
                    )
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                cont.resume(
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        -> BiometricOutcome.Cancelled
                        else -> BiometricOutcome.Error(errorCode, errString.toString())
                    },
                )
            }
            // onAuthenticationFailed (finger not recognised): the prompt stays up and says so.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}

/** The hosting activity, needed for [BiometricPrompt]. */
fun Context.findFragmentActivity(): FragmentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    error("No FragmentActivity")
}
