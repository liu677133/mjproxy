package com.diaryproxy.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean isReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!isBoot && !isReplaced) {
            return;
        }

        if (!ProxyForegroundService.isGuardianEnabled(context)) {
            return;
        }

        Intent serviceIntent = new Intent(context, ProxyForegroundService.class);
        serviceIntent.setAction(ProxyForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
