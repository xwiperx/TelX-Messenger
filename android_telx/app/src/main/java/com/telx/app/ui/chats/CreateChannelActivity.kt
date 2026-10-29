package com.telx.app.ui.chats

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.net.Api
import com.telx.app.ui.chat.ChatActivity
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telegram-style two-step "New Channel" flow. */
class CreateChannelActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var checkJob: Job? = null
    private var lastAvailable: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_channel)
        setSupportActionBar(findViewById<Toolbar>(R.id.channel_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "New Channel"

        val title = findViewById<TextInputEditText>(R.id.et_new_channel_title)
        val desc = findViewById<TextInputEditText>(R.id.et_new_channel_desc)
        val username = findViewById<TextInputEditText>(R.id.et_new_channel_username)
        val hint = findViewById<android.widget.TextView>(R.id.tv_username_hint)
        val btn = findViewById<MaterialButton>(R.id.btn_create_channel)

        username.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                checkJob?.cancel()
                val u = s?.toString()?.trim()?.removePrefix("@") ?: ""
                lastAvailable = null
                if (u.isEmpty()) { hint.text = ""; return@afterTextChanged }
                checkJob = ui.launch {
                    delay(450)
                    try {
                        // exact-username probe via public search (unique index server-side is the final guard)
                        val j = withContext(Dispatchers.IO) { Api.searchPublic(u) }
                        val taken = (0 until j.length()).any { i ->
                            j.getJSONObject(i).optString("username", "").equals(u, ignoreCase = true) }
                        lastAvailable = !taken
                        hint.text = if (taken) "✗ This link is already taken" else "✓ telx.ch/$u is available"
                        hint.setTextColor(if (taken) 0xFFE53935.toInt() else 0xFF31B645.toInt())
                    } catch (_: Exception) {}
                }
            }
            override fun beforeTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(p0: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btn.setOnClickListener {
            val t = title.text?.toString()?.trim() ?: ""
            if (t.isEmpty()) { Helpers.toast(this, "Enter a channel name"); return@setOnClickListener }
            val u = username.text?.toString()?.trim()?.removePrefix("@") ?: ""
            val d = desc.text?.toString()?.trim() ?: ""
            btn.isEnabled = false
            ui.launch {
                try {
                    val j = withContext(Dispatchers.IO) { Api.createChannel(t, d, u, emptyList()) }
                    val cid = j.getInt("chat_id")
                    // invite hint: user can share the public link
                    startActivity(Intent(this@CreateChannelActivity, ChatActivity::class.java)
                        .putExtra("chat_id", cid))
                    finish()
                } catch (e: Exception) {
                    Helpers.toast(this@CreateChannelActivity, if (e is ApiErr) e.message else "Couldn't create channel")
                    btn.isEnabled = true
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
