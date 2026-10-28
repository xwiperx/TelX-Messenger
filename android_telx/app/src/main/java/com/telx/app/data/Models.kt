package com.telx.app.data

import org.json.JSONArray
import org.json.JSONObject

class ApiErr(val code: String, message: String) : Exception(message)

class User(j: JSONObject) {
    val id: Int = j.optInt("id")
    val first: String = j.optString("first", "")
    val last: String = j.optString("last", "")
    val name: String = j.optString("name", "$first $last".trim())
    val username: String = j.optString("username", "")
    val bio: String = j.optString("bio", "")
    val avatar: String? = j.optStringOrNull("avatar")
    val online: Boolean = j.optBoolean("online")
    val lastTs: Long = j.optLong("last_ts", 0)
    val privacyLock: Boolean = j.optBoolean("privacy_lock")
    val email: String = j.optString("email", "")
    val twofa: Boolean = j.optBoolean("twofa")
    val isMe: Boolean = j.optBoolean("is_me")
    val isContact: Boolean = j.optBoolean("is_contact")
    val hasStory: Boolean = j.optBoolean("has_story")
    val blockedByMe: Boolean = j.optBoolean("blocked_by_me")
    val blockedMe: Boolean = j.optBoolean("blocked_me")
    val role: String = j.optString("role", "")
    val viewedTs: Long = j.optLong("viewed_ts", 0)

    companion object {
        fun parseArray(arr: JSONArray?): List<User> {
            val out = ArrayList<User>()
            if (arr != null) for (i in 0 until arr.length()) out.add(User(arr.getJSONObject(i)))
            return out
        }
    }
}

class Reaction(j: JSONObject) {
    val emoji: String = j.optString("emoji")
    val count: Int = j.optInt("count")
    val mine: Boolean = j.optBoolean("mine")
}

class ReplyInfo(j: JSONObject) {
    val id: Int = j.optInt("id")
    val name: String = j.optString("name", "")
    val type: String = j.optString("type", "text")
    val text: String = j.optString("text", "")
    val hasMedia: Boolean = j.optBoolean("has_media")
    val deleted: Boolean = j.optBoolean("deleted")
}

class Message(j: JSONObject) {
    val id: Int = j.optInt("id")
    val chatId: Int = j.optInt("chat_id")
    val senderId: Int = j.optInt("sender_id")
    val senderName: String = j.optString("sender_name", "")
    val type: String = j.optString("type", "text")
    val text: String = j.optString("text", "")
    val media: String? = j.optStringOrNull("media")
    val thumb: String? = j.optStringOrNull("thumb")
    val mediaName: String = j.optString("media_name", "")
    val mediaSize: Long = j.optLong("media_size", 0)
    val mediaDuration: Int = j.optInt("media_duration", 0)
    val sticker: String? = j.optStringOrNull("sticker")
    val fwdFrom: Int? = if (j.has("fwd_from") && !j.isNull("fwd_from")) j.getInt("fwd_from") else null
    val fwdName: String = j.optString("fwd_name", "")
    val replyTo: Int? = if (j.has("reply_to") && !j.isNull("reply_to")) j.getInt("reply_to") else null
    val edited: Boolean = j.optBoolean("edited")
    val scheduled: Boolean = j.optBoolean("scheduled")
    val enc: Boolean = j.optBoolean("enc")
    val ts: Long = j.optLong("ts", 0)
    val link: JSONObject? = if (j.has("link") && !j.isNull("link")) j.getJSONObject("link") else null
    val reply: ReplyInfo? = if (j.has("reply") && !j.isNull("reply")) ReplyInfo(j.getJSONObject("reply")) else null
    var reactions: List<Reaction> = parseReactions(j.optJSONArray("reactions"))
    var state: Int = STATE_OK // 0 pending, 1 failed, 2 ok

    val isMine: Boolean get() = senderId == Prefs.myId && senderId != 0
    val hasMedia: Boolean get() = type in listOf("photo", "file", "voice", "video", "gif")

    companion object {
        const val STATE_PENDING = 0
        const val STATE_FAILED = 1
        const val STATE_OK = 2
        fun parseArray(arr: JSONArray?): ArrayList<Message> {
            val out = ArrayList<Message>()
            if (arr != null) for (i in 0 until arr.length()) out.add(Message(arr.getJSONObject(i)))
            return out
        }
    }
}

internal fun parseReactions(arr: JSONArray?): List<Reaction> {
    val out = ArrayList<Reaction>()
    if (arr != null) for (i in 0 until arr.length()) out.add(Reaction(arr.getJSONObject(i)))
    return out
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

class Chat(j: JSONObject) {
    val id: Int = j.optInt("id")
    val type: String = j.optString("type", "private")
    val title: String = j.optString("title", "")
    val username: String? = j.optStringOrNull("username")
    val description: String? = j.optStringOrNull("description")
    val secret: Boolean = j.optBoolean("secret")
    val avatar: String? = j.optStringOrNull("avatar")
    val unread: Int = j.optInt("unread", 0)
    val muted: Boolean = j.optBoolean("muted")
    val pinned: Boolean = j.optBoolean("pinned")
    val archived: Boolean = j.optBoolean("archived")
    val draft: String? = j.optStringOrNull("draft")
    val autoDeleteSecs: Int = j.optInt("auto_delete_secs", 0)
    val membersCount: Int = j.optInt("members_count", 0)
    val lastMessage: Message? = if (j.has("last_message") && !j.isNull("last_message")) Message(j.getJSONObject("last_message")) else null
    val lastTs: Long = j.optLong("last_ts", 0)
    val peer: User? = if (j.has("peer") && !j.isNull("peer")) User(j.getJSONObject("peer")) else null
    val peerLastRead: Int = j.optInt("peer_last_read", 0)
    val peerLastDelivered: Int = j.optInt("peer_last_delivered", 0)
    val myRole: String = j.optString("my_role", "")
    val typing: Boolean = j.optBoolean("typing")

    val isChannel: Boolean get() = type == "channel"
    val isGroup: Boolean get() = type == "group"
    val isAdmin: Boolean get() = (isChannel && myRole == "admin") || (isGroup && myRole == "admin")

    val displayName: String
        get() = when (type) {
            "saved" -> "Saved Messages"
            "group" -> if (title.isNotEmpty()) title else "Group"
            "channel" -> if (title.isNotEmpty()) title else "Channel"
            else -> peer?.name ?: title
        }
    val displayAvatar: String? get() = if (type == "group" || type == "channel") avatar else peer?.avatar ?: avatar
    val isSaved get() = type == "saved"
}

class Story(j: JSONObject) {
    val id: Int = j.optInt("id")
    val media: String? = j.optStringOrNull("media")
    val thumb: String? = j.optStringOrNull("thumb")
    val type: String = j.optString("type", "photo")
    val duration: Int = j.optInt("duration", 15)
    val ts: Long = j.optLong("ts", 0)
    val views: Int = j.optInt("views", 0)

    companion object {
        fun parseArray(arr: org.json.JSONArray?): List<Story> {
            val out = ArrayList<Story>()
            if (arr != null) for (i in 0 until arr.length()) out.add(Story(arr.getJSONObject(i)))
            return out
        }
    }
}

class StoryGroup(j: JSONObject) {
    val user: User = User(j.getJSONObject("user"))
    val seen: Boolean = j.optBoolean("seen")
    val stories: List<Story> = Story.parseArray(j.optJSONArray("stories"))
}

class Session(j: JSONObject) {
    val id: Int = j.optInt("id")
    val device: String = j.optString("device", "")
    val ip: String = j.optString("ip", "")
    val current: Boolean = j.optBoolean("current")
    val createdTs: Long = j.optLong("created_ts", 0)
    val lastTs: Long = j.optLong("last_ts", 0)
}

class Event(j: JSONObject) {
    val id: Int = j.optInt("id")
    val type: String = j.optString("type", "")
    val chatId: Int? = if (j.has("chat_id") && !j.isNull("chat_id")) j.getInt("chat_id") else null
    val fromUser: Int? = if (j.has("from_user") && !j.isNull("from_user")) j.getInt("from_user") else null
    val data: JSONObject = j.optJSONObject("data") ?: JSONObject()
    val ts: Long = j.optLong("ts", 0)
}
