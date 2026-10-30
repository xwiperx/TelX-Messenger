package com.telx.app.data

import android.content.Context
import android.content.SharedPreferences
import com.telx.app.App

/** Local session + app settings storage. */
object Prefs {
    private val sp: SharedPreferences by lazy {
        App.instance.getSharedPreferences("telx", Context.MODE_PRIVATE)
    }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var myId: Int
        get() = sp.getInt("my_id", 0)
        set(v) = sp.edit().putInt("my_id", v).apply()

    var myName: String
        get() = sp.getString("my_name", "") ?: ""
        set(v) = sp.edit().putString("my_name", v).apply()

    var myUsername: String
        get() = sp.getString("my_username", "") ?: ""
        set(v) = sp.edit().putString("my_username", v).apply()

    var myAvatar: String?
        get() = sp.getString("my_avatar", null)
        set(v) = sp.edit().putString("my_avatar", v).apply()

    /** Long-polling cursor. */
    var sinceId: Int
        get() = sp.getInt("since_id", 0)
        set(v) = sp.edit().putInt("since_id", v).apply()

    /** 0 = system, 1 = light, 2 = dark */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** Wallpaper: null = default pattern, otherwise "pattern:N", "color:#RRGGBB" or "custom:filename" */
    var wallpaper: String?
        get() = sp.getString("wallpaper", null)
        set(v) = sp.edit().putString("wallpaper", v).apply()

    var wallpaperDim: Int
        get() = sp.getInt("wallpaper_dim", 12)
        set(v) = sp.edit().putInt("wallpaper_dim", v).apply()

    /** blur the custom wallpaper (Telegram-style) */
    var wallpaperBlur: Boolean
        get() = sp.getBoolean("wallpaper_blur", false)
        set(v) = sp.edit().putBoolean("wallpaper_blur", v).apply()

    var privacyLock: Boolean
        get() = sp.getBoolean("privacy_lock", false)
        set(v) = sp.edit().putBoolean("privacy_lock", v).apply()

    /** chat bubble text size multiplier (1.0 = default, 0.85 … 1.4) */
    var chatFontScale: Float
        get() = sp.getFloat("chat_font_scale", 1.0f)
        set(v) = sp.edit().putFloat("chat_font_scale", v).apply()

    /** custom accent color for MY bubbles, "" = Telegram blue */
    var bubbleAccent: String
        get() = sp.getString("bubble_accent", "") ?: ""
        set(v) = sp.edit().putString("bubble_accent", v).apply()

    /** app-lock PIN (SHA-256 hex), empty = lock disabled */
    var appLockPin: String
        get() = sp.getString("app_lock_pin", "") ?: ""
        set(v) = sp.edit().putString("app_lock_pin", v).apply()

    /** timestamp when the app was last unlocked (re-lock after 5 min in background) */
    var lastUnlockedAt: Long
        get() = sp.getLong("last_unlocked_at", 0)
        set(v) = sp.edit().putLong("last_unlocked_at", v).apply()

    /** auto-download media on Wi-Fi / mobile data */
    var autoDownloadWifi: Boolean
        get() = sp.getBoolean("auto_dl_wifi", true)
        set(v) = sp.edit().putBoolean("auto_dl_wifi", v).apply()

    var autoDownloadMobile: Boolean
        get() = sp.getBoolean("auto_dl_mobile", false)
        set(v) = sp.edit().putBoolean("auto_dl_mobile", v).apply()

    /** auto-download max size in MB (bigger files always need a tap) */
    var autoDownloadMaxMb: Int
        get() = sp.getInt("auto_dl_max_mb", 20)
        set(v) = sp.edit().putInt("auto_dl_max_mb", v).apply()

    /** multi-account registry — JSON array of [{id, token, name, username, avatar}] */
    var accounts: String
        get() = sp.getString("accounts", "[]") ?: "[]"
        set(v) = sp.edit().putString("accounts", v).apply()

    // ---------------- v6 additions ----------------

    /** open links in the internal browser instead of the system one */
    var useInAppBrowser: Boolean
        get() = sp.getBoolean("use_in_app_browser", true)
        set(v) = sp.edit().putBoolean("use_in_app_browser", v).apply()

    /** per-chat app-lock: JSON set of chat ids locked with the app PIN */
    var lockedChats: String
        get() = sp.getString("locked_chats", "[]") ?: "[]"
        set(v) = sp.edit().putString("locked_chats", v).apply()

    /** custom app accent color ("#RRGGBB", "" = default Telegram blue) */
    var customAccent: String
        get() = sp.getString("custom_accent", "") ?: ""
        set(v) = sp.edit().putString("custom_accent", v).apply()

    /** full custom theme JSON: {accent, bubble, bubbleTheirs, bgLight, bgDark, nav} */
    var customTheme: String
        get() = sp.getString("custom_theme", "") ?: ""
        set(v) = sp.edit().putString("custom_theme", v).apply()

    /** chat wallpaper: "animated" = moving gradient (v6) */
    var wallpaperAnimated: Boolean
        get() = sp.getBoolean("wallpaper_animated", false)
        set(v) = sp.edit().putBoolean("wallpaper_animated", v).apply()

    /** selected launcher icon alias (default | dark | teal) */
    var launcherIcon: String
        get() = sp.getString("launcher_icon", "default") ?: "default"
        set(v) = sp.edit().putString("launcher_icon", v).apply()

    /** Material You dynamic colors (Android 12+), overrides custom accent */
    var materialYou: Boolean
        get() = sp.getBoolean("material_you", true)
        set(v) = sp.edit().putBoolean("material_you", v).apply()

    /** last version the update dialog was shown for */
    var updateDismissedVersion: String
        get() = sp.getString("update_dismissed", "") ?: ""
        set(v) = sp.edit().putString("update_dismissed", v).apply()

    /** recent chats cache for the home-screen widget — JSON [{id,name}] */
    var recentChats: String
        get() = sp.getString("recent_chats", "[]") ?: "[]"
        set(v) = sp.edit().putString("recent_chats", v).apply()

    // ---------------- v7 additions ----------------

    /** Power Saving mode: disables animations/autoplay to save battery. */
    var powerSaving: Boolean
        get() = sp.getBoolean("power_saving", false)
        set(v) = sp.edit().putBoolean("power_saving", v).apply()

    /** auto-enable Power Saving below this battery percentage (0 = never). */
    var powerSavingThreshold: Int
        get() = sp.getInt("power_saving_threshold", 20)
        set(v) = sp.edit().putInt("power_saving_threshold", v).apply()

    /** message signature appended to outgoing texts ("" = off). */
    var signature: String
        get() = sp.getString("msg_signature", "") ?: ""
        set(v) = sp.edit().putString("msg_signature", v).apply()

    /** quick biometric login (passkey-style) enabled flag + encrypted creds handle. */
    var quickLogin: Boolean
        get() = sp.getBoolean("quick_login", false)
        set(v) = sp.edit().putBoolean("quick_login", v).apply()

    /** encrypted quick-login credentials blob (AES/GCM via Android Keystore). */
    var quickLoginBlob: String
        get() = sp.getString("quick_login_blob", "") ?: ""
        set(v) = sp.edit().putString("quick_login_blob", v).apply()

    fun isChatLocked(chatId: Int): Boolean {
        return try {
            val arr = org.json.JSONArray(lockedChats)
            (0 until arr.length()).any { arr.optInt(it) == chatId }
        } catch (_: Exception) { false }
    }

    fun setChatLocked(chatId: Int, locked: Boolean) {
        try {
            val arr = org.json.JSONArray(lockedChats)
            val cur = (0 until arr.length()).map { arr.optInt(it) }.filter { it != chatId }.toMutableList()
            if (locked) cur.add(chatId)
            val out = org.json.JSONArray()
            cur.forEach { out.put(it) }
            lockedChats = out.toString()
        } catch (_: Exception) {}
    }

    fun clearSession() {
        token = ""
        sinceId = 0
        sp.edit().remove("my_id").remove("my_name").remove("my_username").remove("my_avatar").apply()
    }
}
