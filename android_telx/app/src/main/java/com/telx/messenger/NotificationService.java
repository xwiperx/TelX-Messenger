package com.telx.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * TelX — NotificationService (NO Firebase)
 * A foreground service that long-polls the TelX PHP API every 15s while the
 * app is in background, and raises native Android notifications — including
 * Quick-Reply (answer straight from the notification shade).
 */
public class NotificationService extends Service {

    public static final String BASE_URL = MainActivity.BASE_URL + "v2/";
    public static final String PREFS = "telx_prefs";
    private static final String CHANNEL_ID = "telx_messages";
    private static final String CHANNEL_NAME = "پیام‌های TelX";
    private static final long POLL_MS = 15_000;

    private static boolean appVisible = false;
    private static boolean running = false;

    private HandlerThread workerThread;
    private Handler worker;
    private final Set<String> shownIds = new HashSet<>();

    public static void start(Context ctx) {
        if (running) return;
        Intent i = new Intent(ctx, NotificationService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        Notification n = buildSilentNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
        workerThread = new HandlerThread("TelXPoll");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(pollLoop);
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            try {
                if (!appVisible) pollOnce();
            } catch (Throwable ignored) { }
            worker.postDelayed(this, POLL_MS);
        }
    };

    /** GET /notifications.php → raise a notification for every unseen item */
    private void pollOnce() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = p.getString("token", "");
        if (token.isEmpty()) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + "notifications.php").openConnection();
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            String body = read(c);
            if (body == null) return;
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("ok")) return;
            JSONArray items = o.optJSONArray("items");
            if (items == null) return;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String id = it.optString("id");
                if (shownIds.contains(id)) continue;
                shownIds.add(id);
                if (shownIds.size() > 200) shownIds.clear();
                String title = it.optString("title", "TelX");
                String text = it.optString("body", "پیام جدید");
                String chatId = it.optString("chatId", "");
                nm.notify(id.hashCode(), buildMessageNotification(title, text, chatId));
            }
            // acknowledge (mark read server-side so they don't repeat)
            markRead(token);
        } catch (Throwable ignored) { }
    }

    private void markRead(String token) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + "notifications.php").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("content-type", "application/json");
                c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
                c.getResponseCode();
            } catch (Throwable ignored) { }
        }).start();
    }

    private Notification buildMessageNotification(String title, String text, String chatId) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, title.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pOpen)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);

        // Quick Reply from the notification shade
        if (Build.VERSION.SDK_INT >= 24 && !chatId.isEmpty()) {
            android.app.RemoteInput remoteInput = new android.app.RemoteInput.Builder("quick_reply")
                    .setLabel("پاسخ…").build();
            Intent reply = new Intent(this, QuickReplyReceiver.class);
            reply.putExtra("chatId", chatId);
            reply.putExtra("title", title);
            PendingIntent pReply = PendingIntent.getBroadcast(this, chatId.hashCode(), reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Action action = new Notification.Action.Builder(
                    R.drawable.ic_notification, "پاسخ", pReply)
                    .addRemoteInput(remoteInput)
                    .build();
            b.addAction(action);
        }
        return b.build();
    }

    private Notification buildSilentNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("TelX فعال است")
                .setContentText("آماده دریافت پیام‌های جدید")
                .setContentIntent(pOpen)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private String read(HttpURLConnection c) {
        try {
            int code = c.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
