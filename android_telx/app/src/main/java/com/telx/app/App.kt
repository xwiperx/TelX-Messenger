package com.telx.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.telx.app.data.Prefs
import com.telx.app.net.Events
import com.telx.app.svc.PollService

class App : Application() {

    companion object {
        const val CH_MESSAGES = "messages_v2"   // v2: carries the Telegram-style notification sound
        const val CH_SERVICE = "service"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        // v6: Material You dynamic colors on Android 12+ (unless disabled in settings)
        if (com.telx.app.data.Prefs.materialYou) {
            com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        }
        // track foreground state so the poller can adapt its cadence
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var acts = 0
            override fun onActivityStarted(a: android.app.Activity) { acts++; Events.foreground = true }
            override fun onActivityStopped(a: android.app.Activity) {
                acts = (acts - 1).coerceAtLeast(0)
                if (acts == 0) Events.foreground = false
            }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        if (Prefs.token.isNotEmpty()) {
            Events.start()
            PollService.start(this)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // remove the old silent channel from previous builds
        nm.deleteNotificationChannel("messages")
        // Telegram-style notification sound (bundled raw resource)
        val soundUri = android.net.Uri.parse("android.resource://${packageName}/${R.raw.tg_note}")
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New message notifications"
                setSound(soundUri, audioAttrs)
                enableVibration(true)
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN))
    }
}
