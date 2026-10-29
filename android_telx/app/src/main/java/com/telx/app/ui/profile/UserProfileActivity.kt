package com.telx.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.data.User
import com.telx.app.data.optStringOrNull
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Other user's profile page. */
class UserProfileActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var user: User? = null

    private lateinit var avatar: AvatarView
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var username: TextView
    private lateinit var bio: TextView
    private lateinit var btnMessage: MaterialButton
    private lateinit var btnContact: MaterialButton
    private lateinit var btnBlock: TextView
    private lateinit var btnEdit: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)
        setSupportActionBar(findViewById<Toolbar>(R.id.up_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        avatar = findViewById(R.id.up_avatar)
        name = findViewById(R.id.up_name)
        status = findViewById(R.id.up_status)
        username = findViewById(R.id.up_username)
        bio = findViewById(R.id.up_bio)
        btnMessage = findViewById(R.id.up_message)
        btnContact = findViewById(R.id.up_contact)
        btnBlock = findViewById(R.id.up_block)
        btnEdit = findViewById(R.id.up_edit)
        avatar.setOnClickListener {
            val url = user?.avatar ?: Prefs.myAvatar
            if (!url.isNullOrEmpty())
                startActivity(Intent(this, com.telx.app.ui.common.PhotoViewerActivity::class.java)
                    .putExtra("url", url))
        }
        btnEdit.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }

        val uid = intent.getIntExtra("user_id", 0)
        load(uid)

        btnMessage.setOnClickListener {
            val u = user ?: return@setOnClickListener
            ui.launch {
                try {
                    val j = withContext(Dispatchers.IO) { Api.chatOpen(u.id) }
                    startActivity(Intent(this@UserProfileActivity, ChatActivity::class.java)
                        .putExtra("chat_id", j.getInt("chat_id")))
                    finish()
                } catch (e: Exception) { Helpers.toast(this@UserProfileActivity, e.message ?: "Failed") }
            }
        }
        btnContact.setOnClickListener {
            val u = user ?: return@setOnClickListener
            ui.launch {
                try {
                    if (u.isContact) {
                        withContext(Dispatchers.IO) { Api.contactRemove(u.id) }
                        Helpers.toast(this@UserProfileActivity, "Removed from contacts")
                    } else {
                        withContext(Dispatchers.IO) { Api.contactAdd(u.id) }
                        Helpers.toast(this@UserProfileActivity, "Added to contacts")
                    }
                    load(u.id)
                } catch (e: Exception) { Helpers.toast(this@UserProfileActivity, e.message ?: "Failed") }
            }
        }
        btnBlock.setOnClickListener {
            val u = user ?: return@setOnClickListener
            ui.launch {
                try {
                    if (u.blockedByMe) {
                        withContext(Dispatchers.IO) { Api.unblock(u.id) }
                        Helpers.toast(this@UserProfileActivity, "Unblocked")
                    } else {
                        withContext(Dispatchers.IO) { Api.block(u.id) }
                        Helpers.toast(this@UserProfileActivity, "Blocked")
                    }
                    load(u.id)
                } catch (e: Exception) { Helpers.toast(this@UserProfileActivity, e.message ?: "Failed") }
            }
        }
    }

    private fun load(uid: Int) {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.profileGet(uid) }
                user = User(j.getJSONObject("user"))
                runOnUiThread { bind() }
            } catch (e: Exception) { Helpers.toast(this@UserProfileActivity, "User not found") }
        }
    }

    private fun bind() {
        val u = user ?: return
        avatar.bindUser(u, showDot = true)
        name.text = u.name
        status.text = Helpers.lastSeenText(u.online, u.lastTs)
        status.setTextColor(if (u.online) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
        username.text = if (u.username.isNotEmpty()) "@${u.username}" else "No username"
        bio.text = if (u.bio.isEmpty()) "No bio yet" else u.bio
        btnContact.text = if (u.isContact) "Remove contact" else "Add contact"
        btnBlock.text = if (u.blockedByMe) "Unblock user" else "Block user"
        if (u.isMe) {
            // my own profile: offer profile editing instead of messaging controls
            btnMessage.visibility = View.GONE
            btnContact.visibility = View.GONE
            btnBlock.visibility = View.GONE
            btnEdit.visibility = View.VISIBLE
        } else {
            btnMessage.visibility = if (u.blockedMe) View.GONE else View.VISIBLE
            btnContact.visibility = View.VISIBLE
            btnBlock.visibility = View.VISIBLE
            btnEdit.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        user?.let { load(it.id) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
