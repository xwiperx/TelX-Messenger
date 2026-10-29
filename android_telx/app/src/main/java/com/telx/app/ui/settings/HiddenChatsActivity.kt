package com.telx.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.Chat
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6: hidden chats — chats the user removed from the main list.
 * Opens behind the app-lock PIN when one is set (Telegram-style privacy).
 * Tap a chat to open it, long-press to unhide back to the main list.
 */
class HiddenChatsActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val chats = ArrayList<Chat>()
    private lateinit var adapter: HAdapter
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden)
        val toolbar = findViewById<Toolbar>(R.id.hidden_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.hidden_recycler)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HAdapter()
        list.adapter = adapter
        empty = findViewById(R.id.hidden_empty)

        // PIN gate (same app PIN as the app lock)
        if (Prefs.appLockPin.isNotEmpty()) askPin()
        else load()
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter your PIN"
            setPadding(Helpers.dp(this@HiddenChatsActivity, 20), 0, Helpers.dp(this@HiddenChatsActivity, 20), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter PIN to view hidden chats")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text?.toString() ?: ""
                if (AppLock.hash(pin) == Prefs.appLockPin) {
                    AppLock.unlockSuccess()
                    load()
                } else { Helpers.toast(this, "Wrong PIN"); finish() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun load() {
        ui.launch {
            try {
                val list = withContext(Dispatchers.IO) { Api.hiddenChats() }
                chats.clear()
                chats.addAll(list)
                adapter.notifyDataSetChanged()
                empty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) { Helpers.toast(this@HiddenChatsActivity, "Couldn't load") }
        }
    }

    inner class HAdapter : RecyclerView.Adapter<HAdapter.HVH>() {
        inner class HVH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: AvatarView = v.findViewById(R.id.hc_avatar)
            val name: TextView = v.findViewById(R.id.hc_name)
            val msg: TextView = v.findViewById(R.id.hc_last)
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HVH =
            HVH(layoutInflater.inflate(R.layout.item_hidden_chat, parent, false))
        override fun getItemCount(): Int = chats.size
        override fun onBindViewHolder(h: HVH, pos: Int) {
            val c = chats[pos]
            h.avatar.bind(c.displayName, c.displayAvatar, Helpers.colorFor(c.id))
            h.name.text = c.displayName
            h.msg.text = c.lastMessage?.text?.ifEmpty { "Media" } ?: "No messages"
            h.itemView.setOnClickListener {
                startActivity(Intent(this@HiddenChatsActivity, com.telx.app.ui.chat.ChatActivity::class.java)
                    .putExtra("chat_id", c.id))
            }
            h.itemView.setOnLongClickListener {
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.setHidden(c.id, false) }
                        Helpers.toast(this@HiddenChatsActivity, "Chat unhidden")
                        chats.removeAt(pos); notifyDataSetChanged()
                    } catch (_: Exception) {}
                }
                true
            }
        }
    }
}
