package com.telx.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telx.app.R
import com.telx.app.data.Session
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows all active devices/sessions with ability to terminate them. */
class SessionsActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private val sessions = ArrayList<Session>()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        setSupportActionBar(findViewById<Toolbar>(R.id.sessions_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Active Sessions"
        val recycler = findViewById<RecyclerView>(R.id.sessions_recycler)
        adapter = Adapter(sessions) { s ->
            if (s.current) return@Adapter
            android.app.AlertDialog.Builder(this)
                .setTitle("Terminate session")
                .setMessage("Log out ${s.device} (${s.ip})?")
                .setPositiveButton("Terminate") { _, _ ->
                    ui.launch {
                        try {
                            withContext(Dispatchers.IO) { Api.revokeSession(s.id) }
                            load()
                        } catch (e: Exception) { Helpers.toast(this, "Failed") }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        load()
    }

    private fun load() {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.sessions() }
                sessions.clear()
                if (arr != null) for (i in 0 until arr.length()) sessions.add(Session(arr.getJSONObject(i)))
                adapter.notifyDataSetChanged()
            } catch (e: Exception) { Helpers.toast(this@SessionsActivity, "Couldn't load sessions") }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class Adapter(
        private val items: List<Session>,
        private val onTerminate: (Session) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.session_icon)
            val device: TextView = v.findViewById(R.id.session_device)
            val meta: TextView = v.findViewById(R.id.session_meta)
            val current: TextView = v.findViewById(R.id.session_current)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            val df = SimpleDateFormat("dd MMM, HH:mm", Locale.US)
            h.device.text = s.device
            h.meta.text = "IP ${s.ip} · active ${df.format(Date(s.lastTs * 1000))}"
            h.current.text = if (s.current) "This device" else "Terminate"
            h.current.setTextColor(if (s.current) 0xFF31B645.toInt() else 0xFFE53935.toInt())
            h.current.setOnClickListener { onTerminate(s) }
        }
    }
}
