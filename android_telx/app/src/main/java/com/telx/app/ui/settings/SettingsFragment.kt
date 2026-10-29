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
        bindRow(v, R.id.row_font_size) { showFontSizeDialog() }
        bindRow(v, R.id.row_autodownload) { showAutoDownloadDialog() }
        bindRow(v, R.id.row_wallpaper) {
            startActivity(Intent(requireContext(), com.telx.app.ui.chat.WallpaperActivity::class.java))
        }
        // v6 rows
        bindRow(v, R.id.row_theme_editor) { startActivity(Intent(requireContext(), ThemeEditorActivity::class.java)) }
        bindRow(v, R.id.row_hidden) { startActivity(Intent(requireContext(), HiddenChatsActivity::class.java)) }
        bindRow(v, R.id.row_storage) { startActivity(Intent(requireContext(), StorageActivity::class.java)) }
        bindRow(v, R.id.row_icons) { showIconPicker() }
        bindRow(v, R.id.row_selfdestruct) { showSelfDestructDialog() }
        bindRow(v, R.id.row_browser) {
            Prefs.useInAppBrowser = !Prefs.useInAppBrowser
            Helpers.toast(context, if (Prefs.useInAppBrowser) "Links now open inside TelX"
                else "Links now open in the system browser")
        }
        bindRow(v, R.id.row_materialyou) {
            Prefs.materialYou = !Prefs.materialYou
            (activity as? MainActivity)?.applyTheme()
            Helpers.toast(context, "Material You " +
                    (if (Prefs.materialYou) "enabled (Android 12+)" else "disabled"))
        }
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

    /** v6: launcher icon picker — switch between the shipped icon variants. */
    private fun showIconPicker() {
        val labels = arrayOf("Telegram Blue (default)", "Dark Night", "Teal")
        val keys = arrayOf("default", "dark", "teal")
        val aliases = arrayOf("com.telx.app.AliasDefault", "com.telx.app.AliasDark", "com.telx.app.AliasTeal")
        val pm = requireContext().packageManager
        AlertDialog.Builder(requireContext())
            .setTitle("App icon")
            .setSingleChoiceItems(labels, keys.indexOf(Prefs.launcherIcon)) { d, which ->
                d.dismiss()
                for ((i, alias) in aliases.withIndex()) {
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(requireContext(), alias),
                        if (i == which) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP)
                }
                Prefs.launcherIcon = keys[which]
                Helpers.toast(context, "Icon changed — the launcher may take a moment to refresh")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** v6: account self-destruct timer (like Telegram's inactive account deletion). */
    private fun showSelfDestructDialog() {
        val labels = arrayOf("Off", "6 months", "12 months", "18 months")
        val months = intArrayOf(0, 6, 12, 18)
        AlertDialog.Builder(requireContext())
            .setTitle("Delete my account if inactive for")
            .setSingleChoiceItems(labels, -1) { d, which ->
                d.dismiss()
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.setSelfDestruct(months[which]) }
                        Helpers.toast(context, if (which == 0) "Self-destruct disabled" else "Set to ${labels[which]}")
                    } catch (e: Exception) { Helpers.toast(context, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val options = arrayOf("System default", "Light", "Dark", "Auto by time (10 PM – 7 AM)")
        AlertDialog.Builder(requireContext())
            .setTitle("Appearance")
            .setSingleChoiceItems(options, Prefs.theme) { d, which ->
                Prefs.theme = which
                (activity as? MainActivity)?.applyTheme()
                d.dismiss()
            }
            .show()
    }

    /** Telegram-style message text size picker with live preview. */
    private fun showFontSizeDialog() {
        val labels = arrayOf("Small", "Default", "Medium", "Large", "Extra large")
        val scales = floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f, 1.45f)
        var current = scales.indexOfFirst { kotlin.math.abs(it - Prefs.chatFontScale) < 0.01f }
        if (current < 0) current = 1
        val preview = android.widget.TextView(requireContext()).apply {
            text = """Sample: Hello! How are you today? 🙂
Big enough to read comfortably."""
            setPadding(Helpers.dp(requireContext(), 24), Helpers.dp(requireContext(), 12), Helpers.dp(requireContext(), 24), Helpers.dp(requireContext(), 12))
            textSize = 15.5f
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Message Text Size")
            .setCustomTitle(preview)
            .setSingleChoiceItems(labels, current) { d, which ->
                current = which
                Prefs.chatFontScale = scales[which]
                preview.textSize = 15.5f * scales[which]
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Auto-download settings: network type + max size. */
    private fun showAutoDownloadDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_autodownload, null)
        val swWifi = v.findViewById<SwitchMaterial>(R.id.sw_auto_wifi)
        val swMobile = v.findViewById<SwitchMaterial>(R.id.sw_auto_mobile)
        val etMax = v.findViewById<android.widget.EditText>(R.id.et_auto_max_mb)
        swWifi.isChecked = Prefs.autoDownloadWifi
        swMobile.isChecked = Prefs.autoDownloadMobile
        etMax.setText(Prefs.autoDownloadMaxMb.toString())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Automatic Media Download")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                Prefs.autoDownloadWifi = swWifi.isChecked
                Prefs.autoDownloadMobile = swMobile.isChecked
                Prefs.autoDownloadMaxMb = etMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 200) ?: 20
            }
            .setNegativeButton("Cancel", null)
            .show()
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
