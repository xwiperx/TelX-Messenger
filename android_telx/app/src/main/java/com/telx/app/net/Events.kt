package com.telx.app.net

import android.util.Log
import com.telx.app.data.Event
import com.telx.app.data.Prefs

/**
 * Event bus with client-paced polling.
 *
 * The server supports wait=0 (instant return) so we control cadence here:
 *  - app in foreground: poll every FOREGROUND_MS for Telegram-like latency
 *  - app in background (PollService only): poll every BACKGROUND_MS to stay light
 * A single daemon thread feeds all registered listeners.
 */
object Events {

    interface Listener { fun onEvent(e: Event) }

    private const val FOREGROUND_MS = 2000L
    private const val BACKGROUND_MS = 25000L
    private const val MAX_BACKOFF = 10000L

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private var thread: Thread? = null
    private val lock = Any()

    @Volatile private var running = false
    @Volatile var foreground = true

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun start() {
        synchronized(lock) {
            val t = thread
            if (t?.isAlive == true && running) return
            running = true
            thread = Thread(loop, "telx-poller").apply { isDaemon = true; start() }
        }
    }

    fun restart() {
        synchronized(lock) {
            running = false
            val old = thread
            if (old != null) {
                try { old.join(1500) } catch (_: Exception) {}
            }
            running = true
            thread = Thread(loop, "telx-poller").apply { isDaemon = true; start() }
        }
    }

    private val loop = Runnable {
        var backoff = 2000L
        while (running) {
            val token = Prefs.token
            if (token.isEmpty()) { sleep(3000); continue }
            val idle = FOREGROUND_MS.takeIf { foreground } ?: BACKGROUND_MS
            try {
                val j = Api.events(Prefs.sinceId)
                backoff = 2000L
                val arr = j.optJSONArray("events")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = Event(arr.getJSONObject(i))
                        if (e.id > Prefs.sinceId) Prefs.sinceId = e.id
                        for (l in listeners) {
                            try { l.onEvent(e) } catch (ex: Exception) { Log.w("Events", "listener", ex) }
                        }
                    }
                }
                // synthetic unread-total event so UI and notifications stay in sync
                val total = j.optInt("unread_total", -1)
                if (total >= 0) {
                    val synthetic = Event(org.json.JSONObject()
                        .put("id", 0).put("type", "_unread")
                        .put("data", org.json.JSONObject().put("unread_total", total)))
                    for (l in listeners) {
                        try { l.onEvent(synthetic) } catch (_: Exception) {}
                    }
                }
                sleep(idle)
            } catch (e: Exception) {
                if (!running) break
                sleep(backoff)
                if (backoff < MAX_BACKOFF) backoff = (backoff * 3 / 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
