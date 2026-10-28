package com.telx.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records voice messages as AAC (.m4a). */
class VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var startedAt = 0L
        private set

    fun start(context: Context): File {
        val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else
            @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(96000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        return f
    }

    /** @return file + duration in seconds, or null if too short / failed */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        return try {
            r.stop()
            val dur = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(1)
            val f = file
            if (f == null || !f.exists() || f.length() < 512) { f?.delete(); null }
            else f to dur
        } catch (e: Exception) {
            file?.delete()
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
            recorder = null
        }
    }

    fun cancel() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        file?.delete()
        file = null
    }
}
