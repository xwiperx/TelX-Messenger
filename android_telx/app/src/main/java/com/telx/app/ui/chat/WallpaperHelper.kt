package com.telx.app.ui.chat

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Prefs

/** Applies the user's chosen wallpaper to chat backgrounds. */
object WallpaperHelper {

    /** bumped when settings change so open chats re-apply */
    @Volatile var version: Int = 0

    fun apply(root: ViewGroup, dim: Int = Prefs.wallpaperDim) {
        val w = Prefs.wallpaper
        root.setBackgroundResource(R.drawable.chat_bg_default)
        if (w.isNullOrEmpty()) return
        if (w.startsWith("pattern:")) {
            val res = when (w.removePrefix("pattern:")) {
                "1" -> R.drawable.chat_bg_dots
                "2" -> R.drawable.chat_bg_waves
                "3" -> R.drawable.chat_bg_stars
                else -> R.drawable.chat_bg_default
            }
            root.setBackgroundResource(res)
        } else if (w.startsWith("uri:")) {
            val uri = Uri.parse(w.removePrefix("uri:"))
            root.background = null
            root.setBackgroundColor(Color.parseColor("#B8C4CE"))
            Glide.with(root.context).load(uri).centerCrop()
                .into(object : com.bumptech.glide.request.target.CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: com.bumptech.glide.request.transition.Transition<in Drawable>?) {
                        root.background = resource
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        root.setBackgroundResource(R.drawable.chat_bg_default)
                    }
                })
        }
        // dim overlay via foreground scrim
        val alpha = dim.coerceIn(0, 60)
        root.foreground = ColorDrawable(Color.argb(alpha, 255, 255, 255))
    }
}
