package com.telx.app.ui.common

import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.widget.Toast
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

    fun toast(context: Context?, msg: String) {
        if (context == null) return
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun colorToHex(c: Int): String = "#%06X".format(0xFFFFFF and c)

    fun isDarkColor(c: Int): Boolean =
        Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 < 150
}
