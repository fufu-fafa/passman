package app.passman

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.passman.ui.NoKeyboardLearning
import app.passman.ui.PassManRoot
import app.passman.ui.PassManTheme

/** A FragmentActivity (a ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val app get() = application as PassManApp

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore saved UI state: it could hold entry text from before a lock.
        super.onCreate(null)
        // SEC-10: no app-switcher previews. Release builds also block screenshots and recordings.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        if (resources.getBoolean(R.bool.secure_window)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // No system autofill prompts to save or fill PassMan's own fields.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Dark-only theme: always light system-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PassManTheme {
                NoKeyboardLearning {
                    PassManRoot(
                        vault = app.vault,
                        clipboard = app.clipboard,
                        onCloseApp = {
                            app.vault.lock()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        app.autoLock.onInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        app.autoLock.onInteraction()
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        app.autoLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        app.autoLock.onBackground()
    }

    override fun onDestroy() {
        // SEC-5: lock when the app closes.
        if (isFinishing) app.vault.lock()
        super.onDestroy()
    }
}
