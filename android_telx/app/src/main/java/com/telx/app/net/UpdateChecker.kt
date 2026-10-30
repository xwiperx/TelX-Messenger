package com.telx.app.net

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.telx.app.BuildConfig
import com.telx.app.data.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v6: in-app update checker — reads the latest GitHub release, compares the
 * version with the running build, and shows a Telegram-style dialog with the
 * changelog (release notes) + a direct Download button.
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/xwiperx/TelX-Messenger/releases/latest"
    private const val PAGE =
        "https://github.com/xwiperx/TelX-Messenger/releases/latest"

    class Release(val tag: String, val name: String, val notes: String, val apkUrl: String?, val ts: Long)

    /** Blocking network call — run on a background thread. */
    fun fetch(): Release? = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "TelX-Android")
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val j = JSONObject(text)
        var apk: String? = null
        val assets = j.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name", "").endsWith(".apk", true)) {
                apk = a.optString("browser_download_url"); break
            }
        }
        Release(
            j.optString("tag_name", "latest"),
            j.optString("name", "New version"),
            j.optString("body", "").trim(),
            apk,
            j.optLong("published_at", 0L)
        )
    } catch (e: Exception) { null }

    /** returns true if the fetched release is newer than the running build. */
    fun isNewer(r: Release): Boolean {
        // tag pattern: v<code>_<name> or a plain name; compare build numbers when present
        val m = Regex("v(\\d+)").find(r.tag)
        val remoteCode = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return remoteCode > BuildConfig.VERSION_CODE
    }

    /** Non-blocking UI entry point: shows the update dialog when a new version exists. */
    fun check(activity: Activity) {
        Thread {
            val r = fetch() ?: return@Thread
            if (!isNewer(r)) return@Thread
            if (Prefs.updateDismissedVersion == r.tag) return@Thread
            activity.runOnUiThread { showUpdateDialog(activity, r) }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, r: Release) {
        val notes = if (r.notes.isEmpty()) "Bug fixes and improvements." else
            r.notes.replace("\r", "").take(1200)
        val body = "Version ${r.tag.replaceFirst("v", "")}\n\n$notes"
        val dlg = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("TelX update available")
            .setMessage(body)
            .setPositiveButton("Download") { _, _ ->
                val url = r.apkUrl ?: PAGE
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: ActivityNotFoundException) {}
            }
            .setNeutralButton("Later") { d, _ -> d.dismiss() }
            .setNegativeButton("Skip this version") { _, _ ->
                Prefs.updateDismissedVersion = r.tag
            }
            .create()
        dlg.show()
    }
}
