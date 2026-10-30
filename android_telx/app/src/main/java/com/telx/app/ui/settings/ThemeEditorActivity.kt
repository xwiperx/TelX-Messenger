package com.telx.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.ui.common.Helpers
import org.json.JSONObject

/**
 * v6: theme editor — pick an accent color, bubble color and background
 * manually (preset swatches + hex input), save as the active custom theme,
 * and export/import themes as JSON files (Telegram .attheme-style).
 */
class ThemeEditorActivity : AppCompatActivity() {

    private val presets = listOf(
        "Telegram Blue" to 0xFF3390EC.toInt(),
        "Teal" to 0xFF00897B.toInt(),
        "Purple" to 0xFF8E44AD.toInt(),
        "Orange" to 0xFFF2743B.toInt(),
        "Green" to 0xFF2ECC71.toInt(),
        "Pink" to 0xFFE84393.toInt(),
        "Red" to 0xFFE74C3C.toInt(),
        "Navy" to 0xFF29434E.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_editor)
        val toolbar = findViewById<Toolbar>(R.id.theme_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val swatches = findViewById<LinearLayout>(R.id.theme_swatches)
        val preview = findViewById<View>(R.id.theme_preview_bubble)
        val hexInput = findViewById<EditText>(R.id.theme_hex)
        val bubblePreview = findViewById<TextView>(R.id.theme_preview_text)

        fun applyToPreview(color: Int) {
            preview.background?.setTint(color)
            bubblePreview.setTextColor(Color.WHITE)
        }
        var chosen = loadTheme().optString("accent", "#3390EC")
        try { applyToPreview(Color.parseColor(chosen)) } catch (_: Exception) {}
        findViewById<EditText>(R.id.theme_hex).setText(chosen)

        presets.forEach { (_, color) ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(Helpers.dp(this, 44), Helpers.dp(this, 44))
            lp.marginEnd = Helpers.dp(this, 12)
            v.layoutParams = lp
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            v.setOnClickListener {
                chosen = "#%06X".format(0xFFFFFF and color)
                try { applyToPreview(color) } catch (_: Exception) {}
                hexInput.setText(chosen)
            }
            swatches.addView(v)
        }
        findViewById<TextView>(R.id.theme_apply).setOnClickListener {
            var hex = hexInput.text?.toString()?.trim() ?: ""
            if (hex.isNotEmpty() && !hex.startsWith("#")) hex = "#$hex"
            try { Color.parseColor(hex) } catch (e: Exception) {
                Helpers.toast(this, "Invalid color (use #RRGGBB)"); return@setOnClickListener
            }
            val t = JSONObject().put("accent", hex)
            Prefs.customTheme = t.toString()
            Prefs.customAccent = hex
            Helpers.toast(this, "Theme applied — restart TelX to see it everywhere")
        }
        findViewById<TextView>(R.id.theme_reset).setOnClickListener {
            Prefs.customTheme = ""
            Prefs.customAccent = ""
            Helpers.toast(this, "Back to the default Telegram blue")
        }
        findViewById<TextView>(R.id.theme_export).setOnClickListener { exportTheme() }
        findViewById<TextView>(R.id.theme_import).setOnClickListener { importTheme() }
    }

    private fun loadTheme(): JSONObject = try {
        if (Prefs.customTheme.isNotEmpty()) JSONObject(Prefs.customTheme) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun exportTheme() {
        try {
            val json = loadTheme().put("accent", findViewById<EditText>(R.id.theme_hex).text?.toString() ?: "#3390EC")
            val f = java.io.File(cacheDir, "telx_theme_${System.currentTimeMillis()}.json")
            f.writeText(json.toString(2))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Export theme"))
        } catch (e: Exception) { Helpers.toast(this, "Export failed") }
    }

    private val importResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val j = JSONObject(text)
            if (!j.optString("accent").startsWith("#")) throw Exception()
            Prefs.customTheme = j.toString()
            Prefs.customAccent = j.optString("accent")
            findViewById<EditText>(R.id.theme_hex).setText(j.optString("accent"))
            Helpers.toast(this, "Theme imported — applied")
        } catch (e: Exception) { Helpers.toast(this, "Not a valid TelX theme file") }
    }

    private fun importTheme() { importResult.launch(arrayOf("application/json", "text/*", "*/*")) }
}
