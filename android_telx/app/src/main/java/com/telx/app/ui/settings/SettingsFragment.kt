package com.telx.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.svc.PollService
import com.telx.app.ui.AuthActivity
import com.telx.app.ui.MainActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private lateinit var avatar: AvatarView
    private lateinit var name: TextView
    private lateinit var username: TextView
    private lateinit var lockSwitch: SwitchMaterial

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        avatar = v.findViewById(R.id.settings_avatar)
        name = v.findViewById(R.id.settings_name)
        username = v.findViewById(R.id.settings_username)
        lockSwitch = v.findViewById(R.id.sw_privacy_lock)

        val header = v.findViewById<LinearLayout>(R.id.settings_header)
        header.setOnClickListener { startActivity(Intent(requireContext(), com.telx.app.ui.profile.EditProfileActivity::class.java)) }
        avatar.setOnClickListener { startActivity(Intent(requireContext(), com.telx.app.ui.profile.EditProfileActivity::class.java)) }

        lockSwitch.isChecked = Prefs.privacyLock
        lockSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.privacyLock = checked
            ui.launch {
                try { withContext(Dispatchers.IO) { Api.profileUpdate(privacyLock = checked) } }
                catch (e: Exception) { Helpers.toast(context, "Failed to update privacy setting") }
            }
        }

        bindRow(v, R.id.row_theme) { showThemeDialog() }
        bindRow(v, R.id.row_wallpaper) { showWallpaperDialog() }
        bindRow(v, R.id.row_backup) { startActivity(Intent(requireContext(), BackupActivity::class.java)) }
        bindRow(v, R.id.row_2fa) { show2faDialog() }
        bindRow(v, R.id.row_sessions) { startActivity(Intent(requireContext(), SessionsActivity::class.java)) }
        bindRow(v, R.id.row_password) { showChangePasswordDialog() }
        bindRow(v, R.id.row_blocked) { startActivity(Intent(requireContext(), com.telx.app.ui.contacts.BlockedUsersActivity::class.java)) }
        bindRow(v, R.id.row_delete) { showDeleteDialog() }
        bindRow(v, R.id.row_logout) { confirmLogout() }
        refreshHeader()
    }

    private fun bindRow(v: View, id: Int, action: () -> Unit) {
        v.findViewById<View>(id).setOnClickListener { action() }
    }

    private fun refreshHeader() {
        name.text = Prefs.myName.ifEmpty { "TelX user" }
        username.text = "@${Prefs.myUsername}"
        avatar.bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
        lockSwitch.isChecked = Prefs.privacyLock
    }

    private fun showThemeDialog() {
        val options = arrayOf("System default", "Light", "Dark")
        AlertDialog.Builder(requireContext())
            .setTitle("Appearance")
            .setSingleChoiceItems(options, Prefs.theme) { d, which ->
                Prefs.theme = which
                (activity as? MainActivity)?.applyTheme()
                d.dismiss()
            }
            .show()
    }

    private fun showWallpaperDialog() {
        val options = arrayOf("Default pattern", "Pattern — Dots", "Pattern — Waves",
            "Pattern — Stars", "Choose from gallery", "Reset to default")
        AlertDialog.Builder(requireContext())
            .setTitle("Chat wallpaper")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { Prefs.wallpaper = "pattern:0"; notifyWallpaper() }
                    1 -> { Prefs.wallpaper = "pattern:1"; notifyWallpaper() }
                    2 -> { Prefs.wallpaper = "pattern:2"; notifyWallpaper() }
                    3 -> { Prefs.wallpaper = "pattern:3"; notifyWallpaper() }
                    4 -> {
                        (activity as? MainActivity)?.pickWallpaper()
                    }
                    5 -> { Prefs.wallpaper = null; notifyWallpaper() }
                }
            }
            .show()
    }

    private fun notifyWallpaper() {
        com.telx.app.ui.chat.WallpaperHelper.version++
        Helpers.toast(context, "Wallpaper updated for all chats")
    }

    private fun show2faDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_password, null)
        val input = v.findViewById<TextInputEditText>(R.id.et_dialog_password)
        val checked = booleanArrayOf(false)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Two-Step Verification")
            .setMessage("Enter your password to enable or disable email login codes.")
            .setView(v)
            .setPositiveButton("Enable") { _, _ ->
                val p = input.text?.toString() ?: ""
                if (p.isNotEmpty()) toggle2fa(p, true)
            }
            .setNeutralButton("Disable") { _, _ ->
                val p = input.text?.toString() ?: ""
                if (p.isNotEmpty()) toggle2fa(p, false)
            }
            .setNegativeButton("Cancel", null)
            .show()
        checked[0] = false
    }

    private fun toggle2fa(password: String, enable: Boolean) {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.set2fa(password, enable) }
                Helpers.toast(context, if (j.optBoolean("twofa")) "Two-step verification enabled" else "Two-step verification disabled")
            } catch (e: Exception) {
                Helpers.toast(context, if (e is ApiErr) e.message else "Failed")
            }
        }
    }

    private fun showChangePasswordDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val old = v.findViewById<TextInputEditText>(R.id.et_old_password)
        val new = v.findViewById<TextInputEditText>(R.id.et_new_password)
        AlertDialog.Builder(requireContext())
            .setTitle("Change password")
            .setView(v)
            .setPositiveButton("Change") { _, _ ->
                val o = old.text?.toString() ?: ""
                val n = new.text?.toString() ?: ""
                if (o.isEmpty() || n.length < 6) { Helpers.toast(context, "New password must be 6+ chars"); return@setPositiveButton }
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.changePassword(o, n) }
                        Helpers.toast(context, "Password changed")
                    } catch (e: Exception) { Helpers.toast(context, if (e is ApiErr) e.message else "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_password, null)
        val input = v.findViewById<TextInputEditText>(R.id.et_dialog_password)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete account")
            .setMessage("This permanently deletes your account, messages and stories. This action cannot be undone.")
            .setView(v)
            .setPositiveButton("Delete forever") { _, _ ->
                val p = input.text?.toString() ?: ""
                if (p.isEmpty()) { Helpers.toast(context, "Enter your password"); return@setPositiveButton }
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.deleteAccount(p) }
                        withContext(Dispatchers.IO) { runCatching { Api.logout() } }
                        Prefs.clearSession()
                        PollService.stop(requireContext())
                        startActivity(Intent(requireContext(), AuthActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        activity?.finish()
                    } catch (e: Exception) { Helpers.toast(context, if (e is ApiErr) e.message else "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log out") { _, _ ->
                ui.launch {
                    try { withContext(Dispatchers.IO) { Api.logout() } } catch (_: Exception) {}
                    Prefs.clearSession()
                    PollService.stop(requireContext())
                    startActivity(Intent(requireContext(), AuthActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    activity?.finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
