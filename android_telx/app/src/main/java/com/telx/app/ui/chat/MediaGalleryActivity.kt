package com.telx.app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.telx.app.R
import com.telx.app.data.Message
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telegram-style per-chat media gallery: photos / videos / files / voice tabs. */
class MediaGalleryActivity : AppCompatActivity() {

    private val ui = CoroutineScope(Dispatchers.Main)
    private var chatId = 0
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val items = ArrayList<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        setSupportActionBar(findViewById<Toolbar>(R.id.gallery_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Shared Media"
        chatId = intent.getIntExtra("chat_id", 0)
        recycler = findViewById(R.id.gallery_recycler)
        empty = findViewById(R.id.gallery_empty)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_media_grid, parent, false)) {}
            override fun getItemCount() = items.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val iv = h.itemView.findViewById<ImageView>(R.id.media_cell_img)
                val tv = h.itemView.findViewById<TextView>(R.id.media_cell_label)
                val m = items[pos]
                tv.text = when (m.type) {
                    "file" -> m.mediaName.ifEmpty { "File" }
                    "voice" -> "🎤 " + Helpers.duration(m.mediaDuration)
                    else -> ""
                }
                when (m.type) {
                    "photo", "gif", "video", "video_note" ->
                        Glide.with(iv).load(m.thumb ?: m.media).centerCrop().into(iv)
                    else -> { iv.setImageDrawable(null); iv.setBackgroundColor(0xFF3390EC.toInt()) }
                }
                h.itemView.setOnClickListener { openViewer(m) }
            }
        }
        load("media")
    }

    private fun openViewer(m: Message) {
        when (m.type) {
            "photo", "gif", "video", "video_note" -> {
                val d = BottomSheetDialog(this)
                val iv = ImageView(this)
                iv.adjustViewBounds = true
                Glide.with(iv).load(m.media ?: m.thumb).into(iv)
                iv.setOnClickListener { d.dismiss() }
                d.setContentView(iv)
                d.show()
            }
            "file", "voice" -> {
                m.media?.let {
                    try {
                        if (com.telx.app.media.FileDownloader.progress[m.id] == 100)
                            com.telx.app.media.FileDownloader.open(this, m,
                                com.telx.app.media.FileDownloader.fileFor(this, m))
                        else {
                            com.telx.app.media.FileDownloader.tap(this, m)
                            Helpers.toast(this, "Downloading…")
                        }
                    } catch (e: Exception) { Helpers.toast(this, "No app can open this") }
                }
            }
        }
    }

    private fun load(kind: String) {
        ui.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Api.mediaList(chatId, kind) }
                items.clear()
                items.addAll(Message.parseArray(arr))
                recycler.adapter?.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                supportActionBar?.title = "Shared Media (${items.size})"
            } catch (e: Exception) { Helpers.toast(this@MediaGalleryActivity, "Couldn't load media") }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
