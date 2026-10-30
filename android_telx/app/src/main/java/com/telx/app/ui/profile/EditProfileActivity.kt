package com.telx.app.ui.profile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.data.optStringOrNull
import com.telx.app.net.Api
import com.telx.app.ui.MainActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditProfileActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var checkJob: Job? = null
    private var avatarUri: Uri? = null
    private var initialUsername = ""
    // v7: profile music
    private var musicRel: String? = null
    private var musicTitle: String = ""
    private var musicChanged = false

    private lateinit var avatar: AvatarView
    private lateinit var cameraBadge: ImageView
    private lateinit var tilUsername: TextInputLayout
    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etBio: TextInputEditText
    private lateinit var checkIcon: ImageView
    private lateinit var btnSave: MaterialButton
    private lateinit var musicRow: LinearLayout
    private lateinit var musicTitleView: TextView
    private lateinit var musicClear: ImageView

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        avatarUri = Uri.parse("file://$path")
        avatar.bind("me", avatarUri.toString(), Helpers.colorFor(Prefs.myId))
    }

    /** v7: pick an audio file to become my profile music. */
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this@EditProfileActivity)
            .setMessage("Uploading track…")
            .setCancelable(false).create()
        dlg.show()
        ui.launch {
            try {
                val f = withContext(Dispatchers.IO) { copyAudioToCache(uri) }
                    ?: throw IllegalStateException("Couldn't read the file")
                val up = withContext(Dispatchers.IO) { Api.upload("voice", f) }
                f.delete()
                musicRel = up.optString("rel")
                musicChanged = true
                // default title = original file name without extension
                if (musicTitle.isEmpty()) musicTitle = (up.optString("name"))
                    .substringBeforeLast('.').ifEmpty { "My tune" }
                askMusicTitle()
            } catch (e: Exception) {
                Helpers.toast(this@EditProfileActivity, e.message ?: "Upload failed")
            } finally {
                dlg.dismiss()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)
        setSupportActionBar(findViewById<Toolbar>(R.id.edit_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Edit Profile"

        avatar = findViewById(R.id.edit_avatar)
        cameraBadge = findViewById(R.id.edit_camera)
        tilUsername = findViewById(R.id.edit_til_username)
        etFirst = findViewById(R.id.edit_first)
        etLast = findViewById(R.id.edit_last)
        etUsername = findViewById(R.id.edit_username)
        etBio = findViewById(R.id.edit_bio)
        checkIcon = findViewById(R.id.edit_username_check)
        btnSave = findViewById(R.id.btn_save)
        musicRow = findViewById(R.id.edit_music_row)
        musicTitleView = findViewById(R.id.edit_music_title)
        musicClear = findViewById(R.id.edit_music_clear)

        etFirst.setText(Prefs.myName.substringBefore(" "))
        etLast.setText(Prefs.myName.substringAfter(" ", ""))
        etUsername.setText(Prefs.myUsername)
        initialUsername = Prefs.myUsername

        // v7: existing profile music (if any)
        if (!Prefs.myProfileMusicTitle.isNullOrEmpty()) {
            musicTitle = Prefs.myProfileMusicTitle ?: ""
            musicRel = Prefs.myProfileMusicRel
            musicTitleView.text = "🎵 $musicTitle"
            musicClear.visibility = View.VISIBLE
        } else musicTitleView.text = "Set profile music"
        musicRow.setOnClickListener { pickMusic.launch(arrayOf("audio/*")) }
        musicClear.setOnClickListener {
            musicRel = null; musicTitle = ""; musicChanged = true
            musicTitleView.text = "Set profile music"
            musicClear.visibility = View.GONE
            Helpers.toast(this, "Music will be removed on save")
        }

        avatar.bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
        avatar.setOnClickListener { cropLauncher.launch(Intent(this, com.telx.app.ui.common.CropActivity::class.java).putExtra("mode", "circle")) }
        cameraBadge.setOnClickListener { cropLauncher.launch(Intent(this, com.telx.app.ui.common.CropActivity::class.java).putExtra("mode", "circle")) }

        etUsername.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = scheduleCheck(s?.toString()?.trim() ?: "")
            override fun beforeTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnSave.setOnClickListener { save() }
    }

    private fun scheduleCheck(u: String) {
        checkJob?.cancel()
        if (u == initialUsername || u.isEmpty()) { checkIcon.visibility = View.GONE; return }
        checkJob = ui.launch {
            delay(450)
            try {
                val (available, _) = withContext(Dispatchers.IO) { Api.checkUsername(u) }
                checkIcon.setImageResource(if (available) R.drawable.ic_check_circle else R.drawable.ic_cancel)
                checkIcon.setColorFilter(ContextCompat.getColor(this@EditProfileActivity,
                    if (available) R.color.online_green else R.color.error_red))
                checkIcon.visibility = View.VISIBLE
                tilUsername.error = if (available) null else "Username taken"
            } catch (_: Exception) { checkIcon.visibility = View.GONE }
        }
    }

    /** Ask the user for a display title for the chosen track. */
    private fun askMusicTitle() {
        val et = android.widget.EditText(this)
        et.setText(musicTitle)
        et.hint = "Track title"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Profile music 🎵")
            .setMessage("Give this track a name (shown on your profile):")
            .setView(et)
            .setPositiveButton("Keep") { _, _ ->
                musicTitle = et.text.toString().trim().ifEmpty { "My tune" }
                musicTitleView.text = "🎵 $musicTitle"
                musicClear.visibility = View.VISIBLE
                Helpers.toast(this, "Saved when you tap Save")
            }
            .setNegativeButton("Discard") { _, _ ->
                musicRel = null; musicChanged = false
                musicTitleView.text = "Set profile music"
                musicClear.visibility = View.GONE
            }
            .show()
    }

    private fun save() {
        val first = etFirst.text?.toString()?.trim() ?: ""
        val last = etLast.text?.toString()?.trim() ?: ""
        val username = etUsername.text?.toString()?.trim() ?: ""
        val bio = etBio.text?.toString()?.trim() ?: ""
        if (first.length < 2) { Helpers.toast(this, "First name is too short"); return }
        if (tilUsername.error != null) return
        btnSave.isEnabled = false
        ui.launch {
            try {
                var avatarRel: String? = null
                avatarUri?.let { uri ->
                    val f = copyToCache(uri)
                    if (f != null) {
                        val up = withContext(Dispatchers.IO) { Api.upload("avatar", f) }
                        avatarRel = up.optString("rel")
                    }
                }
                val j = withContext(Dispatchers.IO) {
                    Api.profileUpdate(first, last, bio, username, avatarRel = avatarRel,
                        profileMusic = if (musicChanged) (musicRel ?: "") else null,
                        profileMusicTitle = if (musicChanged) musicTitle else null)
                }
                val u = j.optJSONObject("user")
                if (u != null) {
                    Prefs.myName = u.optString("name")
                    Prefs.myUsername = u.optString("username")
                    Prefs.myAvatar = u.optStringOrNull("avatar")
                    Prefs.privacyLock = u.optBoolean("privacy_lock")
                    Prefs.myProfileMusicTitle = u.optString("profile_music_title", "")
                    Prefs.myProfileMusicRel = u.optStringOrNull("profile_music")
                }
                Helpers.toast(this@EditProfileActivity, "Profile saved")
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                Helpers.toast(this@EditProfileActivity, e.message ?: "Save failed")
                btnSave.isEnabled = true
            }
        }
    }

    private fun copyToCache(uri: Uri): File? = try {
        val f = File(cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    } catch (e: Exception) { null }

    /** v7: copy the picked audio into cache with its real extension (keeps mp3/m4a/ogg playable). */
    private fun copyAudioToCache(uri: Uri): File? = try {
        var ext = ""
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (ni >= 0 && c.moveToFirst()) ext = c.getString(ni).substringAfterLast('.', "")
        }
        if (ext.length > 5) ext = ""
        val f = File(cacheDir, "music_${System.currentTimeMillis()}" + (if (ext.isNotEmpty()) ".$ext" else ""))
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.length() == 0L) null else f
    } catch (e: Exception) { null }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
