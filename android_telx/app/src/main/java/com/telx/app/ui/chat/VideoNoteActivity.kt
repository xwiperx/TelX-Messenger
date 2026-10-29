package com.telx.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.telx.app.R
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Telegram-style round VIDEO MESSAGE: records the front camera in a circular
 * preview; on stop, uploads as video_note and sends it to the chat.
 */
class VideoNoteActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var outFile: File? = null
    private var chatId = 0
    private var startedAt = 0L
    private lateinit var preview: SurfaceView
    private val ui = CoroutineScope(Dispatchers.Main)

    private val camPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) initCamera() else { Helpers.toast(this, "Camera permission needed"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getIntExtra("chat_id", 0)
        setContentView(R.layout.activity_video_note)
        preview = findViewById(R.id.vn_surface)
        preview.holder.addCallback(this)
        preview.holder.setFixedSize(640, 640)

        findViewById<ImageView>(R.id.vn_record).setOnClickListener { toggle() }
        findViewById<ImageView>(R.id.vn_close).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) initCamera()
        else camPermission.launch(Manifest.permission.CAMERA)
    }

    private fun initCamera() {
        try {
            val c = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
            c.setDisplayOrientation(90)
            camera = c
            c.setPreviewDisplay(preview.holder)
            c.startPreview()
        } catch (e: Exception) { Helpers.toast(this, "Cannot open camera"); finish() }
    }

    private fun toggle() {
        if (recording) stopAndSend() else startRecording()
    }

    private fun startRecording() {
        val c = camera ?: return
        try {
            val f = File(cacheDir, "vnote_${System.currentTimeMillis()}.mp4")
            val r = MediaRecorder()
            c.unlock()
            r.setCamera(c)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(480, 480)
            r.setVideoEncodingBitRate(1_500_000)
            r.setVideoFrameRate(24)
            r.setMaxDuration(60_000)
            r.setOutputFile(f.absolutePath)
            r.setPreviewDisplay(preview.holder.surface)
            r.prepare()
            r.start()
            recorder = r
            outFile = f
            recording = true
            startedAt = System.currentTimeMillis()
            findViewById<ImageView>(R.id.vn_record).setImageResource(R.drawable.ic_pause)
            findViewById<TextView>(R.id.vn_hint).text = "Recording… tap to stop"
        } catch (e: Exception) {
            Helpers.toast(this, "Recording failed")
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
        }
    }

    private fun stopAndSend() {
        val r = recorder ?: return
        recording = false
        val dur = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(1)
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        val f = outFile
        outFile = null
        findViewById<ImageView>(R.id.vn_record).setImageResource(R.drawable.ic_play)
        if (f == null || !f.exists() || f.length() < 1000) { finish(); return }
        setResult(Activity.RESULT_OK)
        ui.launch {
            try {
                Helpers.toast(this@VideoNoteActivity, "Sending video message…")
                val up = withContext(Dispatchers.IO) { Api.upload("video", f) }
                withContext(Dispatchers.IO) {
                    Api.send(chatId, "video_note", mediaRel = up.optString("rel"),
                        mediaDuration = dur, mediaName = "round.mp4", mediaSize = f.length())
                }
                f.delete()
            } catch (e: Exception) {
                Helpers.toast(this@VideoNoteActivity, "Video message failed")
            }
            finish()
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}

    override fun onDestroy() {
        try { recorder?.release() } catch (_: Exception) {}
        try { camera?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
