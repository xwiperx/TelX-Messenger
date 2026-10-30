package com.telx.app.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.telx.app.R
import com.telx.app.data.ChatStats
import com.telx.app.net.Api
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6: group/channel statistics — members, messages, channel views plus
 * 30-day growth / activity bar charts and top posters (Telegram-style).
 */
class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        val chatId = intent.getIntExtra("chat_id", 0)
        val toolbar = findViewById<Toolbar>(R.id.stats_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val root = findViewById<LinearLayout>(R.id.stats_rows)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val s = withContext(Dispatchers.IO) { Api.chatStats(chatId) }
                render(root, s)
            } catch (e: Exception) {
                Helpers.toast(this@StatsActivity, "Only admins can see statistics")
                finish()
            }
        }
    }

    private fun render(root: LinearLayout, s: ChatStats) {
        val pad = Helpers.dp(this, 16)
        fun cardTitle(t: String): TextView = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(0xFF3390EC.toInt()); setPadding(pad, pad, pad, pad / 2)
        }
        fun statLine(label: String, value: String): TextView = TextView(this).apply {
            text = "$label:   $value"; textSize = 15f; setTextColor(0xFF000000.toInt()); setPadding(pad, pad / 2, pad, pad / 2)
        }
        root.removeAllViews()
        root.addView(statLine(if (s.type == "channel") "Subscribers" else "Members", s.members.toString()))
        root.addView(statLine("Messages", s.messages.toString()))
        if (s.type == "channel") root.addView(statLine("Total views", s.views.toString()))

        if (s.growth.isNotEmpty()) {
            root.addView(cardTitle("Member growth (30 days)"))
            root.addView(BarChartView(this).apply {
                data = s.growth
                barColor = 0xFF4CAF50.toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Helpers.dp(this@StatsActivity, 150))
            })
        }
        if (s.activity.isNotEmpty()) {
            root.addView(cardTitle("Message activity (30 days)"))
            root.addView(BarChartView(this).apply {
                data = s.activity
                barColor = 0xFF3390EC.toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Helpers.dp(this@StatsActivity, 150))
            })
        }
        if (s.top.isNotEmpty()) {
            root.addView(cardTitle("Top posters"))
            s.top.take(10).forEachIndexed { i, (name, count) ->
                root.addView(TextView(this).apply {
                    text = "${i + 1}. $name — $count messages"
                    textSize = 14f; setTextColor(0xFF000000.toInt()); setPadding(pad, pad / 3, pad, pad / 3)
                })
            }
        }
    }

    /** tiny dependency-free bar chart (dates on X, counts on Y). */
    class BarChartView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
        var data: List<Pair<String, Int>> = emptyList()
        var barColor: Int = 0xFF3390EC.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (data.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val maxV = maxOf(1, data.maxOf { it.second })
            val gap = Helpers.dp(context, 3)
            val bw = (w - gap * (data.size + 1)) / data.size.coerceAtLeast(1)
            paint.color = 0x11000000
            c.drawRect(0f, 0f, w, h, paint)
            data.forEachIndexed { i, (_, v) ->
                val bh = (h * 0.9f) * v / maxV
                val left = gap + i * (bw + gap)
                paint.color = barColor
                c.drawRect(left, h - bh, left + bw, h - 2f, paint)
            }
        }
    }
}
