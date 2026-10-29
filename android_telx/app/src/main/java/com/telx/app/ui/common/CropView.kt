package com.telx.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ScaleGestureDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Telegram-style crop surface: pinch to zoom, drag to pan, circular or full-rect
 * mask. Output is a JPEG file written to cacheDir; path is delivered via [onOutput].
 */
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var circle = true
    var onOutput: ((String) -> Unit)? = null
    var onLoaded: (() -> Unit)? = null

    private var src: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverse = Matrix()
    private val bitmapRect = RectF()
    private val frame = RectF()

    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
        color = 0xFFFFFFFF.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = 0x59FFFFFF
    }

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                drawMatrix.postScale(det.scaleFactor, det.scaleFactor, det.focusX, det.focusY)
                clamp()
                invalidate()
                return true
            }
        })

    private val dp get() = context.resources.displayMetrics.density

    /** Decode the picked image in the background (downsampled for memory). */
    fun load(uri: Uri) {
        Thread {
            val bmp = decode(uri)
            post {
                if (bmp != null) {
                    src = bmp
                    if (width > 0 && height > 0) initMatrix()
                    invalidate()
                    onLoaded?.invoke()
                }
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? = try {
        val ins = context.contentResolver.openInputStream(uri)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ins, null, opts)
        ins?.close()
        val maxDim = max(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxDim / (sample * 2) >= 1600) sample *= 2
        val ins2 = context.contentResolver.openInputStream(uri)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeStream(ins2, null, opts2)
        ins2?.close()
        bmp
    } catch (e: Exception) { null }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        computeFrame()
        if (src != null) initMatrix()
    }

    private fun computeFrame() {
        val pad = 18 * dp
        if (circle) {
            val d = min(width, height) - pad * 2
            val cx = width / 2f
            val cy = height / 2f
            frame.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)
        } else {
            frame.set(pad * 0.5f, pad, width - pad * 0.5f, height - pad)
        }
    }

    private fun initMatrix() {
        val s = src ?: return
        val cover = max(frame.width() / s.width, frame.height() / s.height)
        drawMatrix.reset()
        drawMatrix.postScale(cover, cover)
        drawMatrix.postTranslate(
            frame.centerX() - s.width * cover / 2f,
            frame.centerY() - s.height * cover / 2f)
        clamp()
        invalidate()
    }

    /** Keep the bitmap covering the frame; no gaps, bounded zoom. */
    private fun clamp() {
        val s = src ?: return
        val vals = FloatArray(9)
        drawMatrix.getValues(vals)
        val scale = vals[Matrix.MSCALE_X]
        val cover = max(frame.width() / s.width, frame.height() / s.height)
        val target = scale.coerceIn(cover, cover * 8f)
        if (target != scale) {
            val cx = frame.centerX()
            val cy = frame.centerY()
            drawMatrix.postScale(target / scale, target / scale, cx, cy)
        }
        bitmapRect.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        drawMatrix.mapRect(bitmapRect)
        var dx = 0f
        var dy = 0f
        if (bitmapRect.left < frame.left) dx = frame.left - bitmapRect.left
        if (bitmapRect.right > frame.right) dx = frame.right - bitmapRect.right
        if (bitmapRect.width() >= frame.width()) {
            if (dx != 0f) { /* resolved below */ }
        }
        if (bitmapRect.top < frame.top) dy = frame.top - bitmapRect.top
        if (bitmapRect.bottom > frame.bottom) dy = frame.bottom - bitmapRect.bottom
        if (dx != 0f || dy != 0f) drawMatrix.postTranslate(dx, dy)
        // second pass for the axis that is smaller than the frame (center it)
        bitmapRect.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        drawMatrix.mapRect(bitmapRect)
        var dx2 = 0f
        var dy2 = 0f
        if (bitmapRect.width() < frame.width()) dx2 = frame.centerX() - bitmapRect.centerX()
        if (bitmapRect.height() < frame.height()) dy2 = frame.centerY() - bitmapRect.centerY()
        if (dx2 != 0f || dy2 != 0f) drawMatrix.postTranslate(dx2, dy2)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y; dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    lastX = ev.x; lastY = ev.y
                    clamp()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF000000.toInt())
        val s = src ?: return
        canvas.save()
        canvas.concat(drawMatrix)
        canvas.drawBitmap(s, 0f, 0f, null)
        canvas.restore()

        // dim outside the frame
        val p = Path()
        p.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (circle) {
            val oval = Path()
            oval.addOval(frame, Path.Direction.CW)
            p.op(oval, Path.Op.DIFFERENCE)
        } else {
            val r = Path()
            r.addRect(frame, Path.Direction.CW)
            p.op(r, Path.Op.DIFFERENCE)
        }
        canvas.drawPath(p, dimPaint)

        if (circle) {
            canvas.drawOval(frame, ringPaint)
        } else {
            canvas.drawRect(frame, ringPaint)
            // thirds grid like Telegram
            for (i in 1..2) {
                val x = frame.left + frame.width() * i / 3f
                val y = frame.top + frame.height() * i / 3f
                canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint)
                canvas.drawLine(frame.left, y, frame.right, y, gridPaint)
            }
        }
    }

    /** Crop the visible frame region and save it as JPEG in cacheDir. */
    fun output() {
        val s = src ?: return
        drawMatrix.invert(inverse)
        val pts = floatArrayOf(frame.left, frame.top, frame.right, frame.bottom)
        inverse.mapPoints(pts)
        var x = pts[0].roundToInt().coerceIn(0, s.width - 1)
        var y = pts[1].roundToInt().coerceIn(0, s.height - 1)
        var w = (pts[2] - pts[0]).roundToInt().coerceIn(1, s.width - x)
        var h = (pts[3] - pts[1]).roundToInt().coerceIn(1, s.height - y)
        val cropped = try { Bitmap.createBitmap(s, x, y, w, h) } catch (e: Exception) { s }
        // cap output at 1024px like Telegram's avatar processing
        val out = if (max(cropped.width, cropped.height) > 1024) {
            val sc = 1024f / max(cropped.width, cropped.height)
            Bitmap.createScaledBitmap(cropped, (cropped.width * sc).roundToInt(),
                (cropped.height * sc).roundToInt(), true)
        } else cropped
        val f = File(context.cacheDir, "telx_crop_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            onOutput?.invoke(f.absolutePath)
        } catch (_: Exception) {}
    }
}
