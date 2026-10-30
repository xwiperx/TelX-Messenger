package com.telx.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.telx.app.R
import com.telx.app.data.Event
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.chats.ChatsFragment
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.contacts.ContactsFragment
import com.telx.app.ui.contacts.SearchActivity
import com.telx.app.ui.settings.SettingsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v7 Liquid Glass main screen (Telegram-latest design):
 *  - drawer menu REMOVED, replaced by a translucent glass bottom bar with
 *    4 tabs: Chats / Contacts / Settings / Profile
 *  - all actions moved to the ⋮ three-dot overflow menu (top-right)
 *  - stories strip + chat list live in ChatsFragment
 */
class MainActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val chatsFragment by lazy { ChatsFragment() }
    private val contactsFragment by lazy { ContactsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var shownTag: String? = null

    private var pendingStoryVideo = false

    private val pickStory = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showStoryOptionsDialog(uri)
        }
    }

    fun pickStoryMedia(isVideo: Boolean) {
        pendingStoryVideo = isVideo
        pickStory.launch(arrayOf(if (isVideo) "video/*" else "image/*"))
    }

    /** v7: duration + Live mode + thematic album for the new story. */
    private fun showStoryOptionsDialog(uri: Uri) {
        val pad = Helpers.dp(this, 22)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etAlbum = EditText(this).apply {
            hint = "Album (optional, e.g. Travel)"
        }
        val etLive = EditText(this).apply {
            hint = "Type LIVE for a live story"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(etAlbum)
        box.addView(etLive)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Story options")
            .setView(box)
            .setPositiveButton("Continue") { _, _ ->
                val album = etAlbum.text?.toString()?.trim().orEmpty()
                val live = etLive.text?.toString()?.trim().equals("live", ignoreCase = true)
                showStoryDurationDialog(uri, live, album)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStoryDurationDialog(uri: Uri, live: Boolean, album: String) {
        val options = arrayOf("15 seconds", "30 seconds", "45 seconds", "60 seconds")
        val durations = intArrayOf(15, 30, 45, 60)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (live) "Go LIVE with this story?" else "Story visibility duration")
            .setItems(options) { _, which ->
                uploadStory(uri, durations[which], live, album)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadStory(uri: Uri, duration: Int, live: Boolean, album: String) {
        Helpers.toast(this, "Uploading story…")
        ui.launch {
            try {
                val f = copyToCache(uri)
                if (f == null) { Helpers.toast(this@MainActivity, "Could not read file"); return@launch }
                val up = withContext(Dispatchers.IO) {
                    Api.upload("story", f)
                }
                withContext(Dispatchers.IO) {
                    Api.storyUpload(
                        up.optString("rel"),
                        if (up.has("thumb") && !up.isNull("thumb")) up.optString("thumb_rel") else null,
                        pendingStoryVideo, duration,
                        isLive = live,
                        album = album.ifEmpty { null })
                }
                f.delete()
                Helpers.toast(this@MainActivity, if (live) "You are LIVE 🎙" else "Story published")
            } catch (e: Exception) {
                Helpers.toast(this@MainActivity, "Story upload failed")
            }
        }
    }

    private fun copyToCache(uri: Uri): java.io.File? = try {
        val f = java.io.File(cacheDir, "story_${System.currentTimeMillis()}")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.exists() && f.length() > 0) f else null
    } catch (e: Exception) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        if (Prefs.token.isEmpty()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            handleMenu(item.itemId)
            true
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bottom_chats -> show(chatsFragment, "chats")
                R.id.nav_bottom_contacts -> show(contactsFragment, "contacts")
                R.id.nav_bottom_settings -> show(settingsFragment, "settings")
                R.id.nav_bottom_profile -> {
                    startActivity(Intent(this, com.telx.app.ui.profile.UserProfileActivity::class.java)
                        .putExtra("user_id", Prefs.myId))
                    false // don't keep the profile tab visually selected
                }
                else -> false
            }
            true
        }
        if (savedInstanceState == null) show(chatsFragment, "chats")
    }

    /** ⋮ three-dot menu actions (drawer replacement). */
    private fun handleMenu(id: Int) {
        when (id) {
            R.id.action_search -> startActivity(Intent(this, SearchActivity::class.java))
            R.id.main_new_group -> startActivity(Intent(this, com.telx.app.ui.contacts.PickUsersActivity::class.java))
            R.id.main_new_channel -> startActivity(Intent(this, com.telx.app.ui.chats.CreateChannelActivity::class.java))
            R.id.main_new_secret -> startActivity(Intent(this, com.telx.app.ui.contacts.PickSecretActivity::class.java))
            R.id.main_new_message -> startActivity(Intent(this, SearchActivity::class.java))
            R.id.main_new_community -> showCreateCommunityDialog()
            R.id.main_saved -> openSavedMessages()
            R.id.main_mark_read -> markAllRead()
            R.id.main_add_account -> addAccount()
            R.id.main_switch_account -> switchAccount()
            R.id.main_night -> {
                Prefs.theme = if (Prefs.theme == 2) 1 else 2
                applyTheme()
            }
        }
    }

    /** v7: create a community (umbrella for groups + channels). */
    private fun showCreateCommunityDialog() {
        val pad = Helpers.dp(this, 22)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etTitle = EditText(this).apply { hint = "Community name" }
        val etDesc = EditText(this).apply { hint = "Description (optional)" }
        val etUser = EditText(this).apply { hint = "Public link (optional, e.g. telx)" }
        box.addView(etTitle); box.addView(etDesc); box.addView(etUser)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Community")
            .setMessage("Link your groups and channels under one umbrella.")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val title = etTitle.text?.toString()?.trim().orEmpty()
                if (title.length < 3) { Helpers.toast(this, "Name must be 3+ characters"); return@setPositiveButton }
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) {
                            Api.communityCreate(title, etDesc.text?.toString()?.trim().orEmpty(),
                                etUser.text?.toString()?.trim().removePrefix("@").orEmpty())
                        }
                        Helpers.toast(this@MainActivity, "Community created")
                        startActivity(Intent(this@MainActivity,
                            com.telx.app.ui.community.CommunityActivity::class.java)
                            .putExtra("chat_id", j.optInt("chat_id")))
                    } catch (e: Exception) {
                        Helpers.toast(this@MainActivity, if (e is com.telx.app.data.ApiErr) e.message else "Failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markAllRead() {
        ui.launch {
            try {
                withContext(Dispatchers.IO) { Api.markAllRead() }
                Helpers.toast(this@MainActivity, "All chats marked as read")
                (supportFragmentManager.findFragmentById(R.id.fragment_container) as? ChatsFragment)?.load()
            } catch (e: Exception) { Helpers.toast(this@MainActivity, "Failed") }
        }
    }

    private fun openSavedMessages() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.chatOpenSaved() }
                startActivity(Intent(this@MainActivity, com.telx.app.ui.chat.ChatActivity::class.java)
                    .putExtra("chat_id", j.getInt("chat_id")))
            } catch (e: Exception) { Helpers.toast(this@MainActivity, "Couldn't open Saved Messages") }
        }
    }

    // ---------- multi-account (Telegram-like) ----------

    private fun saveAccountSnapshot() {
        try { com.telx.app.ui.common.AccountManager.saveCurrent() } catch (_: Exception) {}
    }

    private fun addAccount() {
        saveAccountSnapshot()
        startActivity(Intent(this, AuthActivity::class.java).putExtra("add_account", true))
    }

    private fun switchAccount() {
        val accs = try { com.telx.app.ui.common.AccountManager.list() } catch (_: Exception) { ArrayList() }
        val others = accs.filter { it.optInt("id") != Prefs.myId }
        if (others.isEmpty()) {
            Helpers.toast(this, "No other account yet — use Add Account")
            return
        }
        val labels = others.map { "${it.optString("name")} (@${it.optString("username")})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Switch account")
            .setItems(labels) { _, which ->
                val o = others[which]
                com.telx.app.ui.common.AccountManager.switch(o.optInt("id"), Prefs.token, this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun applyTheme() {
        when (Prefs.theme) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            3 -> {
                // Auto by time: dark 22:00 – 07:00, light otherwise
                val cal = java.util.Calendar.getInstance()
                val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                AppCompatDelegate.setDefaultNightMode(
                    if (h >= 22 || h < 7) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        // v6: Material You dynamic colors (Android 12+) unless the user disabled them
        if (Prefs.materialYou) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
    }

    private fun show(f: Fragment, tag: String) {
        if (shownTag == tag) return
        shownTag = tag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f, tag)
            .commitAllowingStateLoss()
    }

    /** kept for ChatsFragment compatibility (drawer header is gone in v7). */
    fun refreshDrawerHeader() {}

    override fun onResume() {
        super.onResume()
        saveAccountSnapshot()
        // re-evaluate timed night mode when returning to the app
        if (Prefs.theme == 3) applyTheme()
        Events.addListener(eventsListener)
        // v6: in-app update check (silent, at most once per version)
        com.telx.app.net.UpdateChecker.check(this)
    }

    override fun onPause() {
        super.onPause()
        Events.removeListener(eventsListener)
    }

    private val eventsListener = object : Events.Listener {
        override fun onEvent(e: Event) {
            if (e.type == "profile_updated") {
                // nothing to redraw on the header anymore — settings refresh on resume
            }
        }
    }
}
