package com.telx.app.ui.chat

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.telx.app.R

/**
 * v6: internal video player with true Picture-in-Picture support.
 * Tapping the PiP button (or going home while a video plays) shrinks the
 * player into a floating window over other apps — Telegram-style.
 */
class VideoPlayerActivity : AppCompatActivity() {

    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        val url = intent.getStringExtra("url") ?: run { finish(); return }

        video = findViewById(R.id.vp_video)
        findViewById<ImageView>(R.id.vp_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.vp_pip).setOnClickListener { enterPip() }
        findViewById<ImageView>(R.id.vp_play).setOnClickListener {
            val v = video ?: return@setOnClickListener
            if (v.isPlaying) { v.pause(); (it as ImageView).setImageResource(R.drawable.ic_play) }
            else { v.start(); (it as ImageView).setImageResource(R.drawable.ic_pause) }
        }
        video?.setVideoURI(Uri.parse(url))
        video?.setOnPreparedListener { it.isLooping = false; video?.start() }
        video?.setOnCompletionListener { finish() }
        video?.start()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    /** auto-PiP: user pressed home while playing → shrink instead of leaving. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val v = video
        if (Build.VERSION.SDK_INT >= 26 && v != null && v.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        findViewById<View>(R.id.vp_controls)?.visibility = if (isInPip) View.GONE else View.VISIBLE
        findViewById<View>(R.id.vp_toolbar)?.visibility = if (isInPip) View.GONE else View.VISIBLE
    }
}
