package com.telx.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.telx.app.R
import com.telx.app.data.Event
import com.telx.app.data.Prefs
import com.telx.app.net.Events
import com.telx.app.ui.chats.ChatsFragment
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.contacts.ContactsFragment
import com.telx.app.ui.contacts.SearchActivity
import com.telx.app.ui.profile.ProfileFragment
import com.telx.app.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), Events.Listener {

    private lateinit var nav: BottomNavigationView
    private val chatsFragment by lazy { ChatsFragment() }
    private val contactsFragment by lazy { ContactsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val profileFragment by lazy { ProfileFragment() }

    private var pendingStoryVideo = false
    private val pickStory = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showStoryDurationDialog(uri)
        }
    }
    private val pickWallpaper = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Prefs.wallpaper = "uri:$uri"
            com.telx.app.ui.chat.WallpaperHelper.version++
            Helpers.toast(this, "Wallpaper set for all chats")
        }
    }

    fun pickStoryMedia(isVideo: Boolean) {
        pendingStoryVideo = isVideo
        pickStory.launch(arrayOf(if (isVideo) "video/*" else "image/*"))
    }

    fun pickWallpaper() {
        pickWallpaper.launch(arrayOf("image/*"))
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
        val ui = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        Helpers.toast(this, "Uploading story…")
        ui.launch {
            try {
                val f = copyToCache(uri)
                if (f == null) { Helpers.toast(this@MainActivity, "Could not read file"); return@launch }
                val kind = if (pendingStoryVideo) "story" else "story"
                val up = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.telx.app.net.Api.upload(kind, f)
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
        nav = findViewById(R.id.bottom_nav)
        nav.background = null
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> show(chatsFragment)
                R.id.nav_contacts -> show(contactsFragment)
                R.id.nav_settings -> show(settingsFragment)
                R.id.nav_profile -> show(profileFragment)
            }
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_chats
        } else {
            // re-attach the previously selected tab
            when (nav.selectedItemId) {
                R.id.nav_contacts -> show(contactsFragment)
                R.id.nav_settings -> show(settingsFragment)
                R.id.nav_profile -> show(profileFragment)
                else -> show(chatsFragment)
            }
        }
        setNavBadge(intent?.getIntExtra("unread_total", 0) ?: 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setNavBadge(intent.getIntExtra("unread_total", 0))
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

    fun setNavBadge(total: Int) {
        val badge: BadgeDrawable = nav.getOrCreateBadge(R.id.nav_chats)
        if (total > 0) {
            badge.isVisible = true
            badge.number = total
        } else {
            badge.isVisible = false
            badge.clearNumber()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> { startActivity(Intent(this, SearchActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        Events.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Events.removeListener(this)
    }

    override fun onEvent(e: Event) {
        if (e.type == "_unread") {
            runOnUiThread { setNavBadge(e.data.optInt("unread_total", 0)) }
            return
        }
        if (e.type == "message_new" || e.type == "message_read" || e.type == "chat_created") {
            runOnUiThread {
                val t = e.data.optInt("unread_total", -1)
                if (t >= 0) setNavBadge(t)
            }
        }
    }
}
