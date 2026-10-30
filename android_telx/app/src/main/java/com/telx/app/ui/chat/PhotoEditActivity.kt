package com.telx.app.ui.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.telx.app.R
import com.telx.app.ui.common.Helpers
import java.io.File

/**
 * v6: Telegram-style photo editor — rotate, filters and crop before sending.
 * Returns the edited image via a "path" result extra (same contract as CropActivity).
 */
class PhotoEditActivity : AppCompatActivity() {

    private var srcPath: String? = null
    private var bitmap: Bitmap? = null
    private var rotation = 0
    private var currentFilter = "original"
    private lateinit var image: ImageView

    private val filters = listOf(
        "original" to "Original",
        "gray" to "Mono",
        "sepia" to "Sepia",
        "warm" to "Warm",
        "cool" to "Cool",
        "vivid" to "Vivid",
        "faded" to "Faded",
        "contrast" to "Punch"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_edit)
        image = findViewById(R.id.pe_image)

        srcPath = intent.getStringExtra("path")
        if (srcPath == null) {
            val uri: Uri? = intent.getParcelableExtra("uri")
            if (uri != null) {
                val f = File(cacheDir, "edit_src")
                try { contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } } } catch (_: Exception) {}
                srcPath = if (f.exists() && f.length() > 0) f.absolutePath else null
            }
        }
        val p = srcPath
        if (p == null) { finish(); return }
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        bitmap = BitmapFactory.decodeFile(p, opts) ?: run { finish(); return }
        image.setImageBitmap(bitmap)

        findViewById<ImageView>(R.id.pe_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.pe_crop).setOnClickListener { launchCrop() }
        findViewById<ImageView>(R.id.pe_save).setOnClickListener { saveAndReturn() }

        // action row: rotate left / rotate right
        val actions = findViewById<LinearLayout>(R.id.pe_actions)
        actionButton("Rotate left") { rotate(-90) }.also { actions.addView(it) }
        actionButton("Rotate right") { rotate(90) }.also { actions.addView(it) }

        // filter chips
        val row = findViewById<LinearLayout>(R.id.pe_filters)
        filters.forEach { (key, label) ->
            val t = TextView(this)
            t.text = label
            t.textSize = 14f
            t.setTextColor(if (key == currentFilter) 0xFF3390EC.toInt() else 0xFFFFFFFF.toInt())
            t.setPadding(Helpers.dp(this, 18), Helpers.dp(this, 10), Helpers.dp(this, 18), Helpers.dp(this, 10))
            t.setOnClickListener {
                currentFilter = key
                applyFilter()
                row.removeAllViews()
                filters.forEach { (k, l) -> renderChip(row, k, l) }
            }
            row.addView(t)
        }
    }

    private fun renderChip(row: LinearLayout, key: String, label: String) {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTextColor(if (key == currentFilter) 0xFF3390EC.toInt() else 0xFFFFFFFF.toInt())
        t.setPadding(Helpers.dp(this, 18), Helpers.dp(this, 10), Helpers.dp(this, 18), Helpers.dp(this, 10))
        t.setOnClickListener {
            currentFilter = key
            applyFilter()
            row.removeAllViews()
            filters.forEach { (k, l) -> renderChip(row, k, l) }
        }
        row.addView(t)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTextColor(0xFFFFFFFF.toInt())
        t.gravity = Gravity.CENTER
        t.setPadding(Helpers.dp(this, 18), Helpers.dp(this, 10), Helpers.dp(this, 18), Helpers.dp(this, 10))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun rotate(deg: Int) {
        val b = bitmap ?: return
        val m = Matrix()
        m.postRotate(deg.toFloat())
        bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        applyFilter()
    }

    private fun applyFilter() {
        val b = bitmap ?: return
        image.clearColorFilter()
        when (currentFilter) {
            "gray" -> image.colorFilter = cmFilter(floatArrayOf(
                0.33f, 0.33f, 0.33f, 0f, 0f,
                0.33f, 0.33f, 0.33f, 0f, 0f,
                0.33f, 0.33f, 0.33f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            "sepia" -> image.colorFilter = cmFilter(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            "warm" -> image.colorFilter = cmFilter(floatArrayOf(
                1.1f, 0f, 0f, 0f, 10f,
                0f, 1.05f, 0f, 0f, 0f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            "cool" -> image.colorFilter = cmFilter(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 5f,
                0f, 0f, 1.15f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f))
            "vivid" -> image.colorFilter = cmFilter(floatArrayOf(
                1.25f, 0.1f, 0.05f, 0f, 0f,
                0.05f, 1.25f, 0.1f, 0f, 0f,
                0.1f, 0.05f, 1.25f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            "faded" -> image.colorFilter = cmFilter(floatArrayOf(
                0.9f, 0.12f, 0.1f, 0f, 25f,
                0.12f, 0.88f, 0.1f, 0f, 20f,
                0.1f, 0.12f, 0.85f, 0f, 15f,
                0f, 0f, 0f, 0.95f, 0f))
            "contrast" -> image.colorFilter = cmFilter(floatArrayOf(
                1.35f, 0f, 0f, 0f, -25f,
                0f, 1.35f, 0f, 0f, -25f,
                0f, 0f, 1.35f, 0f, -25f,
                0f, 0f, 0f, 1f, 0f))
        }
        image.setImageBitmap(b)
    }

    private fun cmFilter(arr: FloatArray) = ColorMatrixColorFilter(ColorMatrix(arr))

    private val cropResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        bitmap = BitmapFactory.decodeFile(path) ?: return@registerForActivityResult
        image.setImageBitmap(bitmap)
        applyFilter()
    }

    private fun launchCrop() {
        val tmp = File(cacheDir, "edit_tmp.jpg")
        try { tmp.outputStream().use { bitmap?.compress(Bitmap.CompressFormat.JPEG, 92, it) } } catch (_: Exception) {}
        cropResult.launch(android.content.Intent(this, com.telx.app.ui.common.CropActivity::class.java)
            .putExtra("path", tmp.absolutePath)
            .putExtra("mode", "free"))
    }

    private fun saveAndReturn() {
        findViewById<android.widget.ProgressBar>(R.id.pe_progress).visibility = android.view.View.VISIBLE
        Thread {
            try {
                val out = File(cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                val shown = (image.drawable as? BitmapDrawable)?.bitmap ?: bitmap
                // bake the filter into the saved file
                if (shown != null) {
                    if (currentFilter != "original" && image.colorFilter != null) {
                        val cp = shown.copy(Bitmap.Config.ARGB_8888, true)
                        val c = android.graphics.Canvas(cp)
                        val tmp = android.graphics.Paint().apply { colorFilter = image.colorFilter }
                        c.drawBitmap(cp, 0f, 0f, tmp)
                        cp.compress(Bitmap.CompressFormat.JPEG, 90, out.outputStream())
                    } else {
                        out.outputStream().use { shown.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    }
                    runOnUiThread {
                        findViewById<android.widget.ProgressBar>(R.id.pe_progress).visibility = android.view.View.GONE
                        setResult(Activity.RESULT_OK, android.content.Intent().putExtra("path", out.absolutePath))
                        finish()
                    }
                } else runOnUiThread { finish() }
            } catch (e: Exception) {
                runOnUiThread { finish() }
            }
        }.start()
    }
}
