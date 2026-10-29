package com.telx.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.data.Chat
import com.telx.app.data.Event
import com.telx.app.data.Message
import com.telx.app.data.Prefs
import com.telx.app.data.User
import com.telx.app.data.optStringOrNull
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ChatActivity : AppCompatActivity(), Events.Listener {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var chatId = 0
    private var chat: Chat? = null
    private val messages = ArrayList<Message>()
    private lateinit var adapter: MessageAdapter
    private var replyTo: Message? = null
    private var editing: Message? = null
    private val selection = LinkedHashSet<Int>()
    private var selecting = false
    private var pendingId = AtomicInteger(-1)
    private var loadingOlder = false
    private var infoJob: Job? = null
    private var typingSentAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var draftApplied = false
    // v6: send options
    private var silentSend = false
    private var anonSend = false
    private var pendingEffect: String? = null
    private var albumGroupId = 0L

    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var avatar: AvatarView
    private lateinit var inputRow: View
    private lateinit var channelBar: View
    private lateinit var channelMuteIcon: ImageView
    private lateinit var channelMuteLabel: TextView
    private lateinit var input: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnAttach: ImageView
    private lateinit var btnMic: ImageView
    private lateinit var replyBar: View
    private lateinit var replyMode: TextView
    private lateinit var replyName: TextView
    private lateinit var replyText: TextView
    private lateinit var replyClose: ImageView
    private lateinit var pinnedBar: View
    private lateinit var pinnedText: TextView
    private lateinit var pinnedClose: ImageView
    private lateinit var recordingBar: View
    private lateinit var recordingTime: TextView
    private lateinit var root: LinearLayout
    private lateinit var empty: TextView

    private val voiceRecorder = com.telx.app.media.VoiceRecorder()
    private var recording = false
    private var recordTicker: Job? = null
    private var downloadTicker: Job? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePicked(uri, kind = "photo")
    }
    // v6: multi-photo album picker
    private val pickMultiMedia = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) sendAlbum(uris.take(6))
    }
    // v6: photo editor result
    private val editPhotoResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        ui.launch { sendEditedPhoto(File(path)) }
    }
    // v6: video trimmer result
    private val trimVideoResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        ui.launch { sendTrimmedVideo(File(path)) }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePicked(uri, kind = "file")
    }
    private val pickSticker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addStickerToPack(uri)
    }
    private var stickerPackTarget = 0

    /** Called from the sticker sheet: pick an image to add into one of my packs. */
    fun launchStickerPick(packId: Int) {
        stickerPackTarget = packId
        pickSticker.launch(arrayOf("image/*"))
    }

    private fun addStickerToPack(uri: Uri) {
        ui.launch {
            try {
                val f = copyToCache(uri, "sticker")
                if (f == null) { Helpers.toast(this@ChatActivity, "Could not read image"); return@launch }
                val up = withContext(Dispatchers.IO) { Api.upload("photo", f) }
                withContext(Dispatchers.IO) { Api.stickerAdd(stickerPackTarget, up.optString("rel")) }
                Helpers.toast(this@ChatActivity, "Sticker added to pack")
                f.delete()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Failed to add sticker")
            }
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Helpers.toast(this, "Microphone permission needed for voice messages")
    }
    private val contactPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showContactShareDialog() else Helpers.toast(this, "Contacts permission needed to share a contact")
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        chatId = intent.getIntExtra("chat_id", 0)
        if (chatId <= 0) { finish(); return }

        root = findViewById(R.id.chat_root)
        // v6: per-chat lock — gate this chat behind the app PIN when set
        if (Prefs.isChatLocked(chatId) && Prefs.appLockPin.isNotEmpty()) {
            root.visibility = View.INVISIBLE
            gateChatPin()
        }
        toolbar = findViewById(R.id.chat_toolbar)
        titleView = findViewById(R.id.chat_title)
        statusView = findViewById(R.id.chat_status)
        avatar = findViewById(R.id.chat_avatar)
        recycler = findViewById(R.id.chat_recycler)
        inputRow = findViewById(R.id.input_row)
        channelBar = findViewById(R.id.channel_bar)
        channelMuteIcon = findViewById(R.id.channel_mute_icon)
        channelMuteLabel = findViewById(R.id.channel_mute_label)
        input = findViewById(R.id.chat_input)
        btnSend = findViewById(R.id.btn_send)
        btnAttach = findViewById(R.id.btn_attach)
        btnMic = findViewById(R.id.btn_mic)
        replyBar = findViewById(R.id.reply_bar)
        replyMode = findViewById(R.id.reply_mode)
        replyName = findViewById(R.id.reply_name)
        replyText = findViewById(R.id.reply_text)
        replyClose = findViewById(R.id.reply_close)
        pinnedBar = findViewById(R.id.pinned_bar)
        pinnedText = findViewById(R.id.pinned_text)
        pinnedClose = findViewById(R.id.pinned_close)
        recordingBar = findViewById(R.id.recording_bar)
        recordingTime = findViewById(R.id.recording_time)
        empty = findViewById(R.id.chat_empty)

        WallpaperHelper.apply(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_chat)
        toolbar.setOnMenuItemClickListener { menuItem -> handleMenu(menuItem.itemId) }
        toolbar.findViewById<View>(R.id.chat_header).setOnClickListener { openPeerProfile() }

        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        recycler.layoutManager = lm
        adapter = MessageAdapter(messages)
        adapter.onMessageLongClick = { showMessageActions(it) }
        adapter.onImageClick = { showImage(it) }
        adapter.onRetryClick = { retryFailed(it) }
        adapter.onReactClick = { m, emoji -> react(m, emoji) }
        // v6 callbacks
        adapter.onEffect = { eff -> playEffect(eff) }
        adapter.onCommentsClick = { m ->
            startActivity(Intent(this, CommentsActivity::class.java)
                .putExtra("post_id", m.id).putExtra("chat_id", chatId))
        }
        adapter.onTagClick = { kind, value ->
            if (kind == "tag") showChatSearch("#$value")
            else openMention(value)
        }
        adapter.onMessageClick = { msg ->
            if (selecting) toggleSelect(msg)
            else showMessageActions(msg)
        }
        recycler.adapter = adapter
        // Telegram-style swipe-to-reply on message rows
        val swipeReply = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, android.view.View.GONE) {  // direction set per-row in getMovementFlags
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val m = adapter.messageAtRow(vh.bindingAdapterPosition) ?: return 0
                return if (m.type == "system" || m.id < 0 || selecting) 0
                else androidx.recyclerview.widget.ItemTouchHelper.Callback.makeMovementFlags(0,
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT)
            }
            override fun onChildDraw(c: android.graphics.Canvas, rv: RecyclerView,
                vh: RecyclerView.ViewHolder, dx: Float, dy: Float, act: Int, cur: Boolean) {
                val m = adapter.messageAtRow(vh.bindingAdapterPosition) ?: return super.onChildDraw(c, rv, vh, dx, dy, act, cur)
                if (act == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val abs = dx * 0.35f
                    vh.itemView.translationX = abs
                    val d = androidx.core.content.ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_reply)
                    if (d != null) {
                        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                        d.setTint(0xFF3390EC.toInt())
                        val bounds = vh.itemView
                        val x = if (dx > 0) 48f else (bounds.width - 48 - d.intrinsicWidth).toFloat()
                        c.save()
                        c.translate(x, bounds.top + bounds.height / 2f - d.intrinsicHeight / 2f)
                        c.clipRect(0, 0, bounds.width, bounds.height)
                        d.draw(c)
                        c.restore()
                    }
                }
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.translationX = 0f
                val m = adapter.messageAtRow(vh.bindingAdapterPosition) ?: return
                if (m.type != "system" && m.id > 0 && !selecting) showReplyFor(m, false)
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeReply).attachToRecyclerView(recycler)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && !loadingOlder && lm.findFirstCompletelyVisibleItemPosition() <= 3
                    && messages.isNotEmpty() && messages.first().id > 0) {
                    loadOlder()
                }
            }
        })

        btnAttach.setOnClickListener { showAttachSheet() }
        btnSend.setOnClickListener { sendCurrent() }
        btnSend.setOnLongClickListener { showSendOptions(); true }
        findViewById<ImageView>(R.id.btn_emoji).setOnClickListener {
            com.telx.app.ui.common.EmojiSheet.show(this, input)
        }
        adapter.onFileClick = { m ->
            com.telx.app.media.FileDownloader.tap(this, m)
            startDownloadTicker()
        }
        btnMic.setOnTouchListener { v, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        startRecording()
                    else
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (recording) stopRecordingAndSend()
                }
            }
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrEmpty()
                btnMic.visibility = if (hasText) View.GONE else View.VISIBLE
                btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                maybeSendTyping()
            }
            override fun beforeTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        replyClose.setOnClickListener { clearReply() }
        pinnedBar.setOnClickListener { showPinnedList() }
        pinnedClose.setOnClickListener { ui.launch { try { withContext(Dispatchers.IO) { Api.pinSet(chatId, 0, false) } } catch (_: Exception) {} } }
        recordingBar.findViewById<ImageView>(R.id.recording_cancel).setOnClickListener { cancelRecording() }

        loadInfo()
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        com.telx.app.svc.PollService.currentOpenChat = chatId
        Events.addListener(this)
        infoJob = ui.launch {
            while (true) {
                delay(3500)
                loadInfo(quiet = true)
            }
        }
        loadInfo(quiet = true)
    }

    override fun onPause() {
        super.onPause()
        com.telx.app.svc.PollService.currentOpenChat = 0
        Events.removeListener(this)
        infoJob?.cancel()
        saveDraft()
        if (recording) cancelRecording()
        adapter.stopPlayback()
    }

    // ---------- data ----------

    private fun loadInfo(quiet: Boolean = false) {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.chatInfo(chatId) }
                val c = Chat(j.getJSONObject("chat"))
                chat = c
                val peer = j.optJSONObject("peer")?.let { User(it) }
                if (!quiet) {
                    titleView.text = c.displayName
                    avatar.bind(c.displayName, c.displayAvatar, Helpers.colorFor(if (c.isSaved) -1 else c.id))
                    // privacy lock of the peer → protect our chat window from screenshots
                    if (peer != null && peer.privacyLock) {
                        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    // restore draft once per chat opening (Telegram behaviour)
                    if (!draftApplied) {
                        draftApplied = true
                        val d = c.draft
                        if (!d.isNullOrEmpty() && input.text?.isEmpty() != false) {
                            input.setText(d)
                            input.setSelection(d.length)
                        }
                    }
                }
                val typing = j.optJSONArray("typing")
                val isTyping = typing != null && typing.length() > 0
                statusView.text = when {
                    isTyping -> "typing…"
                    c.isChannel -> "${c.membersCount} subscribers"
                    c.type == "group" -> "${c.membersCount} members"
                    c.isSaved -> ""
                    else -> Helpers.lastSeenText(peer?.online == true, peer?.lastTs ?: 0)
                }
                // v6: slow mode hint in the input
                input.hint = if (c.isGroup && c.slowMode > 0 && !c.isAdmin)
                    "Slow mode: wait ${c.slowMode}s between messages" else "Message"
                statusView.setTextColor(
                    if (isTyping || peer?.online == true) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
                // pinned message
                val pm = j.optJSONObject("pinned_message")
                if (pm != null) {
                    val m = Message(pm)
                    pinnedBar.visibility = View.VISIBLE
                    pinnedText.text = "📌 " + (if (m.text.isNotEmpty()) m.text else "Media message")
                } else pinnedBar.visibility = View.GONE
                // broadcast mode: only admins can post
                if (c.isChannel && c.myRole != "admin") {
                    inputRow.visibility = View.GONE
                    channelBar.visibility = View.VISIBLE
                    channelBar.setOnClickListener { toggleChannelMute() }
                    renderChannelMute(c)
                } else {
                    inputRow.visibility = View.VISIBLE
                    channelBar.visibility = View.GONE
                }
                // sender names in group chats / channels
                adapter.showSenderNames = c.type == "group" || c.isChannel
                // read/delivery watermarks
                adapter.peerReadId = c.peerLastRead
                adapter.peerDeliveredId = maxOf(adapter.peerDeliveredId, c.peerLastDelivered)
                handler.post { if (!selecting) adapter.notifyAllChanged() }
                updateMenuForChat(c)
            } catch (e: Exception) {
                if (!quiet) Helpers.toast(this@ChatActivity, "Couldn't load chat")
            }
        }
    }

    private fun renderChannelMute(c: Chat) {
        if (c.muted) {
            channelMuteIcon.setImageResource(R.drawable.ic_muted)
            channelMuteLabel.text = "Unmute"
        } else {
            channelMuteIcon.setImageResource(R.drawable.ic_volume)
            channelMuteLabel.text = "Mute"
        }
    }

    private fun toggleChannelMute() {
        val c = chat ?: return
        ui.launch {
            try {
                withContext(Dispatchers.IO) { Api.setMute(chatId, !c.muted) }
                loadInfo(quiet = true)
                Helpers.toast(this@ChatActivity, if (!c.muted) "Channel muted" else "Channel unmuted")
            } catch (_: Exception) {}
        }
    }

    private fun updateMenuForChat(c: Chat) {
        val m = toolbar.menu
        m.findItem(R.id.chat_edit)?.isVisible = c.isChannel && c.myRole == "admin"
        m.findItem(R.id.chat_subs)?.isVisible = c.isChannel
        m.findItem(R.id.chat_leave)?.isVisible = c.isChannel
        m.findItem(R.id.chat_delete)?.isVisible = c.isChannel && c.myRole == "admin"
        m.findItem(R.id.chat_clear)?.isVisible = !c.isChannel || c.myRole == "admin"
        // v6: admin panel for groups and channels
        m.findItem(R.id.chat_admin)?.isVisible = (c.isGroup || c.isChannel) && c.isAdmin
    }

    private fun loadMessages() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.messages(chatId) }
                val list = Message.parseArray(j.optJSONArray("messages"))
                messages.clear()
                messages.addAll(list)
                adapter.notifyAllChanged()
                recycler.post { recycler.scrollToPosition(adapter.lastRow().coerceAtLeast(0)) }
                empty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                markReadUpTo()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, "Couldn't load messages")
            }
        }
    }

    private fun loadOlder() {
        val firstId = messages.firstOrNull()?.id ?: return
        if (firstId <= 1) return
        loadingOlder = true
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.messages(chatId, beforeId = firstId) }
                val older = Message.parseArray(j.optJSONArray("messages"))
                if (older.isNotEmpty() && older.last().id < firstId) {
                    messages.addAll(0, older)
                    adapter.notifyAllChanged()
                    recycler.post { recycler.scrollToPosition(older.size) }
                    empty.visibility = View.GONE
                }
            } catch (_: Exception) {} finally { loadingOlder = false }
        }
    }

    private fun markReadUpTo() {
        val last = messages.lastOrNull { it.id > 0 }?.id ?: return
        ui.launch { withContext(Dispatchers.IO) { Api.markRead(chatId, last) } }
    }

    private fun saveDraft() {
        val d = input.text?.toString() ?: ""
        if (d.isNotEmpty()) Api.tryCall("chats", "set_draft") {
            put("chat_id", chatId); put("draft", d)
        }
    }

    private fun clearDraft() {
        Api.tryCall("chats", "set_draft") { put("chat_id", chatId); put("draft", "") }
    }

    // ---------- events ----------

    override fun onEvent(e: Event) {
        if (e.chatId != chatId && e.type != "chat_cleared") return
        handler.post {
            when (e.type) {
                "message_new" -> {
                    val mj = e.data.optJSONObject("message") ?: return@post
                    val m = Message(mj)
                    val dupIdx = messages.indexOfFirst { it.id == m.id }
                    if (dupIdx >= 0) {
                        // server echo of our own message — refresh in place
                        messages[dupIdx] = m
                        adapter.notifyMessageChanged(m.id)
                    } else {
                        // drop our pending optimistic twin with same content
                        val pendIdx = messages.indexOfFirst {
                            it.id < 0 && it.state == Message.STATE_PENDING && it.type == m.type &&
                                    (it.text == m.text || (it.media == m.media && it.media != null))
                        }
                        if (pendIdx >= 0 && m.isMine) messages.removeAt(pendIdx)
                        messages.add(m)
                        adapter.notifyAllChanged()
                        recycler.post { recycler.scrollToPosition(adapter.lastRow()) }
                    }
                    empty.visibility = View.GONE
                    markReadUpTo()
                }
                "message_edited" -> {
                    val mj = e.data.optJSONObject("message") ?: return@post
                    val m = Message(mj)
                    val idx = messages.indexOfFirst { it.id == m.id }
                    if (idx >= 0) { messages[idx] = m; adapter.notifyMessageChanged(m.id) }
                }
                "message_deleted" -> {
                    val mid = e.data.optInt("message_id")
                    val idx = messages.indexOfFirst { it.id == mid }
                    if (idx >= 0) { messages.removeAt(idx); adapter.notifyAllChanged() }
                    empty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
                "message_reacted" -> {
                    val mid = e.data.optInt("message_id")
                    val idx = messages.indexOfFirst { it.id == mid }
                    if (idx >= 0) {
                        val rs = e.data.optJSONArray("reactions")
                        if (rs != null) messages[idx].reactions = com.telx.app.data.parseReactions(rs)
                        adapter.notifyMessageChanged(mid)
                    }
                }
                "message_pinned" -> loadInfo(quiet = true)
                "message_read" -> {
                    val last = e.data.optInt("last_read_id")
                    if (last > adapter.peerReadId) {
                        adapter.peerReadId = last
                        adapter.notifyAllChanged()
                    }
                }
                "message_delivered" -> {
                    val last = e.data.optInt("last_delivered_id")
                    if (last > adapter.peerDeliveredId) {
                        adapter.peerDeliveredId = last
                        adapter.notifyAllChanged()
                    }
                }
                "chat_cleared" -> { messages.clear(); adapter.notifyAllChanged(); empty.visibility = View.VISIBLE }
            }
        }
    }

    // ---------- sending ----------

    private fun maybeSendTyping() {
        val c = chat
        if (c != null && (c.isChannel || c.isSaved)) return
        val now = System.currentTimeMillis()
        if (now - typingSentAt > 3000) {
            typingSentAt = now
            ui.launch { withContext(Dispatchers.IO) { Api.typing(chatId) } }
        }
    }

    private fun sendCurrent() {
        val t = input.text?.toString()?.trim() ?: ""
        if (t.isEmpty()) return
        if (editing != null) {
            val m = editing!!
            editing = null
            clearReplyBar()
            input.setText("")
            ui.launch {
                try {
                    withContext(Dispatchers.IO) { Api.editMessage(m.id, t) }
                } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Edit failed") }
            }
            return
        }
        input.setText("")
        clearDraft()
        val rt = replyTo?.id ?: 0
        replyTo = null
        clearReplyBar()
        sendOptimistic("text", t, null, null, 0, rt, silent = silentSend, anon = anonSend, effect = pendingEffect)
        // one-shot send options reset (Telegram behavior)
        silentSend = false
        anonSend = false
        pendingEffect = null
    }

    private fun sendOptimistic(type: String, text: String, mediaRel: String?, thumbRel: String?, dur: Int, replyId: Int,
                              mediaName: String = "", mediaSize: Long = 0,
                              silent: Boolean = false, anon: Boolean = false,
                              effect: String? = null, groupId: Long = 0) {
        val m = Message(JSONObject()
            .put("id", pendingId.decrementAndGet())
            .put("chat_id", chatId)
            .put("sender_id", Prefs.myId)
            .put("sender_name", Prefs.myName)
            .put("type", type)
            .put("text", text)
            .putOpt("media", mediaRel?.let { Api.BASE + it })
            .putOpt("thumb", thumbRel?.let { Api.BASE + it })
            .put("media_duration", dur)
            .putOpt("media_name", mediaName)
            .putOpt("media_size", if (mediaSize > 0) mediaSize else null)
            .put("ts", System.currentTimeMillis() / 1000))
        m.state = Message.STATE_PENDING
        messages.add(m)
        adapter.notifyAllChanged()
        recycler.post { recycler.scrollToPosition(adapter.lastRow()) }
        empty.visibility = View.GONE

        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) {
                    Api.send(chatId, type, text, mediaRel, thumbRel, mediaName = mediaName,
                        mediaSize = mediaSize, mediaDuration = dur, replyTo = replyId,
                        silent = silent, anon = anon, effect = effect, groupId = groupId)
                }
                val real = Message(j.getJSONObject("message"))
                val idx = messages.indexOfFirst { it.id == m.id }
                if (idx >= 0) {
                    messages[idx] = real
                    adapter.notifyMessageChanged(real.id)
                }
                adapter.peerDeliveredId = maxOf(adapter.peerDeliveredId, real.id)
                if (real.effect != null) playEffect(real.effect)
            } catch (e: Exception) {
                val idx = messages.indexOfFirst { it.id == m.id }
                if (idx >= 0) {
                    messages[idx].state = Message.STATE_FAILED
                    adapter.notifyMessageChanged(m.id)
                    Helpers.toast(this@ChatActivity,
                        if (e is ApiErr) e.message else "Message not sent")
                }
            }
        }
    }

    private fun retryFailed(m: Message) {
        val idx = messages.indexOfFirst { it.id == m.id }
        if (idx < 0) return
        messages.removeAt(idx)
        adapter.notifyAllChanged()
        sendOptimistic(m.type, m.text,
            m.media?.removePrefix(Api.BASE), m.thumb?.removePrefix(Api.BASE),
            m.mediaDuration, m.replyTo ?: 0)
    }

    private fun handlePicked(uri: Uri, kind: String) {
        ui.launch {
            try {
                Helpers.toast(this@ChatActivity, "Sending…")
                val f = copyToCache(uri, kind)
                if (f == null) { Helpers.toast(this@ChatActivity, "Could not read file"); return@launch }
                val mime = try { contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
                val isVideo = kind == "photo" && (mime.startsWith("video/") ||
                    f.name.endsWith(".mp4", true) || f.name.endsWith(".mov", true) || f.name.endsWith(".webm", true) ||
                    f.name.endsWith(".3gp", true))
                if (isVideo) {
                    // video: generate a thumbnail on-device and upload both
                    val thumb = Helpers.videoThumb(this@ChatActivity, f)
                    val up = withContext(Dispatchers.IO) { Api.upload("video", f) }
                    var thumbRel: String? = null
                    if (thumb != null) {
                        try {
                            val tup = withContext(Dispatchers.IO) { Api.upload("photo", thumb) }
                            thumbRel = tup.optString("thumb_rel").ifEmpty { tup.optString("rel") }
                        } catch (_: Exception) {}
                        thumb.delete()
                    }
                    sendOptimistic("video", "", up.optString("rel"), thumbRel, 0, replyTo?.id ?: 0)
                } else if (kind == "file") {
                    // any file type: PDF/ZIP/etc with name + size shown in the bubble
                    val displayName = queryDisplayName(uri) ?: "file"
                    val up = withContext(Dispatchers.IO) { Api.upload("file", f) }
                    sendOptimistic("file", "", up.optString("rel"), null, 0, replyTo?.id ?: 0,
                        mediaName = displayName, mediaSize = up.optLong("size", f.length()))
                } else {
                    // photo: Telegram-style auto compression (GIFs stay untouched)
                    val compressed = withContext(Dispatchers.IO) { Helpers.compressImage(this@ChatActivity, f) }
                    val up = withContext(Dispatchers.IO) { Api.upload("photo", compressed) }
                    val type = if (up.optString("mime") == "image/gif") "gif" else "photo"
                    sendOptimistic(type, "", up.optString("rel"), up.optStringOrNull("thumb_rel"),
                        0, replyTo?.id ?: 0)
                    if (compressed !== f) compressed.delete()
                }
                replyTo = null; clearReplyBar()
                f.delete()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Upload failed")
            }
        }
    }

    /** original filename from the content resolver (for file bubbles). */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun copyToCache(uri: Uri, kind: String): File? = try {
        val name = "up_${System.currentTimeMillis()}_${kind}"
        val f = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.exists() && f.length() > 0) f else null
    } catch (e: Exception) { null }

    // ---------- voice ----------

    private fun startRecording() {
        try {
            voiceRecorder.start(this)
            recording = true
            recordingBar.visibility = View.VISIBLE
            var secs = 0
            recordTicker = ui.launch {
                while (recording) {
                    delay(1000); secs++
                    recordingTime.text = "● ${Helpers.duration(secs)}"
                }
            }
        } catch (e: Exception) {
            Helpers.toast(this, "Cannot start recording")
        }
    }

    private fun stopRecordingAndSend() {
        if (!recording) return
        recording = false
        recordTicker?.cancel()
        recordingBar.visibility = View.GONE
        val result = voiceRecorder.stop() ?: return
        val (f, dur) = result
        ui.launch {
            try {
                Helpers.toast(this@ChatActivity, "Uploading voice…")
                val up = withContext(Dispatchers.IO) { Api.upload("voice", f) }
                sendOptimistic("voice", "", up.optString("rel"), null, dur, replyTo?.id ?: 0)
                replyTo = null; clearReplyBar()
                f.delete()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, "Voice upload failed")
            }
        }
    }

    private fun cancelRecording() {
        if (!recording) return
        recording = false
        recordTicker?.cancel()
        recordingBar.visibility = View.GONE
        voiceRecorder.cancel()
        Helpers.toast(this, "Recording cancelled")
    }

    // ---------- actions ----------

    private fun clearReply() { replyTo = null; editing = null; clearReplyBar() }

    private fun clearReplyBar() { replyBar.visibility = View.GONE }

    private fun showReplyFor(m: Message, isEdit: Boolean) {
        replyBar.visibility = View.VISIBLE
        if (isEdit) {
            editing = m
            replyMode.text = "Editing message"
            input.setText(m.text)
            input.setSelection(m.text.length)
            input.requestFocus()
        } else {
            editing = null
            replyMode.text = "Reply to"
        }
        replyName.text = m.senderName.ifEmpty { "Saved Messages" }
        replyText.text = when {
            m.text.isNotEmpty() -> m.text
            m.hasMedia -> "Media message"
            else -> "Message"
        }
    }

    /** Long-press send → Telegram-style schedule message dialog. */
    private fun scheduleMessage() {
        val options = arrayOf("In 1 hour", "In 8 hours", "Tomorrow morning (9 AM)", "Pick below…")
        val base = System.currentTimeMillis() / 1000
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = base * 1000
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        val presets = longArrayOf(base + 3600, base + 8 * 3600, cal.timeInMillis / 1000)
        var picked = presets[0]
        val v = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val etTime = v.findViewById<EditText>(R.id.et_schedule_time)
        val preview = v.findViewById<TextView>(R.id.schedule_preview)
        fun updPreview() {
            val d = java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(picked * 1000))
            preview.text = "Will send: $d"
        }
        updPreview()
        AlertDialog.Builder(this)
            .setTitle("Schedule message")
            .setSingleChoiceItems(options, 0) { _, which ->
                if (which < 3) { picked = presets[which]; updPreview(); etTime.visibility = View.GONE }
                else etTime.visibility = View.VISIBLE
            }
            .setView(v)
            .setPositiveButton("Schedule") { _, _ ->
                val custom = etTime.text?.toString()?.toLongOrNull()
                if (etTime.visibility == View.VISIBLE) {
                    if (custom == null || custom <= System.currentTimeMillis() / 1000) {
                        Helpers.toast(this, "Enter a future epoch time"); return@setPositiveButton
                    }
                    picked = custom
                }
                val t = input.text?.toString()?.trim() ?: ""
                if (t.isEmpty()) { Helpers.toast(this, "Type a message first"); return@setPositiveButton }
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.send(chatId, "text", t, scheduledTs = picked) }
                        Helpers.toast(this@ChatActivity, "Message scheduled")
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Schedule failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownloadTicker() {
        // refresh file rows while any download for this chat is active/paused
        if (downloadTicker != null) return
        downloadTicker = ui.launch {
            while (isActive) {
                delay(400)
                val busy = messages.any {
                    val p = com.telx.app.media.FileDownloader.progress[it.id]
                    p != null && p in 0..99
                }
                adapter.notifyAllChanged()
                if (!busy) { downloadTicker?.cancel(); downloadTicker = null }
            }
        }
    }

    private fun showAttachSheet() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_attach, null)
        v.findViewById<LinearLayout>(R.id.attach_photo).setOnClickListener { d.dismiss(); pickMedia.launch(arrayOf("image/*")) }
        v.findViewById<LinearLayout>(R.id.attach_gallery).setOnClickListener { d.dismiss(); pickMedia.launch(arrayOf("image/*", "video/mp4")) }
        v.findViewById<LinearLayout>(R.id.attach_file).setOnClickListener { d.dismiss(); pickFile.launch(arrayOf("*/*")) }
        // v6 attach rows: photo editor / album / trim / music / format
        v.findViewById<LinearLayout>(R.id.attach_edit_photo).setOnClickListener { d.dismiss(); launchPhotoEditor() }
        v.findViewById<LinearLayout>(R.id.attach_album).setOnClickListener { d.dismiss(); pickMultiMedia.launch("image/*") }
        v.findViewById<LinearLayout>(R.id.attach_trim).setOnClickListener { d.dismiss(); launchVideoTrim() }
        v.findViewById<LinearLayout>(R.id.attach_music).setOnClickListener { d.dismiss(); MusicPlayerSheet.show(this, chatId) }
        v.findViewById<LinearLayout>(R.id.attach_sticker).setOnClickListener {
            d.dismiss()
            com.telx.app.ui.common.StickerPickerSheet.show(this) { url ->
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) {
                            Api.send(chatId, "sticker", stickerFile = url.removePrefix(Api.BASE))
                        }
                        val real = Message(j.getJSONObject("message"))
                        messages.add(real)
                        adapter.notifyAllChanged()
                        recycler.post { recycler.scrollToPosition(adapter.lastRow()) }
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't send sticker") }
                }
            }
        }
        v.findViewById<LinearLayout>(R.id.attach_videonote).setOnClickListener {
            d.dismiss()
            startActivity(Intent(this, VideoNoteActivity::class.java).putExtra("chat_id", chatId))
        }
        v.findViewById<LinearLayout>(R.id.attach_location).setOnClickListener { d.dismiss(); showLocationDialog() }
        v.findViewById<LinearLayout>(R.id.attach_contact).setOnClickListener { d.dismiss(); showContactShareDialog() }
        d.setContentView(v)
        d.show()
    }

    /** Share a location: prefill from last-known GPS when permitted, else manual. */
    private fun showLocationDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_share_location, null)
        val etTitle = v.findViewById<EditText>(R.id.et_loc_title)
        val etLat = v.findViewById<EditText>(R.id.et_loc_lat)
        val etLng = v.findViewById<EditText>(R.id.et_loc_lng)
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val last = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }
                if (last != null) {
                    etLat.setText("%.5f".format(last.latitude))
                    etLng.setText("%.5f".format(last.longitude))
                }
            }
        } catch (_: Exception) {}
        AlertDialog.Builder(this)
            .setTitle("Share my location")
            .setView(v)
            .setPositiveButton("Share") { _, _ ->
                val lat = etLat.text?.toString()?.toDoubleOrNull()
                val lng = etLng.text?.toString()?.toDoubleOrNull()
                if (lat == null || lng == null) { Helpers.toast(this, "Enter coordinates"); return@setPositiveButton }
                val title0 = etTitle.text?.toString()?.trim().orEmpty()
                val json = JSONObject().put("lat", lat).put("lng", lng)
                    .put("title", if (title0.isEmpty()) "My location" else title0)
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.send(chatId, "location", mediaJson = json.toString()) }
                        val real = Message(j.getJSONObject("message"))
                        messages.add(real)
                        adapter.notifyAllChanged()
                        recycler.post { recycler.scrollToPosition(adapter.lastRow()) }
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed to send location") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Share a contact card from the device phonebook (Telegram-style). */
    private fun showContactShareDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            contactPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        val names = ArrayList<String>()
        val phones = ArrayList<String>()
        try {
            val cr = contentResolver
            cr.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    names.add(c.getString(0) ?: "")
                    phones.add(c.getString(1) ?: "")
                }
            }
        } catch (_: Exception) {}
        if (names.isEmpty()) { Helpers.toast(this, "No contacts on this device"); return }
        AlertDialog.Builder(this)
            .setTitle("Share contact")
            .setItems(names.toTypedArray()) { _, which ->
                val json = JSONObject().put("name", names[which]).put("phone", phones[which])
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.send(chatId, "contact", mediaJson = json.toString()) }
                        val real = Message(j.getJSONObject("message"))
                        messages.add(real)
                        adapter.notifyAllChanged()
                        recycler.post { recycler.scrollToPosition(adapter.lastRow()) }
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed to send contact") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMessageActions(m: Message) {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_message_actions, null)
        val reactRow = v.findViewById<LinearLayout>(R.id.reaction_picker)
        val emojis = listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🎉", "⭐")
        for (e in emojis) {
            val t = TextView(this)
            t.text = e
            t.textSize = 26f
            t.setPadding(Helpers.dp(this, 10), Helpers.dp(this, 6), Helpers.dp(this, 10), Helpers.dp(this, 6))
            t.setOnClickListener {
                d.dismiss()
                react(m, if (m.reactions.any { it.mine && it.emoji == e }) null else e)
            }
            reactRow.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val mine = m.isMine && m.id > 0
        val canForward = m.isMine || chat?.peer?.privacyLock != true
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (m.type == "system" || m.id < 0) return
        items.add("Reply" to { showReplyFor(m, false) })
        if (m.text.isNotEmpty()) items.add("Copy" to {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("msg", m.text))
            Helpers.toast(this, "Copied")
        })
        if (canForward)
            items.add("Forward" to { startSelectionForForward(m) })
        if (mine && m.type == "text" && m.state == Message.STATE_OK)
            items.add("Edit" to { showReplyFor(m, true) })
        // v6: multi-pin (toggle per message)
        if (m.id > 0) {
            val c = chat
            val canPin = c?.isAdmin == true || (c != null && !c.isGroup && !c.isChannel)
            if (canPin) {
                if (m.pinned) items.add("Unpin" to { pinMsg(m.id, false) })
                else items.add("Pin" to { pinMsg(m.id, true) })
            }
        }
        items.add("Select" to { startSelection() })
        if (mine || (chat?.type == "group") || (chat?.isChannel == true && chat?.myRole == "admin"))
            items.add("Delete" to { confirmDelete(listOf(m.id)) })
        v.findViewById<LinearLayout>(R.id.msg_action_list).let { container ->
            for ((label, action) in items) {
                val t = TextView(this)
                t.text = label
                t.textSize = 16f
                t.setPadding(Helpers.dp(this, 20), Helpers.dp(this, 14), Helpers.dp(this, 20), Helpers.dp(this, 14))
                t.setOnClickListener { d.dismiss(); action() }
                container.addView(t)
            }
        }
        d.setContentView(v)
        d.show()
    }

    private fun react(m: Message, emoji: String?) {
        if (m.id < 0) return
        ui.launch {
            try { withContext(Dispatchers.IO) { Api.react(m.id, emoji) } }
            catch (e: Exception) { Helpers.toast(this@ChatActivity, "Reaction failed") }
        }
    }

    /** v6 helper: pin/unpin one message (multi-pin). */
    private fun pinMsg(mid: Int, pin: Boolean) {
        ui.launch {
            try { withContext(Dispatchers.IO) { Api.pinSet(chatId, mid, pin) } }
            catch (_: Exception) { Helpers.toast(this@ChatActivity, "Pin failed") }
        }
    }

    // ---------- selection mode ----------

    private fun startSelection() {
        selecting = true
        selection.clear()
        updateSelectionToolbar()
    }

    private fun startSelectionForForward(m: Message) {
        startSelection()
        toggleSelect(m)
        showForwardSheet()
    }

    private fun toggleSelect(m: Message) {
        if (m.id < 0) return
        if (selection.contains(m.id)) selection.remove(m.id) else selection.add(m.id)
        updateSelectionToolbar()
    }

    private fun updateSelectionToolbar() {
        if (!selecting || selection.isEmpty()) {
            selecting = false
            toolbar.menu.clear()
            toolbar.inflateMenu(R.menu.menu_chat)
            toolbar.setOnMenuItemClickListener { menuItem -> handleMenu(menuItem.itemId) }
            titleView.visibility = View.VISIBLE
            loadInfo(quiet = true)
            return
        }
        titleView.text = "${selection.size} selected"
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_chat_select)
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sel_delete -> confirmDelete(selection.toList())
                R.id.sel_forward -> showForwardSheet()
                R.id.sel_copy -> {
                    val sb = StringBuilder()
                    messages.filter { selection.contains(it.id) }.forEach { sb.appendLine(it.text) }
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("msgs", sb.toString()))
                    Helpers.toast(this, "Copied ${selection.size} messages")
                    exitSelection()
                }
            }
            true
        }
    }

    private fun exitSelection() {
        selecting = false
        selection.clear()
        updateSelectionToolbar()
    }

    private fun confirmDelete(ids: List<Int>) {
        AlertDialog.Builder(this)
            .setMessage("Delete ${ids.size} message(s)?")
            .setPositiveButton("Delete") { _, _ ->
                ui.launch {
                    for (id in ids) {
                        try { withContext(Dispatchers.IO) { Api.deleteMessage(id) } } catch (_: Exception) {}
                    }
                    if (selecting) exitSelection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- forward ----------

    private fun showForwardSheet() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.chatsList() }
                val chats = ArrayList<Chat>()
                val arr = j.optJSONArray("chats")
                if (arr != null) for (i in 0 until arr.length()) chats.add(Chat(arr.getJSONObject(i)))
                val names = chats.map { it.displayName }.toTypedArray()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ChatActivity)
                        .setTitle("Forward to…")
                        .setItems(names) { _, which ->
                            val target = chats[which]
                            ui.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Api.forward(selection.toList().ifEmpty { listOf((messages.lastOrNull()?.id ?: 0)) }, target.id)
                                    }
                                    Helpers.toast(this@ChatActivity, "Forwarded")
                                } catch (e: Exception) {
                                    Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Forward failed")
                                }
                                exitSelection()
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ -> exitSelection() }
                        .show()
                }
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't load chats") }
        }
    }

    // ---------- misc ----------

    private fun openPeerProfile() {
        val c = chat ?: return
        when {
            c.type == "private" -> c.peer?.let {
                startActivity(Intent(this, com.telx.app.ui.profile.UserProfileActivity::class.java).putExtra("user_id", it.id))
            }
            c.type == "group" -> showGroupInfo()
            c.isChannel -> showChannelInfo()
        }
    }

    private fun showGroupInfo() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.groupInfo(chatId) }
                val members = com.telx.app.data.User.parseArray(j.optJSONArray("members"))
                val names = members.map { (if (it.role == "admin") "★ " else "") + it.name }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("${chat?.displayName} · ${members.size} members")
                    .setItems(names) { _, which ->
                        val u = members[which]
                        if (!u.isMe) startActivity(
                            Intent(this@ChatActivity, com.telx.app.ui.profile.UserProfileActivity::class.java)
                                .putExtra("user_id", u.id))
                    }
                    .setPositiveButton("Leave group") { _, _ ->
                        ui.launch {
                            try { withContext(Dispatchers.IO) { Api.leaveGroup(chatId) }; finish() }
                            catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't load group") }
        }
    }

    private fun confirmDeleteChannel() {
        AlertDialog.Builder(this)
            .setTitle("Delete channel")
            .setMessage("The channel \"${chat?.displayName}\" and all of its messages will be removed for everyone. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.deleteChannel(chatId) }
                        Helpers.toast(this@ChatActivity, "Channel deleted")
                        finish()
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Delete failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showChannelInfo() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.channelInfo(chatId) }
                val c = Chat(j.getJSONObject("chat"))
                val subs = com.telx.app.data.User.parseArray(j.optJSONArray("subscribers"))
                withContext(Dispatchers.Main) { renderChannelSheet(c, subs) }
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't load channel") }
        }
    }

    private val channelPhotoResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        ui.launch {
            try {
                val f = File(path)
                val up = withContext(Dispatchers.IO) { Api.upload("avatar", f) }
                withContext(Dispatchers.IO) { Api.updateChannel(chatId, avatarRel = up.optString("rel")) }
                f.delete()
                loadInfo()
                Helpers.toast(this@ChatActivity, "Channel photo updated")
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, "Upload failed")
            }
        }
    }

    private val addSubscribersResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val ids = res.data?.getIntegerArrayListExtra("selected_ids") ?: return@registerForActivityResult
        if (ids.isEmpty()) return@registerForActivityResult
        ui.launch {
            try {
                withContext(Dispatchers.IO) { Api.addMembers(chatId, ids) }
                Helpers.toast(this@ChatActivity, "Subscribers added")
                loadInfo()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Failed")
            }
        }
    }

    private fun renderChannelSheet(c: Chat, subs: List<User>) {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_channel_info, null)
        val avatar = v.findViewById<AvatarView>(R.id.channel_sheet_avatar)
        val name = v.findViewById<TextView>(R.id.channel_sheet_name)
        val subsText = v.findViewById<TextView>(R.id.channel_sheet_subs)
        val desc = v.findViewById<TextView>(R.id.channel_sheet_desc)
        val link = v.findViewById<TextView>(R.id.channel_sheet_link)
        val isAdmin = c.myRole == "admin"

        avatar.bind(c.displayName, c.displayAvatar, Helpers.colorFor(c.id))
        name.text = c.displayName
        subsText.text = "${c.membersCount} subscribers"
        desc.text = if (c.description.isNullOrEmpty()) "No description" else c.description
        link.text = if (!c.username.isNullOrEmpty()) "telx.ch/${c.username}" else "Private channel — invite only"
        link.visibility = View.VISIBLE

        v.findViewById<View>(R.id.channel_sheet_photo_btn).visibility = if (isAdmin) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.channel_sheet_photo_btn).setOnClickListener {
            d.dismiss()
            channelPhotoResult.launch(Intent(this, com.telx.app.ui.common.CropActivity::class.java)
                .putExtra("mode", "circle"))
        }

        val addRow = v.findViewById<View>(R.id.channel_add_subs)
        addRow.visibility = if (isAdmin) View.VISIBLE else View.GONE
        addRow.setOnClickListener {
            d.dismiss()
            addSubscribersResult.launch(Intent(this, com.telx.app.ui.contacts.PickUsersActivity::class.java)
                .putExtra("result_mode", true))
        }
        val privateRow = v.findViewById<View>(R.id.channel_toggle_private)
        privateRow.visibility = if (isAdmin) View.VISIBLE else View.GONE
        privateRow.findViewById<TextView>(R.id.channel_private_label).text =
            if (c.username.isNullOrEmpty()) "Make channel public" else "Make channel private"
        privateRow.setOnClickListener {
            d.dismiss()
            if (c.username.isNullOrEmpty()) {
                // going public requires choosing a public link
                showEditChannelDialog()
            } else {
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.updateChannel(chatId, username = "") }
                        Helpers.toast(this@ChatActivity, "Channel is private now")
                        loadInfo()
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Failed")
                    }
                }
            }
        }
        val adminsRow = v.findViewById<View>(R.id.channel_manage_admins)
        adminsRow.visibility = if (isAdmin && subs.isNotEmpty()) View.VISIBLE else View.GONE
        adminsRow.setOnClickListener { d.dismiss(); manageChannelAdmins(subs) }
        val deleteRow = v.findViewById<View>(R.id.channel_delete)
        deleteRow.visibility = if (isAdmin) View.VISIBLE else View.GONE
        deleteRow.setOnClickListener { d.dismiss(); confirmDeleteChannel() }
        v.findViewById<View>(R.id.channel_leave).setOnClickListener {
            d.dismiss()
            AlertDialog.Builder(this)
                .setMessage("Leave this channel?")
                .setPositiveButton("Leave") { _, _ ->
                    ui.launch {
                        try { withContext(Dispatchers.IO) { Api.leaveChannel(chatId) }; finish() }
                        catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
        d.setContentView(v)
        d.show()
    }

    private fun manageChannelAdmins(subs: List<User>) {
        val names = subs.map { (if (it.role == "admin") "★ " else "") + it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subscribers")
            .setItems(names) { _, which ->
                val u = subs[which]
                if (u.id == Prefs.myId) return@setItems
                val makeAdmin = u.role != "admin"
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.setChannelAdmin(chatId, u.id, makeAdmin) }
                        Helpers.toast(this@ChatActivity, if (makeAdmin) "Promoted to admin" else "Demoted")
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                }
            }
            .show()
    }

    private fun showEditChannelDialog() {
        val c = chat ?: return
        val v = layoutInflater.inflate(R.layout.dialog_channel, null)
        val title = v.findViewById<EditText>(R.id.et_channel_title)
        val desc = v.findViewById<EditText>(R.id.et_channel_desc)
        val username = v.findViewById<EditText>(R.id.et_channel_username)
        title.setText(c.title)
        desc.setText(c.description ?: "")
        username.setText(c.username ?: "")
        AlertDialog.Builder(this)
            .setTitle("Edit channel")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            Api.updateChannel(chatId,
                                title = title.text?.toString()?.trim(),
                                description = desc.text?.toString()?.trim() ?: "",
                                username = username.text?.toString()?.trim()?.removePrefix("@"))
                        }
                        loadInfo()
                        Helpers.toast(this@ChatActivity, "Channel updated")
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Update failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImage(m: Message) {
        // v6: videos open in the internal PiP-capable player
        if (m.type == "video" && m.media != null) {
            startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra("url", m.media))
            return
        }
        val dialog = AlertDialog.Builder(this).create()
        val iv = ImageView(this)
        iv.setBackgroundColor(Color.BLACK)
        iv.adjustViewBounds = true
        Glide.with(this).load(m.media ?: m.thumb).into(iv)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.setContentView(iv)
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleMenu(id: Int): Boolean {
        when (id) {
            R.id.chat_search -> showChatSearch()
            R.id.chat_mute -> {
                val muted = chat?.muted == true
                ui.launch {
                    try { withContext(Dispatchers.IO) { Api.setMute(chatId, !muted) }
                        Helpers.toast(this@ChatActivity, if (!muted) "Muted" else "Unmuted") }
                    catch (e: Exception) {}
                }
            }
            R.id.chat_edit -> showEditChannelDialog()
            R.id.chat_subs -> showChannelInfo()
            R.id.chat_delete -> confirmDeleteChannel()
            R.id.chat_leave -> AlertDialog.Builder(this)
                .setMessage("Leave this channel?")
                .setPositiveButton("Leave") { _, _ ->
                    ui.launch {
                        try { withContext(Dispatchers.IO) { Api.leaveChannel(chatId) }; finish() }
                        catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                    }
                }
                .setNegativeButton("Cancel", null).show()
            R.id.chat_clear -> AlertDialog.Builder(this)
                .setMessage("Clear all history in this chat?")
                .setPositiveButton("Clear") { _, _ ->
                    ui.launch { try { withContext(Dispatchers.IO) { Api.clearHistory(chatId) } } catch (_: Exception) {} }
                }
                .setNegativeButton("Cancel", null).show()
            R.id.chat_media ->
                startActivity(Intent(this, MediaGalleryActivity::class.java)
                    .putExtra("chat_id", chatId))
            R.id.chat_music -> MusicPlayerSheet.show(this, chatId)
            R.id.chat_jump -> jumpToDate()
            R.id.chat_pins -> showPinnedList()
            R.id.chat_admin -> showAdminSheet()
            R.id.chat_report -> reportChat()
            R.id.chat_export -> showExportDialog()
            R.id.chat_autodelete -> showAutoDeleteDialog()
        }
        return true
    }

    /** Export chat history as TXT or JSON — saves to cache and shares (Telegram-like). */
    private fun showExportDialog() {
        val formats = arrayOf("Export as TXT", "Export as JSON")
        AlertDialog.Builder(this)
            .setTitle("Export chat history")
            .setItems(formats) { _, which ->
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.exportChat(chatId) }
                        val arr = j.optJSONArray("messages") ?: org.json.JSONArray()
                        val title = (j.optString("title").ifEmpty { "chat" })
                            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                        val content: String = if (which == 0) {
                            buildString {
                                append("TelX export: $title\n")
                                append("Messages: ${arr.length()}\n\n")
                                for (i in 0 until arr.length()) {
                                    val m = arr.optJSONObject(i) ?: continue
                                    val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                        .format(java.util.Date(m.optLong("ts", 0) * 1000))
                                    val who = m.optString("sender_name").ifEmpty { m.optString("fwd_from", "?") }
                                    val body = m.optString("text").ifEmpty {
                                        "[" + m.optString("type").uppercase() + "] " + m.optString("media", "") }
                                    append("$t  $who:  $body\n")
                                }
                            }
                        } else {
                            arr.toString(2)
                        }
                        val ext = if (which == 0) "txt" else "json"
                        val f = File(cacheDir, "TelX_${title}_${System.currentTimeMillis()}.$ext")
                        f.writeText(content)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@ChatActivity, "$packageName.files", f)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(if (which == 0) "text/plain" else "application/json")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "Export chat history"))
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Export failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Telegram-style auto-delete (TTL) picker for this chat. */
    private fun showAutoDeleteDialog() {
        val options = arrayOf("Off", "24 hours", "7 days", "1 month")
        val secs = intArrayOf(0, 86400, 7 * 86400, 30 * 86400)
        AlertDialog.Builder(this)
            .setTitle("Auto-delete messages in this chat")
            .setSingleChoiceItems(options, -1) { d, which ->
                d.dismiss()
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.setAutoDelete(chatId, secs[which]) }
                        Helpers.toast(this@ChatActivity,
                            if (which == 0) "Auto-delete disabled" else "New messages auto-delete after ${options[which]}")
                    } catch (e: Exception) {
                        Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChatSearch(prefill: String = "") {
        val v = layoutInflater.inflate(R.layout.dialog_search_in_chat, null)
        val searchInput = v.findViewById<EditText>(R.id.et_chat_search)
        if (prefill.isNotEmpty()) searchInput.setText(prefill)
        AlertDialog.Builder(this)
            .setTitle("Search in chat")
            .setView(v)
            .setPositiveButton("Search") { _, _ ->
                val q = searchInput.text?.toString()?.trim() ?: ""
                if (q.isEmpty()) return@setPositiveButton
                ui.launch {
                    try {
                        val arr = withContext(Dispatchers.IO) { Api.searchInChat(chatId, q) }
                        val results = Message.parseArray(arr)
                        if (results.isEmpty()) { Helpers.toast(this@ChatActivity, "No results"); return@launch }
                        val labels = results.map { "${it.senderName}: ${it.text.take(60)}" }.toTypedArray()
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("${results.size} results")
                            .setItems(labels) { _, which ->
                                val m = results[which]
                                val idx = messages.indexOfFirst { it.id == m.id }
                                if (idx >= 0) recycler.scrollToPosition(idx)
                                else Helpers.toast(this@ChatActivity, "Scroll up to load this message")
                            }
                            .show()
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Search failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onBackPressed() {
        if (selecting) { exitSelection(); return }
        if (replyTo != null || editing != null) { clearReply(); return }
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ================= v6 features =================

    /** per-chat lock: PIN gate (same PIN as the app lock). */
    private fun gateChatPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter your PIN"
            setPadding(Helpers.dp(this@ChatActivity, 24), 0, Helpers.dp(this@ChatActivity, 24), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("This chat is locked")
            .setCancelable(false)
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text?.toString() ?: ""
                if (com.telx.app.ui.settings.AppLock.hash(pin) == Prefs.appLockPin) {
                    com.telx.app.ui.settings.AppLock.unlockSuccess()
                    root.visibility = View.VISIBLE
                } else {
                    Helpers.toast(this, "Wrong PIN")
                    finish()
                }
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .show()
    }

    /** message effects overlay (confetti / fireworks / hearts). */
    private fun playEffect(effect: String?) {
        if (effect.isNullOrEmpty()) return
        val ev = com.telx.app.ui.common.EffectView(this)
        root.addView(ev, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        ev.play(effect)
    }

    /** long-press send → schedule / silent / anonymous / effects menu. */
    private fun showSendOptions() {
        val canAnon = chat?.isAdmin == true && (chat?.isGroup == true || chat?.isChannel == true)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Schedule message" to { scheduleMessage() })
        if (silentSend) items.add("Send without sound ✓ (tap to turn off)" to { silentSend = false })
        else items.add("Send without sound" to { silentSend = true; Helpers.toast(this, "Next message will be sent silently") })
        if (canAnon) {
            if (anonSend) items.add("Send as channel ✓ (tap to turn off)" to { anonSend = false })
            else items.add("Send as channel (anon)" to { anonSend = true; Helpers.toast(this, "Next post will be sent as the channel") })
        }
        items.add("Message effect…" to { pickEffect() })
        items.add("Format text" to { showFormatDialog() })
        val labels = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Send options")
            .setItems(labels) { _, which -> items[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickEffect() {
        val effects = arrayOf("No effect", "🎉 Confetti", "🎆 Fireworks", "❤️ Hearts")
        AlertDialog.Builder(this)
            .setTitle("Message effect")
            .setSingleChoiceItems(effects, 0) { d, which ->
                pendingEffect = when (which) {
                    1 -> "confetti"; 2 -> "fireworks"; 3 -> "hearts"; else -> null
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Telegram-style format toolbar for the selected text. */
    private fun showFormatDialog() {
        val labels = arrayOf("Bold  **text**", "Italic  __text__", "Strikethrough  ~~text~~",
            "Mono  `text`", "Spoiler  ||text||")
        AlertDialog.Builder(this)
            .setTitle("Format text")
            .setItems(labels) { _, which ->
                val marker = when (which) {
                    0 -> "**"; 1 -> "__"; 2 -> "~~"; 3 -> "`"; else -> "||"
                }
                com.telx.app.ui.common.RichText.wrap(input, marker)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- photo editor flow ----
    private val pickForEdit = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) editPhotoResult.launch(Intent(this, PhotoEditActivity::class.java).putExtra("uri", uri))
    }
    private fun launchPhotoEditor() { pickForEdit.launch(arrayOf("image/*")) }

    // ---- video trim flow ----
    private val pickForTrim = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) trimVideoResult.launch(Intent(this, VideoTrimActivity::class.java).putExtra("uri", uri))
    }
    private fun launchVideoTrim() { pickForTrim.launch(arrayOf("video/*")) }

    /** send several photos as one album (shared group id → grouped grid). */
    private fun sendAlbum(uris: List<Uri>) {
        val gid = System.currentTimeMillis()
        albumGroupId = gid
        Helpers.toast(this, "Sending ${uris.size} photos…")
        ui.launch {
            for (uri in uris) {
                try {
                    val f = copyToCache(uri, "album") ?: continue
                    val compressed = withContext(Dispatchers.IO) { Helpers.compressImage(this@ChatActivity, f) }
                    val up = withContext(Dispatchers.IO) { Api.upload("photo", compressed) }
                    sendOptimistic(if (up.optString("mime") == "image/gif") "gif" else "photo", "",
                        up.optString("rel"), up.optStringOrNull("thumb_rel"), 0, 0, groupId = gid)
                    if (compressed !== f) compressed.delete()
                    f.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun sendEditedPhoto(f: File) {
        try {
            val up = withContext(Dispatchers.IO) { Api.upload("photo", f) }
            sendOptimistic("photo", "", up.optString("rel"), up.optStringOrNull("thumb_rel"), 0, replyTo?.id ?: 0)
            f.delete()
        } catch (e: Exception) {
            Helpers.toast(this, if (e is ApiErr) e.message else "Upload failed")
        }
    }

    private suspend fun sendTrimmedVideo(f: File) {
        try {
            val thumb = withContext(Dispatchers.IO) { Helpers.videoThumb(this@ChatActivity, f) }
            val up = withContext(Dispatchers.IO) { Api.upload("video", f) }
            var thumbRel: String? = null
            if (thumb != null) {
                try { thumbRel = withContext(Dispatchers.IO) { Api.upload("photo", thumb) }.optString("rel") }
                catch (_: Exception) {}
                thumb.delete()
            }
            sendOptimistic("video", "", up.optString("rel"), thumbRel, 0, replyTo?.id ?: 0)
            f.delete()
        } catch (e: Exception) {
            Helpers.toast(this, if (e is ApiErr) e.message else "Upload failed")
        }
    }

    /** list of all pinned messages (multi-pin) with quick unpin. */
    private fun showPinnedList() {
        ui.launch {
            try {
                val pins = withContext(Dispatchers.IO) { Api.pinList(chatId) }
                if (pins.isEmpty()) { Helpers.toast(this@ChatActivity, "No pinned messages"); return@launch }
                val labels = pins.map { "📌 ${it.text.ifEmpty { "[${it.type}]" }.take(60)}" }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("${pins.size} pinned")
                    .setItems(labels) { _, which ->
                        val m = pins[which]
                        val idx = messages.indexOfFirst { it.id == m.id }
                        if (idx >= 0) recycler.scrollToPosition(adapter.rowOfMessage(m.id).coerceAtLeast(0))
                    }
                    .setNeutralButton("Unpin all") { _, _ ->
                        ui.launch { try { withContext(Dispatchers.IO) { Api.pinSet(chatId, 0, false) } } catch (_: Exception) {} }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't load pins") }
        }
    }

    /** jump-to-date calendar (Telegram-style "go to date"). */
    private fun jumpToDate() {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, mo, d ->
            val date = "%04d-%02d-%02d".format(y, mo + 1, d)
            ui.launch {
                try {
                    val list = withContext(Dispatchers.IO) { Api.messagesByDate(chatId, date) }
                    if (list.isEmpty()) { Helpers.toast(this@ChatActivity, "Nothing around $date"); return@launch }
                    messages.clear()
                    messages.addAll(list)
                    adapter.notifyAllChanged()
                    recycler.post { recycler.scrollToPosition(0) }
                    empty.visibility = View.GONE
                } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Couldn't jump to date") }
            }
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
            .show()
    }

    /** report this chat/group/channel for spam (v6 moderation). */
    private fun reportChat() {
        val reasons = arrayOf("Spam", "Fake account", "Violence", "Pornography", "Other")
        AlertDialog.Builder(this)
            .setTitle("Report ${chat?.displayName}")
            .setItems(reasons) { _, which ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.report("chat", chatId, reasons[which]) }
                        Helpers.toast(this@ChatActivity, "Report sent. Thank you.")
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Report failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** tap an @mention → open that user's profile. */
    private fun openMention(username: String) {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.searchUsers(username) }
                if (arr.length() == 0) { Helpers.toast(this@ChatActivity, "User @$username not found"); return@launch }
                val uid = arr.getJSONObject(0).optInt("id")
                startActivity(Intent(this@ChatActivity, com.telx.app.ui.profile.UserProfileActivity::class.java)
                    .putExtra("user_id", uid))
            } catch (_: Exception) {}
        }
    }

    /** v6 group/channel admin panel: slow mode, permissions, join requests,
     *  invite links, banned members, statistics. */
    private fun showAdminSheet() {
        val c = chat ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (c.isGroup) items.add("Slow mode" to { pickSlowMode() })
        items.add("Member permissions" to { pickPerms() })
        items.add("Join requests" to { showJoinRequests() })
        items.add("Invite links" to { showInviteLinks() })
        items.add("Banned members" to { showBannedMembers() })
        items.add("Statistics" to {
            startActivity(Intent(this, com.telx.app.ui.settings.StatsActivity::class.java)
                .putExtra("chat_id", chatId))
        })
        AlertDialog.Builder(this)
            .setTitle("Manage ${c.displayName}")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickSlowMode() {
        val labels = arrayOf("Off", "10 seconds", "30 seconds", "1 minute", "5 minutes", "15 minutes")
        val secs = intArrayOf(0, 10, 30, 60, 300, 900)
        AlertDialog.Builder(this)
            .setTitle("Slow mode — wait time between member messages")
            .setSingleChoiceItems(labels, -1) { d, which ->
                d.dismiss()
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.setSlowMode(chatId, secs[which]) }
                        Helpers.toast(this@ChatActivity, "Slow mode: ${labels[which]}")
                        loadInfo(quiet = true)
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickPerms() {
        val c = chat ?: return
        val rights = listOf("send_media" to "Send photos & media", "send_links" to "Send links",
            "send_stickers" to "Send stickers & GIFs", "add_members" to "Add new members")
        val cur = c.perms
        val states = rights.map { it.first }.map { cur?.optInt(it, 1) == 1 }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Member permissions (members other than admins)")
            .setMultiChoiceItems(rights.map { it.second }.toTypedArray(), states) { _, which, checked ->
                states[which] = checked
            }
            .setPositiveButton("Save") { _, _ ->
                ui.launch {
                    try {
                        val j = org.json.JSONObject()
                        rights.forEachIndexed { i, (key, _) -> j.put(key, if (states[i]) 1 else 0) }
                        withContext(Dispatchers.IO) { Api.setPerms(chatId, j) }
                        Helpers.toast(this@ChatActivity, "Permissions saved")
                        loadInfo(quiet = true)
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJoinRequests() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.requestsList(chatId) }
                if (arr.length() == 0) { Helpers.toast(this@ChatActivity, "No pending requests"); return@launch }
                val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Join requests")
                    .setItems(names) { _, which ->
                        val uid = arr.getJSONObject(which).optInt("id")
                        AlertDialog.Builder(this@ChatActivity)
                            .setMessage("Approve ${names[which]}?")
                            .setPositiveButton("Approve") { _, _ ->
                                ui.launch { try { withContext(Dispatchers.IO) { Api.requestDecide(chatId, uid, true) } } catch (_: Exception) {} }
                            }
                            .setNegativeButton("Decline") { _, _ ->
                                ui.launch { try { withContext(Dispatchers.IO) { Api.requestDecide(chatId, uid, false) } } catch (_: Exception) {} }
                            }
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Only admins can review requests") }
        }
    }

    private fun showInviteLinks() {
        ui.launch {
            try {
                val links = withContext(Dispatchers.IO) { Api.linksList(chatId) }
                val labels = links.map {
                    "${if (it.revoked) "⛔ " else "🔗 "}${it.name.ifEmpty { "Invite link" }} — ${it.uses}" +
                        (if (it.usageLimit > 0) "/${it.usageLimit}" else "") + " uses" +
                        (if (it.expiresTs > 0) " · expires" else "")
                }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Invite links")
                    .setItems(labels) { _, which ->
                        val l = links[which]
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("invite", l.link))
                        Helpers.toast(this@ChatActivity, "Link copied: ${l.link}")
                    }
                    .setNeutralButton("+ New link") { _, _ -> createInviteLink() }
                    .setPositiveButton("Revoke") { _, _ ->
                        AlertDialog.Builder(this@ChatActivity)
                            .setItems(links.filter { !it.revoked }.map { it.name.ifEmpty { it.link } }.toTypedArray()) { _, w ->
                                val target = links.filter { !it.revoked }[w]
                                ui.launch { try { withContext(Dispatchers.IO) { Api.linkRevoke(chatId, target.id) } } catch (_: Exception) {} }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed to load links") }
        }
    }

    private fun createInviteLink() {
        val v = layoutInflater.inflate(R.layout.dialog_link_create, null)
        val name = v.findViewById<EditText>(R.id.et_link_name)
        val days = v.findViewById<EditText>(R.id.et_link_days)
        val limit = v.findViewById<EditText>(R.id.et_link_limit)
        AlertDialog.Builder(this)
            .setTitle("New invite link")
            .setView(v)
            .setPositiveButton("Create") { _, _ ->
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) {
                            Api.linkCreate(chatId,
                                name.text?.toString()?.trim() ?: "",
                                days.text?.toString()?.toIntOrNull() ?: 0,
                                limit.text?.toString()?.toIntOrNull() ?: 0)
                        }
                        Helpers.toast(this@ChatActivity, "Created: ${j.optString("link")}")
                    } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBannedMembers() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.bannedList(chatId) }
                if (arr.length() == 0) { Helpers.toast(this@ChatActivity, "No banned members"); return@launch }
                val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Banned members")
                    .setItems(names) { _, which ->
                        val uid = arr.getJSONObject(which).optInt("id")
                        AlertDialog.Builder(this@ChatActivity)
                            .setMessage("Unban ${names[which]}?")
                            .setPositiveButton("Unban") { _, _ ->
                                ui.launch { try { withContext(Dispatchers.IO) { Api.banMember(chatId, uid, false) } } catch (_: Exception) {} }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@ChatActivity, "Failed") }
        }
    }
}
