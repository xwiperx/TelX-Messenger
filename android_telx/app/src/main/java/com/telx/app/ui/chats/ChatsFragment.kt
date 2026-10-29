package com.telx.app.ui.chats

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.telx.app.R
import com.telx.app.data.Chat
import com.telx.app.data.Event
import com.telx.app.data.Prefs
import com.telx.app.data.StoryGroup
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.contacts.SearchActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsFragment : Fragment(), Events.Listener {

    private val ui = CoroutineScope(Dispatchers.Main)
    private lateinit var recycler: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var adapter: ChatsAdapter
    private val stories = ArrayList<StoryGroup>()
    private val allChats = ArrayList<Chat>()
    private val chats = ArrayList<Chat>()   // shown list = filtered by active folder
    private val folders = ArrayList<com.telx.app.data.Folder>()
    private var activeFolderId = 0   // 0 = All chats
    private var reloadJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.chats_recycler)
        swipe = view.findViewById(R.id.chats_swipe)
        empty = view.findViewById(R.id.chats_empty)
        adapter = ChatsAdapter(chats, stories)
        adapter.onChatClick = { openChat(it) }
        adapter.onChatLongClick = { showChatActions(it) }
        adapter.onAddStory = { showStoryPicker() }
        adapter.onStoryClick = { g ->
            startActivity(Intent(requireContext(), com.telx.app.ui.stories.StoriesActivity::class.java))
        }
        adapter.onArchivedClick = { showArchivedDialog() }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setColorSchemeColors(0xFF3390EC.toInt())
        swipe.setOnRefreshListener { load() }

        view.findViewById<FloatingActionButton>(R.id.fab_new).setOnClickListener { showCreateSheet() }
        load()
    }

    // ---------- chat folders (Telegram filters) ----------

    private fun renderFolderTabs(root: View) {
        val scroll = root.findViewById<android.widget.HorizontalScrollView>(R.id.folder_scroll)
        val tabs = root.findViewById<LinearLayout>(R.id.folder_tabs)
        tabs.removeAllViews()
        scroll.visibility = if (folders.isEmpty() && activeFolderId == 0) View.GONE else View.VISIBLE

        fun chip(label: String, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
            val tv = TextView(requireContext())
            tv.text = label
            tv.textSize = 14f
            tv.isSingleLine = true
            val hpad = Helpers.dp(requireContext(), 14)
            val vpad = Helpers.dp(requireContext(), 6)
            tv.setPadding(hpad, vpad, hpad, vpad)
            tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF3390EC.toInt())
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = Helpers.dp(requireContext(), 16).toFloat()
                setColor(if (selected) 0xFF3390EC.toInt() else 0x1400A8F0)
            }
            tv.setOnClickListener { onClick() }
            tv.setOnLongClickListener { onLong(); true }
            tabs.addView(tv)
            (tv.layoutParams as LinearLayout.LayoutParams).marginEnd = Helpers.dp(requireContext(), 6)
        }

        chip("All chats", activeFolderId == 0, {
            activeFolderId = 0; renderFolderTabs(requireView()); refreshChatList()
        }) {}
        for (f in folders)
            chip((if (f.emoji.isNotEmpty()) "${f.emoji} " else "") + f.name,
                activeFolderId == f.id,
                { activeFolderId = f.id; renderFolderTabs(requireView()); refreshChatList() },
                { editFolderDialog(f.id) })
        chip("＋", false, { newFolderDialog() }) {}
    }

    /** Refill the visible chat list from the active folder. */
    private fun refreshChatList() {
        val f = folders.firstOrNull { it.id == activeFolderId }
        chats.clear()
        if (f == null) chats.addAll(allChats)
        else for (c in allChats) if (f.chats.contains(c.id)) chats.add(c)
        adapter.notifyDataSetChanged()
        empty.text = if (chats.isEmpty() && allChats.isNotEmpty())
            "Nothing in this folder yet.\nLong-press a chat → Add to folder"
        else "No chats yet\nTap + to start messaging"
        empty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun newFolderDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_new_folder, null)
        val etName = v.findViewById<android.widget.EditText>(R.id.et_folder_name)
        val etEmoji = v.findViewById<android.widget.EditText>(R.id.et_folder_emoji)
        AlertDialog.Builder(requireContext())
            .setTitle("New folder")
            .setView(v)
            .setPositiveButton("Create") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) { Helpers.toast(context, "Enter a folder name"); return@setPositiveButton }
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.folderSave(0, name, etEmoji.text?.toString() ?: "") }
                        loadFolders()
                    } catch (e: Exception) { Helpers.toast(context, "Failed to create folder") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editFolderDialog(folderId: Int) {
        val f = folders.firstOrNull { it.id == folderId } ?: return
        val items = arrayOf("Rename / edit", "Delete folder")
        AlertDialog.Builder(requireContext())
            .setTitle("${f.emoji} ${f.name}")
            .setItems(items) { _, which ->
                if (which == 0) {
                    val v = layoutInflater.inflate(R.layout.dialog_new_folder, null)
                    val etName = v.findViewById<android.widget.EditText>(R.id.et_folder_name)
                    val etEmoji = v.findViewById<android.widget.EditText>(R.id.et_folder_emoji)
                    etName.setText(f.name); etEmoji.setText(f.emoji)
                    AlertDialog.Builder(requireContext())
                        .setTitle("Edit folder")
                        .setView(v)
                        .setPositiveButton("Save") { _, _ ->
                            ui.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Api.folderSave(f.id, etName.text?.toString()?.trim() ?: f.name,
                                            etEmoji.text?.toString() ?: "") }
                                    loadFolders()
                                } catch (e: Exception) { Helpers.toast(context, "Failed") }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setMessage("Delete folder '${f.name}'?")
                        .setPositiveButton("Delete") { _, _ ->
                            ui.launch {
                                try {
                                    withContext(Dispatchers.IO) { Api.folderDelete(f.id) }
                                    if (activeFolderId == f.id) activeFolderId = 0
                                    loadFolders()
                                } catch (e: Exception) { Helpers.toast(context, "Failed") }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun loadFolders() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.folders() }
                folders.clear()
                folders.addAll(com.telx.app.data.Folder.parseArray(arr))
            } catch (_: Exception) {}
            if (view != null) { renderFolderTabs(requireView()); refreshChatList() }
        }
    }

    private fun showCreateSheet() {
        val d = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.sheet_create, null)
        v.findViewById<LinearLayout>(R.id.create_group).setOnClickListener {
            d.dismiss(); startActivity(Intent(requireContext(),
                com.telx.app.ui.contacts.PickUsersActivity::class.java))
        }
        v.findViewById<LinearLayout>(R.id.create_channel).setOnClickListener {
            d.dismiss(); startActivity(Intent(requireContext(),
                com.telx.app.ui.chats.CreateChannelActivity::class.java))
        }
        v.findViewById<LinearLayout>(R.id.create_message).setOnClickListener {
            d.dismiss(); startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        v.findViewById<LinearLayout>(R.id.create_secret).setOnClickListener {
            d.dismiss(); startActivity(Intent(requireContext(),
                com.telx.app.ui.contacts.PickSecretActivity::class.java))
        }
        d.setContentView(v)
        d.show()
    }

    private fun openChat(c: Chat) {
        val i = Intent(requireContext(), ChatActivity::class.java)
        i.putExtra("chat_id", c.id)
        startActivity(i)
    }

    fun load() {
        ui.launch {
            try {
                val chatsJ = withContext(Dispatchers.IO) { Api.chatsList() }
                allChats.clear()
                val arr = chatsJ.optJSONArray("chats")
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = Chat(arr.getJSONObject(i))
                    allChats.add(c)
                    com.telx.app.svc.PollService.MuteCache.setMuted(c.id, c.muted)
                }
                (activity as? com.telx.app.ui.MainActivity)?.refreshDrawerHeader()
                try {
                    val feed = withContext(Dispatchers.IO) { Api.storiesFeed() }
                    stories.clear()
                    if (feed != null) for (i in 0 until feed.length())
                        stories.add(StoryGroup(feed.getJSONObject(i)))
                } catch (_: Exception) {}
                loadFolders()
                if (view != null) refreshChatList()
                adapter.archivedCount = chatsJ.optInt("archived_count", 0)
            } catch (e: Exception) {
                Helpers.toast(context, "Couldn't refresh — check connection")
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun showStoryPicker() {
        val options = arrayOf("Upload photo story", "Upload video story")
        AlertDialog.Builder(requireContext())
            .setTitle("New Story")
            .setItems(options) { _, which ->
                (activity as? com.telx.app.ui.MainActivity)
                    ?.pickStoryMedia(isVideo = which == 1)
            }
            .show()
    }

    private fun showChatActions(c: Chat) {
        val items = ArrayList<String>()
        items.add(if (c.pinned) "Unpin from top" else "Pin to top")
        items.add(if (c.muted) "Unmute notifications" else "Mute notifications")
        items.add(if (c.archived) "Unarchive" else "Archive")
        items.add("Add to folder")
        items.add("Clear history")
        if (c.type == "group") items.add("Leave group")
        if (c.isChannel) items.add("Leave channel")
        AlertDialog.Builder(requireContext())
            .setTitle(c.displayName)
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                if (label == "Add to folder") { showAddToFolder(c); return@setItems }
                ui.launch {
                    try {
                        when (label) {
                            "Pin to top" -> withContext(Dispatchers.IO) { Api.setChatPinned(c.id, true) }
                            "Unpin from top" -> withContext(Dispatchers.IO) { Api.setChatPinned(c.id, false) }
                            "Mute notifications" -> withContext(Dispatchers.IO) { Api.setMute(c.id, true) }
                            "Unmute notifications" -> withContext(Dispatchers.IO) { Api.setMute(c.id, false) }
                            "Archive" -> withContext(Dispatchers.IO) { Api.setArchived(c.id, true) }
                            "Unarchive" -> withContext(Dispatchers.IO) { Api.setArchived(c.id, false) }
                            "Clear history" -> withContext(Dispatchers.IO) { Api.clearHistory(c.id) }
                            "Leave group" -> withContext(Dispatchers.IO) { Api.leaveGroup(c.id) }
                            "Leave channel" -> withContext(Dispatchers.IO) { Api.leaveChannel(c.id) }
                        }
                        load()
                    } catch (e: Exception) { Helpers.toast(context, "Action failed") }
                }
            }
            .show()
    }

    private fun showAddToFolder(c: Chat) {
        if (folders.isEmpty()) {
            Helpers.toast(context, "Create a folder first (＋ tab above the chat list)")
            newFolderDialog()
            return
        }
        val labels = folders.map { (if (it.emoji.isNotEmpty()) "${it.emoji} " else "") + it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add '${c.displayName}' to folder")
            .setItems(labels) { _, which ->
                val f = folders[which]
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.folderAssign(f.id, c.id, true) }
                        Helpers.toast(context, "Added to ${f.name}")
                        loadFolders()
                    } catch (e: Exception) { Helpers.toast(context, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Telegram-style archived chats list: open, unarchive or clear each entry. */
    private fun showArchivedDialog() {
        ui.launch {
            try {
                val all = withContext(Dispatchers.IO) { Api.chatsList(includeArchived = true) }
                val archived = ArrayList<Chat>()
                val arr = all.optJSONArray("chats")
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = Chat(arr.getJSONObject(i))
                    if (c.archived) archived.add(c)
                }
                if (archived.isEmpty()) { Helpers.toast(context, "No archived chats"); return@launch }
                val labels = archived.map {
                    "${it.displayName}  ·  ${it.lastMessage?.text ?: ""}".trim().take(60)
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Archived Chats (${archived.size})")
                    .setItems(labels) { _, which -> openChat(archived[which]) }
                    .setNeutralButton("Unarchive all") { _, _ ->
                        ui.launch {
                            for (c in archived) {
                                try { withContext(Dispatchers.IO) { Api.setArchived(c.id, false) } } catch (_: Exception) {}
                            }
                            load()
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(context, "Couldn't load archived chats") }
        }
    }

    override fun onResume() {
        super.onResume()
        Events.addListener(this)
        load()
    }

    override fun onPause() {
        super.onPause()
        Events.removeListener(this)
    }

    override fun onEvent(e: Event) {
        val relevant = e.type in setOf(
            "message_new", "message_read", "message_deleted", "message_edited",
            "chat_created", "chat_deleted", "chat_updated", "chat_cleared",
            "group_updated", "message_reacted", "message_pinned",
            "story_new", "story_deleted", "story_viewed", "profile_updated")
        if (!relevant) return
        reloadJob?.cancel()
        reloadJob = ui.launch {
            delay(600)
            load()
        }
    }
}
