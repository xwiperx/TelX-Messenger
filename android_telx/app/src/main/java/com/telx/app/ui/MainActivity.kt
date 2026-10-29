package com.telx.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.telx.app.R
import com.telx.app.data.Event
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.chats.ChatsFragment
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.contacts.ContactsFragment
import com.telx.app.ui.contacts.SearchActivity
import com.telx.app.ui.settings.SettingsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telegram-style main screen: toolbar + drawer menu (no bottom tabs),
 * chat list with stories strip lives in ChatsFragment.
 */
class MainActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val chatsFragment by lazy { ChatsFragment() }
    private val contactsFragment by lazy { ContactsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private lateinit var drawer: DrawerLayout
    private lateinit var drawerView: NavigationView
    private var pendingStoryVideo = false

    private val pickStory = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showStoryDurationDialog(uri)
        }
    }

    fun pickStoryMedia(isVideo: Boolean) {
        pendingStoryVideo = isVideo
        pickStory.launch(arrayOf(if (isVideo) "video/*" else "image/*"))
    }

    private fun showStoryDurationDialog(uri: Uri) {
        val options = arrayOf("15 seconds", "30 seconds", "45 seconds", "60 seconds")
        val durations = intArrayOf(15, 30, 45, 60)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Story visibility duration")
            .setItems(options) { _, which ->
                uploadStory(uri, durations[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadStory(uri: Uri, duration: Int) {
        Helpers.toast(this, "Uploading story…")
        ui.launch {
            try {
                val f = copyToCache(uri)
                if (f == null) { Helpers.toast(this@MainActivity, "Could not read file"); return@launch }
                val up = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.telx.app.net.Api.upload("story", f)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.telx.app.net.Api.storyUpload(
                        up.optString("rel"),
                        if (up.has("thumb") && !up.isNull("thumb")) up.optString("thumb_rel") else null,
                        pendingStoryVideo, duration)
                }
                f.delete()
                Helpers.toast(this@MainActivity, "Story published")
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
        drawer = findViewById(R.id.drawer_layout)
        drawerView = findViewById(R.id.drawer_view)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(androidx.core.view.GravityCompat.START) }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { startActivity(Intent(this, SearchActivity::class.java)); true }
                else -> false
            }
        }

        // drawer header = my profile
        val header = drawerView.getHeaderView(0)
        val avatar = header.findViewById<AvatarView>(R.id.drawer_avatar)
        val nameView = header.findViewById<TextView>(R.id.drawer_name)
        val usernameView = header.findViewById<TextView>(R.id.drawer_username)
        fun bindHeader() {
            avatar.bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
            nameView.text = Prefs.myName.ifEmpty { "TelX user" }
            usernameView.text = if (Prefs.myUsername.isNotEmpty()) "@${Prefs.myUsername}" else "tap to add details"
        }
        bindHeader()
        header.setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, com.telx.app.ui.profile.UserProfileActivity::class.java)
                .putExtra("user_id", Prefs.myId))
        }

        val nightSwitch = drawerView.menu.findItem(R.id.nav_night)?.actionView?.findViewById<SwitchMaterial>(R.id.drawer_night_switch)
        nightSwitch?.isChecked = Prefs.theme == 2
        nightSwitch?.setOnCheckedChangeListener { _, checked ->
            Prefs.theme = if (checked) 2 else 1
            applyTheme()
        }

        drawerView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_new_group -> startActivity(Intent(this, com.telx.app.ui.contacts.PickUsersActivity::class.java))
                R.id.nav_new_channel -> startActivity(Intent(this, com.telx.app.ui.chats.CreateChannelActivity::class.java))
                R.id.nav_new_secret -> startActivity(Intent(this, com.telx.app.ui.contacts.PickSecretActivity::class.java))
                R.id.nav_saved -> openSavedMessages()
                R.id.nav_contacts -> { show(contactsFragment); drawer.closeDrawer(androidx.core.view.GravityCompat.START); return@setNavigationItemSelectedListener true }
                R.id.nav_settings -> { show(settingsFragment); drawer.closeDrawer(androidx.core.view.GravityCompat.START); return@setNavigationItemSelectedListener true }
                R.id.nav_chats -> { show(chatsFragment); drawer.closeDrawer(androidx.core.view.GravityCompat.START); return@setNavigationItemSelectedListener true }
                R.id.nav_night -> { nightSwitch?.toggle() }
            }
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            true
        }

        show(chatsFragment)
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

    fun applyTheme() {
        when (Prefs.theme) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commitAllowingStateLoss()
    }

    fun refreshDrawerHeader() {
        val header = drawerView.getHeaderView(0)
        header.findViewById<AvatarView>(R.id.drawer_avatar)
            .bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
        header.findViewById<TextView>(R.id.drawer_name).text = Prefs.myName.ifEmpty { "TelX user" }
        header.findViewById<TextView>(R.id.drawer_username).text =
            if (Prefs.myUsername.isNotEmpty()) "@${Prefs.myUsername}" else "tap to add details"
    }

    override fun onResume() {
        super.onResume()
        refreshDrawerHeader()
        Events.addListener(eventsListener)
    }

    override fun onPause() {
        super.onPause()
        Events.removeListener(eventsListener)
    }

    private val eventsListener = object : Events.Listener {
        override fun onEvent(e: Event) {
            if (e.type == "profile_updated") {
                runOnUiThread { refreshDrawerHeader() }
            }
        }
    }
}
