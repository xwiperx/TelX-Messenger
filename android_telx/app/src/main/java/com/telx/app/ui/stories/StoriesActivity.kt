package com.telx.app.ui.stories

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.data.Story
import com.telx.app.data.StoryGroup
import com.telx.app.data.User
import com.telx.app.net.Api
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Instagram/Telegram style story viewer with progress bars, tap navigation and hold-to-pause. */
class StoriesActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val groups = ArrayList<StoryGroup>()
    private var groupIndex = 0
    private var storyIndex = 0
    private var paused = false
    private var destroyed = false
    private var progressJob: Job? = null

    private lateinit var barsRow: LinearLayout
    private lateinit var avatar: AvatarView
    private lateinit var nameView: TextView
    private lateinit var timeView: TextView
    private lateinit var mediaFrame: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView
    private lateinit var btnClose: ImageView
    private lateinit var btnDelete: ImageView
    private lateinit var btnViewers: TextView
    private lateinit var emptyView: TextView
    // v7: live stories + reply thread
    private lateinit var liveBadge: TextView
    private lateinit var btnReplies: TextView
    private lateinit var btnEndLive: ImageView
    private lateinit var replyBar: LinearLayout
    private lateinit var replyInput: android.widget.EditText
    private lateinit var replySend: ImageView

    private val currentGroup: StoryGroup?
        get() = groups.getOrNull(groupIndex)

    private val currentStory: Story?
        get() = currentGroup?.stories?.getOrNull(storyIndex)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContentView(R.layout.activity_stories)

        barsRow = findViewById(R.id.story_bars)
        avatar = findViewById(R.id.story_user_avatar)
        nameView = findViewById(R.id.story_user_name)
        timeView = findViewById(R.id.story_user_time)
        mediaFrame = findViewById(R.id.story_media_frame)
        imageView = findViewById(R.id.story_image)
        videoView = findViewById(R.id.story_video)
        btnClose = findViewById(R.id.story_close)
        btnDelete = findViewById(R.id.story_delete)
        btnViewers = findViewById(R.id.story_viewers)
        emptyView = findViewById(R.id.story_empty)
        // v7
        liveBadge = findViewById(R.id.story_live_badge)
        btnReplies = findViewById(R.id.story_replies)
        btnEndLive = findViewById(R.id.story_end_live)
        replyBar = findViewById(R.id.story_reply_bar)
        replyInput = findViewById(R.id.story_reply_input)
        replySend = findViewById(R.id.story_reply_send)

        btnClose.setOnClickListener { finish() }
        btnDelete.setOnClickListener { confirmDelete() }
        btnViewers.setOnClickListener { showViewers() }
        emptyView.setOnClickListener { finish() }
        btnReplies.setOnClickListener { showReplies() }
        btnEndLive.setOnClickListener { confirmEndLive() }
        replySend.setOnClickListener { sendReply() }
        replyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendReply(); true } else false
        }

        mediaFrame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { paused = true; pauseMedia() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    paused = false
                    resumeMedia()
                }
            }
            true
        }
        // tap zones
        val left = findViewById<View>(R.id.story_tap_left)
        val right = findViewById<View>(R.id.story_tap_right)
        left.setOnClickListener { goPrev() }
        right.setOnClickListener { goNext() }

        loadFeed()
    }

    private fun loadFeed() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.storiesFeed() }
                groups.clear()
                if (arr != null) for (i in 0 until arr.length()) groups.add(StoryGroup(arr.getJSONObject(i)))
                if (groups.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    mediaFrame.visibility = View.GONE
                    barsRow.removeAllViews()
                    nameView.text = ""
                    avatar.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    btnViewers.visibility = View.GONE
                    return@launch
                }
                emptyView.visibility = View.GONE
                mediaFrame.visibility = View.VISIBLE
                avatar.visibility = View.VISIBLE
                showStory()
            } catch (e: Exception) {
                Helpers.toast(this@StoriesActivity, "Couldn't load stories")
                finish()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showStory() {
        val g = currentGroup ?: return
        val s = currentStory ?: run { goNextGroup(); return }
        if (destroyed) return

        // header
        avatar.bind(g.user.name, g.user.avatar, Helpers.colorFor(g.user.id))
        nameView.text = if (g.user.isMe) "My story" else g.user.name
        timeView.text = Helpers.chatListTs(s.ts)
        val isMine = g.user.isMe
        btnDelete.visibility = if (isMine) View.VISIBLE else View.GONE
        btnViewers.visibility = if (isMine) View.VISIBLE else View.GONE
        if (isMine) btnViewers.text = "${s.views} views"
        // v7: live badge + owner tools + viewer reply bar
        liveBadge.visibility = if (s.isLive) View.VISIBLE else View.GONE
        btnEndLive.visibility = if (isMine && s.isLive) View.VISIBLE else View.GONE
        btnReplies.visibility = if (isMine) View.VISIBLE else View.GONE
        replyBar.visibility = if (isMine) View.GONE else View.VISIBLE
        replyInput.setText("")

        // progress bars
        barsRow.removeAllViews()
        val weight = 1f
        for (i in g.stories.indices) {
            val bar = View(this)
            val lp = LinearLayout.LayoutParams(0, Helpers.dp(this, 3), weight)
            lp.marginEnd = Helpers.dp(this, 3)
            bar.layoutParams = lp
            bar.setBackgroundResource(R.drawable.story_bar)
            barsRow.addView(bar)
        }

        // media
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        imageView.visibility = View.GONE
        if (s.type == "video" && !s.media.isNullOrEmpty()) {
            videoView.visibility = View.VISIBLE
            videoView.setVideoURI(Uri.parse(s.media))
            videoView.setOnPreparedListener { mp -> mp.isLooping = s.isLive; mp.start() }
            videoView.setOnCompletionListener { if (!s.isLive) goNext() }
            // live video has no countdown either — the owner ends it
            runProgress(if (s.isLive) 0 else -1)
        } else {
            imageView.visibility = View.VISIBLE
            Glide.with(this).load(s.media ?: s.thumb).into(imageView)
            // live stories stay open until the owner ends them — no countdown
            runProgress(if (s.isLive) 0 else s.duration.coerceIn(5, 60))
        }

        if (!g.user.isMe) {
            ui.launch { withContext(Dispatchers.IO) { Api.storyView(s.id) } }
        }
    }

    private fun runProgress(durationSecs: Int) {
        progressJob?.cancel()
        progressJob = ui.launch {
            val bars = barsRow
            val g = currentGroup ?: return@launch
            val total = durationSecs * 1000L
            val start = System.currentTimeMillis()
            var progress = 0f
            while (progress < 1f && !destroyed) {
                if (!paused) {
                    progress = if (total <= 0) {
                        // video: estimate from playback position
                        try { videoView.currentPosition.toFloat() / videoView.duration.coerceAtLeast(1) }
                        catch (e: Exception) { 1f }
                    } else (System.currentTimeMillis() - start) / total.toFloat()
                    for (i in g.stories.indices) {
                        val bar = bars.getChildAt(i) ?: continue
                        val bg = bar.background.mutate() as GradientDrawable
                        when {
                            i < storyIndex -> bg.setColor(0xFFFFFFFF.toInt())
                            i == storyIndex -> {
                                bg.setColor(0x66FFFFFF.toInt())
                                val layerCount = 1
                                // draw progress via clip: use two-layer trick with LevelListDrawable is complex;
                                // simplest: set bar foreground alpha progress via custom drawing below
                                bar.post { drawPartial(bar, progress) }
                            }
                            else -> bg.setColor(0x66FFFFFF.toInt())
                        }
                    }
                }
                delay(60)
            }
            if (!destroyed && progress >= 1f && !paused) goNext()
        }
    }

    private fun drawPartial(bar: View, progress: Float) {
        // use a layer drawable split trick: foreground = filled portion
        val fg = GradientDrawable()
        fg.setColor(0xFFFFFFFF.toInt())
        fg.cornerRadius = Helpers.dp(bar.context, 2).toFloat()
        val w = (bar.width * progress).toInt()
        fg.setBounds(0, 0, w, bar.height)
        bar.foreground = fg
    }

    private fun pauseMedia() { try { if (videoView.isPlaying) videoView.pause() } catch (_: Exception) {} }
    private fun resumeMedia() { try { if (videoView.visibility == View.VISIBLE) videoView.start() } catch (_: Exception) {} }

    private fun goNext() {
        val g = currentGroup ?: return
        if (storyIndex < g.stories.size - 1) {
            storyIndex++
            showStory()
        } else goNextGroup()
    }

    private fun goPrev() {
        if (storyIndex > 0) {
            storyIndex--
            showStory()
        } else if (groupIndex > 0) {
            groupIndex--
            storyIndex = 0
            showStory()
        }
    }

    private fun goNextGroup() {
        if (groupIndex < groups.size - 1) {
            groupIndex++
            storyIndex = 0
            showStory()
        } else finish()
    }

    private fun confirmDelete() {
        val s = currentStory ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete story")
            .setMessage("This story will be removed for everyone.")
            .setPositiveButton("Delete") { _, _ ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.storyDelete(s.id) }
                        val g = currentGroup
                        if (g != null) {
                            val newStories = g.stories.filterNot { it.id == s.id }
                            if (newStories.isEmpty()) {
                                groups.removeAt(groupIndex)
                                if (groups.isEmpty()) { finish(); return@launch }
                                if (groupIndex >= groups.size) groupIndex = groups.size - 1
                                storyIndex = 0
                            } else {
                                val idx = storyIndex.coerceAtMost(newStories.size - 1)
                                // rebuild group stories
                                val j = org.json.JSONArray()
                                for (st in newStories) j.put(org.json.JSONObject()
                                    .put("id", st.id).put("media", st.media).put("thumb", st.thumb)
                                    .put("type", st.type).put("duration", st.duration).put("ts", st.ts))
                                val nj = org.json.JSONObject().put("user",
                                    org.json.JSONObject().put("id", g.user.id).put("name", g.user.name)
                                        .put("username", g.user.username).putOpt("avatar", g.user.avatar)
                                        .put("is_me", g.user.isMe))
                                    .put("seen", g.seen).put("stories", j)
                                groups[groupIndex] = StoryGroup(nj)
                                storyIndex = idx
                            }
                            showStory()
                        }
                    } catch (e: Exception) { Helpers.toast(this@StoriesActivity, "Delete failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showViewers() {
        val s = currentStory ?: return
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.storyViewers(s.id) }
                val viewers = User.parseArray(arr)
                val names = if (viewers.isEmpty()) arrayOf("No views yet")
                else viewers.map { it.name }.toTypedArray()
                AlertDialog.Builder(this@StoriesActivity)
                    .setTitle("Story views (${viewers.size})")
                    .setItems(names, null)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: Exception) { Helpers.toast(this@StoriesActivity, "Couldn't load viewers") }
        }
    }

    override fun onDestroy() {
        destroyed = true
        progressJob?.cancel()
        videoView.stopPlayback()
        super.onDestroy()
    }

    // ---------------- v7: story replies / live stories ----------------

    /** Viewer sends a reply to this story — lands in the owner's chat thread. */
    private fun sendReply() {
        val s = currentStory ?: return
        val txt = replyInput.text.toString().trim()
        if (txt.isEmpty()) { Helpers.toast(this, "Type a reply first"); return }
        replyInput.setText("")
        ui.launch {
            try {
                withContext(Dispatchers.IO) { Api.storyReply(s.id, txt) }
                Helpers.toast(this@StoriesActivity, "Reply sent")
            } catch (e: Exception) {
                Helpers.toast(this@StoriesActivity, if (e is com.telx.app.data.ApiErr) e.message else "Reply failed")
            }
        }
    }

    /** Owner reads the replies to their story (live chat thread). */
    private fun showReplies() {
        val s = currentStory ?: return
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.storyReplies(s.id) }
                val lines = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val name = r.optJSONObject("user")?.optString("name") ?: "?"
                    lines.add("💬 $name:  " + r.optString("text"))
                }
                AlertDialog.Builder(this@StoriesActivity)
                    .setTitle("Replies (${lines.size})")
                    .setItems(if (lines.isEmpty()) arrayOf("No replies yet") else lines.toTypedArray(), null)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: Exception) {
                Helpers.toast(this@StoriesActivity, if (e is com.telx.app.data.ApiErr) e.message else "Couldn't load replies")
            }
        }
    }

    /** Owner ends a live story — it turns into a normal expired story. */
    private fun confirmEndLive() {
        val s = currentStory ?: return
        AlertDialog.Builder(this)
            .setTitle("End live story?")
            .setMessage("The live badge disappears and the story is archived normally.")
            .setPositiveButton("End live") { _, _ ->
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.storyEndLive(s.id) }
                        Helpers.toast(this@StoriesActivity, "Live ended")
                        showStory()
                    } catch (e: Exception) {
                        Helpers.toast(this@StoriesActivity, if (e is com.telx.app.data.ApiErr) e.message else "Failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
