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
