package com.telx.app.ui.settings

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.ui.common.Helpers
import java.security.MessageDigest

/**
 * App lock: PIN gate shown when Prefs.appLockPin is set (SHA-256).
 * Re-locks after the app has been in background for 5+ minutes.
 */
object AppLock {
    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }

    fun isLocked(): Boolean =
        Prefs.appLockPin.isNotEmpty() &&
                System.currentTimeMillis() - Prefs.lastUnlockedAt > 5 * 60 * 1000L

    fun unlockSuccess() { Prefs.lastUnlockedAt = System.currentTimeMillis() }
}

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        val input = findViewById<TextInputEditText>(R.id.lock_pin_input)
        val err = findViewById<TextView>(R.id.lock_error)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { check(input, err); true } else false
        }
        findViewById<TextView>(R.id.lock_ok).setOnClickListener { check(input, err) }
        findViewById<TextView>(R.id.lock_forgot).setOnClickListener {
            // PIN is local-only; forgetting requires clearing app data
            AlertDialog.Builder(this)
                .setTitle("Forgot PIN?")
                .setMessage("The PIN is stored only on this device. To reset it you must clear the app's storage (Android Settings → Apps → TelX → Storage → Clear data).")
                .setPositiveButton("OK", null).show()
        }
        // v7: passkey-style biometric quick unlock (enabled in Privacy & Security)
        val btnBio = findViewById<TextView>(R.id.lock_biometric)
        val canBio = com.telx.app.data.Prefs.quickLogin &&
            androidx.biometric.BiometricManager.from(this)
                .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        if (canBio) {
            btnBio.visibility = android.view.View.VISIBLE
            btnBio.setOnClickListener { askBiometric() }
            btnBio.postDelayed({ askBiometric() }, 350)   // gentle auto-prompt
        }
    }

    /** v7: unlock the app with the device fingerprint / face. */
    private fun askBiometric() {
        val prompt = androidx.biometric.BiometricPrompt(this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    AppLock.unlockSuccess()
                    Helpers.toast(this@LockActivity, "Unlocked")
                    finish()
                }
            })
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TelX")
            .setSubtitle("Confirm your fingerprint to continue")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) {}
    }

    private fun check(input: EditText, err: TextView) {
        val pin = input.text?.toString() ?: ""
        if (pin.length < 4) { err.text = "Enter your PIN (4+ digits)"; return }
        if (AppLock.hash(pin) == Prefs.appLockPin) {
            AppLock.unlockSuccess()
            Helpers.toast(this, "Unlocked")
            finish()
        } else err.text = "Wrong PIN"
    }
}
