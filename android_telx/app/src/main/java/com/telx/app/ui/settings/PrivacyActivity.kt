package com.telx.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Privacy & Security: last seen / phone / photo visibility, app lock PIN. */
class PrivacyActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private lateinit var lastSeen: SwitchMaterial
    private lateinit var phoneSw: SwitchMaterial
    private lateinit var photoSw: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        setSupportActionBar(findViewById<Toolbar>(R.id.privacy_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Privacy & Security"

        lastSeen = findViewById(R.id.sw_last_seen_everybody)
        phoneSw = findViewById(R.id.sw_phone_visible)
        photoSw = findViewById(R.id.sw_photo_visible)

        load()
        lastSeen.setOnCheckedChangeListener { _, c -> save(0, if (c) 0 else 2) }
        phoneSw.setOnCheckedChangeListener { _, c -> save(1, if (c) 0 else 2) }
        photoSw.setOnCheckedChangeListener { _, c -> save(2, if (c) 0 else 2) }

        findViewById<TextView>(R.id.row_applock).setOnClickListener { showPinDialog() }
        findViewById<TextView>(R.id.row_blocked).setOnClickListener {
            startActivity(android.content.Intent(this, com.telx.app.ui.contacts.BlockedUsersActivity::class.java))
        }
    }

    private fun load() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.profileGet() }
                val u = j.optJSONObject("user") ?: return@launch
                lastSeen.isChecked = u.optInt("privacy_last_seen", 0) == 0
                phoneSw.isChecked = u.optInt("privacy_phone", 0) == 0
                photoSw.isChecked = u.optInt("privacy_photo", 0) == 0
            } catch (_: Exception) {}
        }
    }

    private fun save(which: Int, value: Int) {
        ui.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (which) {
                        0 -> Api.profileUpdate(privacyLastSeen = value)
                        1 -> Api.profileUpdate(privacyPhone = value)
                        else -> Api.profileUpdate(privacyPhoto = value)
                    }
                }
            } catch (e: Exception) {
                Helpers.toast(this@PrivacyActivity, if (e is ApiErr) e.message else "Failed")
            }
        }
    }

    private fun showPinDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_password, null)
        val input = v.findViewById<EditText>(R.id.et_dialog_password)
        AlertDialog.Builder(this)
            .setTitle("App Lock PIN")
            .setMessage(if (com.telx.app.data.Prefs.appLockPin.isNotEmpty())
                "Enter a NEW PIN to change it, or leave empty and press Remove to disable."
            else "Choose a PIN (4+ digits). You'll be asked for it when opening TelX.")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text?.toString() ?: ""
                if (pin.length < 4) { Helpers.toast(this, "PIN must be 4+ digits"); return@setPositiveButton }
                Prefs_set(pin)
            }
            .setNeutralButton("Remove") { _, _ -> Prefs_set("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun Prefs_set(pin: String) {
        com.telx.app.data.Prefs.appLockPin = if (pin.isEmpty()) "" else AppLock.hash(pin)
        Helpers.toast(this, if (pin.isEmpty()) "App lock disabled" else "App lock enabled")
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
