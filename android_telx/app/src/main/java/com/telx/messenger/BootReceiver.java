package com.telx.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** TelX — BootReceiver: restart the notification service after device reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
        if (p.getString("token", "").isEmpty()) return; // not logged in yet
        NotificationService.start(context);
    }
}
