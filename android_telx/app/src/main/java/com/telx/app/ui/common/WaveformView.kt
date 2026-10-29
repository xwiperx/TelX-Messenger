package com.telx.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Telegram-style voice message waveform: deterministic pseudo-amplitude bars
 * (seeded per message id) with a playback progress fill.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bars = 30
    private var heights = FloatArray(bars) { 0.4f }
    private var progress = 0f   // 0..1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var playedColor: Int = 0xFF3390EC.toInt()
    private var restColor: Int = 0x66808A94.toInt()

    /** seed bars from message id so each voice message looks distinct but stable */
    fun seedFrom(id: Int) {
        val seed = abs(id) % 97 + 3
        heights = FloatArray(bars) { i ->
            val v = abs(sin(seed * 0.61 + i * 1.27)) * 0.72f +
                abs(sin(seed * 0.13 + i * 0.37)) * 0.28f
            0.18f + v * 0.82f
        }
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun setColors(played: Int, rest: Int) {
        playedColor = played; restColor = rest
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2f
        val barW = (w - gap * (bars - 1)) / bars
        val radius = barW / 2
        for (i in 0 until bars) {
            val x = i * (barW + gap)
            val bh = heights[i].coerceIn(0.12f, 1f) * h
            val top = (h - bh) / 2f
            paint.color = if ((i + 1) / bars.toFloat() <= progress + 0.0001f) playedColor else restColor
            c.drawRoundRect(RectF(x, top, x + barW, top + bh), radius, radius, paint)
        }
    }
}
