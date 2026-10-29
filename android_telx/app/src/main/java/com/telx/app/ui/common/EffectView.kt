package com.telx.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Telegram-style full-screen message effects (TelX v6):
 * "confetti"  — 🎉 colorful falling paper
 * "fireworks" — 🎆 radial bursts
 * "hearts"    — ❤️ floating hearts
 *
 * Add to the activity root, play(), then remove itself when finished.
 */
class EffectView(context: Context) : View(context) {

    private class Particle {
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var size = 8f
        var rot = 0f; var vr = 0f
        var color = 0
        var life = 1f
        var decay = 0.01f
        var shape = 0            // 0 rect 1 circle
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random()
    private val parts = ArrayList<Particle>()
    private var animator: ValueAnimator? = null
    private var kind = "confetti"

    companion object {
        private val CONFETTI = intArrayOf(0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(),
            0xFF3F51B5.toInt(), 0xFF03A9F4.toInt(), 0xFF4CAF50.toInt(), 0xFFFFC107.toInt())
        private val HEARTS = intArrayOf(0xFFE91E63.toInt(), 0xFFF06292.toInt(), 0xFFFF5252.toInt())
        private val SPARKS = intArrayOf(0xFFFFEB3B.toInt(), 0xFFFF9800.toInt(), 0xFFF44336.toInt(), 0xFFFFFFFF.toInt())
    }

    fun play(effect: String, anchorX: Float = 0.5f, anchorY: Float = 0.35f) {
        kind = effect
        parts.clear()
        val w = (context.resources.displayMetrics.widthPixels)
        val h = (context.resources.displayMetrics.heightPixels)
        val ax = w * anchorX
        val ay = h * anchorY
        when (effect) {
            "fireworks" -> {
                for (burst in 0 until 5) {
                    val bx = w * (0.15f + rnd.nextFloat() * 0.7f)
                    val by = h * (0.1f + rnd.nextFloat() * 0.4f)
                    for (i in 0 until 46) {
                        val ang = rnd.nextFloat() * 2f * Math.PI.toFloat()
                        val sp = 5f + rnd.nextFloat() * 11f
                        parts.add(Particle().apply {
                            x = bx; y = by
                            vx = sp * cos(ang); vy = sp * sin(ang)
                            size = 3f + rnd.nextFloat() * 4f
                            color = SPARKS[rnd.nextInt(SPARKS.size)]
                            shape = 1; life = 1f; decay = 0.016f + rnd.nextFloat() * 0.02f
                        })
                    }
                }
            }
            "hearts" -> {
                for (i in 0 until 26) {
                    parts.add(Particle().apply {
                        x = ax + (rnd.nextFloat() - 0.5f) * w * 0.5f
                        y = ay + rnd.nextFloat() * h * 0.25f
                        vx = (rnd.nextFloat() - 0.5f) * 2.4f
                        vy = -(1.6f + rnd.nextFloat() * 3.2f)
                        size = 14f + rnd.nextFloat() * 22f
                        color = HEARTS[rnd.nextInt(HEARTS.size)]
                        shape = 1; decay = 0.008f + rnd.nextFloat() * 0.012f
                    })
                }
            }
            else -> { // confetti
                for (i in 0 until 90) {
                    parts.add(Particle().apply {
                        x = rnd.nextFloat() * w
                        y = -20f - rnd.nextFloat() * h * 0.35f
                        vx = (rnd.nextFloat() - 0.5f) * 4f
                        vy = 3.5f + rnd.nextFloat() * 6.5f
                        size = 6f + rnd.nextFloat() * 9f
                        rot = rnd.nextFloat() * 360f
                        vr = (rnd.nextFloat() - 0.5f) * 24f
                        color = CONFETTI[rnd.nextInt(CONFETTI.size)]
                        decay = 0.004f + rnd.nextFloat() * 0.006f
                    })
                }
            }
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                step()
                invalidate()
                if (parts.all { it.life <= 0f }) end()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { detach() }
            })
            start()
        }
    }

    private fun step() {
        for (p in parts) {
            if (p.life <= 0) continue
            p.x += p.vx
            p.y += p.vy
            if (kind == "fireworks") { p.vy += 0.18f }       // gravity
            if (kind == "confetti") { p.vx = p.vx * 0.995f; p.rot += p.vr }
            p.life -= p.decay
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in parts) {
            if (p.life <= 0) continue
            paint.color = p.color
            paint.alpha = (255 * p.life.coerceIn(0f, 1f)).toInt()
            if (kind == "hearts") {
                drawHeart(canvas, paint, p.x, p.y, p.size)
            } else if (p.shape == 1) {
                canvas.drawCircle(p.x, p.y, p.size / 2f, paint)
            } else {
                canvas.save()
                canvas.rotate(p.rot, p.x, p.y)
                canvas.drawRect(p.x - p.size / 2f, p.y - p.size / 4f, p.x + p.size / 2f, p.y + p.size / 4f, paint)
                canvas.restore()
            }
        }
    }

    private fun drawHeart(c: Canvas, p: Paint, x: Float, y: Float, s: Float) {
        val path = android.graphics.Path()
        val top = y - s * 0.55f
        path.moveTo(x, y + s * 0.6f)
        path.cubicTo(x - s * 1.1f, y, x - s * 0.55f, top, x, top + s * 0.35f)
        path.cubicTo(x + s * 0.55f, top, x + s * 1.1f, y, x, y + s * 0.6f)
        path.close()
        c.drawPath(path, p)
    }

    private fun detach() {
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
