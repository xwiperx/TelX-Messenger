package com.telx.app.ui.chat

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.ui.common.Helpers

/** Renders messages in Telegram style: blue bubbles for me, white for peers. */
class MessageAdapter(
    private val items: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onMessageClick: ((Message) -> Unit)? = null
    var onMessageLongClick: ((Message) -> Unit)? = null
    var onImageClick: ((Message) -> Unit)? = null
    var onRetryClick: ((Message) -> Unit)? = null

    companion object {
        const val TYPE_MINE = 0
        const val TYPE_THEIR = 1
        const val TYPE_SYSTEM = 2
        private var player: MediaPlayer? = null
        private var playingId = -1
    }

    /** peer's last-read message id, set/updated by ChatActivity */
    @Volatile var peerReadId: Int = 0

    override fun getItemViewType(position: Int): Int {
        val m = items[position]
        return when {
            m.type == "system" -> TYPE_SYSTEM
            m.isMine -> TYPE_MINE
            else -> TYPE_THEIR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SYSTEM -> SystemVH(inf.inflate(R.layout.item_msg_system, parent, false))
            TYPE_MINE -> BubbleVH(inf.inflate(R.layout.item_msg_mine, parent, false), this)
            else -> BubbleVH(inf.inflate(R.layout.item_msg_their, parent, false), this)
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = items[position]
        if (holder is SystemVH) {
            holder.text.text = m.text
            return
        }
        holder as BubbleVH
        holder.bind(m)
    }

    fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playingId = -1
    }

    class SystemVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msg_system_text)
    }

    class BubbleVH(v: View, adapter: MessageAdapter) : RecyclerView.ViewHolder(v) {
        private val replyBlock: View = v.findViewById(R.id.msg_reply_block)
        private val replyName: TextView = v.findViewById(R.id.msg_reply_name)
        private val replyText: TextView = v.findViewById(R.id.msg_reply_text)
        private val fwd: TextView = v.findViewById(R.id.msg_fwd)
        private val image: ImageView = v.findViewById(R.id.msg_media_image)
        private val fileRow: View = v.findViewById(R.id.msg_file_row)
        private val fileName: TextView = v.findViewById(R.id.msg_file_name)
        private val fileSize: TextView = v.findViewById(R.id.msg_file_size)
        private val voiceRow: View = v.findViewById(R.id.msg_voice_row)
        private val voiceBtn: ImageView = v.findViewById(R.id.msg_voice_btn)
        private val voiceDur: TextView = v.findViewById(R.id.msg_voice_duration)
        private val text: TextView = v.findViewById(R.id.msg_text)
        private val linkBlock: View = v.findViewById(R.id.msg_link_block)
        private val linkSite: TextView = v.findViewById(R.id.msg_link_site)
        private val linkTitle: TextView = v.findViewById(R.id.msg_link_title)
        private val linkDesc: TextView = v.findViewById(R.id.msg_link_desc)
        private val linkImage: ImageView = v.findViewById(R.id.msg_link_image)
        private val edited: TextView = v.findViewById(R.id.msg_edited)
        private val time: TextView = v.findViewById(R.id.msg_time)
        private val ticks: ImageView = v.findViewById(R.id.msg_ticks)
        private val reactions: LinearLayout = v.findViewById(R.id.msg_reactions)
        private val status: ImageView = v.findViewById(R.id.msg_status)

        fun bind(m: Message) {
            val ctx = itemView.context
            val maxW = (ctx.resources.displayMetrics.widthPixels * 0.68f).toInt()

            // forward header
            fwd.visibility = if (m.fwdFrom != null) View.VISIBLE else View.GONE
            if (m.fwdFrom != null) fwd.text = "Forwarded from ${m.fwdName.ifEmpty { "Unknown" }}"

            // reply block
            if (m.reply != null) {
                replyBlock.visibility = View.VISIBLE
                replyName.text = m.reply.name.ifEmpty { "Deleted message" }
                val rt = when {
                    m.reply.deleted -> "Deleted message"
                    m.reply.text.isNotEmpty() -> m.reply.text
                    m.reply.hasMedia -> "Media message"
                    else -> "Message"
                }
                replyText.text = rt
            } else replyBlock.visibility = View.GONE

            // media
            image.visibility = View.GONE
            fileRow.visibility = View.GONE
            voiceRow.visibility = View.GONE
            when (m.type) {
                "photo", "gif", "video" -> {
                    image.visibility = View.VISIBLE
                    image.adjustViewBounds = true
                    image.maximumWidth = maxW
                    val url = m.media ?: m.thumb
                    Glide.with(image).load(url).placeholder(R.drawable.media_placeholder)
                        .override(720).into(image)
                    image.setOnClickListener { adapter.onImageClick?.invoke(m) }
                }
                "file" -> {
                    fileRow.visibility = View.VISIBLE
                    fileName.text = m.mediaName.ifEmpty { "File" }
                    fileSize.text = Helpers.fileSize(m.mediaSize)
                }
                "voice" -> {
                    voiceRow.visibility = View.VISIBLE
                    voiceDur.text = Helpers.duration(m.mediaDuration)
                    voiceBtn.setImageResource(if (playingId == m.id) R.drawable.ic_pause else R.drawable.ic_play)
                    voiceBtn.setOnClickListener { toggleVoice(m, voiceBtn) }
                }
            }

            // text
            if (m.text.isEmpty()) text.visibility = View.GONE
            else { text.visibility = View.VISIBLE; text.text = m.text }

            // link preview
            val l = m.link
            if (l != null && m.type == "text") {
                linkBlock.visibility = View.VISIBLE
                linkSite.text = (l.optString("site", "")).ifEmpty { try { java.net.URI(l.optString("url")).host ?: "" } catch (e: Exception) { "" } }
                linkTitle.text = l.optString("title", "")
                linkTitle.visibility = if (l.optString("title", "").isEmpty()) View.GONE else View.VISIBLE
                linkDesc.text = l.optString("desc", "")
                linkDesc.visibility = if (l.optString("desc", "").isEmpty()) View.GONE else View.VISIBLE
                val li = if (l.has("image") && !l.isNull("image")) l.optString("image") else null
                if (!li.isNullOrEmpty()) {
                    linkImage.visibility = View.VISIBLE
                    Glide.with(linkImage).load(li).override(560).centerCrop().into(linkImage)
                } else linkImage.visibility = View.GONE
            } else linkBlock.visibility = View.GONE

            // meta
            edited.text = if (m.edited) "edited" else ""
            edited.visibility = if (m.edited) View.VISIBLE else View.GONE
            time.text = Helpers.timeShort(m.ts)

            if (m.isMine) {
                when (m.state) {
                    Message.STATE_PENDING -> {
                        status.visibility = View.VISIBLE
                        ticks.visibility = View.GONE
                        status.setImageResource(R.drawable.ic_clock)
                    }
                    Message.STATE_FAILED -> {
                        status.visibility = View.VISIBLE
                        ticks.visibility = View.GONE
                        status.setImageResource(R.drawable.ic_error)
                    }
                    else -> {
                        status.visibility = View.GONE
                        ticks.visibility = View.VISIBLE
                        ticks.setImageResource(if (m.id > 0 && m.id <= peerReadId) R.drawable.ic_read else R.drawable.ic_check_single)
                    }
                }
            } else {
                status.visibility = View.GONE
                ticks.visibility = View.GONE
            }

            // reactions chips
            reactions.removeAllViews()
            if (m.reactions.isNotEmpty()) {
                reactions.visibility = View.VISIBLE
                for (r in m.reactions) {
                    val chip = LayoutInflater.from(ctx).inflate(R.layout.item_reaction_chip, reactions, false) as TextView
                    chip.text = "${r.emoji} ${r.count}"
                    chip.setBackgroundResource(if (r.mine) R.drawable.chip_reaction_mine else R.drawable.chip_reaction)
                    reactions.addView(chip)
                }
            } else reactions.visibility = View.GONE

            itemView.setOnClickListener { adapter.onMessageClick?.invoke(m) }
            itemView.setOnLongClickListener { adapter.onMessageLongClick?.invoke(m); true }
            if (m.state == Message.STATE_FAILED) {
                itemView.setOnClickListener { adapter.onRetryClick?.invoke(m) }
            }
        }

        private fun toggleVoice(m: Message, btn: ImageView) {
            val mp = player
            if (playingId == m.id && mp != null) {
                try { mp.stop(); mp.release() } catch (_: Exception) {}
                player = null
                playingId = -1
                btn.setImageResource(R.drawable.ic_play)
                return
            }
            try {
                mp?.release()
            } catch (_: Exception) {}
            val media = m.media ?: return
            val p = MediaPlayer()
            try {
                p.setDataSource(media)
                p.prepare()
                p.setOnCompletionListener {
                    playingId = -1
                    try { it.release() } catch (_: Exception) {}
                    btn.setImageResource(R.drawable.ic_play)
                }
                p.start()
                player = p
                playingId = m.id
                btn.setImageResource(R.drawable.ic_pause)
            } catch (e: Exception) {
                Helpers.toast(btn.context, "Cannot play audio")
            }
        }
    }
}
