package com.telx.app.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.data.User
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.profile.UserProfileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Global search: users + messages across all chats. Also used to start new chats. */
class SearchActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var searchJob: Job? = null
    private val users = ArrayList<User>()
    private val messages = ArrayList<Message>()
    private lateinit var usersAdapter: ContactsAdapter
    private lateinit var msgsAdapter: MsgSearchAdapter
    private lateinit var usersTitle: TextView
    private lateinit var msgsTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        setSupportActionBar(findViewById<Toolbar>(R.id.search_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Search"

        val input = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_search)
        val usersRecycler = findViewById<RecyclerView>(R.id.search_users)
        val msgsRecycler = findViewById<RecyclerView>(R.id.search_messages)
        usersTitle = findViewById(R.id.search_users_title)
        msgsTitle = findViewById(R.id.search_messages_title)

        usersAdapter = ContactsAdapter(users)
        usersAdapter.onClick = {
            ui.launch {
                try {
                    val j = withContext(Dispatchers.IO) { Api.chatOpen(it.id) }
                    startActivity(Intent(this@SearchActivity, ChatActivity::class.java)
                        .putExtra("chat_id", j.getInt("chat_id")))
                    finish()
                } catch (e: Exception) {
                    if (e.message?.contains("blocked", true) == true)
                        Helpers.toast(this@SearchActivity, e.message)
                    else
                        startActivity(Intent(this@SearchActivity, UserProfileActivity::class.java).putExtra("user_id", it.id))
                }
            }
        }
        msgsAdapter = MsgSearchAdapter(messages) { m ->
            // open the chat that contains this message
            ui.launch {
                startActivity(Intent(this@SearchActivity, ChatActivity::class.java)
                    .putExtra("chat_id", m.chatId))
                finish()
            }
        }
        usersRecycler.layoutManager = LinearLayoutManager(this)
        usersRecycler.adapter = usersAdapter
        msgsRecycler.layoutManager = LinearLayoutManager(this)
        msgsRecycler.adapter = msgsAdapter

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString() ?: "")
            override fun beforeTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.requestFocus()
    }

    private fun schedule(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            users.clear(); messages.clear()
            usersAdapter.notifyDataSetChanged(); msgsAdapter.notifyDataSetChanged()
            usersTitle.visibility = View.GONE; msgsTitle.visibility = View.GONE
            return
        }
        searchJob = ui.launch {
            delay(400)
            try {
                val j = withContext(Dispatchers.IO) { Api.searchGlobal(q) }
                users.clear()
                messages.clear()
                val ua = j.optJSONArray("users")
                if (ua != null) for (i in 0 until ua.length()) users.add(User(ua.getJSONObject(i)))
                val ma = j.optJSONArray("messages")
                if (ma != null) for (i in 0 until ma.length()) messages.add(Message(ma.getJSONObject(i)))
                usersAdapter.notifyDataSetChanged()
                msgsAdapter.notifyDataSetChanged()
                usersTitle.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
                msgsTitle.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
                usersTitle.text = "Users (${users.size})"
                msgsTitle.text = "Messages (${messages.size})"
            } catch (_: Exception) {}
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class MsgSearchAdapter(
    private val items: List<Message>,
    private val onClick: (Message) -> Unit
) : RecyclerView.Adapter<MsgSearchAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.msg_search_title)
        val text: TextView = v.findViewById(R.id.msg_search_text)
        val time: TextView = v.findViewById(R.id.msg_search_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_message_search, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.title.text = m.senderName.ifEmpty { "Message" }
        h.text.text = m.text.ifEmpty { "Media message" }
        h.time.text = Helpers.dateShort(m.ts)
        h.itemView.setOnClickListener { onClick(m) }
    }
}
