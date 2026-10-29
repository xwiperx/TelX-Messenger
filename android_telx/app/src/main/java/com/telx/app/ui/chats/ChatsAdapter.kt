package com.telx.app.ui.chats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Chat
import com.telx.app.data.StoryGroup
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers

/** Chat list: first cell is the horizontal stories row, then all chats. */
class ChatsAdapter(
    private val chats: List<Chat>,
    private val stories: List<StoryGroup>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onChatClick: ((Chat) -> Unit)? = null
    var onChatLongClick: ((Chat) -> Unit)? = null
    var onAddStory: (() -> Unit)? = null
    var onStoryClick: ((StoryGroup) -> Unit)? = null

    private val storiesAdapter by lazy {
        StoriesRowAdapter(stories).apply {
            onAddStory = { this@ChatsAdapter.onAddStory?.invoke() }
            onStory = { this@ChatsAdapter.onStoryClick?.invoke(it) }
        }
    }

    companion object {
        const val TYPE_STORIES = 0
        const val TYPE_CHAT = 1
    }

    override fun getItemViewType(position: Int) =
        if (position == 0) TYPE_STORIES else TYPE_CHAT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_STORIES)
            StoriesVH(inf.inflate(R.layout.item_stories_row, parent, false))
        else
            ChatVH(inf.inflate(R.layout.item_chat, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (h is StoriesVH) {
            if (h.recycler.adapter == null) {
                h.recycler.layoutManager = LinearLayoutManager(h.recycler.context, RecyclerView.HORIZONTAL, false)
                h.recycler.adapter = storiesAdapter
            } else {
                storiesAdapter.notifyDataSetChanged()
            }
        } else if (h is ChatVH) {
            h.bind(chats[position - 1], onChatClick, onChatLongClick)
        }
    }

    override fun getItemCount(): Int = 1 + chats.size

    class StoriesVH(v: View) : RecyclerView.ViewHolder(v) {
        val recycler: RecyclerView = v.findViewById(R.id.stories_row)
    }

    class ChatVH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: AvatarView = v.findViewById(R.id.chat_avatar)
        private val title: TextView = v.findViewById(R.id.chat_title)
        private val preview: TextView = v.findViewById(R.id.chat_preview)
        private val time: TextView = v.findViewById(R.id.chat_time)
        private val unread: TextView = v.findViewById(R.id.chat_unread)
        private val mutedIcon: ImageView = v.findViewById(R.id.chat_muted)
        private val pinIcon: ImageView = v.findViewById(R.id.chat_pinned)
        private val outIcon: ImageView = v.findViewById(R.id.chat_out)
        private val ticks: ImageView = v.findViewById(R.id.chat_ticks)

        fun bind(c: Chat, click: ((Chat) -> Unit)?, longClick: ((Chat) -> Unit)?) {
            val showDot = c.type == "private" && (c.peer?.online == true)
            avatar.bind(c.displayName, c.displayAvatar, Helpers.colorFor(if (c.isSaved) -1 else c.id), showDot)
            if (c.isSaved) avatar.bind("Saved", null, 0xFF3390EC.toInt())
            title.text = c.displayName
            val lm = c.lastMessage
            preview.setTypeface(null, android.graphics.Typeface.NORMAL)
            preview.setTextColor(0xFF8A8A8E.toInt())
            if (c.typing) {
                preview.text = "typing…"
                preview.setTypeface(null, android.graphics.Typeface.ITALIC)
                preview.setTextColor(0xFF3390EC.toInt())
                outIcon.visibility = View.GONE
                ticks.visibility = View.GONE
            } else if (lm == null) {
                preview.text = when {
                    c.isChannel -> "${c.membersCount} subscribers"
                    c.type == "group" -> "${c.membersCount} members"
                    else -> "No messages yet"
                }
                outIcon.visibility = View.GONE
                ticks.visibility = View.GONE
            } else {
                var p = when (lm.type) {
                    "photo" -> "🖼 Photo"
                    "voice" -> "🎤 Voice message"
                    "file" -> "📎 ${if (lm.mediaName.isNotEmpty()) lm.mediaName else "File"}"
                    "video" -> "📹 Video"
                    "gif" -> "GIF"
                    "sticker" -> "${lm.sticker ?: ""} Sticker"
                    "system" -> lm.text
                    else -> lm.text
                }
                if (lm.fwdFrom != null) p = "Fwd: $p"
                if ((c.type == "group" || c.isChannel) && lm.type != "system" && lm.senderName.isNotEmpty() && !lm.isMine)
                    p = "${lm.senderName.split(" ").first()}: $p"
                if (!c.draft.isNullOrEmpty()) {
                    p = "Draft: ${c.draft}"
                    preview.setTypeface(null, android.graphics.Typeface.ITALIC)
                    preview.setTextColor(0xFFE53935.toInt())
                }
                if (lm.isMine && lm.type != "system") {
                    outIcon.visibility = View.VISIBLE
                    ticks.visibility = View.VISIBLE
                    ticks.setImageResource(if (lm.id <= c.peerLastRead) R.drawable.ic_read else R.drawable.ic_check_single)
                    ticks.setColorFilter(if (lm.id <= c.peerLastRead) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
                } else {
                    outIcon.visibility = View.GONE
                    ticks.visibility = View.GONE
                }
                preview.text = p
            }
            time.text = if (lm != null) Helpers.timeShort(lm.ts) else ""
            if (c.unread > 0) {
                unread.visibility = View.VISIBLE
                unread.text = if (c.unread > 99) "99+" else c.unread.toString()
                unread.setBackgroundResource(if (c.muted) R.drawable.badge_muted else R.drawable.badge_unread)
            } else {
                unread.visibility = View.GONE
            }
            mutedIcon.visibility = if (c.muted) View.VISIBLE else View.GONE
            pinIcon.visibility = if (c.pinned) View.VISIBLE else View.GONE
            itemView.setOnClickListener { click?.invoke(c) }
            itemView.setOnLongClickListener { longClick?.invoke(c); true }
        }
    }
}

/** Horizontal stories strip: [+ add] + one cell per user group. */
class StoriesRowAdapter(private val groups: List<StoryGroup>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onAddStory: (() -> Unit)? = null
    var onStory: ((StoryGroup) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0)
            AddVH(inf.inflate(R.layout.item_story_add, parent, false))
        else
            UserVH(inf.inflate(R.layout.item_story_user, parent, false))
    }

    override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

    override fun getItemCount(): Int = 1 + groups.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (h is AddVH) {
            // Telegram-style: my own avatar (photo, or initials when none) with a + badge
            h.avatar.bind(com.telx.app.data.Prefs.myName, com.telx.app.data.Prefs.myAvatar,
                Helpers.colorFor(com.telx.app.data.Prefs.myId))
            h.root.setOnClickListener { onAddStory?.invoke() }
        } else if (h is UserVH) {
            val g = groups[position - 1]
            h.avatar.bind(g.user.name, g.user.avatar, Helpers.colorFor(g.user.id))
            h.ring.setImageResource(if (g.seen) R.drawable.ring_seen else R.drawable.ring_unseen)
            h.name.text = if (g.user.isMe) "My story" else g.user.name.split(" ").first()
            h.root.setOnClickListener { onStory?.invoke(g) }
        }
    }

    class AddVH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: AvatarView = v.findViewById(R.id.story_add_avatar)
        val root: View = v
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: AvatarView = v.findViewById(R.id.story_avatar)
        val ring: ImageView = v.findViewById(R.id.story_ring)
        val name: TextView = v.findViewById(R.id.story_name)
        val root: View = v
    }
}
