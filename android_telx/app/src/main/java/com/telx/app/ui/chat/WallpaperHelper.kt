package com.telx.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.telx.app.R
import com.telx.app.data.Prefs
import java.io.File

/** Applies the user's chosen wallpaper to chat backgrounds. */
object WallpaperHelper {

    /** bumped when settings change so open chats re-apply */
    @Volatile var version: Int = 0

    private fun customFile(ctx: android.content.Context) = File(ctx.filesDir, "wallpaper.jpg")
    private fun customBlurFile(ctx: android.content.Context) = File(ctx.filesDir, "wallpaper_blur.jpg")

    /** Save a cropped image into app storage as the custom wallpaper; returns wallpaper value. */
    fun importCustom(ctx: android.content.Context, srcPath: String): String {
        val bmp = BitmapFactory.decodeFile(srcPath)
        if (bmp != null) {
            val max = 1440
            val sc = max.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (sc < 1f) Bitmap.createScaledBitmap(bmp,
                (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
            customFile(ctx).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            scaled.recycle()
        }
        return "custom:wallpaper.jpg"
    }

    /** Regenerate the blurred variant of the custom wallpaper. */
    fun refreshBlur(ctx: android.content.Context) {
        if (!Prefs.wallpaperBlur) { customBlurFile(ctx).delete(); return }
        val src = BitmapFactory.decodeFile(customFile(ctx).absolutePath) ?: return
        val bmp = stackBlur(src, 14)
        customBlurFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
    }

    /** simple fast box blur (approximates the Telegram-style blurred wallpaper) */
    fun stackBlur(input: Bitmap, radius: Int): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = radius.coerceAtLeast(1)
        val tmp = IntArray(pixels.size)
        fun boxPass(srcArr: IntArray, dstArr: IntArray, width: Int, height: Int, horizontal: Boolean) {
            val div = r * 2 + 1
            for (yOuter in 0 until height) {
                for (xOuter in 0 until width) {
                    var ar = 0; var ag = 0; var ab = 0
                    for (k in -r..r) {
                        val x = if (horizontal) (xOuter + k).coerceIn(0, width - 1) else xOuter
                        val y = if (horizontal) yOuter else (yOuter + k).coerceIn(0, height - 1)
                        val px = srcArr[y * width + x]
                        ar += (px shr 16) and 0xFF
                        ag += (px shr 8) and 0xFF
                        ab += px and 0xFF
                    }
                    ar /= div; ag /= div; ab /= div
                    dstArr[yOuter * width + xOuter] =
                        (0xFF shl 24) or (ar shl 16) or (ag shl 8) or ab
                }
            }
        }
        boxPass(pixels, tmp, w, h, true)
        boxPass(tmp, pixels, w, h, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Render the current wallpaper into the given root (chat screen / preview). */
    fun apply(root: ViewGroup, dim: Int = Prefs.wallpaperDim) {
        val ctx = root.context
        val w = Prefs.wallpaper
        root.setBackgroundResource(R.drawable.chat_bg_default)
        if (w.isNullOrEmpty()) { applyDim(root, dim); return }
        when {
            // v6: animated gradient wallpaper
            w == "animated" -> {
                root.setBackgroundResource(R.drawable.chat_bg_animated)
                (root.background as? android.graphics.drawable.AnimationDrawable)?.start()
            }
            w.startsWith("pattern:") -> {
                val res = when (w.removePrefix("pattern:")) {
                    "1" -> R.drawable.chat_bg_dots
                    "2" -> R.drawable.chat_bg_waves
                    "3" -> R.drawable.chat_bg_stars
                    else -> R.drawable.chat_bg_default
                }
                root.setBackgroundResource(res)
            }
            w.startsWith("color:") -> {
                root.background = try {
                    ColorDrawable(Color.parseColor(w.removePrefix("color:")))
                } catch (e: Exception) { ColorDrawable(0xFFB8C4CE.toInt()) }
            }
            w.startsWith("custom:") -> {
                val f = if (Prefs.wallpaperBlur) customBlurFile(ctx) else customFile(ctx)
                if (f.exists()) {
                    root.background = null
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) {
                        root.background = BitmapDrawable(ctx.resources, bmp)
                        bmp.recycle()
                    }
                }
            }
            w.startsWith("uri:") -> {
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
        }
        applyDim(root, dim)
    }

    /** Telegram-style dim overlay via foreground scrim. */
    fun applyDim(root: ViewGroup, dim: Int) {
        val alpha = dim.coerceIn(0, 60)
        root.foreground = ColorDrawable(Color.argb(alpha, 255, 255, 255))
    }
}
