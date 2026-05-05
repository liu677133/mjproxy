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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable error) {
            // v1.5.4+：BR-1 — Android 13+ 后台限制下，doze 期 startForegroundService 会抛
            // ForegroundServiceStartNotAllowedException / IllegalStateException。捕获并记录到
            // FeedbackSupport，让用户在"操作记录"里能看到"开机自启失败"——主动打开 App 即可。
            // MainActivity onResume 已二次确认 service 状态，无需此处再投递重试。
            try {
                FeedbackSupport.recordOperationalError(
                        context,
                        "boot_autostart",
                        "开机自启代理服务失败，Android 后台限制可能阻止了前台服务启动；可手动打开应用恢复",
                        error.getClass().getSimpleName() + ": " + error.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }
}
