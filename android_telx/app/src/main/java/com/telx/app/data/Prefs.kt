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

    fun clearSession() {
        token = ""
        sinceId = 0
        sp.edit().remove("my_id").remove("my_name").remove("my_username").remove("my_avatar").apply()
    }
}
