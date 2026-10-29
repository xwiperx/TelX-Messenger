package com.telx.app.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.User
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pick a contact and start an end-to-end encrypted secret chat. */
class PickSecretActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val users = ArrayList<User>()
    private lateinit var adapter: ContactsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)
        setSupportActionBar(findViewById<Toolbar>(R.id.blocked_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "New Secret Chat"

        val recycler = findViewById<RecyclerView>(R.id.blocked_recycler)
        adapter = ContactsAdapter(users)
        adapter.onClick = { u ->
            ui.launch {
                try {
                    val j = withContext(Dispatchers.IO) { Api.call("chats", "open_secret") { put("user_id", u.id) } }
                    startActivity(Intent(this@PickSecretActivity, ChatActivity::class.java)
                        .putExtra("chat_id", j.getInt("chat_id")))
                    finish()
                } catch (e: Exception) {
                    Helpers.toast(this@PickSecretActivity, if (e.message.isNullOrEmpty()) "Failed" else e.message)
                }
            }
        }
        adapter.onLongClick = adapter.onClick
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<TextView>(R.id.blocked_empty).text = "No contacts yet.\nAdd people from Search first."
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.contactsList() }
                users.clear()
                if (arr != null) for (i in 0 until arr.length()) users.add(User(arr.getJSONObject(i)))
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.blocked_empty).visibility =
                    if (users.isEmpty()) View.VISIBLE else View.GONE
            } catch (_: Exception) {}
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
