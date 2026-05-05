package com.diaryproxy.app;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * v1.5.0：附件多模态决议 + content block 构造。
 * <p>
 * 不持有状态：所有方法 pure；用 ProxyStorageHelper.AttachmentRef 作为输入。
 * 调用方在 DiaryProxyServer / MainActivity 内自己拿 attachments / cfg 传进来。
 */
final class AttachmentSupport {

    private AttachmentSupport() {
    }

    /** 哪些 requestKind 允许附件随主请求发送（用户在 plan 里决定的 4 类）。 */
    static final List<String> WHITELISTED_REQUEST_KINDS = Collections.unmodifiableList(Arrays.asList(
            "normal-chat",
            "interactive-story",
            "story-like",
            "meta-prompt"
    ));

    /** 单图软警告阈值（user 提示但允许继续）。 */
    static final long SIZE_SOFT_LIMIT_IMAGE_BYTES = 8L * 1024L * 1024L;
    /** 单次请求所有附件合计硬上限（超过直接拒绝）。 */
    static final long SIZE_HARD_LIMIT_TOTAL_BYTES = 32L * 1024L * 1024L;
    /** 单文档（非图片）软警告阈值。 */
    static final long SIZE_SOFT_LIMIT_DOC_BYTES = 256L * 1024L;

    static final class AttachmentDecision {
        boolean willInject;            // 本次是否会真发送
        String dropReason = "";        // 不发送的原因（白名单不命中 / 全部超限 / 副模型失败 等）
        String sizeWarning = "";       // 软警告文本（即使 willInject=true 也可能有）
        boolean blockHard;             // 总体积超硬限 → 阻断保存按钮
        List<ProxyStorageHelper.AttachmentRef> accepted = new ArrayList<>();
        List<ProxyStorageHelper.AttachmentRef> dropped = new ArrayList<>();
    }

    /**
     * 决议本次请求是否注入附件。
     */
    static AttachmentDecision decide(
            String requestKind,
            List<ProxyStorageHelper.AttachmentRef> all,
            ProxyConfig cfg) {
        AttachmentDecision result = new AttachmentDecision();
        if (all == null || all.isEmpty()) {
            result.willInject = false;
            return result;
        }
        if (!WHITELISTED_REQUEST_KINDS.contains(safe(requestKind))) {
            result.willInject = false;
            result.dropReason = "当前请求是【" + describeRequestKind(requestKind) + "】，本次未附带 " + all.size() + " 项附件";
            result.dropped.addAll(all);
            return result;
        }
        long total = 0L;
        for (ProxyStorageHelper.AttachmentRef ref : all) {
            if (ref == null) continue;
            total += ref.byteSize;
        }
        if (total > SIZE_HARD_LIMIT_TOTAL_BYTES) {
            result.willInject = false;
            result.blockHard = true;
            result.dropReason = "附件总大小 " + formatSize(total) + " 超过 "
                    + formatSize(SIZE_HARD_LIMIT_TOTAL_BYTES) + " 上限";
            result.dropped.addAll(all);
            return result;
        }
        StringBuilder warns = new StringBuilder();
        for (ProxyStorageHelper.AttachmentRef ref : all) {
            if (ref == null) continue;
            if (isImageMime(ref.mime) && ref.byteSize > SIZE_SOFT_LIMIT_IMAGE_BYTES) {
                if (warns.length() > 0) warns.append("；");
                warns.append("「").append(ref.displayName).append("」")
                        .append(formatSize(ref.byteSize)).append(" 较大，部分上游可能拒绝");
            } else if (!isImageMime(ref.mime) && ref.byteSize > SIZE_SOFT_LIMIT_DOC_BYTES) {
                if (warns.length() > 0) warns.append("；");
                warns.append("文档「").append(ref.displayName).append("」")
                        .append(formatSize(ref.byteSize)).append(" 偏大");
            }
            result.accepted.add(ref);
        }
        result.willInject = !result.accepted.isEmpty();
        result.sizeWarning = warns.toString();
        return result;
    }

    /**
     * 判断当前 adapter 是否能在协议层承载图片。
     * v1.5.1+：去掉了 multimodalCapability 开关——是否要直传图片改由"副模型策略"承担：
     *   - captionStrategy=off：直接走 adapter 多模态发图
     *   - captionStrategy=inject：副模型 caption 后注入文字，主请求不带图
     *   - captionStrategy=tool：注入 describe_image tool，主模型按需调
     */
    static boolean isMultimodalUpstream(String adapterPreset, ProxyConfig.ProviderProfile profile, String modelName) {
        return defaultMultimodalForAdapter(adapterPreset, modelName);
    }

    static boolean defaultMultimodalForAdapter(String adapterPreset, String modelName) {
        if (TextUtils.isEmpty(adapterPreset)) return false;
        switch (adapterPreset) {
            case ProxyConfig.ADAPTER_OPENAI_COMPATIBLE:
            case ProxyConfig.ADAPTER_CLAUDE_MESSAGES:
            case ProxyConfig.ADAPTER_OPENAI_RESPONSES:
            case ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT:
                return true;
            case ProxyConfig.ADAPTER_GENERIC_CUSTOM:
            default:
                return false;
        }
    }

    static boolean isImageMime(String mime) {
        return safe(mime).toLowerCase(Locale.ROOT).startsWith("image/");
    }

    static String describeRequestKind(String requestKind) {
        if (TextUtils.isEmpty(requestKind)) return "未知请求";
        switch (requestKind) {
            case "normal-diary":
                return "日记生成";
            case "holiday-diary":
                return "节日日记";
            case "memory-extract":
                return "长期记忆抽取";
            case "turtle-soup-judge":
                return "海龟汤裁判";
            case "health-check":
                return "上游健康检查";
            case "preset-reply":
                return "预设回复";
            case "pass-through":
                return "兜底透传";
            default:
                return requestKind;
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + "B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    // v1.5.4+：AS-1 — 已移除死代码 buildOpenAiImageBlocks / buildClaudeImageBlocks /
    // buildGeminiImageParts / buildOpenAiResponsesImageBlocks。
    // DiaryProxyServer 用 convertContentTo* 系列内联实现转 image block，从未调用本文件的方法。
    // 下方 buildDocumentTextBlock（文档附件转纯文本）仍在使用，保留。

    /**
     * 文档附件（pdf/txt/md/csv）转纯文本注入到 user message 末尾。
     * 返回形如 "\n\n[附件: foo.pdf]\n<文档文本截断>"。失败返回空串。
     */
    static String buildDocumentTextBlock(List<ProxyStorageHelper.AttachmentRef> refs, BlobReader reader) {
        if (refs == null || refs.isEmpty() || reader == null) return "";
        StringBuilder builder = new StringBuilder();
        for (ProxyStorageHelper.AttachmentRef ref : refs) {
            if (ref == null || isImageMime(ref.mime)) continue;
            try {
                String text = reader.readText(ref);
                if (TextUtils.isEmpty(text)) continue;
                if (builder.length() == 0) builder.append("\n");
                builder.append("\n[附件: ").append(safe(ref.displayName)).append("]\n");
                builder.append(text.length() > 4096 ? text.substring(0, 4096) + "\n…(已截断)" : text);
            } catch (Exception ignored) {
            }
        }
        return builder.toString();
    }

    /** Caller injects this so we can read bytes / text without binding to Context here. */
    interface BlobReader {
        String readBase64(ProxyStorageHelper.AttachmentRef ref) throws Exception;
        String readText(ProxyStorageHelper.AttachmentRef ref) throws Exception;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
