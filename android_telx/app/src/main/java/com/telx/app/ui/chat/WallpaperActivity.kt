package com.telx.app.ui.chat

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.ui.common.CropActivity
import com.telx.app.ui.common.Helpers

/**
 * Telegram-style wallpaper picker: live preview, gallery photo (zoom & fit via
 * CropActivity), blur toggle, dim slider, solid colors and pattern tiles.
 */
class WallpaperActivity : AppCompatActivity() {

    private lateinit var preview: ViewGroup

    private val pickWallpaper = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        Prefs.wallpaper = WallpaperHelper.importCustom(this, path)
        WallpaperHelper.refreshBlur(this)
        applyPreview()
        WallpaperHelper.version++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper)
        setSupportActionBar(findViewById<Toolbar>(R.id.wallpaper_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Chat Wallpaper"

        preview = findViewById(R.id.wallpaper_preview)

        findViewById<View>(R.id.row_gallery).setOnClickListener {
            startActivity(Intent(this, CropActivity::class.java).putExtra("mode", "rect"))
        }

        val blurSwitch = findViewById<Switch>(R.id.sw_blur)
        blurSwitch.isChecked = Prefs.wallpaperBlur
        blurSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.wallpaperBlur = checked
            Thread { WallpaperHelper.refreshBlur(this) }.start()
            applyPreview()
            WallpaperHelper.version++
        }

        val dimSeek = findViewById<SeekBar>(R.id.seek_dim)
        dimSeek.progress = Prefs.wallpaperDim
        dimSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                Prefs.wallpaperDim = value
                WallpaperHelper.applyDim(preview, value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { WallpaperHelper.version++ }
        })

        // solid color presets (Telegram palette)
        val colorsRow = findViewById<android.widget.LinearLayout>(R.id.colors_row)
        val colors = listOf("#D6E3EE", "#C7DDEB", "#E5DCE5", "#DCE8DC", "#F0E6D8",
            "#E8E8E8", "#2A3942", "#1F2C36")
        for (c in colors) {
            val v = View(this)
            val size = Helpers.dp(this, 36)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = Helpers.dp(this, 14)
            v.layoutParams = lp
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(c))
                setStroke(Helpers.dp(this@WallpaperActivity, 1), 0x33000000)
            }
            v.background = gd
            v.setOnClickListener {
                Prefs.wallpaper = "color:$c"
                applyPreview()
                WallpaperHelper.version++
            }
            colorsRow.addView(v)
        }

        // pattern tiles
        val patternsRow = findViewById<android.widget.LinearLayout>(R.id.patterns_row)
        val patterns = listOf(
            "Default" to R.drawable.chat_bg_default,
            "Dots" to R.drawable.chat_bg_dots,
            "Waves" to R.drawable.chat_bg_waves,
            "Stars" to R.drawable.chat_bg_stars)
        patterns.forEachIndexed { idx, (name, res) ->
            val v = View(this)
            val size = Helpers.dp(this, 84)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = Helpers.dp(this, 12)
            v.layoutParams = lp
            v.setBackgroundResource(res)
            v.setOnClickListener {
                Prefs.wallpaper = "pattern:$idx"
                applyPreview()
                WallpaperHelper.version++
            }
            patternsRow.addView(v)
        }

        findViewById<TextView>(R.id.btn_reset_wallpaper).setOnClickListener {
            Prefs.wallpaper = null
            applyPreview()
            WallpaperHelper.version++
        }

        applyPreview()
    }

    private fun applyPreview() {
        WallpaperHelper.apply(preview)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
