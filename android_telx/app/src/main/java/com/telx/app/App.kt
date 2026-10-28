package com.telx.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.telx.app.data.Prefs
import com.telx.app.net.Events
import com.telx.app.svc.PollService

class App : Application() {

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_SERVICE = "service"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        if (Prefs.token.isNotEmpty()) {
            Events.start()
            PollService.start(this)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New message notifications"
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN))
    }
}
