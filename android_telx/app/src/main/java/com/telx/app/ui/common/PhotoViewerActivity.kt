package com.telx.app.ui.common

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.telx.app.R

/** Fullscreen photo viewer — tap anywhere to close (Telegram-style). */
class PhotoViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)
        val url = intent.getStringExtra("url")
        val iv = findViewById<android.widget.ImageView>(R.id.photo_view)
        if (url.isNullOrEmpty()) { finish(); return }
        Glide.with(this).load(url)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(iv)
        iv.setOnClickListener { finish() }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
