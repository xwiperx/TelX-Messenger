package com.telx.app.media

import android.content.Context
import android.os.Environment
import com.telx.app.data.Message
import com.telx.app.ui.common.Helpers
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Telegram-style file downloads:
 *  - tap → download to app's Downloads dir with a progress notification row
 *  - pause/resume supported via HTTP Range on our own downloader
 *  - completed files are opened via FileProvider/ACTION_VIEW
 */
object FileDownloader {

    /** progress percent per message id (0-100), -1 = paused, 100 = done */
    val progress = ConcurrentHashMap<Int, Int>()

    fun isDone(msgId: Int): Boolean = progress[msgId] == 100
    fun isPaused(msgId: Int): Boolean = progress[msgId] == -1

    /** Toggle behavior: download / pause / resume / open. */
    fun tap(ctx: Context, m: Message) {
        val url = m.media ?: return
        val part = partFile(ctx, m)
        when {
            progress[m.id] == null || progress[m.id] == -1 -> start(ctx, m, url, part)
            progress[m.id] == 100 -> open(ctx, m, part)
            else -> progress[m.id] = -1 // request pause
        }
    }

    private fun partFile(ctx: Context, m: Message): File {
        val name = m.mediaName.ifEmpty { m.media?.substringAfterLast("/") ?: "file" }
        return File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
            "telx_${m.id}_$name")
    }

    /** Local download target for a message file (public for gallery use). */
    fun fileFor(ctx: Context, m: Message): File = partFile(ctx, m)

    fun start(ctx: Context, m: Message, url: String, out: File) {
        val startPos = if (out.exists()) out.length() else 0L
        Thread {
            try {
                progress[m.id] = if (startPos == 0L) 0 else (startPos * 100 / maxOf(1, m.mediaSize)).toInt().coerceAtMost(95)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (startPos > 0) conn.setRequestProperty("Range", "bytes=$startPos-")
                val total = conn.contentLengthLong.let { if (it <= 0) m.mediaSize else it + startPos }
                val code = conn.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                var append = code == 206
                var written = startPos
                if (code == 200 && startPos > 0) {
                    // server ignored the Range header → start the file over
                    out.delete()
                    append = false
                    written = 0L
                }
                java.io.FileOutputStream(out, append).use { fos ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        while (ins.read(buf).also { read = it } > 0) {
                            if (progress[m.id] == -1) { Thread.currentThread().interrupt(); return@Thread }
                            fos.write(buf, 0, read)
                            written += read
                            if (total > 0) progress[m.id] = (written * 100 / total).toInt().coerceIn(1, 99)
                        }
                    }
                }
                progress[m.id] = 100
            } catch (e: Exception) {
                if (progress[m.id] == -1) return@Thread // paused
                progress.remove(m.id) // failed → allow retry
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Helpers.toast(ctx, "Download failed")
                }
            }
        }.start()
    }

    /** Open a finished download — or save a copy to system Downloads if no viewer exists. */
    fun open(ctx: Context, m: Message, f: File) {
        try {
            val name = m.mediaName.ifEmpty { f.name }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, Helpers.mimeFor(name))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            // no app for this type — save a copy into the system Downloads folder
            try {
                val dst = File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), f.name)
                f.copyTo(dst, overwrite = true)
                Helpers.toast(ctx, "Saved to Downloads")
            } catch (_: Exception) {
                Helpers.toast(ctx, "No app can open this file")
            }
        }
    }
}
