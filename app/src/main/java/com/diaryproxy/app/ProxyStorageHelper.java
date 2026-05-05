package com.diaryproxy.app;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

    static String getAttachmentDraftDirDisplayPath(Context context) {
        return getAttachmentDraftDir(context).getAbsolutePath();
    }

    /**
     * 把附件 byte[] 持久化到 cache 子目录，返回 AttachmentRef。同步以避免 id 冲突。
     *
     * <p>v1.5.6+：PSH-4 — charCount 由 caller 在后台线程预先算好后传入，
     * 不再在本方法内调用 {@link PdfTextExtractor#decodeAttachmentText}。
     * 这样可以避免主线程被 PDF 解码阻塞数百毫秒到数秒（低端机解 8MB PDF
     * 可达 1-3s，会触发 ANR 风险）。</p>
     *
     * @param charCount 字符数；图片或解码失败时传 -1（UI 不显示字数）。
     */
    static synchronized AttachmentRef appendAttachmentDraft(
            Context context,
            String mime,
            String displayName,
            byte[] bytes,
            int charCount) throws IOException {
        if (context == null || bytes == null || bytes.length == 0) {
            throw new IOException("附件数据为空");
        }
        File dir = getAttachmentDraftDir(context);
        ensureDir(dir);
        // v1.5.6+：PSH-1 — 不再用 Math.abs(displayName.hashCode())：
        // 当 hashCode 恰好为 Integer.MIN_VALUE 时 Math.abs 仍返回负数，导致 id 出现负号
        // （文件名虽然合法但与正例不一致，下游可能因此意外）。改用 & 0x7FFFFFFF 强制取正。
        String id = "att_" + System.currentTimeMillis() + "_"
                + ((displayName == null ? 0 : displayName.hashCode()) & 0x7FFFFFFF);
        File blob = new File(dir, id + ".bin");
        try (FileOutputStream out = new FileOutputStream(blob)) {
            out.write(bytes);
        }
        AttachmentRef ref = new AttachmentRef();
        ref.id = id;
        ref.displayName = nonEmpty(displayName, "未命名");
        ref.mime = nonEmpty(mime, "application/octet-stream");
        ref.byteSize = bytes.length;
        ref.localPath = blob.getAbsolutePath();
        ref.createdAtMs = System.currentTimeMillis();
        // v1.5.5+：DPS-8 — 非图片附件计算字数；图片保持 -1，UI 不显示字数。
        // v1.5.6+：PSH-4 — 字符数改由 caller 预先在后台线程算好后传入；图片场景 caller 应传 -1。
        if (AttachmentSupport.isImageMime(ref.mime)) {
            ref.charCount = -1;
        } else {
            ref.charCount = charCount;
        }
        File meta = new File(dir, id + ".meta.json");
        try {
            JSONObject root = new JSONObject();
            root.put("id", ref.id);
            root.put("displayName", ref.displayName);
            root.put("mime", ref.mime);
            root.put("byteSize", ref.byteSize);
            root.put("createdAtMs", ref.createdAtMs);
            root.put("charCount", ref.charCount);
            writeText(meta, root.toString(), false);
        } catch (Exception error) {
            // meta 写失败不阻断（blob 已存）
        }
        return ref;
    }

    /** 列出当前 draft 目录下所有附件的元数据（不读取 blob 内容）。损坏项跳过。 */
    static synchronized List<AttachmentRef> listAttachmentDrafts(Context context) {
        List<AttachmentRef> list = new ArrayList<>();
        if (context == null) {
            return list;
        }
        File dir = getAttachmentDraftDir(context);
        if (!dir.isDirectory()) {
            return list;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return list;
        }
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(".meta.json")) {
                continue;
            }
            try {
                String text = readText(file);
                JSONObject root = new JSONObject(text);
                AttachmentRef ref = new AttachmentRef();
                ref.id = root.optString("id", "");
                ref.displayName = root.optString("displayName", "");
                ref.mime = root.optString("mime", "");
                ref.byteSize = root.optLong("byteSize", 0L);
                ref.createdAtMs = root.optLong("createdAtMs", 0L);
                ref.charCount = root.optInt("charCount", -1);
                ref.localPath = new File(dir, ref.id + ".bin").getAbsolutePath();
                if (TextUtils.isEmpty(ref.id)) {
                    continue;
                }
                if (!new File(ref.localPath).isFile()) {
                    continue;
                }
                list.add(ref);
            } catch (Exception ignored) {
            }
        }
        // v1.5.6+：PSH-2 — 同毫秒内连续 appendAttachmentDraft 两个附件会出现 createdAtMs 相同。
        // 之前 comparator 只比 createdAtMs，相同时序无定义可能 frame-by-frame 颠倒，导致 UI
        // 上图片顺序与用户实际选择顺序不一致。次序加 id 字符串比较：id 含 timestamp 前缀
        // + displayName.hashCode 后缀，"同毫秒不同附件"几乎必然有不同 hashCode，可稳定排序；
        // 即使 hashCode 也碰巧相等（同名连传两次），id 字符串至少保证稳定（不会随每次 sort 翻转）。
        Collections.sort(list, (a, b) -> {
            int byTime = Long.compare(a.createdAtMs, b.createdAtMs);
            if (byTime != 0) return byTime;
            return nonEmpty(a.id, "").compareTo(nonEmpty(b.id, ""));
        });
        return list;
    }

    /** 读取 attachment blob 的字节内容（用于 base64 编码、上传等）。 */
    static synchronized byte[] readAttachmentBytes(AttachmentRef ref) throws IOException {
        if (ref == null || TextUtils.isEmpty(ref.localPath)) {
            throw new IOException("附件路径为空");
        }
        File file = new File(ref.localPath);
        if (!file.isFile()) {
            throw new IOException("附件已不存在：" + ref.localPath);
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** 删除指定 id 的 draft（blob + meta）。 */
    static synchronized boolean removeAttachmentDraft(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) {
            return false;
        }
        File dir = getAttachmentDraftDir(context);
        boolean a = new File(dir, id + ".bin").delete();
        boolean b = new File(dir, id + ".meta.json").delete();
        return a || b;
    }

    /** 清空所有 draft 附件（用户主动点击 / 发送成功后） */
    static synchronized void clearAttachmentDrafts(Context context) {
        if (context == null) {
            return;
        }
        File dir = getAttachmentDraftDir(context);
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile()) {
                file.delete();
            }
        }
    }

    static synchronized void clearAttachmentDraftsByIds(Context context, List<String> ids) {
        if (context == null || ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            removeAttachmentDraft(context, id);
        }
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

    private static File getAttachmentDraftDir(Context context) {
        File cacheRoot = context == null ? null : context.getCacheDir();
        if (cacheRoot == null) {
            cacheRoot = getAppDataDir(context);
        }
        return new File(new File(cacheRoot, "attachments"), "draft");
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
        // v1.5.6+：debug 文件支持两个详细程度
        //   - verbose（默认）：保留所有改写前 / 改写后 system + user + 请求体；
        //   - simplified：仅头部元信息 + 改写前请求体 + 最终发送请求体 + 工具调用，便于直接对比"输入 vs 实际转发"。
        boolean simplified = "simplified".equalsIgnoreCase(safeNonNull(record.detailLevel));
        builder.append("==================================================\n");
        builder.append("时间：").append(formatTime(record.occurredAtMs, "yyyy-MM-dd HH:mm:ss")).append('\n');
        if (simplified) {
            builder.append("（精简模式：已隐藏改写前/改写后 system + user 块）\n");
        }
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
        if (!TextUtils.isEmpty(record.adapterNote)) {
            builder.append("处理备注：").append(record.adapterNote).append('\n');
        }
        if (!TextUtils.isEmpty(record.attachmentSummary)) {
            builder.append("附件处理：").append(record.attachmentSummary).append('\n');
        }
        if (!TextUtils.isEmpty(record.upstreamPath)) {
            builder.append("上游路径：").append(record.upstreamPath).append('\n');
        }
        // verbose 模式：保留改写前 system / user 文本块
        if (!simplified && !TextUtils.isEmpty(record.originalSystemText)) {
            builder.append('\n').append("改写前 system：\n");
            builder.append(record.originalSystemText.trim()).append('\n');
        }
        if (!simplified && !TextUtils.isEmpty(record.originalUserText)) {
            builder.append('\n').append("改写前 user：\n");
            builder.append(record.originalUserText.trim()).append('\n');
        }
        // 改写前请求体在两种模式下都保留（便于对比"输入 vs 实际转发"）
        if (!TextUtils.isEmpty(record.originalRequestBody)) {
            builder.append('\n').append("改写前请求体：\n");
            builder.append(record.originalRequestBody.trim()).append('\n');
        }
        // verbose 模式：保留改写后 system / user 文本块
        if (!simplified && !TextUtils.isEmpty(record.systemText)) {
            builder.append('\n').append("改写后 system：\n");
            builder.append(record.systemText.trim()).append('\n');
        }
        if (!simplified && !TextUtils.isEmpty(record.userText)) {
            builder.append('\n').append("改写后 user：\n");
            builder.append(record.userText.trim()).append('\n');
        }
        builder.append('\n').append("最终发送请求体：\n");
        String summarizedBody = summarizeMediaInJson(record.finalRequestBody);
        if (!TextUtils.isEmpty(record.finalRequestBody)
                && !TextUtils.equals(summarizedBody, record.finalRequestBody)) {
            builder.append("（图片/文件 base64 已折叠；实际转发体仍保留完整 base64）\n");
            builder.append(summarizedBody.trim()).append('\n');
        } else {
            builder.append(nonEmpty(record.finalRequestBody, "").trim()).append('\n');
        }
        appendToolCallTurnsSection(builder, record.toolCallTurns);
        builder.append('\n');
        return builder.toString();
    }

    private static String safeNonNull(String value) {
        return value == null ? "" : value;
    }

    private static void appendToolCallTurnsSection(StringBuilder builder, List<DebugToolCallTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        builder.append('\n').append("工具调用记录：\n");
        for (DebugToolCallTurn turn : turns) {
            if (turn == null) {
                continue;
            }
            builder.append("[第 ").append(turn.depth).append(" 轮]\n");
            if (turn.entries == null || turn.entries.isEmpty()) {
                builder.append("  （本轮无 tool_call）\n");
                continue;
            }
            for (int i = 0; i < turn.entries.size(); i++) {
                DebugToolCallEntry entry = turn.entries.get(i);
                if (entry == null) {
                    continue;
                }
                builder.append("  调用 ").append(i + 1)
                        .append("：name=").append(nonEmpty(entry.name, "?"));
                if (!TextUtils.isEmpty(entry.id)) {
                    builder.append(", id=").append(entry.id);
                }
                builder.append(", 由代理处理=").append(entry.proxyHandled ? "是" : "否").append('\n');
                builder.append("    参数：\n");
                String args = TextUtils.isEmpty(entry.argumentsJson)
                        ? "(空)"
                        : prettyJsonOrRaw(summarizeMediaInJson(entry.argumentsJson));
                builder.append(indentLines(args, "      ")).append('\n');
                if (entry.proxyHandled) {
                    builder.append("    结果：\n");
                    String result = TextUtils.isEmpty(entry.resultText) ? "(空)" : entry.resultText.trim();
                    builder.append(indentLines(result, "      ")).append('\n');
                    if (!TextUtils.isEmpty(entry.toolDebugRaw)) {
                        builder.append("    原始响应：\n");
                        builder.append(indentLines(formatToolDebugRaw(entry.toolDebugRaw), "      ")).append('\n');
                    }
                } else {
                    builder.append("    结果：(代理未处理，交由游戏侧)\n");
                }
            }
        }
    }

    private static String prettyJsonOrRaw(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        try {
            Object root = new JSONTokener(text).nextValue();
            if (root instanceof JSONObject) {
                return ((JSONObject) root).toString(2);
            }
            if (root instanceof JSONArray) {
                return ((JSONArray) root).toString(2);
            }
        } catch (Exception ignored) {
        }
        return text.trim();
    }

    /**
     * 美化"工具原始响应"段，便于阅读：
     * 1. 把 "response body:\n..." 那段从 raw 中切出，单独走 prettyJsonOrRaw 美化；
     * 2. 整体超过 8KB 时尾部截断并标注剩余字符数，避免调试 dump 文件被一次工具调用撑爆。
     */
    private static final int TOOL_DEBUG_RAW_MAX_CHARS = 8192;

    private static String formatToolDebugRaw(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        String formatted = raw;
        int marker = raw.indexOf("response body:\n");
        if (marker >= 0) {
            String header = raw.substring(0, marker + "response body:".length());
            String body = raw.substring(marker + "response body:\n".length());
            String pretty = prettyJsonOrRaw(body);
            formatted = header + "\n" + pretty;
        }
        if (formatted.length() > TOOL_DEBUG_RAW_MAX_CHARS) {
            int omitted = formatted.length() - TOOL_DEBUG_RAW_MAX_CHARS;
            formatted = formatted.substring(0, TOOL_DEBUG_RAW_MAX_CHARS)
                    + "\n…（已截断 " + omitted + " 字符）";
        }
        return formatted;
    }

    private static String indentLines(String text, String prefix) {
        if (TextUtils.isEmpty(text)) {
            return prefix;
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + lines.length * prefix.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(lines[i]);
        }
        return sb.toString();
    }

    private static String summarizeMediaInJson(String jsonText) {
        if (TextUtils.isEmpty(jsonText)) {
            return "";
        }
        try {
            Object root = new JSONTokener(jsonText).nextValue();
            Object summarized = summarizeJsonValue(root, "");
            if (summarized instanceof JSONObject) {
                return ((JSONObject) summarized).toString(2);
            }
            if (summarized instanceof JSONArray) {
                return ((JSONArray) summarized).toString(2);
            }
        } catch (Exception ignored) {
        }
        return jsonText;
    }

    private static Object summarizeJsonValue(Object value, String key) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject target = new JSONObject();
            JSONArray names = source.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String name = names.optString(i, "");
                target.put(name, summarizeJsonValue(source.opt(name), name));
            }
            return target;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray target = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                target.put(summarizeJsonValue(source.opt(i), key));
            }
            return target;
        }
        if (value instanceof String) {
            return summarizePossiblyMediaString((String) value, key);
        }
        return value;
    }

    private static String summarizePossiblyMediaString(String value, String key) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (value.startsWith("data:image/")) {
            int comma = value.indexOf(',');
            String header = comma > 0 ? value.substring(0, comma) : "data:image/*;base64";
            int dataLength = comma > 0 ? value.length() - comma - 1 : value.length();
            return header + ",<base64 " + dataLength + " chars>";
        }
        if (("url".equals(lowerKey) || "image_url".equals(lowerKey) || "data".equals(lowerKey))
                && value.length() > 512
                && looksLikeBase64(value)) {
            return "<base64 " + value.length() + " chars>";
        }
        return value;
    }

    private static boolean looksLikeBase64(String value) {
        int checked = 0;
        for (int i = 0; i < value.length() && checked < 256; i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            checked++;
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+'
                    || c == '/'
                    || c == '='
                    || c == '-'
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        return checked >= 64;
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

    /** v1.5.0：附件草稿元数据。byte[] 不放在内存里，按需用 readAttachmentBytes 读。 */
    static final class AttachmentRef {
        String id = "";
        String displayName = "";
        String mime = "";
        long byteSize;
        String localPath = "";
        long createdAtMs;
        /**
         * v1.5.5+：DPS-8 — 文档附件解码后的字符数（≈"字数"）。
         * 图片附件保持 -1（不显示）。仅对 PDF / 文本类附件在 appendAttachmentDraft 时计算一次。
         */
        int charCount = -1;
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
        String adapterNote = "";
        String attachmentSummary = "";
        String upstreamPath = "";
        String originalSystemText = "";
        String originalUserText = "";
        String originalRequestBody = "";
        String systemText = "";
        String userText = "";
        String finalRequestBody = "";
        /** 每一轮模型返回的 tool_calls + 由代理执行得到的工具结果。允许为 null/空。 */
        List<DebugToolCallTurn> toolCallTurns = new ArrayList<>();
        /**
         * v1.5.6+：调试提示词导出文件的详细程度。
         * <ul>
         *   <li>"verbose"（默认）：完整保留改写前 system / user / 请求体 +
         *       改写后 system / user + 最终发送请求体 + 工具调用记录。</li>
         *   <li>"simplified"：去掉「改写前 system」「改写前 user」「改写后 system」「改写后 user」
         *       4 个块；保留头部元信息 + 改写前请求体 + 最终发送请求体 + 工具调用记录，
         *       便于"对比改写前后请求体"。</li>
         * </ul>
         * 由 caller（DiaryProxyServer.executeChatRequest）从 cfg.debugPromptDetailLevel 写入。
         */
        String detailLevel = "verbose";
    }

    /** 一轮"模型 → 工具 → 模型"的循环。depth 是 1-based 序号。 */
    static final class DebugToolCallTurn {
        int depth;
        List<DebugToolCallEntry> entries = new ArrayList<>();
    }

    /** 单个工具调用：来自模型的入参 + 代理执行结果（仅 proxyHandled 才有结果）。 */
    static final class DebugToolCallEntry {
        String id = "";
        String name = "";
        String argumentsJson = "";
        boolean proxyHandled;
        String resultText = "";
        /**
         * 工具内部的调试附加信息（仅 proxyHandled 才填）。
         * 例如 describe_image：副模型的原始 HTTP 响应（status + URL + body）。
         * 例如 web_search：可留空（搜索引擎结果已在 resultText）。
         */
        String toolDebugRaw = "";
    }
}
