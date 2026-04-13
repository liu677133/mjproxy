package com.diaryproxy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class ProxyForegroundService extends Service {

    public static final String ACTION_START = "com.diaryproxy.app.action.START";
    public static final String ACTION_STOP = "com.diaryproxy.app.action.STOP";
    public static final String ACTION_UPDATE_CONFIG = "com.diaryproxy.app.action.UPDATE_CONFIG";

    public static final String ACTION_LOG_EVENT = "com.diaryproxy.app.event.LOG";
    public static final String ACTION_STATE_EVENT = "com.diaryproxy.app.event.STATE";

    public static final String EXTRA_LOG_LINE = "log_line";
    public static final String EXTRA_GUARDIAN_RUNNING = "guardian_running";
    public static final String EXTRA_SERVER_RUNNING = "server_running";
    public static final String EXTRA_RECENT_CHAT_REQUEST = "recent_chat_request";
    public static final String EXTRA_LAST_CHAT_REQUEST_AT = "last_chat_request_at";

    public static final String PREF_GUARDIAN_ENABLED = "guardian_enabled";
    private static final String PREF_STATE_GUARDIAN_RUNNING = "state_guardian_running";
    private static final String PREF_STATE_SERVER_RUNNING = "state_server_running";
    private static final String PREF_STATE_LAST_CHAT_REQUEST_AT = "state_last_chat_request_at";

    private static final String CHANNEL_ID = "diary_proxy_guardian";
    private static final int NOTIFICATION_ID = 41021;
    private static final long MONITOR_INTERVAL_MS = 2000L;
    private static final long RECENT_REQUEST_WINDOW_MS = 120000L;
    private static final int MAX_LOG_LINES = 180;

    private static final Deque<String> RECENT_LOGS = new ArrayDeque<>();
    private static volatile boolean sGuardianRunning = false;
    private static volatile boolean sServerRunning = false;
    private static volatile boolean sRecentChatRequest = false;
    private static volatile long sLastChatRequestAtMs = 0L;

    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitorFuture;

    private DiaryProxyServer server;
    private ProxyConfig config;
    private int activePort = -1;

    private boolean lastBroadcastGuardianRunning = false;
    private boolean lastBroadcastServerRunning = false;
    private boolean lastBroadcastRecentChatRequest = false;

    public static final class ServiceStateSnapshot {
        public final boolean guardianRunning;
        public final boolean serverRunning;
        public final long lastChatRequestAtMs;

        ServiceStateSnapshot(boolean guardianRunning, boolean serverRunning, long lastChatRequestAtMs) {
            this.guardianRunning = guardianRunning;
            this.serverRunning = serverRunning;
            this.lastChatRequestAtMs = lastChatRequestAtMs;
        }

        public boolean hasRecentChatRequest() {
            return lastChatRequestAtMs > 0
                    && System.currentTimeMillis() - lastChatRequestAtMs <= RECENT_REQUEST_WINDOW_MS;
        }
    }

    public static boolean isGuardianRunning() {
        return sGuardianRunning;
    }

    public static boolean isServerRunning() {
        return sServerRunning;
    }

    public static boolean hasRecentChatRequest() {
        return sRecentChatRequest;
    }

    public static synchronized String getRecentLogs() {
        StringBuilder builder = new StringBuilder();
        for (String line : RECENT_LOGS) {
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    public static synchronized void clearRecentLogs() {
        RECENT_LOGS.clear();
    }

    public static ServiceStateSnapshot getCachedState(Context context) {
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE);
        boolean guardianRunning = sp.getBoolean(PREF_STATE_GUARDIAN_RUNNING, sGuardianRunning);
        boolean serverRunning = sp.getBoolean(PREF_STATE_SERVER_RUNNING, sServerRunning);
        long lastChatRequestAtMs = sp.getLong(PREF_STATE_LAST_CHAT_REQUEST_AT, sLastChatRequestAtMs);
        return new ServiceStateSnapshot(guardianRunning, serverRunning, lastChatRequestAtMs);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        pushLog("guardian service created");
        pushLog("runtime device: " + RuntimeDiagnostics.buildDeviceSummary());
        pushLog("runtime env: " + RuntimeDiagnostics.buildRuntimeSummary(this));
        persistRuntimeState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String action = intent != null ? intent.getAction() : ACTION_START;

            if (ACTION_STOP.equals(action)) {
                setGuardianEnabled(false);
                sGuardianRunning = false;
                sRecentChatRequest = false;
                sLastChatRequestAtMs = 0L;
                persistRuntimeState();
                pushLog("guardian stop requested");
                stopSelf();
                return START_NOT_STICKY;
            }

            if (ACTION_START.equals(action)) {
                setGuardianEnabled(true);
            }

            config = ProxyConfig.load(this);
            sGuardianRunning = true;
            persistRuntimeState();
            startForeground(NOTIFICATION_ID, buildNotification());
            pushLog("guardian active env: " + RuntimeDiagnostics.buildRuntimeSummary(this));

            if (ACTION_START.equals(action)) {
                ensureServerRunning("manual_start");
            } else if (ACTION_UPDATE_CONFIG.equals(action)) {
                applyConfigToServer("config_updated");
            } else {
                ensureServerRunning("sticky_restart");
            }

            ensureMonitor();
            updateTargetState();
            broadcastStateIfChanged(true);
            refreshNotification();
            return START_STICKY;
        } catch (Throwable error) {
            sGuardianRunning = false;
            sServerRunning = false;
            persistRuntimeState();
            pushLog("guardian fatal start error: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            broadcastStateIfChanged(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        stopServer("service_destroyed");
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }
        monitorExecutor.shutdownNow();
        sGuardianRunning = false;
        sServerRunning = false;
        sRecentChatRequest = false;
        sLastChatRequestAtMs = 0L;
        persistRuntimeState();
        broadcastStateIfChanged(true);
        pushLog("guardian service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureMonitor() {
        if (monitorFuture != null && !monitorFuture.isCancelled()) {
            return;
        }
        monitorFuture = monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                tickMonitor();
            } catch (Exception error) {
                pushLog("monitor error: " + error.getMessage());
            }
        }, 0, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void tickMonitor() {
        boolean recentChatRequest = updateTargetState();
        if (recentChatRequest) {
            ensureServerRunning("recent_chat_request");
        }
        if (!recentChatRequest && server == null) {
            refreshNotification();
        }
        broadcastStateIfChanged(false);
    }

    private boolean updateTargetState() {
        sRecentChatRequest = hasRecentChatRequestWindow();
        persistRuntimeState();
        return sRecentChatRequest;
    }

    private void ensureServerRunning(String reason) {
        if (server != null) {
            return;
        }
        config = ProxyConfig.load(this);
        String validationError = DiaryProxyServer.validateUpstreamConfig(config);
        if (!TextUtils.isEmpty(validationError)) {
            sServerRunning = false;
            persistRuntimeState();
            pushLog("server start blocked: " + validationError);
            refreshNotification();
            broadcastStateIfChanged(true);
            return;
        }
        try {
            DiaryProxyServer proxyServer = new DiaryProxyServer(this, config, this::pushLog);
            proxyServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            server = proxyServer;
            activePort = config.port;
            sServerRunning = true;
            pushLog("server started on localhost:" + activePort + " reason=" + reason);
            pushLog("server upstream proxy=" + config.resolvedUpstreamProxyType()
                    + " timeoutMs=" + config.timeoutMs
                    + " env=" + RuntimeDiagnostics.buildRuntimeSummary(this));
            pushLog("tip: target app should use http://localhost:" + activePort + config.getEndpointPreviewPath());
        } catch (IOException error) {
            sServerRunning = false;
            pushLog("server start failed: " + error.getMessage());
        }
        persistRuntimeState();
        refreshNotification();
        broadcastStateIfChanged(true);
    }

    private void applyConfigToServer(String reason) {
        config = ProxyConfig.load(this);
        if (server == null) {
            ensureServerRunning(reason);
            return;
        }
        if (config.port != activePort) {
            pushLog("port changed, restarting server");
            stopServer("port_changed");
            ensureServerRunning(reason);
            return;
        }
        server.updateConfig(config);
        pushLog("server config hot-updated");
        persistRuntimeState();
        refreshNotification();
    }

    private void stopServer(String reason) {
        if (server == null) {
            sServerRunning = false;
            return;
        }
        try {
            server.stop();
        } catch (Exception ignored) {
        }
        server = null;
        activePort = -1;
        sServerRunning = false;
        persistRuntimeState();
        pushLog("server stopped reason=" + reason);
        refreshNotification();
        broadcastStateIfChanged(true);
    }

    private boolean hasRecentChatRequestWindow() {
        long lastAt = sLastChatRequestAtMs;
        return lastAt > 0 && System.currentTimeMillis() - lastAt <= RECENT_REQUEST_WINDOW_MS;
    }

    private void markRecentChatRequest() {
        sLastChatRequestAtMs = System.currentTimeMillis();
        sRecentChatRequest = true;
        persistRuntimeState();
        refreshNotification();
        broadcastStateIfChanged(false);
    }

    private void setGuardianEnabled(boolean enabled) {
        SharedPreferences.Editor editor = getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(PREF_GUARDIAN_ENABLED, enabled);
        editor.apply();
    }

    static boolean isGuardianEnabled(Context context) {
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE);
        return sp.getBoolean(PREF_GUARDIAN_ENABLED, true);
    }

    private void pushLog(String msg) {
        if (msg != null && msg.startsWith("proxy recv chat req=")) {
            markRecentChatRequest();
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = ts + "  " + msg;
        synchronized (ProxyForegroundService.class) {
            RECENT_LOGS.addLast(line);
            while (RECENT_LOGS.size() > MAX_LOG_LINES) {
                RECENT_LOGS.removeFirst();
            }
        }

        Intent event = new Intent(ACTION_LOG_EVENT);
        event.setPackage(getPackageName());
        event.putExtra(EXTRA_LOG_LINE, line);
        sendBroadcast(event);
    }

    private void broadcastStateIfChanged(boolean force) {
        boolean nowGuardian = sGuardianRunning;
        boolean nowServer = sServerRunning;
        boolean nowRecent = sRecentChatRequest;
        if (!force
                && nowGuardian == lastBroadcastGuardianRunning
                && nowServer == lastBroadcastServerRunning
                && nowRecent == lastBroadcastRecentChatRequest) {
            return;
        }
        lastBroadcastGuardianRunning = nowGuardian;
        lastBroadcastServerRunning = nowServer;
        lastBroadcastRecentChatRequest = nowRecent;

        Intent event = new Intent(ACTION_STATE_EVENT);
        event.setPackage(getPackageName());
        event.putExtra(EXTRA_GUARDIAN_RUNNING, nowGuardian);
        event.putExtra(EXTRA_SERVER_RUNNING, nowServer);
        event.putExtra(EXTRA_RECENT_CHAT_REQUEST, nowRecent);
        event.putExtra(EXTRA_LAST_CHAT_REQUEST_AT, sLastChatRequestAtMs);
        sendBroadcast(event);
    }

    private void refreshNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification());
            } catch (Throwable error) {
                pushLog("notification refresh failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags());

        Intent stopIntent = new Intent(this, ProxyForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags());

        String title = sServerRunning ? "妹居代理运行中" : "妹居代理守护已启动";
        String targetPart = sRecentChatRequest ? "最近 2 分钟内检测到聊天请求" : "最近 2 分钟内暂无聊天请求";
        String text = sServerRunning && activePort > 0
                ? "localhost:" + activePort + " | " + targetPart
                : "等待本地代理启动 | " + targetPart;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private int pendingFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "妹居代理守护", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持本地代理常驻并显示最近聊天请求状态");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void persistRuntimeState() {
        SharedPreferences.Editor editor = getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(PREF_STATE_GUARDIAN_RUNNING, sGuardianRunning);
        editor.putBoolean(PREF_STATE_SERVER_RUNNING, sServerRunning);
        editor.putLong(PREF_STATE_LAST_CHAT_REQUEST_AT, sLastChatRequestAtMs);
        editor.apply();
    }
}
