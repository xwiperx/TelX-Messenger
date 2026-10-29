package com.telx.app.ui.common

import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.widget.Toast
import com.telx.app.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Helpers {
    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun timeShort(ts: Long): String =
        if (ts <= 0) "" else SimpleDateFormat("HH:mm", Locale.US).format(Date(ts * 1000))

    fun dateShort(ts: Long): String =
        if (ts <= 0) "" else SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ts * 1000))

    fun chatListTs(ts: Long): CharSequence? =
        if (ts <= 0) "" else DateUtils.getRelativeTimeSpanString(ts * 1000, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)

    fun lastSeenText(online: Boolean, lastTs: Long): String {
        if (online) return "online"
        if (lastTs <= 0) return "last seen recently"
        val diff = System.currentTimeMillis() / 1000 - lastTs
        return when {
            diff < 60 -> "last seen just now"
            diff < 3600 -> "last seen ${diff / 60} minutes ago"
            diff < 86400 && DateUtils.isToday(lastTs * 1000) -> "last seen at ${timeShort(lastTs)}"
            diff < 172800 -> "last seen yesterday at ${timeShort(lastTs)}"
            else -> "last seen ${dateShort(lastTs)}"
        }
    }

    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / 1024f / 1024f)
    }

    fun duration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /**
     * Telegram-style auto-download policy: cellular/metered networks follow the
     * mobile setting, Wi-Fi and unmetered networks follow the Wi-Fi setting.
     */
    fun shouldAutoDownload(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val mobile = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val unmetered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return if (mobile && !unmetered) Prefs.autoDownloadMobile else Prefs.autoDownloadWifi
    }

    /**
     * Telegram-style automatic image compression before upload:
     * downscale to ≤1280px longest side, re-encode JPEG q86.
     * GIFs / tiny files are returned untouched.
     */
    fun compressImage(ctx: Context, src: File): File {
        try {
            val name = src.name.lowercase()
            if (name.endsWith(".gif") || src.length() < 120 * 1024) return src
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(src.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return src
            var sample = 1
            var w = opts.outWidth; var h = opts.outHeight
            val maxSide = 1280
            while (w / 2 >= maxSide || h / 2 >= maxSide) { sample *= 2; w /= 2; h /= 2 }
            val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return src
            val scaled = if (maxOf(bmp.width, bmp.height) > maxSide) {
                val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                android.graphics.Bitmap.createScaledBitmap(bmp,
                    (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
            } else bmp
            val out = File(ctx.cacheDir, "cmp_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }
            if (out.exists() && out.length() in 1 until src.length()) {
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                return out
            }
            out.delete()
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
        } catch (_: Exception) {}
        return src
    }

    /** Grab a video frame as a JPEG thumbnail file (for video uploads). */
    fun videoThumb(ctx: Context, video: File): File? = try {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(video.absolutePath)
        val bmp = r.getFrameAtTime(1_000_000) ?: r.frameAtTime
        r.release()
        if (bmp == null) null else {
            val maxSide = 480
            val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bmp,
                (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
            val out = File(ctx.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            if (out.exists() && out.length() > 0) out else null
        }
    } catch (_: Exception) { null }

    /** Common MIME type by file extension (for opening downloads). */
    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "m4a", "mp4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    /** Telegram-style date separator: Today / Yesterday / 14 March 2026 */
    fun dateSeparator(ts: Long): String {
        if (ts <= 0) return ""
        val cal = java.util.Calendar.getInstance(Locale.US)
        cal.timeInMillis = ts * 1000
        val now = java.util.Calendar.getInstance(Locale.US)
        return when {
            DateUtils.isToday(ts * 1000) -> "Today"
            android.text.format.DateUtils.isToday(ts * 1000 + 86400000L) -> "Yesterday"
            cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) ->
                SimpleDateFormat("d MMMM", Locale.US).format(Date(ts * 1000))
            else -> SimpleDateFormat("d MMMM yyyy", Locale.US).format(Date(ts * 1000))
        }
    }

    /** true when a and b fall on different calendar days */
    fun differentDay(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return true
        val ca = java.util.Calendar.getInstance(Locale.US).apply { timeInMillis = a * 1000 }
        val cb = java.util.Calendar.getInstance(Locale.US).apply { timeInMillis = b * 1000 }
        return ca.get(java.util.Calendar.YEAR) != cb.get(java.util.Calendar.YEAR) ||
                ca.get(java.util.Calendar.DAY_OF_YEAR) != cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private val palette = intArrayOf(
        0xFF7E57C2.toInt(), 0xFF5C6BC0.toInt(), 0xFF42A5F5.toInt(),
        0xFF29B6F6.toInt(), 0xFF26C6DA.toInt(), 0xFF26A69A.toInt(),
        0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFFEC407A.toInt(),
        0xFF8D6E63.toInt(), 0xFF78909C.toInt(), 0xFFFF7043.toInt())

    fun colorFor(id: Int): Int = palette[Math.abs(id) % palette.size]

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        return if (parts.size == 1) parts[0].take(1).uppercase(Locale.US)
        else (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.US)
    }

    fun toast(context: Context?, msg: String?) {
        if (context == null || msg == null) return
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun colorToHex(c: Int): String = "#%06X".format(0xFFFFFF and c)

    fun isDarkColor(c: Int): Boolean =
        Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 < 150
}
