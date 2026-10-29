package com.telx.app.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.telx.app.R
import com.telx.app.data.Event
import com.telx.app.data.User
import com.telx.app.net.Api
import com.telx.app.net.Events
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import com.telx.app.ui.profile.UserProfileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment(), Events.Listener {

    private val ui = CoroutineScope(Dispatchers.Main)
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val contacts = ArrayList<User>()
    private lateinit var adapter: ContactsAdapter

    /** pick an image containing a TelX profile QR → decode → add contact */
    private val pickQr = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val picked = uri
        ui.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeStream(
                        requireContext().contentResolver.openInputStream(picked))
                }
                val text = bmp?.let { com.telx.app.ui.common.QrHelper.decode(it) }
                val uname = text?.takeIf { it.startsWith("telx:@") }?.removePrefix("telx:@")
                if (uname.isNullOrEmpty()) {
                    Helpers.toast(context, "No TelX QR code found in this image")
                } else {
                    withContext(Dispatchers.IO) { Api.contactAddByUsername(uname) }
                    Helpers.toast(context, "Contact added")
                    load()
                }
            } catch (e: Exception) { Helpers.toast(context, e.message ?: "Failed") }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_contacts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.contacts_recycler)
        empty = view.findViewById(R.id.contacts_empty)
        adapter = ContactsAdapter(contacts)
        adapter.onClick = { startActivity(Intent(requireContext(), UserProfileActivity::class.java).putExtra("user_id", it.id)) }
        adapter.onLongClick = { showActions(it) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener { showAddDialog() }
        view.findViewById<View>(R.id.row_new_group).setOnClickListener {
            startActivity(Intent(requireContext(), PickUsersActivity::class.java))
        }
        view.findViewById<View>(R.id.row_blocked).setOnClickListener {
            startActivity(Intent(requireContext(), BlockedUsersActivity::class.java))
        }
        load()
    }

    fun load() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.contactsList() }
                contacts.clear()
                if (arr != null) for (i in 0 until arr.length()) contacts.add(User(arr.getJSONObject(i)))
                adapter.notifyDataSetChanged()
                empty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) { Helpers.toast(context, "Couldn't load contacts") }
        }
    }

    private fun showAddDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etUser = v.findViewById<TextInputEditText>(R.id.et_contact_username)
        val etPhone = v.findViewById<TextInputEditText>(R.id.et_contact_phone)
        v.findViewById<View>(R.id.btn_scan_qr).setOnClickListener { pickQr.launch(arrayOf("image/*")) }
        AlertDialog.Builder(requireContext())
            .setTitle("Add contact")
            .setView(v)
            .setPositiveButton("Add") { _, _ ->
                val u = etUser.text?.toString()?.trim()?.removePrefix("@") ?: ""
                val p = etPhone.text?.toString()?.trim() ?: ""
                if (u.isEmpty() && p.isEmpty()) { Helpers.toast(context, "Enter a username or phone"); return@setPositiveButton }
                ui.launch {
                    try {
                        if (u.isNotEmpty()) withContext(Dispatchers.IO) { Api.contactAddByUsername(u) }
                        else withContext(Dispatchers.IO) { Api.contactAddByPhone(p) }
                        Helpers.toast(context, "Contact added")
                        load()
                    } catch (e: Exception) { Helpers.toast(context, e.message ?: "Failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showActions(u: User) {
        val items = arrayOf("View profile", "Send message", "Remove from contacts")
        AlertDialog.Builder(requireContext())
            .setTitle(u.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), UserProfileActivity::class.java).putExtra("user_id", u.id))
                    1 -> ui.launch {
                        try {
                            val j = withContext(Dispatchers.IO) { Api.chatOpen(u.id) }
                            startActivity(Intent(requireContext(), com.telx.app.ui.chat.ChatActivity::class.java)
                                .putExtra("chat_id", j.getInt("chat_id")))
                        } catch (e: Exception) { Helpers.toast(context, e.message ?: "Failed") }
                    }
                    2 -> ui.launch {
                        try { withContext(Dispatchers.IO) { Api.contactRemove(u.id) }; load() }
                        catch (e: Exception) { Helpers.toast(context, "Failed") }
                    }
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        Events.addListener(this)
        load()
    }

    override fun onPause() {
        super.onPause()
        Events.removeListener(this)
    }

    override fun onEvent(e: Event) {
        if (e.type == "contact_added" || e.type == "profile_updated") load()
    }
}

class ContactsAdapter(private val items: List<User>) : RecyclerView.Adapter<ContactsAdapter.VH>() {

    var onClick: ((User) -> Unit)? = null
    var onLongClick: ((User) -> Unit)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: AvatarView = v.findViewById(R.id.contact_avatar)
        val name: TextView = v.findViewById(R.id.contact_name)
        val status: TextView = v.findViewById(R.id.contact_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = items[position]
        h.avatar.bindUser(u, showDot = true)
        h.name.text = u.name
        h.status.text = if (u.online) "online" else "last seen recently"
        h.status.setTextColor(if (u.online) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
        h.itemView.setOnClickListener { onClick?.invoke(u) }
        h.itemView.setOnLongClickListener { onLongClick?.invoke(u); true }
    }
}
