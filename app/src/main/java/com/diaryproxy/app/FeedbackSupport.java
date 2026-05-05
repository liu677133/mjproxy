package com.diaryproxy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class FeedbackSupport {

    static final String PREF_LAST_OPERATIONAL_ERROR = "lastOperationalErrorReport";
    static final String PREF_LAST_CRASH_REPORT = "lastCrashReport";
    private static final String PREF_DEVICE_INSTANCE_ID = "feedbackDeviceInstanceId";
    private static final int MAX_MESSAGE_CHARS = 2000;
    private static final int MAX_LOG_CHARS = 2000;
    private static final int MAX_DETAIL_CHARS = 1200;
    private static final int MAX_CONTACT_CHARS = 240;
    private static final int MAX_PAYLOAD_CHARS = 12000;
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile("(?i)(authorization|api[_ -]?key|token)\\s*[:=]\\s*[^\\s\\n\\r]+");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(?i)(apikey|api_key|secret|password)\\s*[:=]\\s*[^\\s\\n\\r]+");

    private FeedbackSupport() {
    }

    static void recordOperationalError(Context context, String source, String summary, String detail) {
        persistReport(context, PREF_LAST_OPERATIONAL_ERROR, buildErrorReportJson(
                "operational_error",
                source,
                summary,
                detail
        ));
    }

    static void recordCrash(Context context, Thread thread, Throwable error) {
        String threadName = thread == null ? "" : thread.getName();
        StringBuilder detail = new StringBuilder();
        detail.append(error == null ? "unknown_crash" : error.getClass().getSimpleName());
        if (error != null && !TextUtils.isEmpty(error.getMessage())) {
            detail.append(": ").append(error.getMessage());
        }
        if (!TextUtils.isEmpty(threadName)) {
            detail.append(" @thread=").append(threadName);
        }
        persistReport(context, PREF_LAST_CRASH_REPORT, buildErrorReportJson(
                "crash",
                "uncaught_exception",
                "应用上次运行时发生了未捕获异常",
                detail.toString()
        ));
    }

    static boolean hasPendingCrashReport(Context context) {
        return !TextUtils.isEmpty(loadRawReport(context, PREF_LAST_CRASH_REPORT));
    }

    static String getCrashSummary(Context context) {
        JSONObject report = loadReport(context, PREF_LAST_CRASH_REPORT);
        if (report == null) {
            return "";
        }
        return safe(report.optString("summary", ""));
    }

    static void clearCrashReport(Context context) {
        clearReport(context, PREF_LAST_CRASH_REPORT);
    }

    static SendResult sendUserFeedback(Context context, ProxyConfig config, String category, String message, String contact) {
        return sendFeedbackInternal(context, config, "user_feedback", category, message, contact);
    }

    static SendResult sendErrorReport(Context context, ProxyConfig config, String extraMessage) {
        return sendFeedbackInternal(context, config, "error_report", "错误报告", extraMessage, "");
    }

    private static SendResult sendFeedbackInternal(
            Context context,
            ProxyConfig config,
            String feedbackType,
            String category,
            String message,
            String contact
    ) {
        if (context == null) {
            return SendResult.failure("当前上下文为空，无法发送。", 0);
        }
        ProxyConfig safeConfig = config == null ? ProxyConfig.load(context) : config.copy();
        String webhook = safeConfig.normalizedFeedbackWebhookUrl();
        String validationError = validateWebhookUrl(webhook);
        if (!TextUtils.isEmpty(validationError)) {
            return SendResult.failure(validationError, 0);
        }

        try {
            String sanitizedMessage = clipAndSanitize(message, MAX_MESSAGE_CHARS);
            String sanitizedContact = clipAndSanitize(contact, MAX_CONTACT_CHARS);
            String sanitizedLogs = clipAndSanitize(ProxyForegroundService.getRecentLogs(context), MAX_LOG_CHARS);
            JSONObject payload = buildPayload(
                    context,
                    safeConfig,
                    feedbackType,
                    category,
                    sanitizedMessage,
                    sanitizedContact,
                    sanitizedLogs
            );
            String body = payload.toString();
            if (body.length() > MAX_PAYLOAD_CHARS) {
                payload.put("recentLogs", clipAndSanitize(sanitizedLogs, 1200));
                payload.put("message", clipAndSanitize(sanitizedMessage, 1200));
                payload.put("payloadTruncated", true);
                body = payload.toString();
            }
            if (body.length() > MAX_PAYLOAD_CHARS) {
                return SendResult.failure("反馈内容过长，请精简描述后再试。", 0);
            }
            return postJson(webhook, body);
        } catch (Exception error) {
            return SendResult.failure("发送失败：" + safe(error.getMessage()), 0);
        }
    }

    private static JSONObject buildPayload(
            Context context,
            ProxyConfig config,
            String feedbackType,
            String category,
            String message,
            String contact,
            String recentLogs
    ) throws Exception {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String deviceId = getOrCreateDeviceInstanceId(context);

        JSONObject payload = new JSONObject();
        payload.put("type", safe(feedbackType));
        payload.put("category", safe(category));
        payload.put("message", safe(message));
        payload.put("contact", safe(contact));
        payload.put("timestamp", timestamp);
        payload.put("nonce", nonce);
        payload.put("deviceInstanceId", deviceId);
        payload.put("signature", buildWeakSignature(context, deviceId, timestamp, nonce));
        payload.put("app", buildAppInfo(context));
        payload.put("device", buildDeviceInfo());
        payload.put("runtime", RuntimeDiagnostics.buildRuntimeSummary(context));
        payload.put("config", buildConfigSummary(config));
        payload.put("recentLogs", safe(recentLogs));

        JSONObject operational = loadReport(context, PREF_LAST_OPERATIONAL_ERROR);
        if (operational != null) {
            payload.put("lastOperationalError", operational);
        }
        JSONObject crash = loadReport(context, PREF_LAST_CRASH_REPORT);
        if (crash != null) {
            payload.put("lastCrashReport", crash);
        }
        return payload;
    }

    private static JSONObject buildAppInfo(Context context) throws Exception {
        JSONObject app = new JSONObject();
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();
        String versionName = "";
        long versionCode = 0L;
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName, 0);
            versionName = safe(info.versionName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                versionCode = info.versionCode;
            }
        } catch (Exception ignored) {
        }
        app.put("packageName", packageName);
        app.put("versionName", versionName);
        app.put("versionCode", versionCode);
        return app;
    }

    private static JSONObject buildDeviceInfo() throws Exception {
        JSONObject device = new JSONObject();
        device.put("brand", safe(Build.BRAND));
        device.put("manufacturer", safe(Build.MANUFACTURER));
        device.put("model", safe(Build.MODEL));
        device.put("sdkInt", Build.VERSION.SDK_INT);
        device.put("androidVersion", safe(Build.VERSION.RELEASE));
        return device;
    }

    private static JSONObject buildConfigSummary(ProxyConfig config) throws Exception {
        JSONObject summary = new JSONObject();
        if (config == null) {
            return summary;
        }
        summary.put("adapterPreset", safe(config.adapterPreset));
        summary.put("model", safe(config.model));
        summary.put("upstreamProxyType", safe(config.resolvedUpstreamProxyType()));
        summary.put("personaEnabled", config.personaEnabled);
        summary.put("personaIgnoreAffinityEnabled", config.personaIgnoreAffinityEnabled);
        return summary;
    }

    private static SendResult postJson(String webhook, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(webhook).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(bytes);
            }
            int status = connection.getResponseCode();
            String responseBody = readResponseBody(connection, status >= 400);
            if (status >= 200 && status < 300) {
                return SendResult.success("发送成功", status);
            }
            return SendResult.failure(
                    "服务返回失败，status=" + status + "，内容预览：" + clipAndSanitize(responseBody, 200),
                    status
            );
        } catch (Exception error) {
            return SendResult.failure("提交失败：" + safe(error.getMessage()), 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readResponseBody(HttpURLConnection connection, boolean errorStream) {
        if (connection == null) {
            return "";
        }
        try (InputStream inputStream = errorStream ? connection.getErrorStream() : connection.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (inputStream == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject buildErrorReportJson(String type, String source, String summary, String detail) {
        try {
            JSONObject report = new JSONObject();
            report.put("type", safe(type));
            report.put("source", safe(source));
            report.put("summary", clipAndSanitize(summary, 240));
            report.put("detail", clipAndSanitize(detail, MAX_DETAIL_CHARS));
            report.put("timestamp", System.currentTimeMillis());
            return report;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void persistReport(Context context, String key, JSONObject report) {
        if (context == null || TextUtils.isEmpty(key) || report == null) {
            return;
        }
        context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, report.toString())
                .apply();
    }

    private static void clearReport(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) {
            return;
        }
        context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply();
    }

    private static JSONObject loadReport(Context context, String key) {
        String raw = loadRawReport(context, key);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String loadRawReport(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) {
            return "";
        }
        return context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .getString(key, "");
    }

    private static String getOrCreateDeviceInstanceId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE);
        String existing = safe(sp.getString(PREF_DEVICE_INSTANCE_ID, ""));
        if (!TextUtils.isEmpty(existing)) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        sp.edit().putString(PREF_DEVICE_INSTANCE_ID, created).apply();
        return created;
    }

    private static String buildWeakSignature(Context context, String deviceId, long timestamp, String nonce) {
        String input = safe(context.getPackageName())
                + "|"
                + safe(deviceId)
                + "|"
                + timestamp
                + "|"
                + safe(nonce);
        return sha256Hex(input);
    }

    private static String validateWebhookUrl(String webhook) {
        if (TextUtils.isEmpty(webhook)) {
            return "请先填写反馈 Webhook 地址。";
        }
        try {
            URL url = new URL(webhook);
            String protocol = safe(url.getProtocol()).toLowerCase(Locale.ROOT);
            if (!"https".equals(protocol)) {
                return "反馈地址只支持 https。";
            }
            if (TextUtils.isEmpty(url.getHost())) {
                return "反馈地址缺少主机名。";
            }
            return "";
        } catch (Exception ignored) {
            return "反馈地址格式不正确。";
        }
    }

    private static String clipAndSanitize(String text, int maxChars) {
        String sanitized = sanitizeSensitiveText(text);
        if (TextUtils.isEmpty(sanitized)) {
            return "";
        }
        String trimmed = sanitized.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxChars)) + "...";
    }

    private static String sanitizeSensitiveText(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String sanitized = text;
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = KEY_VALUE_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
        return sanitized;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    static final class SendResult {
        final boolean success;
        final String message;
        final int statusCode;

        private SendResult(boolean success, String message, int statusCode) {
            this.success = success;
            this.message = message;
            this.statusCode = statusCode;
        }

        static SendResult success(String message, int statusCode) {
            return new SendResult(true, message, statusCode);
        }

        static SendResult failure(String message, int statusCode) {
            return new SendResult(false, message, statusCode);
        }
    }
}
