package com.telx.app.ui.chat

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.data.Prefs
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.common.RichText

/**
 * Telegram-style message list (v6):
 *  - date separators, sender names, clock/tick/double-tick statuses
 *  - rich text: **bold**, __italic__, ~~strike~~, `mono`, ||spoiler||, #tags, @mentions
 *  - media albums (grouped photo grids), silent + pinned message flags
 *  - voice messages with playback speed (1x/1.5x/2x) and tap-to-seek waveform
 *  - channel posts with view counter, comment button and effects (confetti…)
 */
class MessageAdapter(
    val items: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onMessageClick: ((Message) -> Unit)? = null
    var onMessageLongClick: ((Message) -> Unit)? = null
    var onImageClick: ((Message) -> Unit)? = null
    var onRetryClick: ((Message) -> Unit)? = null
    var onReactClick: ((Message, String?) -> Unit)? = null
    var onFileClick: ((Message) -> Unit)? = null
    var onTagClick: ((kind: String, value: String) -> Unit)? = null
    var onCommentsClick: ((Message) -> Unit)? = null
    var onEffect: ((String) -> Unit)? = null

    /** show sender names above other people's messages (group / channel) */
    @Volatile var showSenderNames: Boolean = false

    /** channel context → show 💬 comment buttons on posts */
    @Volatile var isChannel: Boolean = false

    /** the channel/group display name (used for anonymous admin posts) */
    @Volatile var chatTitle: String = ""

    /** peer's last-read message id (✓✓ read) */
    @Volatile var peerReadId: Int = 0

    /** peer's last-delivered message id (✓✓ delivered, gray) */
    @Volatile var peerDeliveredId: Int = 0

    companion object {
        /** messages the user explicitly tapped to download (bypasses auto-download policy) */
        val downloadedOverride = HashSet<Int>()
        /** effects already played for these message ids (avoid replay on rebind) */
        val effectsPlayed = HashSet<Int>()
        const val TYPE_MINE = 0
        const val TYPE_THEIR = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_DATE = 3
        private var player: MediaPlayer? = null
        private var playingId = -1
        /** global voice playback speed (Telegram-style) */
        @Volatile var voiceSpeed = 1.0f
    }

    // row mapping: row -> message index, -1 = date header
    private var rowMap: IntArray = IntArray(0)

    /** message at a recycler row (or null for date/system rows) */
    fun messageAtRow(position: Int): Message? {
        val map = rebuildMap()
        val mi = if (position < map.size) map[position] else -1
        return if (mi >= 0) items[mi] else null
    }

    private fun rebuildMap(): IntArray {
        val out = ArrayList<Int>(items.size + 8)
        var prevTs = -1L
        for (i in items.indices) {
            val m = items[i]
            if (Helpers.differentDay(prevTs, m.ts) && m.type != "system") { out.add(-1); prevTs = m.ts }
            else if (m.type != "system" && prevTs < 0) prevTs = m.ts
            out.add(i)
        }
        return out.toIntArray().also { rowMap = it }
    }

    override fun getItemViewType(position: Int): Int {
        val map = rebuildMap()
        val mi = if (position < map.size) map[position] else 0
        if (mi < 0) return TYPE_DATE
        val m = items[mi]
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
            TYPE_DATE -> DateVH(inf.inflate(R.layout.item_date_header, parent, false))
            TYPE_MINE -> BubbleVH(inf.inflate(R.layout.item_msg_mine, parent, false), this)
            else -> BubbleVH(inf.inflate(R.layout.item_msg_their, parent, false), this)
        }
    }

    override fun getItemCount(): Int = rebuildMap().size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val map = rebuildMap()
        val mi = if (position < map.size) map[position] else 0
        if (mi < 0) {
            (holder as DateVH).text.text = Helpers.dateSeparator(items[if (position + 1 < map.size) map[position + 1] else 0].ts)
            return
        }
        val m = items[mi]
        if (holder is SystemVH) { holder.text.text = m.text; return }
        (holder as BubbleVH).bind(m)
    }

    // ---- helpers for ChatActivity (row positions differ from message indices) ----

    fun rowOfMessage(id: Int): Int {
        val map = rebuildMap()
        for (r in map.indices) if (map[r] >= 0 && items[map[r]].id == id) return r
        return -1
    }

    fun notifyMessageChanged(id: Int) {
        val r = rowOfMessage(id)
        if (r >= 0) notifyItemChanged(r) else notifyDataSetChanged()
    }

    fun notifyAllChanged() = notifyDataSetChanged()

    fun lastRow(): Int = itemCount - 1

    fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playingId = -1
    }

    class DateVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.date_text)
    }

    class SystemVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msg_system_text)
    }

    class BubbleVH(v: View, private val adapter: MessageAdapter) : RecyclerView.ViewHolder(v) {
        private val senderName: TextView? = try { v.findViewById(R.id.msg_sender_name) } catch (_: Exception) { null }
        private val replyBlock: View = v.findViewById(R.id.msg_reply_block)
        private val replyName: TextView = v.findViewById(R.id.msg_reply_name)
        private val replyText: TextView = v.findViewById(R.id.msg_reply_text)
        private val fwd: TextView = v.findViewById(R.id.msg_fwd)
        private val image: ImageView = v.findViewById(R.id.msg_media_image)
        private val albumRow: LinearLayout? = try { v.findViewById(R.id.msg_album) } catch (_: Exception) { null }
        private val albumCells: List<ImageView> = listOf(
            try { v.findViewById<ImageView>(R.id.album_cell_1) } catch (_: Exception) { null },
            try { v.findViewById<ImageView>(R.id.album_cell_2) } catch (_: Exception) { null },
            try { v.findViewById<ImageView>(R.id.album_cell_3) } catch (_: Exception) { null },
            try { v.findViewById<ImageView>(R.id.album_cell_4) } catch (_: Exception) { null }
        ).filterNotNull()
        private val commentsBtn: TextView? = try { v.findViewById(R.id.msg_comments) } catch (_: Exception) { null }
        private val fileRow: View = v.findViewById(R.id.msg_file_row)
        private val fileName: TextView = v.findViewById(R.id.msg_file_name)
        private val fileSize: TextView = v.findViewById(R.id.msg_file_size)
        private val fileProgress: android.widget.ProgressBar? = try { v.findViewById(R.id.msg_file_progress) } catch (_: Exception) { null }
        private val fileState: TextView? = try { v.findViewById(R.id.msg_file_state) } catch (_: Exception) { null }
        private val voiceRow: View = v.findViewById(R.id.msg_voice_row)
        private val voiceBtn: ImageView = v.findViewById(R.id.msg_voice_btn)
        private val voiceDur: TextView = v.findViewById(R.id.msg_voice_duration)
        private val voiceWave: com.telx.app.ui.common.WaveformView? =
            try { v.findViewById(R.id.msg_voice_wave) } catch (_: Exception) { null }
        private val text: TextView = v.findViewById(R.id.msg_text)
        private val linkBlock: View = v.findViewById(R.id.msg_link_block)
        private val linkSite: TextView = v.findViewById(R.id.msg_link_site)
        private val linkTitle: TextView = v.findViewById(R.id.msg_link_title)
        private val linkDesc: TextView = v.findViewById(R.id.msg_link_desc)
        private val linkImage: ImageView = v.findViewById(R.id.msg_link_image)
        private val edited: TextView = v.findViewById(R.id.msg_edited)
        private val flags: TextView? = try { v.findViewById(R.id.msg_flags) } catch (_: Exception) { null }
        private val views: TextView? = try { v.findViewById(R.id.msg_views) } catch (_: Exception) { null }
        private val time: TextView = v.findViewById(R.id.msg_time)
        private val ticks: ImageView = v.findViewById(R.id.msg_ticks)
        private val reactions: LinearLayout = v.findViewById(R.id.msg_reactions)
        private val status: ImageView = v.findViewById(R.id.msg_status)
        private val bubble: View? = try { v.findViewById(R.id.msg_bubble) } catch (_: Exception) { null }
        private val stickerImg: ImageView = v.findViewById(R.id.msg_sticker_img)
        private val locBlock: View = v.findViewById(R.id.msg_location_block)
        private val locTitle: TextView = v.findViewById(R.id.msg_location_title)
        private val locCoords: TextView = v.findViewById(R.id.msg_location_coords)
        private val locOpen: TextView = v.findViewById(R.id.msg_location_open)
        private val locImg: ImageView = v.findViewById(R.id.msg_location_img)
        private val contactBlock: View = v.findViewById(R.id.msg_contact_block)
        private val contactInitial: TextView = v.findViewById(R.id.msg_contact_initial)
        private val contactName: TextView = v.findViewById(R.id.msg_contact_name)
        private val contactUsername: TextView = v.findViewById(R.id.msg_contact_username)
        private val roundVideo: ImageView = v.findViewById(R.id.msg_round_video)

        fun bind(m: Message) {
            val ctx = itemView.context
            val maxW = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()

            // ---------- v6: album grouping ----------
            // first message of a group renders ALL album items in a grid;
            // the remaining group members collapse to zero-height rows
            val albumItems: List<Message>? = m.groupId?.let { gid ->
                adapter.items.filter { it.groupId == gid && it.id > 0 }
            }
            val collapsed = albumItems != null && albumItems.isNotEmpty() &&
                    m.id != albumItems.first().id
            val lp = itemView.layoutParams
            if (collapsed) {
                if (lp.height != 0) { lp.height = 0; itemView.layoutParams = lp }
                return
            } else if (lp.height == 0) {
                lp.height = RecyclerView.LayoutParams.WRAP_CONTENT
                itemView.layoutParams = lp
            }

            // ---------- v6: effects (confetti / fireworks / hearts) ----------
            if (m.effect != null && m.id > 0 && m.id !in MessageAdapter.effectsPlayed) {
                MessageAdapter.effectsPlayed.add(m.id)
                adapter.onEffect?.invoke(m.effect)
            }

            // sender name (groups / channels) — anonymous admin posts carry the chat identity
            if (senderName != null) {
                val show = adapter.showSenderNames && !m.isMine && m.type != "system" && m.senderName.isNotEmpty()
                senderName.visibility = if (show || m.anon) View.VISIBLE else View.GONE
                if (show || m.anon) {
                    senderName.text = if (m.anon) adapter.chatTitle else m.senderName
                    senderName.setTextColor(if (m.anon) 0xFF3390EC.toInt() else Helpers.colorFor(m.senderId))
                }
            }

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
            albumRow?.visibility = View.GONE
            fileRow.visibility = View.GONE
            voiceRow.visibility = View.GONE
            stickerImg.visibility = View.GONE
            locBlock.visibility = View.GONE
            contactBlock.visibility = View.GONE
            roundVideo.visibility = View.GONE
            // custom accent color for my bubbles (Telegram-blue default)
            if (m.isMine && bubble != null) {
                val accent = Prefs.bubbleAccent
                if (accent.isNotEmpty()) {
                    try { bubble.background?.setTint(android.graphics.Color.parseColor(accent)) }
                    catch (_: Exception) {}
                }
            }
            val inAlbum = albumItems != null && albumItems.isNotEmpty() &&
                    m.id == albumItems.first().id &&
                    m.type in listOf("photo", "gif", "video")
            when {
                inAlbum -> {
                    // v6: album — up to 4 cells with tap-to-open
                    albumRow?.visibility = View.VISIBLE
                    val shown = albumItems.take(4)
                    albumCells.forEachIndexed { i, cell ->
                        if (i < shown.size) {
                            cell.visibility = View.VISIBLE
                            val am = shown[i]
                            val url = am.thumb ?: am.media
                            if (Helpers.shouldAutoDownload(cell.context) || am.id in MessageAdapter.downloadedOverride) {
                                Glide.with(cell).load(url).placeholder(R.drawable.media_placeholder)
                                    .override(320).centerCrop().into(cell)
                            } else cell.setImageResource(R.drawable.media_placeholder)
                            cell.setOnClickListener {
                                MessageAdapter.downloadedOverride.add(am.id)
                                adapter.onImageClick?.invoke(am)
                            }
                        } else cell.visibility = View.GONE
                    }
                }
                m.type == "photo" || m.type == "gif" || m.type == "video" -> {
                    image.visibility = View.VISIBLE
                    image.adjustViewBounds = true
                    image.maxWidth = maxW
                    val url = m.media ?: m.thumb
                    // auto-download policy: show placeholder until tapped when disabled
                    if (Helpers.shouldAutoDownload(image.context) || m.id in MessageAdapter.downloadedOverride) {
                        Glide.with(image).load(url).placeholder(R.drawable.media_placeholder)
                            .override(720).into(image)
                    } else {
                        image.setImageResource(R.drawable.media_placeholder)
                    }
                    image.setOnClickListener {
                        MessageAdapter.downloadedOverride.add(m.id)
                        Glide.with(image).load(url).placeholder(R.drawable.media_placeholder)
                            .override(720).into(image)
                        adapter.onImageClick?.invoke(m)
                    }
                }
                m.type == "video_note" -> {
                    roundVideo.visibility = View.VISIBLE
                    roundVideo.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x22000000)
                    }
                    roundVideo.clipToOutline = true
                    roundVideo.setOnClickListener {
                        m.media?.let { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(it)).setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    if (m.thumb != null) Glide.with(roundVideo).load(m.thumb).centerCrop().into(roundVideo)
                }
                m.type == "sticker" -> {
                    stickerImg.visibility = View.VISIBLE
                    Glide.with(stickerImg).load(m.sticker ?: m.media).into(stickerImg)
                }
                m.type == "location" -> {
                    locBlock.visibility = View.VISIBLE
                    val mj = m.mediaJson
                    val lat = mj?.optDouble("lat", 0.0) ?: 0.0
                    val lng = mj?.optDouble("lng", 0.0) ?: 0.0
                    locTitle.text = mj?.optString("title", "Location") ?: "Location"
                    locCoords.text = "%.5f, %.5f".format(lat, lng)
                    locImg.setOnClickListener {
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) } catch (_: Exception) {}
                    }
                    locOpen.setOnClickListener {
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) } catch (_: Exception) {}
                    }
                }
                m.type == "contact" -> {
                    contactBlock.visibility = View.VISIBLE
                    val mj = m.mediaJson
                    val name = mj?.optString("name", "Contact") ?: "Contact"
                    val uname = mj?.optString("username", "") ?: ""
                    val uid = mj?.optInt("user_id", 0) ?: 0
                    contactInitial.text = Helpers.initials(name)
                    contactInitial.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Helpers.colorFor(uid))
                    }
                    contactName.text = name
                    contactUsername.text = if (uname.isNotEmpty()) "@$uname" else ""
                    contactBlock.setOnClickListener {
                        if (uid > 0) ctx.startActivity(android.content.Intent(ctx,
                            com.telx.app.ui.profile.UserProfileActivity::class.java)
                            .putExtra("user_id", uid))
                    }
                }
                m.type == "file" -> {
                    fileRow.visibility = View.VISIBLE
                    fileName.text = m.mediaName.ifEmpty { "File" }
                    fileSize.text = Helpers.fileSize(m.mediaSize)
                    // Telegram-style download progress / pause / saved states
                    val p = com.telx.app.media.FileDownloader.progress[m.id]
                    if (p != null) {
                        when {
                            p == 100 -> {
                                fileProgress?.visibility = View.GONE
                                fileState?.visibility = View.VISIBLE
                                fileState?.text = "Saved — tap to open"
                            }
                            p == -1 -> {
                                fileProgress?.visibility = View.VISIBLE
                                fileState?.visibility = View.VISIBLE
                                fileState?.text = "Paused — tap to resume"
                            }
                            else -> {
                                fileProgress?.visibility = View.VISIBLE
                                fileProgress?.progress = p
                                fileState?.visibility = View.VISIBLE
                                fileState?.text = "Downloading… $p% — tap to pause"
                            }
                        }
                    } else {
                        fileProgress?.visibility = View.GONE
                        fileState?.visibility = View.GONE
                    }
                    fileRow.setOnClickListener { adapter.onFileClick?.invoke(m) }
                }
                m.type == "voice" -> {
                    voiceRow.visibility = View.VISIBLE
                    voiceDur.text = Helpers.duration(m.mediaDuration) +
                            if (MessageAdapter.voiceSpeed != 1f) "  ${MessageAdapter.voiceSpeed}x" else ""
                    voiceBtn.setImageResource(if (playingId == m.id) R.drawable.ic_pause else R.drawable.ic_play)
                    voiceWave?.seedFrom(m.id)
                    voiceWave?.setColors(
                        if (m.isMine) 0xFFFFFFFF.toInt() else 0xFF3390EC.toInt(),
                        if (m.isMine) 0x66FFFFFF.toInt() else 0x55808A94.toInt())
                    if (playingId == m.id && player != null) {
                        val p = player
                        try {
                            val pos = p!!.currentPosition
                            val total = maxOf(1, p.duration)
                            voiceWave?.setProgress(pos.toFloat() / total)
                            voiceDur.text = Helpers.duration(pos / 1000) +
                                    if (MessageAdapter.voiceSpeed != 1f) "  ${MessageAdapter.voiceSpeed}x" else ""
                        } catch (_: Exception) {}
                        itemView.postDelayed({ if (playingId == m.id) adapter.notifyMessageChanged(m.id) }, 250)
                    } else {
                        voiceWave?.setProgress(0f)
                    }
                    voiceBtn.setOnClickListener { toggleVoice(m, voiceBtn) }
                    // v6: tap the time to cycle playback speed 1x → 1.5x → 2x
                    voiceDur.setOnClickListener {
                        MessageAdapter.voiceSpeed = when (MessageAdapter.voiceSpeed) {
                            1f -> 1.5f; 1.5f -> 2f; else -> 1f
                        }
                        val mp = player
                        if (playingId == m.id && mp != null) {
                            try { mp.playbackParams = mp.playbackParams.setSpeed(MessageAdapter.voiceSpeed) }
                            catch (_: Exception) {}
                        }
                        adapter.notifyMessageChanged(m.id)
                    }
                    // v6: tap on waveform to seek
                    voiceWave?.setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_UP && playingId == m.id) {
                            val mp = player
                            if (mp != null) {
                                val frac = (ev.x / voiceWave.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                try { mp.seekTo((mp.duration * frac).toInt()) } catch (_: Exception) {}
                                voiceWave.setProgress(frac)
                            }
                        }
                        true
                    }
                }
            }

            // text (rich formatting: bold/italic/strike/mono/spoiler/tags/mentions)
            if (m.text.isEmpty()) text.visibility = View.GONE
            else {
                text.visibility = View.VISIBLE
                text.textSize = 15.5f * Prefs.chatFontScale
                RichText.set(text, m.text) { kind, value ->
                    adapter.onTagClick?.invoke(kind, value)
                }
            }

            // v6: comment button on channel posts
            if (commentsBtn != null) {
                if (adapter.isChannel && m.commentTo == null && m.id > 0) {
                    commentsBtn.visibility = View.VISIBLE
                    commentsBtn.text = if (m.commentCount > 0) "💬 ${m.commentCount} Comments" else "💬 Add comment"
                    commentsBtn.setOnClickListener { adapter.onCommentsClick?.invoke(m) }
                } else commentsBtn.visibility = View.GONE
            }

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
                // v6: open links in the internal browser
                linkBlock.setOnClickListener {
                    if (Prefs.useInAppBrowser) {
                        ctx.startActivity(android.content.Intent(ctx, com.telx.app.ui.common.BrowserActivity::class.java)
                            .putExtra("url", l.optString("url")))
                    } else {
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(l.optString("url"))) ) } catch (_: Exception) {}
                    }
                }
            } else linkBlock.visibility = View.GONE

            // meta
            edited.text = if (m.edited) "edited" else ""
            edited.visibility = if (m.edited) View.VISIBLE else View.GONE
            // v6: silent 🔕 + pinned 📌 flags next to the time
            if (flags != null) {
                var f = ""
                if (m.silent) f += "🔕 "
                if (m.pinned) f += "📌 "
                flags.text = f.trim()
                flags.visibility = if (f.isEmpty()) View.GONE else View.VISIBLE
            }
            if (views != null) {
                if (m.views > 0 && m.type != "system") {
                    views.visibility = View.VISIBLE
                    views.text = "👁 ${m.views}"
                } else views.visibility = View.GONE
            }
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
                        ticks.setImageResource(when {
                            m.id > 0 && m.id <= adapter.peerReadId -> R.drawable.ic_read
                            m.id > 0 && m.id <= adapter.peerDeliveredId -> R.drawable.ic_read
                            else -> R.drawable.ic_check_single
                        })
                        // Telegram blue bubble: white ticks — read = full white, sent = soft white
                        ticks.setColorFilter(
                            if (m.id > 0 && m.id <= adapter.peerReadId) 0xFFFFFFFF.toInt() else 0xB8FFFFFF.toInt())
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
                    chip.setOnClickListener { adapter.onReactClick?.invoke(m, if (r.mine) null else r.emoji) }
                    reactions.addView(chip)
                }
            } else reactions.visibility = View.GONE

            itemView.setOnClickListener {
                if (m.state == Message.STATE_FAILED && m.isMine && m.id < 0) {
                    adapter.onRetryClick?.invoke(m)
                } else {
                    adapter.onMessageClick?.invoke(m)
                }
            }
            itemView.setOnLongClickListener { adapter.onMessageLongClick?.invoke(m); true }
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
                if (MessageAdapter.voiceSpeed != 1f) {
                    try { p.playbackParams = p.playbackParams.setSpeed(MessageAdapter.voiceSpeed) } catch (_: Exception) {}
                }
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
