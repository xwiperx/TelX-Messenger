package com.telx.app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.data.Prefs
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.common.RichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6: channel post comments — a Telegram-style discussion thread under a post.
 * Comments are stored on the server with comment_to = post id.
 */
class CommentsActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var postId = 0
    private val comments = ArrayList<Message>()
    private lateinit var adapter: CAdapter
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)
        postId = intent.getIntExtra("post_id", 0)
        if (postId <= 0) { finish(); return }

        input = findViewById(R.id.comments_input)
        val recycler = findViewById<RecyclerView>(R.id.comments_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CAdapter(comments)
        recycler.adapter = adapter
        findViewById<ImageView>(R.id.comments_back).setOnClickListener { finish() }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.comments_send)
            .setOnClickListener { sendComment() }
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.comments(postId) }
                val post = Message(j.getJSONObject("post"))
                findViewById<TextView>(R.id.comments_title).text = "Comments"
                findViewById<TextView>(R.id.comments_subtitle).text =
                    post.text.ifEmpty { "Channel post" }.take(40)
                comments.clear()
                comments.addAll(Message.parseArray(j.optJSONArray("messages")))
                adapter.notifyDataSetChanged()
                recyclerScroll()
            } catch (e: Exception) {
                Helpers.toast(this@CommentsActivity, "Couldn't load comments")
            }
        }
    }

    private fun recyclerScroll() {
        findViewById<RecyclerView>(R.id.comments_recycler).post {
            findViewById<RecyclerView>(R.id.comments_recycler).scrollToPosition(comments.size.coerceAtLeast(0))
        }
    }

    private fun sendComment() {
        val t = input.text?.toString()?.trim() ?: ""
        if (t.isEmpty()) return
        input.setText("")
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.send(chatIdOfPost(), "text", t, commentTo = postId) }
                comments.add(Message(j.getJSONObject("message")))
                adapter.notifyItemInserted(comments.size - 1)
                recyclerScroll()
            } catch (e: Exception) {
                Helpers.toast(this@CommentsActivity, if (e is com.telx.app.data.ApiErr) e.message else "Comment failed")
            }
        }
    }

    private var cachedChatId = 0
    private fun chatIdOfPost(): Int {
        if (cachedChatId == 0) cachedChatId = intent.getIntExtra("chat_id", 0)
        return cachedChatId
    }

    /** lightweight comment row renderer */
    class CAdapter(private val items: List<Message>) : RecyclerView.Adapter<CAdapter.CVH>() {

        class CVH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.cmt_name)
            val text: TextView = v.findViewById(R.id.cmt_text)
            val time: TextView = v.findViewById(R.id.cmt_time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CVH =
            CVH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: CVH, pos: Int) {
            val m = items[pos]
            h.name.text = if (m.anon) "Channel" else (if (m.isMine) Prefs.myName else m.senderName).ifEmpty { "User" }
            h.name.setTextColor(Helpers.colorFor(m.senderId))
            RichText.set(h.text, m.text)
            h.time.text = Helpers.timeShort(m.ts)
        }
    }
}
