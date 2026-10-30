package com.telx.app.net

import com.telx.app.data.ApiErr
import com.telx.app.data.Chat
import com.telx.app.data.Message
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
             replyTo: Int = 0, scheduledTs: Long = 0, mediaJson: String? = null,
             stickerFile: String? = null, silent: Boolean = false, anon: Boolean = false,
             effect: String? = null, groupId: Long = 0, commentTo: Int = 0,
             buttons: String? = null, inlineJson: String? = null,
             ephemeralTo: Int = 0, repeatEvery: Int = 0): JSONObject =
        call("messages", "send") {
            put("chat_id", chatId); put("type", type); put("text", text)
            if (!mediaRel.isNullOrEmpty()) put("media_rel", mediaRel)
            if (!thumbRel.isNullOrEmpty()) put("thumb_rel", thumbRel)
            if (mediaName.isNotEmpty()) put("media_name", mediaName)
            if (mediaSize > 0) put("media_size", mediaSize)
            if (mediaDuration > 0) put("media_duration", mediaDuration)
            if (replyTo > 0) put("reply_to", replyTo)
            if (scheduledTs > 0) put("scheduled_ts", scheduledTs)
            if (!mediaJson.isNullOrEmpty()) put("media_json", mediaJson)
            if (!stickerFile.isNullOrEmpty()) put("sticker", stickerFile)
            if (silent) put("silent", 1)
            if (anon) put("anon", 1)
            if (!effect.isNullOrEmpty()) put("effect", effect)
            if (groupId > 0) put("group_id", groupId)
            if (commentTo > 0) put("comment_to", commentTo)
            // v7: interactive buttons / inline rich media / one-recipient / recurring
            if (!buttons.isNullOrEmpty()) put("buttons", buttons)
            if (!inlineJson.isNullOrEmpty()) put("inline_json", inlineJson)
            if (ephemeralTo > 0) put("ephemeral_to", ephemeralTo)
            if (repeatEvery > 0) put("repeat_every", repeatEvery)
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
    // v6: multi-pin
    fun pinList(chatId: Int): ArrayList<Message> =
        Message.parseArray(call("messages", "pin_list") { put("chat_id", chatId) }.optJSONArray("messages"))
    fun pinSet(chatId: Int, messageId: Int, pinned: Boolean) =
        call("messages", "pin_set") { put("chat_id", chatId); put("message_id", messageId); put("pinned", if (pinned) 1 else 0) }
    // v6: comments + jump-to-date + audio tab
    fun comments(postId: Int): JSONObject = call("messages", "comments") { put("post_id", postId) }
    fun messagesByDate(chatId: Int, date: String): ArrayList<Message> =
        Message.parseArray(call("messages", "by_date") { put("chat_id", chatId); put("date", date) }.optJSONArray("messages"))
    fun audioList(chatId: Int): ArrayList<Message> =
        Message.parseArray(call("messages", "media_list") { put("chat_id", chatId); put("kind", "audio") }.optJSONArray("messages"))
    fun markRead(chatId: Int, lastReadId: Int) { tryCall("messages", "read") { put("chat_id", chatId); put("last_read_id", lastReadId) } }
    fun markDelivered(chatId: Int, lastId: Int) { tryCall("messages", "delivered") { put("chat_id", chatId); put("last_delivered_id", lastId) } }
    fun typing(chatId: Int) { tryCall("messages", "typing") { put("chat_id", chatId) } }
    fun react(messageId: Int, emoji: String?): JSONObject =
        call("messages", "react") { put("message_id", messageId); if (emoji != null) put("emoji", emoji) }

    fun searchInChat(chatId: Int, q: String): JSONArray = call("messages", "search") { put("chat_id", chatId); put("q", q) }.getJSONArray("messages")
    fun searchGlobal(q: String): JSONObject = call("messages", "search_global") { put("q", q) }

    // ---------------- media gallery / views / export / scheduled ----------------
    fun mediaList(chatId: Int, kind: String = "media"): JSONArray =
        call("messages", "media_list") { put("chat_id", chatId); put("kind", kind) }.getJSONArray("messages")

    fun viewPing(messageIds: List<Int>) { if (messageIds.isEmpty()) return; tryCall("messages", "view_ping") {
        put("message_ids", JSONArray().also { a -> messageIds.forEach { a.put(it) } }) } }

    fun exportChat(chatId: Int): JSONObject = call("messages", "export") { put("chat_id", chatId) }

    // ---------------- stickers ----------------
    fun stickersList(): JSONObject = call("stickers", "list")
    fun stickerPackCreate(title: String): JSONObject = call("stickers", "pack_create") { put("title", title) }
    fun stickerAdd(packId: Int, fileRel: String): JSONObject =
        call("stickers", "sticker_add") { put("pack_id", packId); put("file_rel", fileRel) }
    fun stickerDelete(packId: Int, url: String) = call("stickers", "sticker_delete") { put("pack_id", packId); put("url", url) }
    fun stickerPackDelete(packId: Int) = call("stickers", "pack_delete") { put("pack_id", packId) }

    // ---------------- folders / invites ----------------
    fun folders(): JSONArray = call("chats", "folders").getJSONArray("folders")
    fun folderSave(folderId: Int, name: String, emoji: String): JSONObject =
        call("chats", "folder_save") { put("folder_id", folderId); put("name", name); put("emoji", emoji) }
    fun folderDelete(folderId: Int) = call("chats", "folder_delete") { put("folder_id", folderId) }
    fun folderAssign(folderId: Int, chatId: Int, inside: Boolean) =
        call("chats", "folder_assign") { put("folder_id", folderId); put("chat_id", chatId); put("inside", if (inside) 1 else 0) }

    fun inviteInfo(chatId: Int, reset: Boolean = false): JSONObject =
        call("chats", "invite") { put("chat_id", chatId); put("reset", if (reset) 1 else 0) }
    fun joinInvite(code: String): JSONObject = call("chats", "join_invite") { put("code", code) }

    // ---------------- v6: group/channel admin ----------------
    fun setSlowMode(chatId: Int, seconds: Int) =
        call("chats", "set_slow_mode") { put("chat_id", chatId); put("seconds", seconds) }
    fun setJoinRequest(chatId: Int, enabled: Boolean) =
        call("chats", "set_join_request") { put("chat_id", chatId); put("enabled", if (enabled) 1 else 0) }
    fun setPerms(chatId: Int, perms: JSONObject) =
        call("chats", "set_perms") { put("chat_id", chatId); put("perms", perms.toString()) }
    fun banMember(chatId: Int, userId: Int, banned: Boolean) =
        call("chats", "ban_member") { put("chat_id", chatId); put("user_id", userId); put("banned", if (banned) 1 else 0) }
    fun bannedList(chatId: Int): JSONArray =
        call("chats", "banned_list") { put("chat_id", chatId) }.getJSONArray("banned")
    fun linksList(chatId: Int): ArrayList<com.telx.app.data.InviteLink> =
        com.telx.app.data.InviteLink.parseArray(call("chats", "links_list") { put("chat_id", chatId) }.optJSONArray("links"))
    fun linkCreate(chatId: Int, name: String, expiresDays: Int, usageLimit: Int): JSONObject =
        call("chats", "link_create") {
            put("chat_id", chatId); put("name", name)
            put("expires_days", expiresDays); put("usage_limit", usageLimit)
        }
    fun linkRevoke(chatId: Int, linkId: Int) =
        call("chats", "link_revoke") { put("chat_id", chatId); put("link_id", linkId) }
    fun requestsList(chatId: Int): JSONArray =
        call("chats", "requests_list") { put("chat_id", chatId) }.getJSONArray("requests")
    fun requestDecide(chatId: Int, userId: Int, approve: Boolean) =
        call("chats", "request_decide") { put("chat_id", chatId); put("user_id", userId); put("approve", if (approve) 1 else 0) }
    fun setHidden(chatId: Int, hidden: Boolean) =
        call("chats", "set_hidden") { put("chat_id", chatId); put("hidden", if (hidden) 1 else 0) }
    fun hiddenChats(): ArrayList<Chat> =
        Chat.parseArray(call("chats", "hidden_list").optJSONArray("chats"))
    fun chatStats(chatId: Int): com.telx.app.data.ChatStats =
        com.telx.app.data.ChatStats(call("chats", "chat_stats") { put("chat_id", chatId) }.getJSONObject("stats"))

    // ---------------- v6: security / moderation ----------------
    fun report(targetType: String, targetId: Int, reason: String) =
        call("reports", "report") { put("target_type", targetType); put("target_id", targetId); put("reason", reason) }
    fun setSelfDestruct(months: Int) = call("auth", "set_self_destruct") { put("months", months) }

    fun setAutoDelete(chatId: Int, seconds: Int) = call("chats", "set_auto_delete") { put("chat_id", chatId); put("seconds", seconds) }

    // ---------------- contacts ----------------
    fun contactsList(): JSONArray = call("contacts", "list").getJSONArray("contacts")
    fun contactAdd(userId: Int): JSONObject = call("contacts", "add") { put("user_id", userId) }
    fun contactAddByUsername(username: String): JSONObject = call("contacts", "add") { put("username", username) }
    fun contactAddByPhone(phone: String): JSONObject = call("contacts", "add") { put("phone", phone) }
    fun contactRemove(userId: Int) = call("contacts", "remove") { put("user_id", userId) }
    fun block(userId: Int) = call("contacts", "block") { put("user_id", userId) }
    fun unblock(userId: Int) = call("contacts", "unblock") { put("user_id", userId) }
    fun blockedList(): JSONArray = call("contacts", "blocked_list").getJSONArray("blocked")
    fun searchUsers(q: String): JSONArray = call("contacts", "search_users") { put("q", q) }.getJSONArray("users")
    // ---------------- profile ----------------
    fun profileGet(userId: Int = 0): JSONObject = call("profile", "get") { if (userId > 0) put("user_id", userId) }

    fun profileUpdate(first: String? = null, last: String? = null, bio: String? = null,
                      username: String? = null, privacyLock: Boolean? = null, avatarRel: String? = null,
                      phone: String? = null, privacyLastSeen: Int? = null,
                      privacyPhone: Int? = null, privacyPhoto: Int? = null,
                      profileMusic: String? = null, profileMusicTitle: String? = null,
                      profileTab: Int? = null): JSONObject =
        call("profile", "update") {
            if (!first.isNullOrEmpty()) put("first", first)
            if (last != null) put("last", last)
            if (!bio.isNullOrEmpty()) put("bio", bio)
            if (!username.isNullOrEmpty()) put("username", username)
            if (privacyLock != null) put("privacy_lock", if (privacyLock) 1 else 0)
            if (avatarRel != null) put("avatar_rel", avatarRel)
            if (phone != null) put("phone", phone)
            if (privacyLastSeen != null) put("privacy_last_seen", privacyLastSeen)
            if (privacyPhone != null) put("privacy_phone", privacyPhone)
            if (privacyPhoto != null) put("privacy_photo", privacyPhoto)
            // v7: profile music + default tab
            if (profileMusic != null) put("profile_music", profileMusic)
            if (profileMusicTitle != null) put("profile_music_title", profileMusicTitle)
            if (profileTab != null) put("profile_tab", profileTab)
        }

    // ---------------- stories ----------------
    fun storiesFeed(): JSONArray = call("stories", "feed").getJSONArray("feed")
    fun storyUpload(mediaRel: String, thumbRel: String?, isVideo: Boolean, duration: Int,
                    isLive: Boolean = false, album: String? = null): JSONObject =
        call("stories", "upload") {
            put("media_rel", mediaRel)
            if (thumbRel != null) put("thumb_rel", thumbRel)
            put("media_type", if (isVideo) "video" else "photo")
            put("duration", duration)
            if (isLive) put("is_live", 1)
            if (!album.isNullOrEmpty()) put("album", album)
        }

    fun storyView(storyId: Int) { tryCall("stories", "view") { put("story_id", storyId) } }
    fun storyViewers(storyId: Int): JSONArray = call("stories", "viewers") { put("story_id", storyId) }.getJSONArray("viewers")
    fun storyMine(): JSONArray = call("stories", "mine").getJSONArray("stories")
    fun storyDelete(storyId: Int) = call("stories", "delete") { put("story_id", storyId) }
    // v7: live stories + albums + live-chat replies
    fun storyReply(storyId: Int, text: String) =
        call("stories", "reply") { put("story_id", storyId); put("text", text) }
    fun storyReplies(storyId: Int): JSONArray =
        call("stories", "replies") { put("story_id", storyId) }.getJSONArray("replies")
    fun storyEndLive(storyId: Int) = call("stories", "end_live") { put("story_id", storyId) }

    // ---------------- v7: mighty polls / to-dos / buttons ----------------
    fun pollSend(chatId: Int, pollJson: JSONObject, silent: Boolean = false): JSONObject =
        call("messages", "send") {
            put("chat_id", chatId); put("type", "poll"); put("poll_json", pollJson)
            put("text", pollJson.optString("question"))
            if (silent) put("silent", 1)
        }

    fun pollGet(messageId: Int): JSONObject =
        call("messages", "poll_get") { put("message_id", messageId) }.getJSONObject("poll")

    fun pollVote(messageId: Int, optionIdxs: List<Int>, addOption: String? = null): JSONObject =
        call("messages", "poll_vote") {
            put("message_id", messageId)
            put("option_idxs", JSONArray().also { a -> optionIdxs.forEach { a.put(it) } })
            if (!addOption.isNullOrEmpty()) put("add_option", addOption)
        }.getJSONObject("poll")

    fun pollClose(messageId: Int) = call("messages", "poll_close") { put("message_id", messageId) }

    fun todoSend(chatId: Int, items: JSONArray): JSONObject =
        call("messages", "send") {
            put("chat_id", chatId); put("type", "todo"); put("media_json",
                JSONObject().put("items", items).toString())
        }.getJSONObject("message")

    fun todoToggle(messageId: Int, idx: Int, done: Boolean): JSONObject =
        call("messages", "todo_toggle") {
            put("message_id", messageId); put("idx", idx); put("done", if (done) 1 else 0)
        }.getJSONObject("message")

    fun buttonClick(messageId: Int, buttonId: String) =
        call("messages", "button_click") { put("message_id", messageId); put("button_id", buttonId) }

    // ---------------- v7: communities ----------------
    fun communityCreate(title: String, description: String, username: String): JSONObject =
        call("communities", "create") {
            put("title", title); put("description", description)
            if (username.isNotEmpty()) put("username", username)
        }

    fun communityChats(communityId: Int): ArrayList<Chat> =
        Chat.parseArray(call("communities", "chats") { put("community_id", communityId) }.optJSONArray("chats"))

    fun communityLinkChat(communityId: Int, chatId: Int) =
        call("communities", "link_chat") { put("community_id", communityId); put("chat_id", chatId) }

    fun communityUnlinkChat(communityId: Int, chatId: Int) =
        call("communities", "unlink_chat") { put("community_id", communityId); put("chat_id", chatId) }

    fun setWelcome(chatId: Int, text: String) =
        call("chats", "set_welcome") { put("chat_id", chatId); put("text", text) }

    fun markAllRead() = call("chats", "mark_all_read")

    // ---------------- v7: AI assistant + summaries ----------------
    fun aiComplete(text: String, mode: String, hint: String = ""): String =
        call("ai", "complete") { put("text", text); put("mode", mode); put("hint", hint) }
            .optString("result")

    fun aiSummarize(text: String = "", messageId: Int = 0, chatId: Int = 0, count: Int = 6): String =
        call("ai", "summarize") {
            if (text.isNotEmpty()) put("text", text)
            if (messageId > 0) put("message_id", messageId)
            if (chatId > 0) put("chat_id", chatId)
            put("count", count)
        }.optString("result")

    // ---------------- v7: GIF search ----------------
    fun gifSearch(q: String): JSONArray =
        call("gif", "search") { put("q", q) }.getJSONArray("gifs")

    // ---------------- v7: group calls ----------------
    fun groupCallStart(chatId: Int): JSONObject = call("calls", "group_start") { put("chat_id", chatId) }
    fun groupCallJoin(chatId: Int, callId: String) =
        call("calls", "group_join") { put("chat_id", chatId); put("call_id", callId) }
    fun groupCallLeave(chatId: Int) = call("calls", "group_leave") { put("chat_id", chatId) }
    fun groupCallParticipants(chatId: Int): JSONObject =
        call("calls", "group_participants") { put("chat_id", chatId) }
    fun groupCallEnd(chatId: Int, callId: String) =
        call("calls", "group_end") { put("chat_id", chatId); put("call_id", callId) }

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
