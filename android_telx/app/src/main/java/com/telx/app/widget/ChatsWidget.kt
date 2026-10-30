package com.telx.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.telx.app.R
import com.telx.app.data.Prefs
import org.json.JSONArray

/**
 * v6: home-screen widget — shows the 4 most recent chats and opens the
 * tapped one directly (Telegram-style quick access widget).
 */
class ChatsWidget : AppWidgetProvider() {

    companion object {
        /** refresh every widget instance (call after chat list reloads) */
        fun updateAll(ctx: Context) {
            try {
                val man = AppWidgetManager.getInstance(ctx)
                val ids = man.getAppWidgetIds(ComponentName(ctx, ChatsWidget::class.java))
                if (ids.isNotEmpty()) ChatsWidget().onUpdate(ctx, man, ids)
            } catch (_: Exception) {}
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val recent = try { JSONArray(Prefs.recentChats) } catch (_: Exception) { JSONArray() }
        for (wid in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_chats)
            views.setTextViewText(R.id.widget_title, "TelX")

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, com.telx.app.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_title, openApp)

            val rowIds = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4)
            for (i in rowIds.indices) {
                if (i < recent.length()) {
                    val c = recent.optJSONObject(i) ?: continue
                    val cid = c.optInt("id", 0)
                    val name = c.optString("name", "")
                    views.setTextViewText(rowIds[i], "• $name")
                    views.setOnClickPendingIntent(rowIds[i], PendingIntent.getActivity(
                        context, 1000 + cid,
                        Intent(context, com.telx.app.ui.chat.ChatActivity::class.java)
                            .putExtra("chat_id", cid),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    views.setTextViewText(rowIds[i], "")
                }
            }
            manager.updateAppWidget(wid, views)
        }
    }
}
