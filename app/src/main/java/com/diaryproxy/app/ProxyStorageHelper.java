package com.diaryproxy.app;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ProxyStorageHelper {

    private ProxyStorageHelper() {
    }

    static String getHistoryRootDisplayPath(Context context) {
        return getHistoryRootDir(context).getAbsolutePath();
    }

    static String getDebugPromptRootDisplayPath(Context context) {
        return getDebugPromptRootDir(context).getAbsolutePath();
    }

    static String getLastDiaryRequestDisplayPath(Context context) {
        return getLastDiaryRequestFile(context).getAbsolutePath();
    }

    static synchronized void appendHistoryRecord(Context context, HistoryRecord record) throws IOException {
        if (context == null || record == null) {
            return;
        }
        File categoryDir = new File(getHistoryRootDir(context), record.diaryRecord ? "diary" : "chat");
        ensureDir(categoryDir);
        String day = formatTime(record.occurredAtMs, "yyyy-MM-dd");
        File target = new File(categoryDir, day + ".txt");
        writeText(target, formatHistoryRecord(record), true);
    }

    static synchronized void saveLastDiaryRequest(Context context, CachedDiaryRequest request) throws IOException {
        if (context == null || request == null || TextUtils.isEmpty(request.requestBody)) {
            return;
        }
        JSONObject root = new JSONObject();
        try {
            root.put("savedAtMs", request.savedAtMs);
            root.put("uri", safe(request.uri));
            root.put("requestBody", request.requestBody);
            root.put("requestKind", safe(request.requestKind));
            root.put("diaryMatched", request.diaryMatched);
            root.put("diaryType", safe(request.diaryType));
            root.put("model", safe(request.model));
            root.put("conversationPreview", safe(request.conversationPreview));
        } catch (Exception ignored) {
        }
        writeText(getLastDiaryRequestFile(context), root.toString(), false);
    }

    static synchronized void appendDebugPromptRecord(Context context, DebugPromptRecord record) throws IOException {
        if (context == null || record == null || TextUtils.isEmpty(record.finalRequestBody)) {
            return;
        }
        File debugDir = getDebugPromptRootDir(context);
        ensureDir(debugDir);
        String day = formatTime(record.occurredAtMs, "yyyy-MM-dd");
        File target = new File(debugDir, day + ".txt");
        writeText(target, formatDebugPromptRecord(record), true);
    }

    static synchronized CachedDiaryRequest loadLastDiaryRequest(Context context) throws IOException {
        File target = getLastDiaryRequestFile(context);
        if (context == null || !target.isFile()) {
            return null;
        }
        String text = readText(target);
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(text);
            CachedDiaryRequest request = new CachedDiaryRequest();
            request.savedAtMs = root.optLong("savedAtMs", 0L);
            request.uri = root.optString("uri", "");
            request.requestBody = root.optString("requestBody", "");
            request.requestKind = root.optString("requestKind", "");
            request.diaryMatched = root.optBoolean("diaryMatched", false);
            request.diaryType = root.optString("diaryType", "");
            request.model = root.optString("model", "");
            request.conversationPreview = root.optString("conversationPreview", "");
            return TextUtils.isEmpty(request.requestBody) ? null : request;
        } catch (Exception error) {
            throw new IOException("最近一次日记请求缓存已损坏：" + error.getMessage(), error);
        }
    }

    private static File getHistoryRootDir(Context context) {
        return new File(getAppDataDir(context), "history");
    }

    private static File getDebugPromptRootDir(Context context) {
        return new File(getAppDataDir(context), "debug");
    }

    private static File getLastDiaryRequestFile(Context context) {
        return new File(getAppDataDir(context), "last_diary_request.json");
    }

    private static File getAppDataDir(Context context) {
        File root = context == null ? null : context.getExternalFilesDir(null);
        if (root == null && context != null) {
            root = context.getFilesDir();
        }
        if (root == null) {
            root = new File(".");
        }
        return root;
    }

    private static String formatHistoryRecord(HistoryRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("==================================================\n");
        builder.append("时间：").append(formatTime(record.occurredAtMs, "yyyy-MM-dd HH:mm:ss")).append('\n');
        builder.append('\n');
        if (record.diaryRecord) {
            builder.append(nonEmpty(record.responseText, "（空）")).append('\n');
            builder.append('\n');
            return builder.toString();
        }
        if ("interactive-story".equals(record.requestKind)) {
            builder.append("【互动小剧场】\n\n");
        }
        String userText = nonEmpty(record.userText, "").trim();
        if (!TextUtils.isEmpty(userText)) {
            builder.append("用户：\n").append(userText).append('\n').append('\n');
        }
        builder.append("yuki回复：\n").append(nonEmpty(record.responseText, "（空）")).append('\n');
        builder.append('\n');
        return builder.toString();
    }

    private static String formatDebugPromptRecord(DebugPromptRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("==================================================\n");
        builder.append("时间：").append(formatTime(record.occurredAtMs, "yyyy-MM-dd HH:mm:ss")).append('\n');
        builder.append("请求类型：").append(nonEmpty(record.requestKind, "unknown")).append('\n');
        if (!TextUtils.isEmpty(record.diaryType) && !"none".equals(record.diaryType)) {
            builder.append("日记类型：").append(record.diaryType).append('\n');
        }
        builder.append("协议适配：").append(nonEmpty(record.adapterPreset, "unknown")).append('\n');
        builder.append("模型：").append(nonEmpty(record.model, "unknown")).append('\n');
        builder.append("人设替换：").append(record.personaApplied ? "已应用" : "未应用").append('\n');
        if (!TextUtils.isEmpty(record.personaTier)) {
            builder.append("人设档位：").append(record.personaTier).append('\n');
        }
        if (!TextUtils.isEmpty(record.personaReason)) {
            builder.append("人设原因：").append(record.personaReason).append('\n');
        }
        if (!TextUtils.isEmpty(record.upstreamPath)) {
            builder.append("上游路径：").append(record.upstreamPath).append('\n');
        }
        if (!TextUtils.isEmpty(record.systemText)) {
            builder.append('\n').append("改写后 system：\n");
            builder.append(record.systemText.trim()).append('\n');
        }
        if (!TextUtils.isEmpty(record.userText)) {
            builder.append('\n').append("改写后 user：\n");
            builder.append(record.userText.trim()).append('\n');
        }
        builder.append('\n').append("最终发送请求体：\n");
        builder.append(record.finalRequestBody.trim()).append('\n').append('\n');
        return builder.toString();
    }

    private static String readText(File target) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(target);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(File target, String text, boolean append) throws IOException {
        ensureDir(target.getParentFile());
        try (FileOutputStream outputStream = new FileOutputStream(target, append);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(nonEmpty(text, ""));
        }
    }

    private static void ensureDir(File dir) throws IOException {
        if (dir == null || dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        }
    }

    private static String formatTime(long timeMs, String pattern) {
        long safeTime = timeMs > 0 ? timeMs : System.currentTimeMillis();
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(safeTime));
    }

    private static String nonEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class CachedDiaryRequest {
        long savedAtMs;
        String uri = "";
        String requestBody = "";
        String requestKind = "";
        boolean diaryMatched = false;
        String diaryType = "";
        String model = "";
        String conversationPreview = "";
    }

    static final class HistoryRecord {
        long occurredAtMs;
        boolean success;
        boolean replayed;
        boolean diaryRecord;
        String requestKind = "";
        String diaryType = "";
        String model = "";
        String userText = "";
        String originalConversation = "";
        String forwardedConversation = "";
        String responseText = "";
        String errorMessage = "";
        boolean personaApplied;
        String personaTier = "";
        boolean tokenApplied;
        String diaryTemplateVars = "";
        int statusCode;
    }

    static final class DebugPromptRecord {
        long occurredAtMs;
        String requestKind = "";
        String diaryType = "";
        String adapterPreset = "";
        String model = "";
        boolean personaApplied;
        String personaTier = "";
        String personaReason = "";
        String upstreamPath = "";
        String systemText = "";
        String userText = "";
        String finalRequestBody = "";
    }
}
