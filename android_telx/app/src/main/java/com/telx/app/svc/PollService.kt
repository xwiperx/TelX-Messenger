package com.telx.app.svc

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.telx.app.App
import com.telx.app.R
import com.telx.app.data.Event
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.chat.ChatActivity

/**
 * Foreground service that keeps the long-polling connection alive and posts
 * message notifications with quick-reply — no Firebase needed.
 */
class PollService : android.app.Service(), Events.Listener {

    companion object {
        const val NOTIF_SERVICE_ID = 100
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_NOTIF_ID = "notif_id"
        const val KEY_QUICK_REPLY = "quick_reply_text"
        private val activeNotifications = HashMap<Int, Int>() // chat_id -> notif count

        @JvmStatic var currentOpenChat = 0

        fun start(context: Context) {
            if (Prefs.token.isEmpty()) return
            val intent = Intent(context, PollService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_SERVICE_ID, buildServiceNotification())
        Events.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_SERVICE_ID, buildServiceNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        Events.removeListener(this)
        super.onDestroy()
    }

    private fun buildServiceNotification() =
        NotificationCompat.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TelX")
            .setContentText("Connected — listening for new messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onEvent(e: Event) {
        if (e.type == "_unread") {
            if (e.data.optInt("unread_total", 0) == 0) clearAll()
            return
        }
        if (e.type != "message_new") return
        val chatId = e.data.optInt("chat_id")
        val msg = e.data.optJSONObject("message") ?: return
        if (chatId == 0 || chatId == currentOpenChat) return
        if (MuteCache.isMuted(chatId)) return

        val sender = msg.optString("sender_name", "New message")
        val text = when (msg.optString("type")) {
            "photo", "gif" -> "📷 Photo"
            "voice" -> "🎤 Voice message"
            "file" -> "📎 File"
            "video" -> "📹 Video"
            else -> msg.optString("text", "")
        }
        if (text.isEmpty()) return
        postNotification(chatId, sender, text)
    }

    private fun postNotification(chatId: Int, sender: String, text: String) {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        if (!granted) return

        val notifId = chatId
        val count = (activeNotifications[chatId] ?: 0) + 1
        activeNotifications[chatId] = count

        val open = Intent(this, ChatActivity::class.java)
            .putExtra("chat_id", chatId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pOpen = PendingIntent.getActivity(this, chatId, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val remoteInput = RemoteInput.Builder(KEY_QUICK_REPLY).setLabel("Reply").build()
        val replyIntent = Intent(this, QuickReplyReceiver::class.java)
            .putExtra(KEY_CHAT_ID, chatId)
            .putExtra(KEY_NOTIF_ID, notifId)
        val pReply = PendingIntent.getBroadcast(this, chatId + 5000, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", pReply)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val style = NotificationCompat.BigTextStyle()
            .bigText(if (count > 1) "($count new) $text" else text)

        val builder = NotificationCompat.Builder(this, App.CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(pOpen)
            .setAutoCancel(true)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // pre-O devices: channel sound doesn't exist, set it explicitly
        if (Build.VERSION.SDK_INT < 26) {
            builder.setSound(android.net.Uri.parse("android.resource://${packageName}/${R.raw.tg_note}"))
        }
        val n = builder.build()
        try {
            NotificationManagerCompat.from(this).notify(notifId, n)
        } catch (_: SecurityException) {}
    }

    fun clearAll() {
        val ids = activeNotifications.keys.toList()
        activeNotifications.clear()
        val nm = NotificationManagerCompat.from(this)
        for (id in ids) nm.cancel(id)
    }

    class MuteCache {
        companion object {
            private val muted = HashSet<Int>()
            fun setMuted(chatId: Int, isMuted: Boolean) {
                if (isMuted) muted.add(chatId) else muted.remove(chatId)
            }
            fun isMuted(chatId: Int): Boolean = muted.contains(chatId)
        }
    }
}

/** Fires when the user replies straight from the notification. */
class QuickReplyReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getIntExtra(PollService.KEY_CHAT_ID, 0)
        val notifId = intent.getIntExtra(PollService.KEY_NOTIF_ID, 0)
        val bundle = androidx.core.app.RemoteInput.getResultsFromIntent(intent) ?: return
        val text = bundle.getCharSequence(PollService.KEY_QUICK_REPLY)?.toString() ?: return
        if (chatId <= 0 || text.isEmpty()) return
        val result = goAsync()
        Thread {
            try {
                Api.send(chatId, "text", text)
                NotificationManagerCompat.from(context).cancel(notifId)
            } catch (_: Exception) {}
            // refresh the notification to show the reply was sent
            val answered = NotificationCompat.Builder(context, App.CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sent")
                .setContentText("Your reply was delivered")
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(context).notify(notifId + 1, answered) } catch (_: Exception) {}
            result.finish()
        }.start()
    }
}

/** Restarts the background connection after reboot. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.token.isNotEmpty()) {
            PollService.start(context)
        }
    }
}
