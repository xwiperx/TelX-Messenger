package com.telx.app.ui.chats

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val chats = ArrayList<Chat>()
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
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setColorSchemeColors(0xFF3390EC.toInt())
        swipe.setOnRefreshListener { load() }

        view.findViewById<FloatingActionButton>(R.id.fab_new).setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        load()
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
                chats.clear()
                val arr = chatsJ.optJSONArray("chats")
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = Chat(arr.getJSONObject(i))
                    chats.add(c)
                    com.telx.app.svc.PollService.MuteCache.setMuted(c.id, c.muted)
                }
                (activity as? com.telx.app.ui.MainActivity)?.setNavBadge(chatsJ.optInt("total_unread", 0))
                try {
                    val feed = withContext(Dispatchers.IO) { Api.storiesFeed() }
                    stories.clear()
                    if (feed != null) for (i in 0 until feed.length())
                        stories.add(StoryGroup(feed.getJSONObject(i)))
                } catch (_: Exception) {}
                adapter.notifyDataSetChanged()
                empty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
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
        items.add("Clear history")
        if (c.type == "group") items.add("Leave group")
        AlertDialog.Builder(requireContext())
            .setTitle(c.displayName)
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
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
                        }
                        load()
                    } catch (e: Exception) { Helpers.toast(context, "Action failed") }
                }
            }
            .show()
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
