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

    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var avatar: AvatarView
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

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePicked(uri, kind = "photo")
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePicked(uri, kind = "file")
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Helpers.toast(this, "Microphone permission needed for voice messages")
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        chatId = intent.getIntExtra("chat_id", 0)
        if (chatId <= 0) { finish(); return }

        root = findViewById(R.id.chat_root)
        toolbar = findViewById(R.id.chat_toolbar)
        titleView = findViewById(R.id.chat_title)
        statusView = findViewById(R.id.chat_status)
        avatar = findViewById(R.id.chat_avatar)
        recycler = findViewById(R.id.chat_recycler)
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
        adapter.onMessageClick = { msg ->
            if (selecting) toggleSelect(msg)
        }
        recycler.adapter = adapter
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
        pinnedClose.setOnClickListener { ui.launch { try { withContext(Dispatchers.IO) { Api.unpin(chatId) } } catch (_: Exception) {} } }
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
                }
                val typing = j.optJSONArray("typing")
                val isTyping = typing != null && typing.length() > 0
                statusView.text = when {
                    isTyping -> "typing…"
                    c.type == "group" -> "${c.membersCount} members"
                    c.isSaved -> ""
                    else -> Helpers.lastSeenText(peer?.online == true, peer?.lastTs ?: 0)
                }
                statusView.setTextColor(
                    if (isTyping || peer?.online == true) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
                // pinned message
                val pm = j.optJSONObject("pinned_message")
                if (pm != null) {
                    val m = Message(pm)
                    pinnedBar.visibility = View.VISIBLE
                    pinnedText.text = "📌 " + (if (m.text.isNotEmpty()) m.text else "Media message")
                } else pinnedBar.visibility = View.GONE
                // update read watermark
                adapter.peerReadId = c.peerLastRead
                handler.post { if (!selecting) adapter.notifyDataSetChanged() }
            } catch (e: Exception) {
                if (!quiet) Helpers.toast(this@ChatActivity, "Couldn't load chat")
            }
        }
    }

    private fun loadMessages() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.messages(chatId) }
                val list = Message.parseArray(j.optJSONArray("messages"))
                messages.clear()
                messages.addAll(list)
                adapter.notifyDataSetChanged()
                recycler.post { recycler.scrollToPosition((messages.size - 1).coerceAtLeast(0)) }
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
                    adapter.notifyDataSetChanged()
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

    // ---------- events ----------

    override fun onEvent(e: Event) {
        if (e.chatId != chatId && e.type != "chat_cleared") return
        handler.post {
            when (e.type) {
                "message_new" -> {
                    val mj = e.data.optJSONObject("message") ?: return@post
                    val m = Message(mj)
                    val dupIdx = messages.indexOfFirst { it.id == m.id }
                    if (dupIdx >= 0) { messages[dupIdx] = m }
                    else {
                        // drop our pending optimistic twin with same text
                        val pendIdx = messages.indexOfFirst {
                            it.id < 0 && it.state == Message.STATE_PENDING && it.type == m.type &&
                                    (it.text == m.text || (it.media == m.media && it.media != null))
                        }
                        if (pendIdx >= 0 && m.isMine) messages.removeAt(pendIdx)
                        messages.add(m)
                    }
                    adapter.notifyItemInserted(messages.size - 1)
                    recycler.post { recycler.scrollToPosition(messages.size - 1) }
                    empty.visibility = View.GONE
                    markReadUpTo()
                }
                "message_edited" -> {
                    val mj = e.data.optJSONObject("message") ?: return@post
                    val m = Message(mj)
                    val idx = messages.indexOfFirst { it.id == m.id }
                    if (idx >= 0) { messages[idx] = m; adapter.notifyItemChanged(idx) }
                }
                "message_deleted" -> {
                    val mid = e.data.optInt("message_id")
                    val idx = messages.indexOfFirst { it.id == mid }
                    if (idx >= 0) { messages.removeAt(idx); adapter.notifyItemRemoved(idx) }
                    empty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
                "message_reacted" -> {
                    val mid = e.data.optInt("message_id")
                    val idx = messages.indexOfFirst { it.id == mid }
                    if (idx >= 0) {
                        val rs = e.data.optJSONArray("reactions")
                        if (rs != null) messages[idx].reactions = com.telx.app.data.parseReactions(rs)
                        adapter.notifyItemChanged(idx)
                    }
                }
                "message_pinned" -> loadInfo(quiet = true)
                "message_read" -> {
                    val last = e.data.optInt("last_read_id")
                    if (last > adapter.peerReadId) {
                        adapter.peerReadId = last
                        adapter.notifyDataSetChanged()
                    }
                }
                "chat_cleared" -> { messages.clear(); adapter.notifyDataSetChanged(); empty.visibility = View.VISIBLE }
            }
        }
    }

    // ---------- sending ----------

    private fun maybeSendTyping() {
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
        val rt = replyTo?.id ?: 0
        replyTo = null
        clearReplyBar()
        sendOptimistic("text", t, null, null, 0, rt)
    }

    private fun sendOptimistic(type: String, text: String, mediaRel: String?, thumbRel: String?, dur: Int, replyId: Int) {
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
            .put("ts", System.currentTimeMillis() / 1000))
        m.state = Message.STATE_PENDING
        messages.add(m)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.post { recycler.scrollToPosition(messages.size - 1) }
        empty.visibility = View.GONE

        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) {
                    Api.send(chatId, type, text, mediaRel, thumbRel, mediaDuration = dur, replyTo = replyId)
                }
                val real = Message(j.getJSONObject("message"))
                val idx = messages.indexOfFirst { it.id == m.id }
                if (idx >= 0) {
                    messages[idx] = real
                    adapter.notifyItemChanged(idx)
                }
                adapter.peerReadId = maxOf(adapter.peerReadId, real.id)
            } catch (e: Exception) {
                val idx = messages.indexOfFirst { it.id == m.id }
                if (idx >= 0) {
                    messages[idx].state = Message.STATE_FAILED
                    adapter.notifyItemChanged(idx)
                    Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Message not sent")
                }
            }
        }
    }

    private fun retryFailed(m: Message) {
        val idx = messages.indexOfFirst { it.id == m.id }
        if (idx < 0) return
        messages.removeAt(idx)
        adapter.notifyItemRemoved(idx)
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
                val up = withContext(Dispatchers.IO) { Api.upload(kind, f) }
                val type = when (kind) {
                    "photo" -> if (up.optString("mime") == "image/gif") "gif" else "photo"
                    else -> "file"
                }
                sendOptimistic(type, "", up.optString("rel"), up.optStringOrNull("thumb"),
                    0, replyTo?.id ?: 0)
                replyTo = null; clearReplyBar()
                f.delete()
            } catch (e: Exception) {
                Helpers.toast(this@ChatActivity, if (e is ApiErr) e.message else "Upload failed")
            }
        }
    }

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

    private fun showAttachSheet() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_attach, null)
        v.findViewById<LinearLayout>(R.id.attach_photo).setOnClickListener { d.dismiss(); pickMedia.launch(arrayOf("image/*", "video/mp4")) }
        v.findViewById<LinearLayout>(R.id.attach_file).setOnClickListener { d.dismiss(); pickFile.launch(arrayOf("*/*")) }
        d.setContentView(v)
        d.show()
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
        if (m.id > 0) items.add("Pin" to { ui.launch { withContext(Dispatchers.IO) { Api.pin(chatId, m.id) } } })
        items.add("Select" to { startSelection() })
        if (mine || chat?.type == "group")
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
        ui.launch {
            try { withContext(Dispatchers.IO) { Api.react(m.id, emoji) }; loadInfo(quiet = true) }
            catch (e: Exception) { Helpers.toast(this@ChatActivity, "Reaction failed") }
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
        when (c.type) {
            "private" -> c.peer?.let {
                startActivity(Intent(this, com.telx.app.ui.profile.UserProfileActivity::class.java).putExtra("user_id", it.id))
            }
            "group" -> showGroupInfo()
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

    private fun showImage(m: Message) {
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
            R.id.chat_clear -> AlertDialog.Builder(this)
                .setMessage("Clear all history in this chat?")
                .setPositiveButton("Clear") { _, _ ->
                    ui.launch { try { withContext(Dispatchers.IO) { Api.clearHistory(chatId) } } catch (_: Exception) {} }
                }
                .setNegativeButton("Cancel", null).show()
        }
        return true
    }

    private fun showChatSearch() {
        val v = layoutInflater.inflate(R.layout.dialog_search_in_chat, null)
        val input = v.findViewById<EditText>(R.id.et_chat_search)
        AlertDialog.Builder(this)
            .setTitle("Search in chat")
            .setView(v)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text?.toString()?.trim() ?: ""
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
}
