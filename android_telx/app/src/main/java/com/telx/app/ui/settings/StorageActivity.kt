package com.telx.app.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.telx.app.R
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v6: storage management — shows app storage usage by area (media cache,
 * voice files, downloads, images cache) with one-tap cleanup per area.
 */
class StorageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)
        val toolbar = findViewById<Toolbar>(R.id.storage_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.storage_rows)
        refresh(list)
    }

    private fun refresh(list: LinearLayout) {
        CoroutineScope(Dispatchers.Main).launch {
            val rows = withContext(Dispatchers.IO) { compute() }
            list.removeAllViews()
            for (r in rows) list.addView(rowView(r, list))
        }
    }

    data class Area(val label: String, val dir: File?, val icon: Int)

    private fun compute(): List<Pair<Area, Long>> {
        val cacheDir = cacheDir
        return listOf(
            Area("Media & photos cache", File(cacheDir, "image_manager_disk_cache"), R.drawable.ic_image),
            Area("Voice & video cache", cacheDir, R.drawable.ic_mic),
            Area("Downloads", File(getExternalFilesDir(null), "downloads"), R.drawable.ic_download),
            Area("Wallpapers & temp", File(cacheDir, "wallpapers"), R.drawable.ic_wallpaper)
        ).map { a ->
            val size = if (a.dir?.exists() == true) dirSize(a.dir) else 0L
            a to size
        }
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun rowView(pair: Pair<Area, Long>, list: LinearLayout): View {
        val (area, size) = pair
        val pad = Helpers.dp(this, 16)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val icon = ImageView(this).apply {
            setImageResource(area.icon)
            setColorFilter(0xFF3390EC.toInt())
            layoutParams = LinearLayout.LayoutParams(Helpers.dp(this, 26), Helpers.dp(this, 26))
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad } }
        val label = TextView(this).apply { text = area.label; textSize = 15f; setTextColor(0xFF000000.toInt()) }
        val sizeTv = TextView(this).apply {
            text = if (size > 0) Helpers.fileSize(size) else "Empty"
            textSize = 12f; setTextColor(0xFF8A8A8E.toInt())
        }
        mid.addView(label); mid.addView(sizeTv)
        val btn = TextView(this).apply {
            text = "Clear"; textSize = 13f; setTextColor(if (size > 0) 0xFF3390EC.toInt() else 0xFF8A8A8E.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        area.dir?.walkTopDown()?.filter { it.isFile }?.forEach { it.delete() }
                        try { com.bumptech.glide.Glide.get(applicationContext).clearDiskCache() } catch (_: Exception) {}
                    }
                    refresh(list)
                    Helpers.toast(this@StorageActivity, "Cleared ${Helpers.fileSize(size)}")
                }
            }
            isEnabled = size > 0
        }
        row.addView(icon); row.addView(mid); row.addView(btn)
        return row
    }
}
