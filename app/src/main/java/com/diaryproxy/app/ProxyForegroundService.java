package com.diaryproxy.app;

import android.Manifest;
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
import android.os.SystemClock;
import android.text.TextUtils;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
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
    public static final String EXTRA_REQUEST_STATUS_TEXT = "request_status_text";

    public static final String PREF_GUARDIAN_ENABLED = "guardian_enabled";
    private static final String PREF_STATE_GUARDIAN_RUNNING = "state_guardian_running";
    private static final String PREF_STATE_SERVER_RUNNING = "state_server_running";
    private static final String PREF_STATE_LAST_CHAT_REQUEST_AT = "state_last_chat_request_at";
    private static final String PREF_STATE_REQUEST_STATUS_TEXT = "state_request_status_text";
    private static final String PREF_STATE_RECENT_LOGS = "state_recent_logs";

    private static final String CHANNEL_ID = "diary_proxy_guardian";
    private static final int NOTIFICATION_ID = 41021;
    private static final long MONITOR_INTERVAL_MS = 2000L;
    private static final long RECENT_REQUEST_WINDOW_MS = 120000L;
    private static final long SERVER_RESTART_INTERVAL_MS = 10000L;
    private static final int MAX_LOG_LINES = 180;
    /**
     * v1.5.4+：PFS-2 — 日志写入 SP 的 debounce 间隔。
     * appendRecentLogLocked 把多条日志聚成一次 SP 写入，1s 内的连续日志合并为单次 apply。
     */
    private static final long LOG_FLUSH_DEBOUNCE_MS = 1000L;

    private static final Deque<String> RECENT_LOGS = new ArrayDeque<>();
    private static final Map<Long, String> REQUEST_LABELS_BY_ID = new ConcurrentHashMap<>();
    /**
     * v1.5.4+：PFS-2 — RECENT_LOGS 是否有未持久化的变更。
     * 只在 appendRecentLogLocked / clearRecentLogs 时翻转；flush 后归 false。
     */
    private static volatile boolean sRecentLogsDirty = false;
    /**
     * v1.5.4+：PFS-2 — 单线程 daemon 调度器，承载 debounce 后的 SP 写入。
     * 静态：因为 appendRecentLog 可被外部（FeedbackSupport / 非服务上下文）调用。
     */
    private static final ScheduledExecutorService LOG_FLUSH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "RecentLogsFlusher");
                t.setDaemon(true);
                return t;
            });
    /** 当前挂起的 flush；同一时间最多 1 个。 */
    private static volatile ScheduledFuture<?> sPendingLogFlush = null;

    private static volatile boolean sGuardianRunning = false;
    private static volatile boolean sServerRunning = false;
    private static volatile boolean sRecentChatRequest = false;
    private static volatile long sLastChatRequestAtMs = 0L;
    private static volatile String sRequestStatusText = "";

    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitorFuture;

    /**
     * v1.5.4+：PFS-7 — 服务器生命周期独立 executor。
     * 之前 monitor 线程直接调 ensureServerRunning（内部 NanoHTTPD start 阻塞），
     * 端口被占 / SOCKET_READ_TIMEOUT 时 monitor 心跳停摆。
     * 现在 monitor 把 start 任务派发到本 executor，自身立即返回继续 tick。
     * single-thread daemon：保证 start/stop 串行，避免并发访问 server 字段。
     */
    private final ExecutorService serverLifecycleExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "ServerLifecycle");
                t.setDaemon(true);
                return t;
            });

    /**
     * v1.5.4+：PFS-7 — 防止 monitor 重复派发 start 任务的去重标志。
     * synchronized(this) 块内 set/clear，保证多线程 CAS 语义。
     */
    private volatile boolean serverStartInFlight = false;

    /**
     * v1.5.4+：PFS-7 — server 字段并发访问加 volatile（monitor 线程读，lifecycle 线程写）。
     */
    private volatile DiaryProxyServer server;
    private ProxyConfig config;
    private int activePort = -1;
    private long lastServerEnsureAttemptElapsedMs = 0L;

    private boolean lastBroadcastGuardianRunning = false;
    private boolean lastBroadcastServerRunning = false;
    private boolean lastBroadcastRecentChatRequest = false;
    private String lastBroadcastRequestStatusText = "";

    /**
     * v1.5.4+：PFS-1 — persistRuntimeState dirty 检查所需的"上次落盘值"。
     * 只在 4 个值之一变化时才触发 SP apply()，避免每 2s monitor 心跳无意义写入。
     * 初始化为不可能的初值（lastPersistedLastChatRequestAtMs=-1）确保第一次必写。
     */
    private boolean lastPersistedGuardianRunning = false;
    private boolean lastPersistedServerRunning = false;
    private long lastPersistedLastChatRequestAtMs = -1L;
    private String lastPersistedRequestStatusText = "\u0000"; // 不可能值，确保首写

    public static final class ServiceStateSnapshot {
        public final boolean guardianRunning;
        public final boolean serverRunning;
        public final boolean recentChatRequest;
        public final long lastChatRequestAtMs;
        public final String requestStatusText;

        ServiceStateSnapshot(boolean guardianRunning, boolean serverRunning, long lastChatRequestAtMs) {
            this(guardianRunning, serverRunning, false, lastChatRequestAtMs, "");
        }

        ServiceStateSnapshot(boolean guardianRunning, boolean serverRunning, boolean recentChatRequest, long lastChatRequestAtMs, String requestStatusText) {
            this.guardianRunning = guardianRunning;
            this.serverRunning = serverRunning;
            this.recentChatRequest = recentChatRequest;
            this.lastChatRequestAtMs = lastChatRequestAtMs;
            this.requestStatusText = firstNonEmpty(requestStatusText, "");
        }

        public boolean hasRecentChatRequest() {
            return recentChatRequest
                    || (lastChatRequestAtMs > 0
                    && System.currentTimeMillis() - lastChatRequestAtMs <= RECENT_REQUEST_WINDOW_MS);
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

    /**
     * v1.5.6+：PFS-11 — 缓存最近一次 getRecentLogs 拼出来的快照字符串。
     * RECENT_LOGS 改动时（addLast / removeFirst / clear）置 null，下次 getRecentLogs
     * 才重建。日志读多写少（持久化每秒最多一次，但 UI 拉日志可能很高频），
     * 缓存可省掉每次的 StringBuilder.append × 200。
     * volatile 不是必需（所有访问都在 synchronized(ProxyForegroundService.class) 块内），
     * 仅为静态分析友好性显式标注。
     */
    private static volatile String sCachedLogsSnapshot = null;

    public static synchronized String getRecentLogs() {
        String cached = sCachedLogsSnapshot;
        if (cached != null) {
            return cached;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : RECENT_LOGS) {
            builder.append(line).append("\n");
        }
        String snapshot = builder.toString();
        sCachedLogsSnapshot = snapshot;
        return snapshot;
    }

    public static synchronized String getRecentLogs(Context context) {
        ensureRecentLogsLoaded(context);
        return getRecentLogs();
    }

    public static synchronized void clearRecentLogs() {
        RECENT_LOGS.clear();
        sRecentLogsDirty = false;
        // v1.5.6+：PFS-11 — 列表清空，作废缓存快照
        sCachedLogsSnapshot = null;
    }

    public static synchronized void clearRecentLogs(Context context) {
        RECENT_LOGS.clear();
        // v1.5.4+：PFS-2 — 清空立即持久化；取消 debounce 中的挂起 flush，避免旧内容被后续 flush 误写回。
        sRecentLogsDirty = false;
        // v1.5.6+：PFS-11 — 列表清空，作废缓存快照
        sCachedLogsSnapshot = null;
        ScheduledFuture<?> pending = sPendingLogFlush;
        if (pending != null) {
            pending.cancel(false);
        }
        sPendingLogFlush = null;
        persistRecentLogs(context);
    }

    public static synchronized void appendRecentLog(Context context, String line) {
        appendRecentLogLocked(context, line);
    }

    public static ServiceStateSnapshot getCachedState(Context context) {
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE);
        boolean guardianRunning = sp.getBoolean(PREF_STATE_GUARDIAN_RUNNING, sGuardianRunning);
        boolean serverRunning = sp.getBoolean(PREF_STATE_SERVER_RUNNING, sServerRunning);
        long lastChatRequestAtMs = sp.getLong(PREF_STATE_LAST_CHAT_REQUEST_AT, sLastChatRequestAtMs);
        String requestStatusText = sp.getString(PREF_STATE_REQUEST_STATUS_TEXT, sRequestStatusText);
        boolean recentChatRequest = sRecentChatRequest
                || (lastChatRequestAtMs > 0 && System.currentTimeMillis() - lastChatRequestAtMs <= RECENT_REQUEST_WINDOW_MS);
        if (!recentChatRequest) {
            requestStatusText = "";
        }
        return new ServiceStateSnapshot(guardianRunning, serverRunning, recentChatRequest, lastChatRequestAtMs, requestStatusText);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        ensureRecentLogsLoaded(this);
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
                sRequestStatusText = "";
                REQUEST_LABELS_BY_ID.clear();
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
            FeedbackSupport.recordOperationalError(this, "service_start", "前台服务启动失败", error.getClass().getSimpleName() + ": " + error.getMessage());
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
        // v1.5.4+：PFS-7 — 关闭 server 生命周期 executor，避免 destroyed Service 残留后台线程。
        serverLifecycleExecutor.shutdownNow();
        sGuardianRunning = false;
        sServerRunning = false;
        sRecentChatRequest = false;
        sLastChatRequestAtMs = 0L;
        sRequestStatusText = "";
        REQUEST_LABELS_BY_ID.clear();
        persistRuntimeState();
        broadcastStateIfChanged(true);
        pushLog("guardian service destroyed");
        // v1.5.4+：PFS-2 — 立即 flush 剩余日志，确保 destroyed 消息与之前积压日志都落盘。
        flushRecentLogsNow(getApplicationContext() != null ? getApplicationContext() : this);
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
        if (server == null && shouldAttemptServerRestart()) {
            ensureServerRunning(recentChatRequest ? "recent_chat_request" : "monitor_keepalive");
        } else if (!recentChatRequest && server == null) {
            refreshNotification();
        }
        broadcastStateIfChanged(false);
    }

    private boolean shouldAttemptServerRestart() {
        if (!sGuardianRunning || server != null) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        return now - lastServerEnsureAttemptElapsedMs >= SERVER_RESTART_INTERVAL_MS;
    }

    private boolean updateTargetState() {
        sRecentChatRequest = hasRecentChatRequestWindow();
        if (!sRecentChatRequest && REQUEST_LABELS_BY_ID.isEmpty()) {
            sRequestStatusText = "";
        }
        persistRuntimeState();
        return sRecentChatRequest;
    }

    private void ensureServerRunning(String reason) {
        if (server != null) {
            return;
        }
        // v1.5.4+：PFS-7 — 避免 monitor 线程重复派发同一次 start（lifecycleExecutor 单线程也会串行，
        // 但 serverStartInFlight 让 tickMonitor 能立即 fast-path 跳过，不阻塞 monitor 心跳）。
        synchronized (this) {
            if (serverStartInFlight) {
                return;
            }
            serverStartInFlight = true;
        }
        lastServerEnsureAttemptElapsedMs = SystemClock.elapsedRealtime();
        try {
            serverLifecycleExecutor.execute(() -> startServerAsync(reason));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // onDestroy 后 executor 已 shutdown，回填 flag 以免后续误判。
            synchronized (this) {
                serverStartInFlight = false;
            }
        }
    }

    /**
     * v1.5.4+：PFS-7 — 真正的 NanoHTTPD start 阻塞逻辑，只在 serverLifecycleExecutor 线程执行。
     * monitor 线程只负责派发，不受 start 阻塞影响心跳。
     */
    private void startServerAsync(String reason) {
        try {
            if (server != null) {
                return;
            }
            ProxyConfig snapshot = ProxyConfig.load(this);
            config = snapshot;
            String validationError = DiaryProxyServer.validateUpstreamConfig(snapshot);
            if (!TextUtils.isEmpty(validationError)) {
                sServerRunning = false;
                persistRuntimeState();
                FeedbackSupport.recordOperationalError(this, "server_start", "代理启动前校验失败", validationError);
                pushLog("server start blocked: " + validationError);
                refreshNotification();
                broadcastStateIfChanged(true);
                return;
            }
            try {
                DiaryProxyServer proxyServer = new DiaryProxyServer(this, snapshot, this::pushLog);
                proxyServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                server = proxyServer;
                activePort = snapshot.port;
                sServerRunning = true;
                pushLog("server started on localhost:" + activePort + " reason=" + reason);
                pushLog("server upstream proxy=" + snapshot.resolvedUpstreamProxyType()
                        + " timeoutMs=" + snapshot.timeoutMs
                        + " env=" + RuntimeDiagnostics.buildRuntimeSummary(this));
                pushLog("tip: target app should use http://localhost:" + activePort + snapshot.getEndpointPreviewPath());
            } catch (IOException error) {
                sServerRunning = false;
                FeedbackSupport.recordOperationalError(this, "server_start", "本地代理启动失败", error.getMessage());
                pushLog("server start failed: " + error.getMessage());
            }
            persistRuntimeState();
            refreshNotification();
            broadcastStateIfChanged(true);
        } finally {
            synchronized (this) {
                serverStartInFlight = false;
            }
        }
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
        return !REQUEST_LABELS_BY_ID.isEmpty()
                || (lastAt > 0 && System.currentTimeMillis() - lastAt <= RECENT_REQUEST_WINDOW_MS);
    }

    private void markRecentChatRequest() {
        markRecentRequestStatus("已收到聊天请求，正在识别类型");
    }

    private void markRecentRequestStatus(String statusText) {
        sLastChatRequestAtMs = System.currentTimeMillis();
        sRecentChatRequest = true;
        if (!TextUtils.isEmpty(statusText)) {
            sRequestStatusText = statusText;
        }
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
        updateRequestStatusFromProxyLog(msg);
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = ts + "  " + msg;
        synchronized (ProxyForegroundService.class) {
            appendRecentLogLocked(this, line);
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
        String nowRequestStatusText = firstNonEmpty(sRequestStatusText, "");
        if (!force
                && nowGuardian == lastBroadcastGuardianRunning
                && nowServer == lastBroadcastServerRunning
                && nowRecent == lastBroadcastRecentChatRequest
                && nowRequestStatusText.equals(lastBroadcastRequestStatusText)) {
            return;
        }
        lastBroadcastGuardianRunning = nowGuardian;
        lastBroadcastServerRunning = nowServer;
        lastBroadcastRecentChatRequest = nowRecent;
        lastBroadcastRequestStatusText = nowRequestStatusText;

        Intent event = new Intent(ACTION_STATE_EVENT);
        event.setPackage(getPackageName());
        event.putExtra(EXTRA_GUARDIAN_RUNNING, nowGuardian);
        event.putExtra(EXTRA_SERVER_RUNNING, nowServer);
        event.putExtra(EXTRA_RECENT_CHAT_REQUEST, nowRecent);
        event.putExtra(EXTRA_LAST_CHAT_REQUEST_AT, sLastChatRequestAtMs);
        event.putExtra(EXTRA_REQUEST_STATUS_TEXT, nowRequestStatusText);
        sendBroadcast(event);
    }

    private void refreshNotification() {
        if (!canPostNotifications()) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification());
            } catch (Throwable error) {
                pushLog("notification refresh failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags());

        Intent stopIntent = new Intent(this, ProxyForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags());

        String title = sServerRunning ? "妹居代理运行中" : "妹居代理守护已启动";
        String targetPart = !TextUtils.isEmpty(sRequestStatusText)
                ? sRequestStatusText
                : (sRecentChatRequest ? "最近 2 分钟内检测到聊天请求" : "最近 2 分钟内暂无聊天请求");
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
        // v1.5.4+：PFS-1 — 仅当任一字段相对上次落盘有变化才 apply()，避免 monitor 每 2s 重复写。
        boolean nowGuardian = sGuardianRunning;
        boolean nowServer = sServerRunning;
        long nowLastAt = sLastChatRequestAtMs;
        String nowStatus = firstNonEmpty(sRequestStatusText, "");
        if (nowGuardian == lastPersistedGuardianRunning
                && nowServer == lastPersistedServerRunning
                && nowLastAt == lastPersistedLastChatRequestAtMs
                && nowStatus.equals(lastPersistedRequestStatusText)) {
            return;
        }
        lastPersistedGuardianRunning = nowGuardian;
        lastPersistedServerRunning = nowServer;
        lastPersistedLastChatRequestAtMs = nowLastAt;
        lastPersistedRequestStatusText = nowStatus;

        SharedPreferences.Editor editor = getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(PREF_STATE_GUARDIAN_RUNNING, nowGuardian);
        editor.putBoolean(PREF_STATE_SERVER_RUNNING, nowServer);
        editor.putLong(PREF_STATE_LAST_CHAT_REQUEST_AT, nowLastAt);
        editor.putString(PREF_STATE_REQUEST_STATUS_TEXT, nowStatus);
        editor.apply();
    }

    private void updateRequestStatusFromProxyLog(String msg) {
        if (TextUtils.isEmpty(msg)) {
            return;
        }
        if (msg.startsWith("proxy recv chat req=")) {
            markRecentChatRequest();
            return;
        }
        if (msg.startsWith("proxy send req=")) {
            long reqId = parseRequestId(msg, "proxy send req=");
            String label = requestLabelForKind(parseLogToken(msg, "kind="));
            if (reqId > 0) {
                REQUEST_LABELS_BY_ID.put(reqId, label);
            }
            markRecentRequestStatus("已发送" + label + "请求，正在等待回应");
            return;
        }
        if (msg.startsWith("proxy req=")) {
            long reqId = parseRequestId(msg, "proxy req=");
            if (reqId > 0) {
                REQUEST_LABELS_BY_ID.putIfAbsent(reqId, requestLabelForKind(parseLogToken(msg, "kind=")));
            }
            return;
        }
        if (msg.startsWith("proxy out req=")) {
            long reqId = parseRequestId(msg, "proxy out req=");
            String label = reqId > 0 ? REQUEST_LABELS_BY_ID.remove(reqId) : "";
            markRecentRequestStatus("已返回" + firstNonEmpty(label, "对话") + "请求");
        }
    }

    private static long parseRequestId(String text, String prefix) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(prefix) || !text.startsWith(prefix)) {
            return -1L;
        }
        int start = prefix.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return -1L;
        }
        try {
            return Long.parseLong(text.substring(start, end));
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String parseLogToken(String text, String key) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(key)) {
            return "";
        }
        int start = text.indexOf(key);
        if (start < 0) {
            return "";
        }
        start += key.length();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end).trim();
    }

    private static String requestLabelForKind(String kind) {
        String value = firstNonEmpty(kind, "").trim().toLowerCase(Locale.ROOT);
        if ("normal-diary".equals(value) || "holiday-diary".equals(value)) {
            return "生成日记";
        }
        if ("normal-chat".equals(value)) {
            return "对话";
        }
        if ("interactive-story".equals(value) || "story-like".equals(value)) {
            return "剧情";
        }
        if ("memory-extract".equals(value)) {
            return "长期记忆";
        }
        if ("turtle-soup-judge".equals(value)) {
            return "海龟汤裁判";
        }
        if ("preset-reply".equals(value)) {
            return "预设回复";
        }
        if ("health-check".equals(value)) {
            return "连通测试";
        }
        if ("meta-prompt".equals(value)) {
            return "元提示词";
        }
        if ("pass-through".equals(value)) {
            return "转发";
        }
        return "对话";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private static void appendRecentLogLocked(Context context, String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        RECENT_LOGS.addLast(line);
        while (RECENT_LOGS.size() > MAX_LOG_LINES) {
            RECENT_LOGS.removeFirst();
        }
        // v1.5.6+：PFS-11 — 列表已变更，作废上一次缓存的快照字符串
        sCachedLogsSnapshot = null;
        // v1.5.4+：PFS-2 — 不再每条日志立即 SP apply()，改 dirty 标志 + debounce flush。
        sRecentLogsDirty = true;
        scheduleRecentLogsFlush(context);
    }

    /**
     * v1.5.4+：PFS-2 — 高频日志的 debounce 持久化调度。
     * 第一条日志触发 1s 定时器，期间所有后续日志只标 dirty，1s 后单次 SP 写入。
     * 已挂起 flush 时不重复调度。
     */
    private static void scheduleRecentLogsFlush(Context context) {
        if (context == null) {
            return;
        }
        ScheduledFuture<?> existing = sPendingLogFlush;
        if (existing != null && !existing.isDone()) {
            return; // 已有挂起的 flush，会在 1s 后处理本条
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        sPendingLogFlush = LOG_FLUSH_EXECUTOR.schedule(
                () -> persistRecentLogsIfDirty(appContext),
                LOG_FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static void ensureRecentLogsLoaded(Context context) {
        if (context == null || !RECENT_LOGS.isEmpty()) {
            return;
        }
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE);
        String raw = sp.getString(PREF_STATE_RECENT_LOGS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (!TextUtils.isEmpty(line)) {
                RECENT_LOGS.addLast(line);
            }
        }
        while (RECENT_LOGS.size() > MAX_LOG_LINES) {
            RECENT_LOGS.removeFirst();
        }
        // v1.5.6+：PFS-11 — 首次从 SP 装载完毕，作废空快照（getRecentLogs 此前若被
        // 调用过，cachedSnapshot 可能仍是 ""）
        sCachedLogsSnapshot = null;
    }

    private static void persistRecentLogs(Context context) {
        if (context == null) {
            return;
        }
        String snapshot;
        synchronized (ProxyForegroundService.class) {
            snapshot = getRecentLogs();
            sRecentLogsDirty = false;
        }
        context.getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_STATE_RECENT_LOGS, snapshot)
                .apply();
    }

    /**
     * v1.5.4+：PFS-2 — debounce 到期回调。仅在 dirty 时写 SP。
     * flushRecentLogsNow 直接复用此方法（未 dirty 时跳过，无副作用）。
     *
     * <p>v1.5.6+：PFS-10 — 修复"flush 期间到达的新日志无法触发新调度"的窗口竞态：
     * persistRecentLogs 已把 dirty 置为 false（801-803），但 sPendingLogFlush 直到本方法
     * finally 才置 null。如果在二者之间有新日志 append 进来，scheduleRecentLogsFlush 会
     * 因为 sPendingLogFlush != null && !isDone() 直接 return，最后那批日志就只会 append
     * 到内存而未持久化。修复：在置 null 之后再检查一次 dirty，必要时立刻重新 schedule。</p>
     */
    private static void persistRecentLogsIfDirty(Context context) {
        if (!sRecentLogsDirty) {
            sPendingLogFlush = null;
            return;
        }
        try {
            persistRecentLogs(context);
        } finally {
            sPendingLogFlush = null;
            // PFS-10：二次检查——flush 写 SP 期间又有新日志到达，重新调度一轮 debounce。
            if (sRecentLogsDirty) {
                scheduleRecentLogsFlush(context);
            }
        }
    }

    /**
     * v1.5.4+：PFS-2 — Service onDestroy / clearRecentLogs(Context) 主动 flush。
     * 取消挂起的 debounce 并立即同步写 SP，防止进程被杀时最后一批日志丢失。
     */
    private static void flushRecentLogsNow(Context context) {
        ScheduledFuture<?> pending = sPendingLogFlush;
        if (pending != null) {
            pending.cancel(false);
        }
        sPendingLogFlush = null;
        if (sRecentLogsDirty) {
            persistRecentLogs(context);
        }
    }
}
