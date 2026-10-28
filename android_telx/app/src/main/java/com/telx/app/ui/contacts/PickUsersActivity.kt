package com.telx.app.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.telx.app.R
import com.telx.app.data.User
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Multi-select contact picker — creates a new group from selection. */
class PickUsersActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val users = ArrayList<User>()
    private val selected = HashSet<Int>()
    private lateinit var adapter: PickAdapter
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pick_users)
        setSupportActionBar(findViewById<Toolbar>(R.id.pick_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "New Group"
        counter = findViewById(R.id.pick_counter)
        val recycler = findViewById<RecyclerView>(R.id.pick_recycler)
        adapter = PickAdapter(users) { u, checked ->
            if (checked) selected.add(u.id) else selected.remove(u.id)
            counter.text = "${selected.size} selected"
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btn_create).setOnClickListener {
            if (selected.isEmpty()) { Helpers.toast(this, "Select at least one member"); return@setOnClickListener }
            val v = layoutInflater.inflate(R.layout.dialog_group_title, null)
            val input = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_group_title)
            AlertDialog.Builder(this)
                .setTitle("Group name")
                .setView(v)
                .setPositiveButton("Create") { _, _ ->
                    val title = input.text?.toString()?.trim() ?: ""
                    if (title.isEmpty()) { Helpers.toast(this, "Enter a name"); return@setPositiveButton }
                    ui.launch {
                        try {
                            val j = withContext(Dispatchers.IO) { Api.createGroup(title, selected.toList()) }
                            startActivity(Intent(this@PickUsersActivity, ChatActivity::class.java)
                                .putExtra("chat_id", j.getInt("chat_id")))
                            finish()
                        } catch (e: Exception) { Helpers.toast(this@PickUsersActivity, e.message ?: "Failed") }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.contactsList() }
                users.clear()
                if (arr != null) for (i in 0 until arr.length()) users.add(User(arr.getJSONObject(i)))
                adapter.notifyDataSetChanged()
                if (users.isEmpty()) Helpers.toast(this@PickUsersActivity, "Add contacts first to create a group")
            } catch (_: Exception) {}
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class PickAdapter(
    private val items: List<User>,
    private val onToggle: (User, Boolean) -> Unit
) : RecyclerView.Adapter<PickAdapter.VH>() {

    private val checked = HashSet<Int>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: AvatarView = v.findViewById(R.id.contact_avatar)
        val name: TextView = v.findViewById(R.id.contact_name)
        val status: TextView = v.findViewById(R.id.contact_status)
        val check: android.widget.CheckBox = v.findViewById(R.id.pick_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pick_user, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = items[position]
        h.avatar.bind(u.name, u.avatar, Helpers.colorFor(u.id))
        h.name.text = u.name
        h.status.text = "@${u.username}"
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = checked.contains(u.id)
        h.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(u.id) else checked.remove(u.id)
            onToggle(u, isChecked)
        }
        h.itemView.setOnClickListener { h.check.toggle() }
    }
}

/** Simple blocked users manager. */
class BlockedUsersActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val users = ArrayList<User>()
    private lateinit var adapter: ContactsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)
        setSupportActionBar(findViewById<Toolbar>(R.id.blocked_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Blocked Users"
        val recycler = findViewById<RecyclerView>(R.id.blocked_recycler)
        adapter = ContactsAdapter(users)
        adapter.onClick = {
            AlertDialog.Builder(this)
                .setTitle(it.name)
                .setMessage("Unblock this user?")
                .setPositiveButton("Unblock") { _, _ ->
                    ui.launch { try { withContext(Dispatchers.IO) { Api.unblock(it.id) }; load() } catch (_: Exception) {} }
                }
                .setNegativeButton("Cancel", null).show()
        }
        adapter.onLongClick = adapter.onClick
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<TextView>(R.id.blocked_empty).visibility = View.GONE
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.blockedList() }
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
