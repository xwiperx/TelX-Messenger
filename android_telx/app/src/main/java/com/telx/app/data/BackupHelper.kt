package com.telx.app.data

import com.telx.app.net.Api
import org.json.JSONArray
import org.json.JSONObject

/** Builds and applies the TelX cloud backup payload. All network calls run on the caller thread. */
object BackupHelper {

    /** Collects profile, contacts and recent chat history into one compact JSON string. Blocking. */
    fun buildPayload(): String {
        val root = JSONObject()
        root.put("app", "telx")
        root.put("v", 1)
        root.put("created", System.currentTimeMillis() / 1000)

        // profile
        try {
            val p = Api.profileGet()
            val u = p.optJSONObject("user")
            if (u != null) root.put("profile", JSONObject()
                .put("name", u.optString("name"))
                .put("username", u.optString("username"))
                .put("bio", u.optString("bio")))
        } catch (_: Exception) {}

        // contacts
        val contacts = JSONArray()
        try {
            val arr = Api.contactsList()
            if (arr != null) for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                contacts.put(JSONObject()
                    .put("id", u.optInt("id"))
                    .put("name", u.optString("name"))
                    .put("username", u.optString("username")))
            }
        } catch (_: Exception) {}
        root.put("contacts", contacts)

        // chats + recent messages
        val chats = JSONArray()
        try {
            val cl = Api.chatsList()
            val arr = cl.optJSONArray("chats")
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val cj = JSONObject()
                    .put("id", c.optInt("id"))
                    .put("type", c.optString("type"))
                    .put("title", c.optString("title"))
                    .put("username", c.optString("username"))
                val msgs = JSONArray()
                try {
                    val ml = Api.messages(c.optInt("id"))
                    val ma = ml.optJSONArray("messages")
                    if (ma != null) for (k in 0 until ma.length()) {
                        val m = ma.getJSONObject(k)
                        val o = JSONObject()
                            .put("type", m.optString("type"))
                            .put("from", m.optString("sender_name"))
                            .put("mine", m.optInt("sender_id") == Prefs.myId)
                            .put("ts", m.optLong("ts"))
                        val txt = m.optString("text", "")
                        if (txt.isNotEmpty()) o.put("text", txt)
                        val media = m.optStringOrNull("media")
                        if (media != null) o.put("media", media)
                        msgs.put(o)
                    }
                } catch (_: Exception) {}
                cj.put("messages", msgs)
                chats.put(cj)
            }
        } catch (_: Exception) {}
        root.put("chats", chats)
        return root.toString()
    }

    data class Restored(
        val created: Long,
        val profileName: String,
        val contacts: List<String>,
        val chats: List<Triple<String, String, Int>>  // title, type, message count
    )

    /** Downloads + decrypts the cloud blob. Blocking. Re-adds contacts on success. */
    fun restore(password: String): Restored {
        val j = Api.backupGet()
        val data = j.getString("data")
        val json = Crypto.decrypt(data, password)
        val root = JSONObject(json)
        // re-add contacts that are not yet contacts
        val ca = root.optJSONArray("contacts")
        if (ca != null) for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            val id = c.optInt("id")
            if (id > 0 && id != Prefs.myId) {
                try { Api.contactAdd(id) } catch (_: Exception) {}
            }
        }
        val contacts = ArrayList<String>()
        if (ca != null) for (i in 0 until ca.length()) contacts.add(ca.getJSONObject(i).optString("name", "Unknown"))
        val chats = ArrayList<Triple<String, String, Int>>()
        val ch = root.optJSONArray("chats")
        if (ch != null) for (i in 0 until ch.length()) {
            val c = ch.getJSONObject(i)
            val title = when (c.optString("type")) {
                "saved" -> "Saved Messages"
                else -> c.optString("title").ifEmpty { "Private chat" }
            }
            chats.add(Triple(title, c.optString("type"), c.optJSONArray("messages")?.length() ?: 0))
        }
        val p = root.optJSONObject("profile")
        return Restored(root.optLong("created"), p?.optString("name") ?: "", contacts, chats)
    }
}
