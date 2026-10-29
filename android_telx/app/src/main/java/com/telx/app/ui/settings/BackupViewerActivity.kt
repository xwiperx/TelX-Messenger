package com.telx.app.ui.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.BackupHelper
import com.telx.app.data.Crypto
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Read-only viewer for the decrypted cloud backup (profile / contacts / chats). */
class BackupViewerActivity : AppCompatActivity() {

    private class Row(val kind: Int, val title: String, val sub: String) { // 0 header, 1 chat
        companion object { const val HEADER = 0; const val CHAT = 1 }
    }

    private val ui = CoroutineScope(Dispatchers.Main)
    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_viewer)
        setSupportActionBar(findViewById<Toolbar>(R.id.viewer_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Backup contents"

        val recycler = findViewById<RecyclerView>(R.id.viewer_recycler)
        val empty = findViewById<TextView>(R.id.viewer_empty)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                VH(layoutInflater.inflate(R.layout.item_backup_chat, parent, false))
            override fun getItemCount() = rows.size
            override fun onBindViewHolder(h: VH, position: Int) {
                val r = rows[position]
                h.title.text = r.title
                h.sub.text = r.sub
            }
        }
        val password = intent.getStringExtra("password") ?: ""
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.backupGet() }
                val json = withContext(Dispatchers.IO) { Crypto.decrypt(j.getString("data"), password) }
                val root = JSONObject(json)
                rows.add(Row(Row.HEADER, "Profile",
                    root.optJSONObject("profile")?.optString("name") ?: "—"))
                val ca = root.optJSONArray("contacts") ?: JSONArray()
                rows.add(Row(Row.HEADER, "Contacts (${ca.length()})",
                    (0 until ca.length()).take(8).joinToString(", ") { ca.getJSONObject(it).optString("name", "?") }
                            + if (ca.length() > 8) "…" else ""))
                val ch = root.optJSONArray("chats") ?: JSONArray()
                for (i in 0 until ch.length()) {
                    val c = ch.getJSONObject(i)
                    val title = when (c.optString("type")) {
                        "saved" -> "Saved Messages"
                        "channel" -> "${c.optString("title")} (channel)"
                        "group" -> "${c.optString("title")} (group)"
                        else -> c.optString("title").ifEmpty { "Private chat" }
                    }
                    val msgs = c.optJSONArray("messages") ?: JSONArray()
                    val last = if (msgs.length() > 0) {
                        val m = msgs.getJSONObject(msgs.length() - 1)
                        m.optString("text").ifEmpty { "Media message" }
                    } else "empty"
                    rows.add(Row(Row.CHAT, "$title · ${msgs.length()} msgs", last))
                }
                recycler.adapter?.notifyDataSetChanged()
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Helpers.toast(this@BackupViewerActivity, "Couldn't decrypt backup")
                finish()
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.bk_title)
        val sub: TextView = v.findViewById(R.id.bk_sub)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
