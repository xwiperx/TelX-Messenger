package com.telx.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.telx.app.R
import com.telx.app.data.User

/** Circular avatar with initials fallback and green online dot. */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val image = ImageView(context)
    private val letter = TextView(context)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    var online = false
        set(v) { field = v; invalidate(); }
    private var onlineRing = false

    init {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.visibility = GONE
        letter.gravity = Gravity.CENTER
        letter.setTextColor(Color.WHITE)
        letter.typeface = android.graphics.Typeface.DEFAULT_BOLD
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(letter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        letter.textSize = Math.max(11f, w / 3.2f / resources.displayMetrics.density)
    }

    fun setOnlineRing(enabled: Boolean) { onlineRing = enabled; invalidate() }

    fun bind(name: String, avatarUrl: String?, color: Int, showOnlineDot: Boolean = false) {
        online = showOnlineDot
        letter.text = Helpers.initials(name)
        val size = Helpers.dp(context, 42)
        letter.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        if (!avatarUrl.isNullOrEmpty()) {
            image.visibility = VISIBLE
            letter.visibility = GONE
            Glide.with(this).load(avatarUrl).placeholder(null).into(image)
        } else {
            image.visibility = GONE
            letter.visibility = VISIBLE
            image.setImageDrawable(null)
        }
        invalidate()
    }

    fun bindUser(u: User, showDot: Boolean = false, withRing: Boolean = false) {
        bind(u.name, u.avatar, Helpers.colorFor(u.id), showDot)
        online = showDot && u.online
        setOnlineRing(withRing && u.online)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (onlineRing) {
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = Helpers.dp(context, 3).toFloat()
            ring.color = 0xFF31B645.toInt()
            canvas.drawCircle(w / 2f, height / 2f, w / 2f - ring.strokeWidth / 2f, ring)
        }
        if (online && !onlineRing) {
            val r = w * 0.13f
            dot.color = 0xFF31B645.toInt()
            dot.setShadowLayer(r * 0.6f, 0f, 0f, 0x55000000)
            val cx = w - r - Helpers.dp(context, 2).toFloat()
            val cy = height - r - Helpers.dp(context, 2).toFloat()
            canvas.drawCircle(cx, cy, r, dot)
        }
    }
}

/** Rounded corner image helper (story cell / wallpapers). */
fun loadRounded(view: ImageView, url: String?, radiusDp: Int, placeholderColor: Int) {
    if (url.isNullOrEmpty()) {
        view.setImageDrawable(null)
        view.setBackgroundColor(placeholderColor)
        return
    }
    Glide.with(view).load(url)
        .transform(CenterCrop(), RoundedCorners(Helpers.dp(view.context, radiusDp)))
        .into(view)
}
