package com.telx.app.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.data.BackupHelper
import com.telx.app.data.Crypto
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud Backup — end-to-end encrypted backup of profile, contacts and chat history.
 * The password is never sent to the server; a wrong password simply cannot decrypt.
 */
class BackupActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private lateinit var statusLine: TextView
    private lateinit var sizeLine: TextView
    private lateinit var btnCreate: MaterialButton
    private lateinit var btnRestore: MaterialButton
    private lateinit var btnDelete: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        setSupportActionBar(findViewById<Toolbar>(R.id.backup_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cloud Backup"

        statusLine = findViewById(R.id.backup_status)
        sizeLine = findViewById(R.id.backup_size)
        btnCreate = findViewById(R.id.btn_backup_create)
        btnRestore = findViewById(R.id.btn_backup_restore)
        btnDelete = findViewById(R.id.btn_backup_delete)

        findViewById<TextView>(R.id.backup_note).text =
            "Backups are encrypted on this device with AES-256-GCM using your password. " +
            "The server stores only unreadable ciphertext, so nobody — not even the server — can read your backup without the password."

        btnCreate.setOnClickListener { askPassword("Create backup") { createBackup(it) } }
        btnRestore.setOnClickListener { askPassword("Restore backup") { restoreBackup(it) } }
        btnDelete.setOnClickListener { confirmDelete() }
        refreshMeta()
    }

    private fun refreshMeta() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.backupMeta() }
                if (j.optBoolean("exists")) {
                    statusLine.text = "Backup stored on server"
                    val ts = j.optLong("created_ts", 0)
                    statusLine.append(
                        if (ts > 0) "\nLast backup: " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US)
                            .format(Date(ts * 1000)) else "")
                    sizeLine.text = Helpers.fileSize(j.optLong("size", 0))
                    btnRestore.isEnabled = true
                    btnDelete.isEnabled = true
                } else {
                    statusLine.text = "No backup yet"
                    sizeLine.text = "—"
                    btnRestore.isEnabled = false
                    btnDelete.isEnabled = false
                }
            } catch (e: Exception) {
                statusLine.text = "Couldn't reach the server"
            }
        }
    }

    private fun askPassword(title: String, action: (String) -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_password, null)
        val input = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_dialog_password)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Enter an encryption password (min 6 characters). Keep it safe — it cannot be recovered.")
            .setView(v)
            .setPositiveButton("Continue") { _, _ ->
                val p = input.text?.toString() ?: ""
                if (p.length < 6) Helpers.toast(this, "Password must be at least 6 characters")
                else action(p)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createBackup(password: String) {
        setBusy(true, "Building backup…")
        ui.launch {
            try {
                val payload = withContext(Dispatchers.IO) { BackupHelper.buildPayload() }
                setBusy(true, "Encrypting…")
                val blob = withContext(Dispatchers.IO) { Crypto.encrypt(payload, password) }
                setBusy(true, "Uploading…")
                withContext(Dispatchers.IO) { Api.backupSave(blob) }
                Helpers.toast(this@BackupActivity, "Backup saved (%s)".format(Helpers.fileSize(blob.length.toLong())))
                refreshMeta()
            } catch (e: Exception) {
                Helpers.toast(this@BackupActivity,
                    if (e is ApiErr && e.code == "too_large") "Backup too large — reduce history and retry"
                    else "Backup failed: ${e.message ?: "error"}")
            } finally {
                setBusy(false, "")
            }
        }
    }

    private fun restoreBackup(password: String) {
        setBusy(true, "Downloading…")
        ui.launch {
            try {
                val r = withContext(Dispatchers.IO) { BackupHelper.restore(password) }
                Helpers.toast(this@BackupActivity, "Backup restored — contacts re-added")
                val msg = buildString {
                    append("Restored backup")
                    if (r.created > 0) append(" from ").append(
                        SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(r.created * 1000)))
                    append("\n\nContacts: ").append(r.contacts.size)
                    append("\nChats: ").append(r.chats.size)
                    append("\n\nChats are available read-only:")
                }
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle("Restore complete")
                    .setMessage(msg)
                    .setPositiveButton("View chats") { _, _ ->
                        startActivity(android.content.Intent(this@BackupActivity,
                            BackupViewerActivity::class.java).putExtra("password", password))
                    }
                    .setNegativeButton("Close", null)
                    .show()
                refreshMeta()
            } catch (e: IllegalArgumentException) {
                Helpers.toast(this@BackupActivity, e.message ?: "Wrong password")
            } catch (e: Exception) {
                Helpers.toast(this@BackupActivity, "Restore failed: ${e.message ?: "error"}")
            } finally {
                setBusy(false, "")
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete backup")
            .setMessage("Remove the encrypted backup from the server?")
            .setPositiveButton("Delete") { _, _ ->
                ui.launch {
                    try { withContext(Dispatchers.IO) { Api.backupDelete() }; refreshMeta() }
                    catch (_: Exception) {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBusy(busy: Boolean, text: String) {
        btnCreate.isEnabled = !busy
        btnRestore.isEnabled = !busy
        btnDelete.isEnabled = !busy
        if (busy && text.isNotEmpty()) statusLine.text = text
        else refreshMeta()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
