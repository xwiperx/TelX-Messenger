package com.telx.app.ui.common

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.data.StickerPack
import com.telx.app.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ---------------- Sticker picker (Telegram-style bottom sheet) ---------------- */

object StickerPickerSheet {

    fun show(activity: Activity, onPick: (url: String) -> Unit) {
        val ui = CoroutineScope(Dispatchers.Main)
        ui.launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.stickersList() }
                val builtin = ArrayList<String>()
                val bj = j.optJSONArray("builtin")
                if (bj != null) for (i in 0 until bj.length()) builtin.add(bj.optString(i))
                val packs = ArrayList<StickerPack>()
                val pj = j.optJSONArray("packs")
                if (pj != null) for (i in 0 until pj.length()) packs.add(StickerPack(pj.getJSONObject(i)))
                withContext(Dispatchers.Main) { render(activity, builtin, packs, onPick) }
            } catch (e: Exception) {
                Helpers.toast(activity, "Couldn't load stickers")
            }
        }
    }

    private fun render(activity: Activity, builtin: List<String>, packs: List<StickerPack>, onPick: (String) -> Unit) {
        val d = BottomSheetDialog(activity)
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val pad = Helpers.dp(activity, 12)
        root.setPadding(pad, pad, pad, pad)

        // tabs: builtin + one tab per own pack + "+" manage
        val tabs = LinearLayout(activity)
        tabs.orientation = LinearLayout.HORIZONTAL
        val pages = ArrayList<List<String>>()
        val titles = ArrayList<String>()
        if (builtin.isNotEmpty()) { pages.add(builtin); titles.add("TelX") }
        packs.forEach { titles.add(it.title); pages.add(it.stickers) }

        val grid = RecyclerView(activity)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Helpers.dp(activity, 260))
        grid.layoutParams = lp
        root.addView(tabs)
        root.addView(grid)

        fun showPage(idx: Int) {
            grid.layoutManager = GridLayoutManager(activity, 4)
            val items = pages.getOrNull(idx) ?: emptyList()
            grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_sticker_cell, parent, false)) {}
                override fun getItemCount() = items.size + 1
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val iv = h.itemView.findViewById<ImageView>(R.id.sticker_cell_img)
                    if (pos >= items.size) {
                        iv.setImageResource(R.drawable.ic_add)
                        iv.setOnClickListener { d.dismiss(); managePacks(activity) }
                        return
                    }
                    Glide.with(iv).load(items[pos]).into(iv)
                    iv.setOnClickListener { d.dismiss(); onPick(items[pos]) }
                    iv.setOnLongClickListener {
                        val pack = packs.getOrNull(idx - 1) ?: return@setOnLongClickListener true
                        val url = items[pos]
                        AlertDialog.Builder(activity)
                            .setMessage("Delete this sticker?")
                            .setPositiveButton("Delete") { _, _ ->
                                CoroutineScope(Dispatchers.Main).launch {
                                    try { withContext(Dispatchers.IO) { Api.stickerDelete(pack.id, url) } }
                                    catch (_: Exception) {}
                                }
                            }.setNegativeButton("Cancel", null).show()
                        true
                    }
                }
            }
            // highlight tabs
            tabs.removeAllViews()
            titles.forEachIndexed { i, t ->
                val tv = TextView(activity)
                tv.text = t
                tv.textSize = 13f
                tv.setPadding(Helpers.dp(activity, 10), Helpers.dp(activity, 8), Helpers.dp(activity, 10), Helpers.dp(activity, 8))
                tv.setTextColor(if (i == idx) Color.parseColor("#3390EC") else 0xFF8A8A8E.toInt())
                tv.setOnClickListener { showPage(i) }
                tabs.addView(tv)
            }
        }
        if (pages.isNotEmpty()) showPage(0)
        d.setContentView(root)
        d.show()
    }

    private fun managePacks(activity: Activity) {
        val options = arrayOf("Create new pack", "Add sticker from image")
        AlertDialog.Builder(activity)
            .setTitle("My sticker packs")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val input = EditText(activity)
                    input.hint = "Pack title"
                    AlertDialog.Builder(activity)
                        .setTitle("New sticker pack")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val t = input.text?.toString()?.trim() ?: ""
                            if (t.isEmpty()) return@setPositiveButton
                            CoroutineScope(Dispatchers.Main).launch {
                                try { withContext(Dispatchers.IO) { Api.stickerPackCreate(t) } }
                                catch (e: Exception) { Helpers.toast(activity, "Failed") }
                            }
                        }.setNegativeButton("Cancel", null).show()
                } else {
                    addStickerFromFile(activity)
                }
            }.setNegativeButton("Close", null).show()
    }

    private val pickStickerImg = HashMap<Int, android.net.Uri>()

    fun addStickerFromFile(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val j = withContext(Dispatchers.IO) { Api.stickersList() }
                val packs = ArrayList<StickerPack>()
                val pj = j.optJSONArray("packs")
                if (pj != null) for (i in 0 until pj.length()) packs.add(StickerPack(pj.getJSONObject(i)))
                if (packs.isEmpty()) { Helpers.toast(activity, "Create a pack first"); return@launch }
                val names = packs.map { it.title }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Add sticker to pack")
                    .setItems(names) { _, which ->
                        val pack = packs[which]
                        (activity as? com.telx.app.ui.chat.ChatActivity)?.let {
                            it.launchStickerPick(pack.id)
                        } ?: Helpers.toast(activity, "Open a chat to add stickers")
                    }.setNegativeButton("Cancel", null).show()
            } catch (_: Exception) { Helpers.toast(activity, "Failed") }
        }
    }
}

/* ---------------- Emoji picker sheet ---------------- */

object EmojiSheet {
    private val EMOJIS = listOf(
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩",
        "😘","😗","😚","😙","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","😐",
        "😑","😶","😏","😒","🙄","😬","😮‍💨","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕",
        "🥳","🥺","😭","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓","🤗","🤔",
        "👎","👌","🤞","✌️","🤟","🤘","👏","🙌","🙏","💪","🫶","👍","❤️","🧡","💛","💚",
        "💙","💜","🖤","🤍","💔","❣️","💕","💞","💓","💗","💖","💘","💝","🔥","⭐","🌟",
        "✨","⚡","☄️","💥","🎉","🎊","🎁","🏆","⚽","🎮","🎵","🎶","📞","📱","💬","💭")

    fun show(activity: Activity, input: EditText) {
        val d = BottomSheetDialog(activity)
        val grid = RecyclerView(activity)
        val pad = Helpers.dp(activity, 10)
        grid.setPadding(pad, pad, pad, pad)
        grid.layoutManager = GridLayoutManager(activity, 8)
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_sticker_cell, parent, false)) {}
            override fun getItemCount() = EMOJIS.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val tv = h.itemView.findViewById<TextView>(R.id.sticker_cell_text)
                val iv = h.itemView.findViewById<ImageView>(R.id.sticker_cell_img)
                iv.visibility = View.GONE
                tv.visibility = View.VISIBLE
                tv.text = EMOJIS[pos]
                tv.textSize = 26f
                tv.gravity = Gravity.CENTER
                h.itemView.setOnClickListener {
                    input.text.insert(input.selectionStart, EMOJIS[pos])
                }
            }
        }
        d.setContentView(grid)
        d.show()
    }
}

/* ---------------- QR codes (zxing) ---------------- */

object QrHelper {
    /** encode content into a QR bitmap (square) */
    fun encode(content: String, size: Int = 600): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun showDialog(activity: Activity, title: String, content: String) {
        val iv = ImageView(activity)
        val pad = Helpers.dp(activity, 24)
        iv.setPadding(pad, pad, pad, pad)
        iv.setImageBitmap(encode(content))
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(iv)
            .setPositiveButton("Close", null)
            .show()
    }

    /** decode a QR from a bitmap (e.g. picked image) — returns the text or null */
    fun decode(bmp: Bitmap): String? = try {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val reader = MultiFormatReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)).let {
            reader.setHints(hints); it })
        reader.reset()
        result.text
    } catch (e: Exception) { null }
}

/* ---------------- Multi-account manager ---------------- */

object AccountManager {

    private fun registry(): JSONArray = try { JSONArray(Prefs.accounts) } catch (_: Exception) { JSONArray() }

    /** store current session in the accounts registry */
    fun saveCurrent() {
        if (Prefs.token.isEmpty()) return
        val arr = registry()
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id") == Prefs.myId) {
                found = true
                o.put("token", Prefs.token)
                o.put("name", Prefs.myName)
                o.put("username", Prefs.myUsername)
                o.put("avatar", Prefs.myAvatar ?: "")
                o.put("active", true)
            } else o.put("active", false)
        }
        if (!found) arr.put(JSONObject()
            .put("id", Prefs.myId).put("token", Prefs.token)
            .put("name", Prefs.myName).put("username", Prefs.myUsername)
            .put("avatar", Prefs.myAvatar ?: "").put("active", true))
        Prefs.accounts = arr.toString()
    }

    /** add another account (after logging in on top of an existing session) */
    fun switch(accountId: Int, keepToken: String, context: Context) {
        val arr = registry()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id") != accountId) continue
            // keep current in registry
            saveCurrent()
            Prefs.token = o.optString("token")
            Prefs.myId = o.optInt("id")
            Prefs.myName = o.optString("name")
            Prefs.myUsername = o.optString("username")
            Prefs.myAvatar = o.optString("avatar").ifEmpty { null }
            Prefs.sinceId = 0
            for (k in 0 until arr.length()) arr.optJSONObject(k)?.put("active", arr.optJSONObject(k)?.optInt("id") == accountId)
            Prefs.accounts = arr.toString()
            com.telx.app.net.Events.start()
            com.telx.app.svc.PollService.start(context)
            // restart the app cleanly
            val i2 = android.content.Intent(context, com.telx.app.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(i2)
            return
        }
    }

    fun list(): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val arr = registry()
        for (i in 0 until arr.length()) out.add(arr.optJSONObject(i) ?: continue)
        return out
    }
}

/* ---------------- Add-contact dialog (username OR phone) ---------------- */

object AddContactDialog {
    fun show(activity: Activity) {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_add_contact, null)
        val etUser = v.findViewById<EditText>(R.id.et_contact_username)
        val etPhone = v.findViewById<EditText>(R.id.et_contact_phone)
        AlertDialog.Builder(activity)
            .setTitle("Add Contact")
            .setView(v)
            .setPositiveButton("Add") { _, _ ->
                val u = etUser.text?.toString()?.trim() ?: ""
                val p = etPhone.text?.toString()?.trim() ?: ""
                if (u.isEmpty() && p.isEmpty()) { Helpers.toast(activity, "Enter a username or phone"); return@setPositiveButton }
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val r = withContext(Dispatchers.IO) {
                            if (u.isNotEmpty()) Api.contactAddByUsername(u.removePrefix("@"))
                            else Api.contactAddByPhone(p)
                        }
                        val name = r.optJSONObject("user")?.optString("name") ?: "Contact"
                        Helpers.toast(activity, "Added $name")
                    } catch (e: Exception) {
                        Helpers.toast(activity, if (e is com.telx.app.data.ApiErr) e.message else "Failed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
