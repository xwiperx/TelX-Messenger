package com.telx.app.ui.common

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.telx.app.R

/**
 * Telegram-style photo crop: user picks an image, then zooms/pans to choose
 * which part is used. mode = "circle" (avatars) or "rect" (wallpaper).
 * Result: intent extra "path" with the cropped JPEG in cacheDir.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        cropView.load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        cropView = findViewById(R.id.crop_view)
        val mode = intent.getStringExtra("mode") ?: "circle"
        cropView.circle = mode != "rect"
        findViewById<TextView>(R.id.crop_title).text =
            if (cropView.circle) "Move and scale for profile photo" else "Move and scale for wallpaper"
        findViewById<ImageView>(R.id.btn_crop_cancel).setOnClickListener { finish() }
        val done = findViewById<FloatingActionButton>(R.id.btn_crop_done)
        done.setOnClickListener {
            done.isEnabled = false
            cropView.onOutput = { path ->
                setResult(RESULT_OK, Intent().putExtra("path", path))
                finish()
            }
            cropView.output()
        }
        pickImage.launch(arrayOf("image/*"))
    }
}
