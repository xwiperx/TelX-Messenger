package com.telx.app.net

import com.telx.app.data.ApiErr
import com.telx.app.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Blocking HTTP client for the TelX server. Call from background threads only. */
object Api {

    const val BASE = "https://mybackup-test.alwaysdata.net/telx/"
    private const val API = BASE + "api/"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000
    private const val POLL_READ_TIMEOUT = 30000

    class Resp(val j: JSONObject) {
        val ok: Boolean = j.optBoolean("ok", false)
        val error: String = j.optString("error", "")
        val message: String = j.optString("message", "")
    }

    private fun request(url: String, body: JSONObject?, readTimeout: Int): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = readTimeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val token = Prefs.token
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val payload = (body ?: JSONObject()).toString()
            val os = conn.outputStream
            os.write(payload.toByteArray(Charsets.UTF_8))
            os.close()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = readAll(stream)
            val j = try { JSONObject(text) } catch (e: Exception) {
                throw ApiErr("network", "Bad response (HTTP $code)")
            }
            return Resp(j)
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val sb = StringBuilder()
        try {
            val br = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            var line = br.readLine()
            while (line != null) { sb.append(line); line = br.readLine() }
            br.close()
        } catch (_: Exception) {}
        return sb.toString()
    }

    /** Executes an API action; throws ApiErr on failure. params is applied on a fresh JSONObject. */
    fun call(endpoint: String, action: String, params: JSONObject.() -> Unit = {}): JSONObject {
        val body = JSONObject().apply(params)
        body.put("action", action)
        val url = API + endpoint + ".php"
        val r = request(url, body, READ_TIMEOUT)
        if (!r.ok) throw ApiErr(if (r.error.isEmpty()) "server" else r.error,
            if (r.message.isEmpty()) r.error else r.message)
        return r.j
    }

    fun tryCall(endpoint: String, action: String, params: JSONObject.() -> Unit = {}): JSONObject? =
        try { call(endpoint, action, params) } catch (e: Exception) { null }

    // ---------------- auth ----------------
    fun checkUsername(u: String): Pair<Boolean, String?> {
        val j = call("auth", "check_username") { put("username", u) }
        return j.optBoolean("available") to j.optStringOrNull2("reason")
    }

    fun register(first: String, last: String, username: String, email: String, password: String, device: String): JSONObject =
        call("auth", "register") {
            put("first", first); put("last", last); put("username", username)
            put("email", email); put("password", password); put("device", device)
        }

    fun login(username: String, password: String, device: String): JSONObject =
        call("auth", "login") { put("username", username); put("password", password); put("device", device) }

    fun verify2fa(username: String, password: String, code: String, device: String): JSONObject =
        call("auth", "verify2fa") {
            put("username", username); put("password", password); put("code", code); put("device", device)
        }

    fun forgot(username: String): JSONObject =
        call("auth", "forgot") { put("username", username) }

    fun reset(username: String, code: String, newPass: String): JSONObject =
        call("auth", "reset") { put("username", username); put("code", code); put("new_password", newPass) }

    fun logout() = call("auth", "logout")

    fun set2fa(password: String, enabled: Boolean): JSONObject =
        call("auth", "set2fa") { put("password", password); put("enabled", if (enabled) 1 else 0) }

    fun changePassword(old: String, new: String) = call("auth", "change_password") { put("old", old); put("new", new) }

    fun sessions(): JSONArray = call("auth", "sessions").getJSONArray("sessions")

    fun revokeSession(id: Int) = call("auth", "revoke_session") { put("session_id", id) }

    fun deleteAccount(password: String) = call("auth", "delete_account") { put("password", password) }

    // ---------------- chats ----------------
    fun chatsList(includeArchived: Boolean = false): JSONObject =
        call("chats", "list") { put("include_archived", if (includeArchived) 1 else 0) }

    fun chatOpen(userId: Int): JSONObject = call("chats", "open") { put("user_id", userId) }

    fun chatOpenSaved(): JSONObject = call("chats", "open_saved")

    fun chatInfo(chatId: Int): JSONObject = call("chats", "info") { put("chat_id", chatId) }

    fun createGroup(title: String, memberIds: List<Int>): JSONObject =
        call("chats", "create_group") {
            put("title", title)
            put("member_ids", JSONArray().also { a -> memberIds.forEach { a.put(it) } })
        }

    fun setMute(chatId: Int, muted: Boolean) = call("chats", "set_mute") { put("chat_id", chatId); put("muted", if (muted) 1 else 0) }
    fun setChatPinned(chatId: Int, pinned: Boolean) = call("chats", "set_pinned") { put("chat_id", chatId); put("pinned", if (pinned) 1 else 0) }
    fun setArchived(chatId: Int, archived: Boolean) = call("chats", "set_archived") { put("chat_id", chatId); put("archived", if (archived) 1 else 0) }
    fun setDraft(chatId: Int, draft: String?) { tryCall("chats", "set_draft") { put("chat_id", chatId); put("draft", draft ?: "") } }
    fun clearHistory(chatId: Int) = call("chats", "clear_history") { put("chat_id", chatId) }
    fun leaveGroup(chatId: Int) = call("chats", "leave_group") { put("chat_id", chatId) }
    fun groupInfo(chatId: Int): JSONObject = call("chats", "group_info") { put("chat_id", chatId) }
    fun addMembers(chatId: Int, ids: List<Int>): JSONObject =
        call("chats", "add_members") { put("chat_id", chatId); put("user_ids", JSONArray().also { a -> ids.forEach { a.put(it) } }) }
    fun removeMember(chatId: Int, userId: Int) = call("chats", "remove_member") { put("chat_id", chatId); put("user_id", userId) }

    // ---------------- channels ----------------
    fun createChannel(title: String, description: String, username: String, memberIds: List<Int>): JSONObject =
        call("chats", "create_channel") {
            put("title", title)
            if (description.isNotEmpty()) put("description", description)
            if (username.isNotEmpty()) put("username", username)
            put("member_ids", JSONArray().also { a -> memberIds.forEach { a.put(it) } })
        }

    fun joinChannel(chatId: Int = 0, username: String = ""): JSONObject =
        call("chats", "join_channel") {
            if (chatId > 0) put("chat_id", chatId)
            if (username.isNotEmpty()) put("username", username)
        }

    fun leaveChannel(chatId: Int) = call("chats", "leave_channel") { put("chat_id", chatId) }

    fun updateChannel(chatId: Int, title: String? = null, description: String? = null,
                      username: String? = null, avatarRel: String? = null): JSONObject =
        call("chats", "update_channel") {
            put("chat_id", chatId)
            if (!title.isNullOrEmpty()) put("title", title)
            if (description != null) put("description", description)
            // username "" => make the channel private (server clears the public link)
            if (username != null) put("username", username)
            if (avatarRel != null) put("avatar_rel", avatarRel)
        }

    fun channelInfo(chatId: Int): JSONObject = call("chats", "channel_info") { put("chat_id", chatId) }
    fun deleteChannel(chatId: Int) = call("chats", "delete_channel") { put("chat_id", chatId) }
    fun setChannelAdmin(chatId: Int, userId: Int, admin: Boolean) =
        call("chats", "set_channel_admin") { put("chat_id", chatId); put("user_id", userId); put("admin", if (admin) 1 else 0) }
    fun searchPublic(q: String): JSONArray = call("chats", "search_public") { put("q", q) }.getJSONArray("channels")

    // ---------------- encrypted backup ----------------
    fun backupMeta(): JSONObject = call("backup", "meta")
    fun backupSave(dataB64: String): JSONObject = call("backup", "save") { put("data", dataB64) }
    fun backupGet(): JSONObject = call("backup", "get")
    fun backupDelete() = call("backup", "delete")

    // ---------------- messages ----------------
    fun messages(chatId: Int, beforeId: Int = 0, limit: Int = 40): JSONObject =
        call("messages", "list") { put("chat_id", chatId); put("before_id", beforeId); put("limit", limit) }

    fun send(chatId: Int, type: String, text: String = "", mediaRel: String? = null, thumbRel: String? = null,
             mediaName: String = "", mediaSize: Long = 0, mediaDuration: Int = 0,
             replyTo: Int = 0): JSONObject =
        call("messages", "send") {
            put("chat_id", chatId); put("type", type); put("text", text)
            if (!mediaRel.isNullOrEmpty()) put("media_rel", mediaRel)
            if (!thumbRel.isNullOrEmpty()) put("thumb_rel", thumbRel)
            if (mediaName.isNotEmpty()) put("media_name", mediaName)
            if (mediaSize > 0) put("media_size", mediaSize)
            if (mediaDuration > 0) put("media_duration", mediaDuration)
            if (replyTo > 0) put("reply_to", replyTo)
        }

    fun editMessage(messageId: Int, text: String): JSONObject =
        call("messages", "edit") { put("message_id", messageId); put("text", text) }

    fun deleteMessage(messageId: Int) = call("messages", "delete") { put("message_id", messageId) }

    fun forward(messageIds: List<Int>, toChatId: Int): JSONObject =
        call("messages", "forward") {
            put("message_ids", JSONArray().also { a -> messageIds.forEach { a.put(it) } })
            put("to_chat_id", toChatId)
        }

    fun pin(chatId: Int, messageId: Int) = call("messages", "pin") { put("chat_id", chatId); put("message_id", messageId) }
    fun unpin(chatId: Int) = call("messages", "pin") { put("chat_id", chatId); put("message_id", 0) }
    fun markRead(chatId: Int, lastReadId: Int) { tryCall("messages", "read") { put("chat_id", chatId); put("last_read_id", lastReadId) } }
    fun markDelivered(chatId: Int, lastId: Int) { tryCall("messages", "delivered") { put("chat_id", chatId); put("last_delivered_id", lastId) } }
    fun typing(chatId: Int) { tryCall("messages", "typing") { put("chat_id", chatId) } }
    fun react(messageId: Int, emoji: String?): JSONObject =
        call("messages", "react") { put("message_id", messageId); if (emoji != null) put("emoji", emoji) }

    fun searchInChat(chatId: Int, q: String): JSONArray = call("messages", "search") { put("chat_id", chatId); put("q", q) }.getJSONArray("messages")
    fun searchGlobal(q: String): JSONObject = call("messages", "search_global") { put("q", q) }

    // ---------------- contacts ----------------
    fun contactsList(): JSONArray = call("contacts", "list").getJSONArray("contacts")
    fun contactAdd(userId: Int): JSONObject = call("contacts", "add") { put("user_id", userId) }
    fun contactAddByUsername(username: String): JSONObject = call("contacts", "add") { put("username", username) }
    fun contactRemove(userId: Int) = call("contacts", "remove") { put("user_id", userId) }
    fun block(userId: Int) = call("contacts", "block") { put("user_id", userId) }
    fun unblock(userId: Int) = call("contacts", "unblock") { put("user_id", userId) }
    fun blockedList(): JSONArray = call("contacts", "blocked_list").getJSONArray("blocked")
    fun searchUsers(q: String): JSONArray = call("contacts", "search_users") { put("q", q) }.getJSONArray("users")

    // ---------------- profile ----------------
    fun profileGet(userId: Int = 0): JSONObject = call("profile", "get") { if (userId > 0) put("user_id", userId) }

    fun profileUpdate(first: String? = null, last: String? = null, bio: String? = null,
                      username: String? = null, privacyLock: Boolean? = null, avatarRel: String? = null): JSONObject =
        call("profile", "update") {
            if (!first.isNullOrEmpty()) put("first", first)
            if (last != null) put("last", last)
            if (!bio.isNullOrEmpty()) put("bio", bio)
            if (!username.isNullOrEmpty()) put("username", username)
            if (privacyLock != null) put("privacy_lock", if (privacyLock) 1 else 0)
            if (avatarRel != null) put("avatar_rel", avatarRel)
        }

    // ---------------- stories ----------------
    fun storiesFeed(): JSONArray = call("stories", "feed").getJSONArray("feed")
    fun storyUpload(mediaRel: String, thumbRel: String?, isVideo: Boolean, duration: Int): JSONObject =
        call("stories", "upload") {
            put("media_rel", mediaRel)
            if (thumbRel != null) put("thumb_rel", thumbRel)
            put("media_type", if (isVideo) "video" else "photo")
            put("duration", duration)
        }

    fun storyView(storyId: Int) { tryCall("stories", "view") { put("story_id", storyId) } }
    fun storyViewers(storyId: Int): JSONArray = call("stories", "viewers") { put("story_id", storyId) }.getJSONArray("viewers")
    fun storyMine(): JSONArray = call("stories", "mine").getJSONArray("stories")
    fun storyDelete(storyId: Int) = call("stories", "delete") { put("story_id", storyId) }

    // ---------------- events (client-paced poll; wait=0 → server replies instantly) ----------------
    fun events(sinceId: Int): JSONObject {
        val body = JSONObject().put("action", "poll").put("since_id", sinceId).put("wait", 0)
        return request(API + "events.php", body, POLL_READ_TIMEOUT).j
    }

    // ---------------- uploads ----------------
    fun upload(kind: String, file: File): JSONObject {
        val boundary = "----TelXBoundary" + System.currentTimeMillis()
        val conn = URL(API + "upload.php").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 180000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val token = Prefs.token
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val out = DataOutputStream(conn.outputStream)
            val field = "kind"
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"$field\"\r\n\r\n")
            out.writeBytes(kind)
            out.writeBytes("\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
            FileInputStream(file).use { fis -> fis.copyTo(out, 8192) }
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush(); out.close()
            val code = conn.responseCode
            val text = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            val j = try { JSONObject(text) } catch (e: Exception) { throw ApiErr("network", "Upload failed (HTTP $code)") }
            if (!j.optBoolean("ok", false)) throw ApiErr(j.optString("error", "upload"), j.optString("message", "Upload failed"))
            return j.getJSONObject("upload")
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.optStringOrNull2(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
