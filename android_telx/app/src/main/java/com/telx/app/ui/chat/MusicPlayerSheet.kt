package com.telx.app.ui.chat

import android.content.Context
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v6: Telegram-style music player sheet — plays the audio files of a chat
 * as a queue (voice notes + music files), with album-art thumb, next/prev,
 * seekbar and the same 1x/1.5x/2x speed switch as voice bubbles.
 */
object MusicPlayerSheet {

    private var player: MediaPlayer? = null
    private var queue: ArrayList<Message> = ArrayList()
    private var index = 0
    private var playing = false
    private var dialog: BottomSheetDialog? = null

    fun show(context: Context, chatId: Int, startAt: Message? = null) {
        val ui = CoroutineScope(Dispatchers.Main)
        ui.launch {
            try {
                val list = kotlinx.coroutines.withContext(Dispatchers.IO) { Api.audioList(chatId) }
                queue = list
                index = startAt?.let { m -> queue.indexOfFirst { it.id == m.id }.coerceAtLeast(0) } ?: 0
                val d = BottomSheetDialog(context)
                dialog = d
                val root = buildUi(context, d)
                d.setContentView(root)
                d.setOnDismissListener { release() }
                d.show()
                playCurrent(context)
            } catch (e: Exception) {
                Helpers.toast(context, "No audio in this chat yet")
            }
        }
    }

    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: ImageView

    private fun buildUi(ctx: Context, d: BottomSheetDialog): View {
        val pad = Helpers.dp(ctx, 16)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val art = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Helpers.dp(ctx, 140), Helpers.dp(ctx, 140))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = Helpers.dp(ctx, 16).toFloat()
                setColor(0xFF3390EC.toInt())
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.media_placeholder)
        }
        title = TextView(ctx).apply {
            textSize = 17f; gravity = Gravity.CENTER; maxLines = 1
            setTextColor(0xFF000000.toInt())
        }
        artist = TextView(ctx).apply {
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFF8A8A8E.toInt())
        }
        seek = SeekBar(ctx).apply { progressTintList = android.content.res.ColorStateList.valueOf(0xFF3390EC.toInt()) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun imgBtn(icon: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
            setImageResource(icon)
            setPadding(pad, pad / 2, pad, pad / 2)
            background = null
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Helpers.dp(ctx, 56), Helpers.dp(ctx, 56))
        }
        playBtn = imgBtn(R.drawable.ic_pause) { toggle(ctx) }
        row.addView(imgBtn(R.drawable.ic_reply) { prev(ctx) })
        row.addView(playBtn)
        row.addView(imgBtn(R.drawable.ic_forward) { next(ctx) })
        val speed = TextView(ctx).apply {
            text = "1x"; textSize = 14f; setTextColor(0xFF3390EC.toInt())
            setPadding(pad, 0, pad, 0)
        }
        speed.setOnClickListener {
            val mpSpeed = player
            val cur = if (mpSpeed != null) {
                try { mpSpeed.playbackParams.speed } catch (_: Exception) { 1f }
            } else 1f
            val newSpeed = when (cur) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
            if (mpSpeed != null) {
                try { mpSpeed.playbackParams = mpSpeed.playbackParams.setSpeed(newSpeed) } catch (_: Exception) {}
            }
            speed.text = "${if (newSpeed == 1.5f) "1.5" else newSpeed.toInt()}x"
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) try { player?.seekTo(p) } catch (_: Exception) {}
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(art)
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        root.addView(artist)
        root.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(row)
        root.addView(speed)
        artView = art
        return root
    }

    private var artView: ImageView? = null

    private fun curTrack(): Message? = queue.getOrNull(index)

    private fun playCurrent(ctx: Context) {
        val m = curTrack() ?: return
        title.text = m.mediaName.ifEmpty { "Voice message" }
        artist.text = Helpers.fileSize(m.mediaSize)
        artView?.let { av ->
            if (m.thumb != null) Glide.with(av).load(m.thumb).centerCrop().placeholder(R.drawable.media_placeholder).into(av)
            else av.setImageResource(R.drawable.media_placeholder)
        }
        try { player?.release() } catch (_: Exception) {}
        val mp = MediaPlayer()
        mp.setDataSource(m.media ?: return)
        mp.prepare()
        mp.setOnCompletionListener { next(ctx) }
        mp.start()
        player = mp
        playing = true
        playBtn.setImageResource(R.drawable.ic_pause)
        seek.max = maxOf(1, mp.duration)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            while (playing && mp.isPlaying) {
                try { Thread.sleep(500); h.post { try { seek.progress = mp.currentPosition } catch (_: Exception) {} } }
                catch (_: Exception) { break }
            }
        }.start()
    }

    private fun toggle(ctx: Context) {
        val mp = player ?: return
        if (mp.isPlaying) { mp.pause(); playing = false; playBtn.setImageResource(R.drawable.ic_play) }
        else { mp.start(); playing = true; playBtn.setImageResource(R.drawable.ic_pause) }
    }

    private fun next(ctx: Context) {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        playCurrent(ctx)
    }

    private fun prev(ctx: Context) {
        if (queue.isEmpty()) return
        index = if (index - 1 < 0) queue.size - 1 else index - 1
        playCurrent(ctx)
    }

    fun release() {
        playing = false
        try { player?.release() } catch (_: Exception) {}
        player = null
        dialog = null
    }
}
