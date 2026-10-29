package com.telx.app.ui.chat

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.telx.app.R
import com.telx.app.ui.common.Helpers
import java.io.File
import java.nio.ByteBuffer

/**
 * v6: Telegram-style video trimmer — pick a start/end range on a dual seek bar
 * and cut WITHOUT re-encoding (MediaExtractor + MediaMuxer → instant, lossless
 * for the vast majority of mp4 files).
 * Input extra: "path" (local file). Result extra: "path" (trimmed file).
 */
class VideoTrimActivity : AppCompatActivity() {

    private var srcPath: String? = null
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var trimThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_trim)

        val path = intent.getStringExtra("path") ?: run {
            val uri: Uri? = intent.getParcelableExtra("uri")
            if (uri != null) {
                val f = File(cacheDir, "trim_src.mp4")
                try { contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } } } catch (_: Exception) {}
                if (f.exists() && f.length() > 0) f.absolutePath else null
            } else null
        }
        if (path == null) { finish(); return }
        srcPath = path

        val video = findViewById<VideoView>(R.id.vt_preview)
        video.setVideoPath(path)
        video.start()
        findViewById<ImageView>(R.id.vt_play).setOnClickListener {
            if (video.isPlaying) video.pause() else video.start()
            findViewById<ImageView>(R.id.vt_play).setImageResource(
                if (video.isPlaying) R.drawable.ic_play else R.drawable.ic_pause)
        }

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            durationMs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLong()
        } catch (_: Exception) {}
        r.release()
        if (durationMs <= 0) { finish(); return }
        endMs = durationMs

        val startBar = findViewById<SeekBar>(R.id.vt_start)
        val endBar = findViewById<SeekBar>(R.id.vt_end)
        val range = findViewById<TextView>(R.id.vt_range)
        startBar.max = (durationMs / 1000).toInt()
        endBar.max = (durationMs / 1000).toInt()
        endBar.progress = endBar.max
        fun upd() {
            startMs = startBar.progress * 1000L
            endMs = endBar.progress * 1000L
            if (startMs >= endMs - 500) { startBar.progress = (endBar.progress - 1).coerceAtLeast(0); startMs = startBar.progress * 1000L }
            range.text = "Keep ${Helpers.duration((startMs / 1000).toInt())} → ${Helpers.duration((endMs / 1000).toInt())}"
        }
        startBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { upd() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                try { if (video.isPlaying) { video.seekTo(startMs) } } catch (_: Exception) {}
            }
        })
        endBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { upd() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        upd()

        findViewById<ImageView>(R.id.vt_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.vt_save).setOnClickListener { trim() }
    }

    private fun trim() {
        val src = srcPath ?: return
        val out = File(cacheDir, "trimmed_${System.currentTimeMillis()}.mp4")
        findViewById<View>(R.id.vt_progress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.vt_save).isEnabled = false
        trimThread = Thread {
            try {
                trimVideo(src, out.absolutePath, startMs * 1000L, endMs * 1000L) // µs
                runOnUiThread {
                    findViewById<View>(R.id.vt_progress).visibility = View.GONE
                    setResult(RESULT_OK, android.content.Intent().putExtra("path", out.absolutePath))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<View>(R.id.vt_progress).visibility = View.GONE
                    findViewById<TextView>(R.id.vt_save).isEnabled = true
                    Helpers.toast(this, "Trim failed on this video format")
                }
            }
        }.also { it.start() }
    }

    /** lossless cut: copies the mp4 samples between the two timestamps. */
    private fun trimVideo(srcPath: String, outPath: String, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(srcPath)
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = HashMap<Int, Int>()
        var maxBufferSize = 1 shl 20
        for (i in 0 until extractor.trackCount) {
            val fmt: MediaFormat = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                try { maxBufferSize = maxOf(maxBufferSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) } catch (_: Exception) {}
                trackMap[i] = muxer.addTrack(fmt)
            }
        }
        muxer.start()
        val buf = ByteBuffer.allocate(maxBufferSize * 2)
        val info = MediaCodec.BufferInfo()
        var sawFirst = false
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val trackIdx = extractor.sampleTrackIndex
            if (trackIdx < 0) break
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (pts > endUs) break
            if (sawFirst || pts >= startUs) {
                sawFirst = true
                val mi = trackMap[trackIdx] ?: -1
                if (mi >= 0) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(mi, buf, info)
                }
            }
            extractor.advance()
        }
        muxer.stop()
        muxer.release()
        extractor.release()
    }

    override fun onDestroy() {
        trimThread?.interrupt()
        super.onDestroy()
    }
}
