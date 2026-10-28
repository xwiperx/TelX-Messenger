package com.telx.messenger;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * TelX — QuickReplyReceiver
 * Sends the reply typed directly inside the notification shade.
 */
public class QuickReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence text = results.getCharSequence("quick_reply");
        String chatId = intent.getStringExtra("chatId");
        if (text == null || text.length() == 0 || chatId == null) return;

        SharedPreferences p = context.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
        String token = p.getString("token", "");
        if (token.isEmpty()) return;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("chatId", chatId);
                body.put("type", "text");
                body.put("text", text.toString());
                HttpURLConnection c = (HttpURLConnection) new URL(NotificationService.BASE_URL + "messages.php").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("content-type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.close();
                c.getResponseCode();
            } catch (Throwable ignored) { }
        }).start();
    }
}
