package com.telx.app.ui.community

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.telx.app.R
import com.telx.app.data.Chat
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v7 Communities: one umbrella chat linking related groups & channels.
 * Community members are auto-joined to every linked chat (server-side),
 * so tapping a card opens the chat instantly.
 */
class CommunityActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var chatId = 0
    private var community: Chat? = null
    private val linked = ArrayList<Chat>()
    private lateinit var adapter: LinkedAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community)
        chatId = intent.getIntExtra("chat_id", 0)
        if (chatId <= 0) { finish(); return }

        setSupportActionBar(findViewById<Toolbar>(R.id.community_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Community"

        adapter = LinkedAdapter(linked)
        adapter.onClick = { openLinked(it) }
        adapter.onLongClick = { confirmUnlink(it) }
        findViewById<RecyclerView>(R.id.community_recycler).apply {
            layoutManager = LinearLayoutManager(this@CommunityActivity)
            adapter = this@CommunityActivity.adapter
        }
        emptyView = findViewById(R.id.community_empty)

        findViewById<FloatingActionButton>(R.id.community_fab).setOnClickListener { linkNewChat() }
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.chatInfo(chatId) }
                community = Chat(j.getJSONObject("chat"))
                bindHeader()
                linked.clear()
                linked.addAll(withContext(Dispatchers.IO) { Api.communityChats(chatId) })
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (linked.isEmpty()) View.VISIBLE else View.GONE
                findViewById<FloatingActionButton>(R.id.community_fab).visibility =
                    if (community?.isAdmin == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Helpers.toast(this@CommunityActivity, "Couldn't load community")
            }
        }
    }

    private fun bindHeader() {
        val c = community ?: return
        supportActionBar?.title = c.displayName
        findViewById<AvatarView>(R.id.community_avatar).bind(c.displayName, c.avatar, Helpers.colorFor(c.id))
        findViewById<TextView>(R.id.community_name).text = c.displayName
        findViewById<TextView>(R.id.community_desc).text =
            if (c.description.isNullOrEmpty()) "${c.membersCount} members · umbrella for linked chats"
            else c.description
    }

    private fun openLinked(c: Chat) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra("chat_id", c.id))
    }

    private fun confirmUnlink(c: Chat) {
        if (community?.isAdmin != true) return
        AlertDialog.Builder(this)
            .setMessage("Unlink \"${c.displayName}\" from this community? (the chat itself stays)")
            .setPositiveButton("Unlink") { _, _ ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.communityUnlinkChat(chatId, c.id) }
                        Helpers.toast(this@CommunityActivity, "Unlinked")
                        load()
                    } catch (e: Exception) { Helpers.toast(this@CommunityActivity, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** admins: pick one of my groups/channels to link under this umbrella. */
    private fun linkNewChat() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.chatsList(includeArchived = true) }
                val mine = ArrayList<Chat>()
                val arr = j.optJSONArray("chats")
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = Chat(arr.getJSONObject(i))
                    if ((c.isGroup || c.isChannel) && c.isAdmin && !linked.any { it.id == c.id })
                        mine.add(c)
                }
                if (mine.isEmpty()) { Helpers.toast(this@CommunityActivity,
                    "You need to be an admin of a group/channel to link it"); return@launch }
                val labels = mine.map { (if (it.isChannel) "📣 " else "👥 ") + it.displayName }.toTypedArray()
                AlertDialog.Builder(this@CommunityActivity)
                    .setTitle("Link a chat to the community")
                    .setItems(labels) { _, which ->
                        val target = mine[which]
                        ui.launch {
                            try {
                                withContext(Dispatchers.IO) { Api.communityLinkChat(chatId, target.id) }
                                Helpers.toast(this@CommunityActivity, "Linked — members auto-joined")
                                load()
                            } catch (e: Exception) {
                                Helpers.toast(this@CommunityActivity, if (e is com.telx.app.data.ApiErr) e.message else "Failed")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@CommunityActivity, "Couldn't load your chats") }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private class LinkedAdapter(private val items: List<Chat>) :
        RecyclerView.Adapter<LinkedAdapter.VH>() {
        var onClick: ((Chat) -> Unit)? = null
        var onLongClick: ((Chat) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_linked_chat, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            h.avatar.bind(c.displayName, c.displayAvatar, Helpers.colorFor(c.id))
            h.name.text = c.displayName
            h.typeIcon.setImageResource(if (c.isChannel) R.drawable.ic_channel else R.drawable.ic_group)
            h.preview.text = c.lastMessage?.let { lm ->
                lm.text.ifEmpty { "[${lm.type}]" }
            } ?: (if (c.isChannel) "${c.membersCount} subscribers" else "${c.membersCount} members")
            if (c.unread > 0) {
                h.unread.visibility = View.VISIBLE
                h.unread.text = if (c.unread > 99) "99+" else c.unread.toString()
            } else h.unread.visibility = View.GONE
            h.itemView.setOnClickListener { onClick?.invoke(c) }
            h.itemView.setOnLongClickListener { onLongClick?.invoke(c); true }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: AvatarView = v.findViewById(R.id.linked_avatar)
            val name: TextView = v.findViewById(R.id.linked_name)
            val typeIcon: ImageView = v.findViewById(R.id.linked_type_icon)
            val preview: TextView = v.findViewById(R.id.linked_preview)
            val unread: TextView = v.findViewById(R.id.linked_unread)
        }
    }
}
