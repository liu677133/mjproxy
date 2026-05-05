package com.diaryproxy.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class DiaryProxyServer extends NanoHTTPD {

    private static final Pattern DIALOGUE_LINE_PATTERN = Pattern.compile("^[^\\n:：]{1,24}[：:]\\s*.+$", Pattern.MULTILINE);
    private static final Pattern THINK_PATTERN = Pattern.compile("(?is)<think>[\\s\\S]*?</think>|<thinking>[\\s\\S]*?</thinking>|```thinking[\\s\\S]*?```");
    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    private static final Pattern RESTRICTION_LINE_PATTERN = Pattern.compile("^[\\t \\u3000]*[\\[【]限制[\\]】].*$");
    private static final Pattern CURRENT_TIME_MARKER_PATTERN = Pattern.compile("(?:当前时间|系统时间)\\s*[：:]\\s*\\d{4}年\\d{1,2}月\\d{1,2}日(?:\\s*星期[一二三四五六日天])?(?:\\s*(?:凌晨|早上|上午|中午|下午|傍晚|晚上))?\\s*\\d{1,2}:\\d{2}(?:\\s*\\(农历[^()\\n\\r]*\\))?");
    private static final Pattern BRACKET_DATETIME_MARKER_PATTERN = Pattern.compile("【\\s*\\d{4}年\\d{1,2}月\\d{1,2}日\\s+\\d{1,2}:\\d{2}\\s*】");
    private static final Pattern BRACKET_DIARY_TIME_MARKER_PATTERN = Pattern.compile("【\\s*日记书写时间为\\s*\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}点(?:\\d{1,2}分)?\\s*】");
    private static final Pattern DIARY_CONVERSATION_MARKER_LINE_PATTERN =
            Pattern.compile("(?im)^\\s*[\\[【]?\\s*(?:本次)?对话记录\\s*[\\]】]?\\s*[：:]?\\s*$");
    private static final Pattern FORCE_WEB_SEARCH_PATTERN = Pattern.compile(
            "(?iu)(新闻|资讯|热搜|最新|实时|网上|网络上|互联网|联网|搜索|搜一下|查一下|查查|百度一下|天气|汇率|股价|股票|价格|国际.{0,8}形[势式]|国际.{0,8}局势|current\\s+(news|events)|latest\\s+(news|updates)|today'?s\\s+news|news|weather|stock|price)"
    );
    private static final Pattern MEMORY_EXTRACT_PERSONALITY_PATTERN =
            Pattern.compile("(?is)你的性格特征\\s*[：:]\\s*(.*?)\\n\\s*\\n?规则\\s*[：:]");
    private static final Pattern MEMORY_EXTRACT_CONVERSATION_PATTERN =
            Pattern.compile("(?is)对话记录\\s*[：:]\\s*(.*?)\\n\\s*\\n?只输出\\s*json\\s*数组");
    private static final Pattern MEMORY_EXTRACT_GENDER_TERM_PATTERN =
            Pattern.compile("(?im)主语固定\\s*[：:]\\s*玩家用\\s*[\"“]?([^\"”'’，,\\n]+)[\"”']?\\s*[，,]\\s*我用");
    private static final long REQUEST_WAKE_TIMEOUT_MS = 180000L;
    private static final long RECENT_CHAT_CONVERSATION_MAX_AGE_MS = 10 * 60 * 1000L;

    private final AtomicLong requestCounter = new AtomicLong(1L);
    private final Context appContext;
    private final ProxyLogSink sink;
    private volatile ProxyConfig config;
    // v1.5.6+：DPS-18 — 提为 static 共享。replayLastDiaryRequest 会 new 一个 helper 实例，
    // 旧版作为 instance 字段会导致重发时 conversation 缓存为空，日记 prompt 渲染走 missing 分支。
    // 提为进程内全局共享后，重发实例可读到首次写入的最近聊天记录，模板渲染与首次一致。
    private static volatile String recentChatConversation = "";
    private static volatile long recentChatConversationAtMs = 0L;

    public DiaryProxyServer(Context context, ProxyConfig config, ProxyLogSink sink) {
        super("127.0.0.1", (config == null ? new ProxyConfig() : config).copy().port);
        this.appContext = context == null ? null : context.getApplicationContext();
        this.config = config == null ? new ProxyConfig().ensureDefaults() : config.copy();
        this.sink = sink == null ? line -> { } : sink;
    }

    public void updateConfig(ProxyConfig updated) {
        this.config = updated == null ? new ProxyConfig().ensureDefaults() : updated.copy();
    }

    @Override
    public Response serve(IHTTPSession session) {
        long reqId = requestCounter.getAndIncrement();
        ProxyConfig cfg = config.copy();
        String method = session.getMethod() == null ? "" : session.getMethod().name();
        String uri = ProxyConfig.normalizePath(session.getUri());
        boolean modelsRequest = matchesPath(uri, cfg.getListenModelsPathList()) && Method.GET.equals(session.getMethod());
        boolean chatRequest = matchesPath(uri, cfg.getListenChatPathList()) && Method.POST.equals(session.getMethod());
        log("proxy recv req=" + reqId + " method=" + method + " uri=" + uri + " adapter=" + cfg.adapterPreset);
        if (chatRequest) {
            log("proxy recv chat req=" + reqId + " method=" + method + " uri=" + uri);
        }

        if (Method.OPTIONS.equals(session.getMethod())) {
            return withCors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));
        }

        if ("/api/health".equals(uri)) {
            return withCors(jsonResponse(Response.Status.OK, healthJson(cfg)));
        }

        try {
            if (modelsRequest) {
                UpstreamResponse upstream = forwardModels(cfg);
                if (upstream.streaming) {
                    return withCors(jsonResponse(
                            toStatus(502),
                            errorJson("upstream_stream_unsupported", "上游 models 接口返回了流式响应，当前版本不支持。")
                    ));
                }
                return withCors(newFixedLengthResponse(
                        toStatus(upstream.status),
                        upstream.contentType,
                        normalizeModelsResponse(upstream.body, upstream.status, cfg)
                ));
            }
            if (chatRequest) {
                return handleChat(session, uri, reqId, cfg);
            }
        } catch (Exception error) {
            log("proxy error req=" + reqId + " " + error.getMessage());
            return withCors(jsonResponse(Response.Status.INTERNAL_ERROR, errorJson("proxy_error", error.getMessage())));
        }

        return withCors(jsonResponse(Response.Status.NOT_FOUND, errorJson("not_found", uri)));
    }

    private Response handleChat(IHTTPSession session, String uri, long reqId, ProxyConfig cfg) {
        try {
            long readStartedAt = SystemClock.elapsedRealtime();
            String requestBody = readRequestBodyTextSafely(session);
            long readBodyMs = SystemClock.elapsedRealtime() - readStartedAt;
            int requestBytes = TextUtils.isEmpty(requestBody) ? 0 : requestBody.getBytes(StandardCharsets.UTF_8).length;
            log("proxy in req=" + reqId
                    + " bodyBytes=" + requestBytes
                    + " readBodyMs=" + readBodyMs
                    + " env=" + clip(RuntimeDiagnostics.buildRuntimeSummary(appContext), 220));
            ChatExecutionResult result = executeChatRequest(requestBody, uri, reqId, cfg, false, readBodyMs);
            return withCors(newFixedLengthResponse(
                    toStatus(result.statusCode),
                    firstNonEmpty(result.contentType, "application/json; charset=UTF-8"),
                    firstNonEmpty(result.responseBody, errorJson("chat_error", "Empty response"))
            ));
        } catch (IOException error) {
            String body = errorJson("chat_read_error", error.getMessage());
            return withCors(jsonResponse(Response.Status.INTERNAL_ERROR, body));
        }
    }

    private ChatExecutionResult executeChatRequest(String requestBody, String uri, long reqId, ProxyConfig cfg, boolean replayed, long readBodyMs) {
        ChatExecutionResult result = new ChatExecutionResult();
        result.requestBody = requestBody;
        result.uri = firstNonEmpty(uri, "");
        result.replayed = replayed;
        result.readBodyMs = Math.max(0L, readBodyMs);
        result.requestBytes = TextUtils.isEmpty(requestBody) ? 0 : requestBody.getBytes(StandardCharsets.UTF_8).length;
        result.runtimeSummary = RuntimeDiagnostics.buildRuntimeSummary(appContext);
        result.statusCode = 500;
        result.contentType = "application/json; charset=UTF-8";
        long startedAt = SystemClock.elapsedRealtime();
        PowerManager.WakeLock requestWakeLock = RuntimeDiagnostics.acquireWakeLock(
                appContext,
                "DiaryProxy:chat:" + reqId,
                REQUEST_WAKE_TIMEOUT_MS
        );
        result.requestWakeLockHeld = RuntimeDiagnostics.isWakeLockHeld(requestWakeLock);

        try {
            if (TextUtils.isEmpty(requestBody)) {
                return finishChatExecution(result, cfg, reqId, "empty_body", "请求体为空。");
            }

            long parseStartedAt = SystemClock.elapsedRealtime();
            JSONObject payload = new JSONObject(requestBody);
            result.parseMs = SystemClock.elapsedRealtime() - parseStartedAt;

            long rewriteStartedAt = SystemClock.elapsedRealtime();
            result.originalConversation = buildHistoryConversationTranscript(resolveMessages(payload, cfg));
            result.decision = rewriteRequest(payload, cfg);
            if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(cfg.adapterPreset) && resolveMaxTokensValue(payload) <= 0) {
                result.decision.adapterNote = "claude_default_max_tokens=1024";
            }
            result.forwardedConversation = buildHistoryConversationTranscript(resolveMessages(payload, cfg));
            rememberRecentChatConversation(result.decision, result.originalConversation, result.forwardedConversation);
            result.rewriteMs = SystemClock.elapsedRealtime() - rewriteStartedAt;

            if (!replayed && result.decision != null && result.decision.diaryMatched) {
                cacheLastDiaryRequest(uri, requestBody, result.decision, result.originalConversation);
            }

            ForwardTrace forwardTrace = new ForwardTrace();
            result.forwardTrace = forwardTrace;
            // v1.5.0：附件注入（白名单 + 大小决议；不命中白名单则保留草稿，UI 提示原因）
            AttachmentInjectionResult attachInject = injectAttachmentsIntoPayload(payload, result.decision, cfg);
            result.attachmentInjection = attachInject;
            if (result.decision != null) {
                if (attachInject != null && !TextUtils.isEmpty(attachInject.adapterNote)) {
                    result.decision.adapterNote = appendReason(result.decision.adapterNote, attachInject.adapterNote);
                }
            }
            UpstreamRequest upstreamRequest = buildChatUpstreamRequest(payload, cfg);
            CustomFieldsApplyResult customFieldsResult = applyCustomRequestFieldsToUpstreamRequest(upstreamRequest, cfg, result.decision);
            upstreamRequest = customFieldsResult.request;
            if (result.decision != null && !TextUtils.isEmpty(customFieldsResult.adapterNote)) {
                result.decision.adapterNote = appendReason(result.decision.adapterNote, customFieldsResult.adapterNote);
            }
            // v1.5.0：注入代理 tools（web_search / describe_image），fallback 让位游戏 tools
            upstreamRequest = applyProxyToolsToUpstreamRequest(upstreamRequest, cfg, result.decision, attachInject);
            result.debugUpstreamRequest = upstreamRequest;
            log("proxy send req=" + reqId
                    + " kind=" + (result.decision == null ? "unknown" : result.decision.requestKind)
                    + " diaryMatched=" + (result.decision != null && result.decision.diaryMatched)
                    + " model=" + clip(result.decision == null ? cfg.model : result.decision.modelPreview, 80));
            UpstreamResponse upstream = forwardRaw(upstreamRequest, cfg, forwardTrace);
            // v1.5.0：tool_call 回流（最多 3 轮，仅 web_search / describe_image 白名单内）
            upstream = runToolCallLoopIfNeeded(upstream, payload, cfg, result.decision, forwardTrace, attachInject, result.toolCallTurns);
            result.forwardMs = forwardTrace.totalMs;
            result.decodedCharset = upstream.decodedCharset;
            result.replacementCount = upstream.replacementCount;

            if (upstream.streaming) {
                log("proxy upstream stream req=" + reqId + " contentType=" + upstream.contentType + " replayed=" + replayed);
                result.statusCode = 502;
                result.errorCode = "upstream_stream_unsupported";
                result.errorMessage = "上游返回了流式响应，当前版本仅支持非流式。请关闭 stream，或改用非流式接口。";
                result.responseBody = errorJson(result.errorCode, result.errorMessage);
                result.assistantText = result.errorMessage;
                return finishChatExecution(result, cfg, reqId, null, null);
            }

            String bridgedBody = normalizeChatResponse(upstream.body, upstream.status, payload, cfg);
            String finalBody = normalizeResponse(bridgedBody, result.decision);
            result.success = upstream.status < 400;
            result.statusCode = upstream.status;
            result.contentType = upstream.contentType;
            result.responseBody = finalBody;
            result.assistantText = extractHistoryResponseText(finalBody, cfg);
            // v1.5.0：发送成功后清空已消费的附件草稿（失败保留草稿，便于用户重发）
            if (result.success && appContext != null
                    && result.attachmentInjection != null
                    && result.attachmentInjection.injected
                    && !result.attachmentInjection.consumedIds.isEmpty()) {
                ProxyStorageHelper.clearAttachmentDraftsByIds(appContext, result.attachmentInjection.consumedIds);
            }
            return finishChatExecution(result, cfg, reqId, null, null);
        } catch (JSONException error) {
            return finishChatExecution(result, cfg, reqId, "bad_json", error.getMessage());
        } catch (IOException error) {
            return finishChatExecution(result, cfg, reqId, "upstream_io", error.getMessage());
        } catch (Exception error) {
            return finishChatExecution(result, cfg, reqId, "chat_error", error.getMessage());
        } finally {
            result.totalMs = SystemClock.elapsedRealtime() - startedAt;
            RuntimeDiagnostics.releaseWakeLock(requestWakeLock);
        }
    }

    private ChatExecutionResult finishChatExecution(ChatExecutionResult result, ProxyConfig cfg, long reqId, String errorCode, String errorMessage) {
        if (!TextUtils.isEmpty(errorCode)) {
            result.success = false;
            if (result.statusCode <= 0 || result.statusCode == 500) {
                if ("bad_json".equals(errorCode) || "empty_body".equals(errorCode)) {
                    result.statusCode = 400;
                } else if ("upstream_io".equals(errorCode) || "upstream_stream_unsupported".equals(errorCode)) {
                    result.statusCode = 502;
                } else {
                    result.statusCode = 500;
                }
            }
            result.errorCode = errorCode;
            result.errorMessage = firstNonEmpty(errorMessage, "未知错误");
            result.responseBody = errorJson(result.errorCode, result.errorMessage);
            result.assistantText = firstNonEmpty(result.assistantText, result.errorMessage);
        }

        if (!result.success && appContext != null) {
            String source = "chat_forward/" + firstNonEmpty(result.errorCode, "upstream_error");
            String summary = "聊天转发失败，kind=" + firstNonEmpty(result.decision == null ? "" : result.decision.requestKind, "unknown");
            String detail = "status=" + result.statusCode
                    + ", message=" + firstNonEmpty(result.errorMessage, "")
                    + ", adapter=" + firstNonEmpty(cfg == null ? "" : cfg.adapterPreset, "")
                    + ", model=" + firstNonEmpty(result.decision == null ? "" : result.decision.modelPreview, cfg == null ? "" : cfg.model);
            FeedbackSupport.recordOperationalError(appContext, source, summary, detail);
        }

        persistHistoryIfNeeded(cfg, result);
        persistDebugPromptDumpIfNeeded(cfg, result);
        logChatExecution(reqId, result);
        return result;
    }

    private void cacheLastDiaryRequest(String uri, String requestBody, ChatDecision decision, String originalConversation) {
        if (appContext == null || TextUtils.isEmpty(requestBody)) {
            return;
        }
        ProxyStorageHelper.CachedDiaryRequest cached = new ProxyStorageHelper.CachedDiaryRequest();
        cached.savedAtMs = System.currentTimeMillis();
        cached.uri = firstNonEmpty(uri, "");
        cached.requestBody = requestBody;
        cached.requestKind = decision == null ? "" : decision.requestKind;
        cached.diaryMatched = decision != null && decision.diaryMatched;
        cached.diaryType = decision == null ? "" : decision.diaryType;
        cached.model = decision == null ? "" : decision.modelPreview;
        cached.conversationPreview = firstNonEmpty(originalConversation, decision == null ? "" : decision.originalUserPreview);
        try {
            ProxyStorageHelper.saveLastDiaryRequest(appContext, cached);
        } catch (IOException error) {
            log("proxy cache save failed: " + error.getMessage());
        }
    }

    private void persistHistoryIfNeeded(ProxyConfig cfg, ChatExecutionResult result) {
        if (appContext == null || cfg == null || result == null || !shouldPersistHistory(cfg, result.decision)) {
            return;
        }
        ProxyStorageHelper.HistoryRecord record = new ProxyStorageHelper.HistoryRecord();
        record.occurredAtMs = System.currentTimeMillis();
        record.success = result.success;
        record.replayed = result.replayed;
        record.diaryRecord = result.decision != null && result.decision.diaryMatched;
        record.requestKind = result.decision == null ? "" : result.decision.requestKind;
        record.diaryType = result.decision == null ? "" : result.decision.diaryType;
        record.model = result.decision == null ? "" : result.decision.modelPreview;
        record.userText = sanitizeHistoryMessageText(
                result.decision == null ? "" : result.decision.originalUserPreview,
                "user"
        );
        record.originalConversation = record.userText;
        record.forwardedConversation = firstNonEmpty(result.forwardedConversation, result.decision == null ? "" : result.decision.userPreview);
        record.responseText = firstNonEmpty(result.assistantText, "");
        record.errorMessage = result.errorMessage;
        record.personaApplied = result.decision != null && result.decision.personaApplied;
        record.personaTier = result.decision == null ? "" : result.decision.personaTier;
        record.tokenApplied = result.decision != null && result.decision.tokenApplied;
        record.diaryTemplateVars = result.decision == null ? "" : result.decision.diaryTemplateVars;
        record.statusCode = result.statusCode;
        try {
            ProxyStorageHelper.appendHistoryRecord(appContext, record);
        } catch (IOException error) {
            log("proxy history save failed: " + error.getMessage());
        }
    }

    /**
     * v1.5.0：调试提示词导出。
     * 从 finishChatExecution 调用，覆盖成功 + 异常两条路径。所有数据都从
     * {@link ChatExecutionResult} 提取，包括 tool_call 每轮入参与代理执行结果。
     * 仅当 buildChatUpstreamRequest 走通（即 result.debugUpstreamRequest 非空）时才导出。
     */
    private void persistDebugPromptDumpIfNeeded(ProxyConfig cfg, ChatExecutionResult result) {
        if (appContext == null
                || cfg == null
                || !cfg.debugPromptDumpEnabled
                || result == null
                || result.debugUpstreamRequest == null
                || TextUtils.isEmpty(result.debugUpstreamRequest.body)) {
            return;
        }
        UpstreamRequest request = result.debugUpstreamRequest;
        ChatDecision decision = result.decision;
        AttachmentInjectionResult attachmentInjection = result.attachmentInjection;
        ProxyStorageHelper.DebugPromptRecord record = new ProxyStorageHelper.DebugPromptRecord();
        record.occurredAtMs = System.currentTimeMillis();
        record.requestKind = decision == null ? "" : decision.requestKind;
        record.diaryType = decision == null ? "" : decision.diaryType;
        record.adapterPreset = cfg.adapterPreset;
        record.model = decision == null ? firstNonEmpty(cfg.model, "") : firstNonEmpty(decision.modelPreview, cfg.model);
        record.personaApplied = decision != null && decision.personaApplied;
        record.personaTier = decision == null ? "" : firstNonEmpty(decision.personaTier, "");
        record.personaReason = decision == null ? "" : firstNonEmpty(decision.personaReason, "");
        record.adapterNote = decision == null ? "" : firstNonEmpty(decision.adapterNote, "");
        record.attachmentSummary = buildAttachmentDebugSummary(attachmentInjection);
        record.upstreamPath = resolvePathTemplate(request.path, cfg);
        record.originalSystemText = decision == null ? "" : firstNonEmpty(decision.originalSystemPreview, "").trim();
        record.originalUserText = decision == null ? "" : firstNonEmpty(decision.originalUserPreview, "").trim();
        record.originalRequestBody = firstNonEmpty(result.requestBody, "").trim();
        record.systemText = decision == null ? "" : firstNonEmpty(decision.systemPreview, "").trim();
        record.userText = decision == null ? "" : firstNonEmpty(decision.userPreview, "").trim();
        record.finalRequestBody = request.body;
        record.toolCallTurns = result.toolCallTurns == null
                ? new ArrayList<>()
                : new ArrayList<>(result.toolCallTurns);
        // v1.5.6+：让 ProxyStorageHelper.formatDebugPromptRecord 按详细程度档位输出
        record.detailLevel = ProxyConfig.normalizeDebugPromptDetailLevel(cfg.debugPromptDetailLevel);
        try {
            ProxyStorageHelper.appendDebugPromptRecord(appContext, record);
        } catch (IOException error) {
            log("proxy debug dump save failed: " + error.getMessage());
        }
    }

    private static String buildAttachmentDebugSummary(AttachmentInjectionResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("injected=").append(result.injected)
                .append(", count=").append(result.injectedCount)
                .append(", total=").append(AttachmentSupport.formatSize(result.totalBytes));
        if (!TextUtils.isEmpty(result.adapterNote)) {
            builder.append(", note=").append(result.adapterNote);
        }
        if (!TextUtils.isEmpty(result.dropReason)) {
            builder.append(", reason=").append(result.dropReason);
        }
        if (!TextUtils.isEmpty(result.sizeWarning)) {
            builder.append(", warning=").append(result.sizeWarning);
        }
        if (result.consumedIds != null && !result.consumedIds.isEmpty()) {
            builder.append(", consumed=").append(result.consumedIds);
        }
        return builder.toString();
    }

    private boolean shouldPersistHistory(ProxyConfig cfg, ChatDecision decision) {
        if (cfg == null) {
            return false;
        }
        boolean diaryRecord = decision != null && decision.diaryMatched;
        if (diaryRecord) {
            return cfg.saveDiaryHistory;
        }
        return cfg.saveNormalChatHistory
                && decision != null
                && ("normal-chat".equals(decision.requestKind)
                || "interactive-story".equals(decision.requestKind));
    }

    private void logChatExecution(long reqId, ChatExecutionResult result) {
        ChatDecision decision = result == null || result.decision == null ? new ChatDecision() : result.decision;
        log("proxy req=" + reqId
                + " kind=" + decision.requestKind
                + " streamRequested=" + decision.streamRequested
                + " streamForcedOff=" + decision.streamForcedOff
                + " diary=" + decision.diaryType
                + " diaryMatched=" + decision.diaryMatched
                + " diaryRewritten=" + decision.diaryRewritten
                + " diaryReason=" + decision.diaryReason
                + " templateVars=" + clip(decision.diaryTemplateVars, 120)
                + " personaApplied=" + decision.personaApplied
                + " personaTier=" + decision.personaTier
                + " personaReason=" + decision.personaReason
                + " stripRestriction=" + decision.restrictionLineStripped
                + " stripSystemTime=" + decision.systemTimeStripped
                + " tokenApplied=" + decision.tokenApplied
                + " adapterNote=" + clip(decision.adapterNote, 80)
                + " replayed=" + (result != null && result.replayed)
                + " model=" + clip(decision.modelPreview, 80)
                + " user=" + clip(decision.originalUserPreview, 120));
        log("proxy out req=" + reqId
                + " status=" + (result == null ? 0 : result.statusCode)
                + " success=" + (result != null && result.success)
                + " decode=" + clip(result == null ? "" : result.decodedCharset, 24)
                + " replacement=" + (result == null ? 0 : result.replacementCount)
                + " assistantLen=" + firstNonEmpty(result == null ? "" : result.assistantText, "").length()
                + " preview=" + clip(result == null ? "" : firstNonEmpty(result.assistantText, result.errorMessage), 120));
        ForwardTrace trace = result == null ? null : result.forwardTrace;
        log("proxy timing req=" + reqId
                + " inReadMs=" + (result == null ? 0L : result.readBodyMs)
                + " parseMs=" + (result == null ? 0L : result.parseMs)
                + " rewriteMs=" + (result == null ? 0L : result.rewriteMs)
                + " forwardMs=" + (result == null ? 0L : result.forwardMs)
                + " upOpenMs=" + (trace == null ? 0L : trace.openMs)
                + " upWriteMs=" + (trace == null ? 0L : trace.writeMs)
                + " upWaitMs=" + (trace == null ? 0L : trace.waitResponseMs)
                + " upReadMs=" + (trace == null ? 0L : trace.readBodyMs)
                + " totalMs=" + (result == null ? 0L : result.totalMs)
                + " bodyBytes=" + (result == null ? 0 : result.requestBytes)
                + " respBytes=" + (trace == null ? 0 : trace.responseBytes)
                + " upstreamAttempts=" + (trace == null ? 0 : trace.attemptCount)
                + " retryClose=" + (trace != null && trace.retriedWithConnectionClose)
                + " wakeLock=" + (result != null && result.requestWakeLockHeld)
                + " upstream=" + clip(trace == null ? "" : trace.method + " " + trace.url, 160)
                + " upstreamProxy=" + clip(trace == null ? "" : trace.proxyType, 24)
                + " firstError=" + clip(trace == null ? "" : trace.firstError, 120)
                + " lastError=" + clip(trace == null ? "" : trace.lastError, 120)
                + " env=" + clip(result == null ? "" : result.runtimeSummary, 220));
        if (result != null && result.totalMs >= 15000L) {
            log("proxy slow req=" + reqId
                    + " hint=possible_background_throttle_or_upstream_delay"
                    + " totalMs=" + result.totalMs
                    + " env=" + clip(result.runtimeSummary, 220));
        }
    }

    private ChatDecision rewriteRequest(JSONObject payload, ProxyConfig cfg) {
        ChatDecision decision = new ChatDecision();
        decision.modelPreview = resolveRequestModel(payload, cfg);
        decision.streamRequested = disableStreamingRequest(payload);
        decision.streamForcedOff = decision.streamRequested;

        JSONArray messages = resolveMessages(payload, cfg);
        if (messages == null || messages.length() == 0) {
            decision.requestKind = "pass-through";
            decision.diaryReason = "messages_missing";
            decision.personaReason = "messages_missing";
            applyConfiguredModel(payload, cfg);
            if (cfg.stripEnableThinkingEnabled && removeEnableThinkingField(payload)) {
                decision.adapterNote = appendReason(decision.adapterNote, "enable_thinking_removed");
            }
            return decision;
        }

        MessageRef systemRef = findFirstMessage(messages, "system");
        MessageRef userRef = findLastMessage(messages, "user");

        decision.systemPreview = systemRef == null ? "" : getMessageText(systemRef.message);
        decision.userPreview = userRef == null ? "" : getMessageText(userRef.message);
        decision.originalSystemPreview = decision.systemPreview;
        decision.originalUserPreview = decision.userPreview;
        decision.requestKind = "normal-chat";

        DiaryDetection detection = detectDiary(decision.userPreview, cfg);
        decision.diaryMatched = detection.matched;
        decision.diaryType = detection.diaryType;
        decision.diaryReason = detection.reason;

        if (detection.matched) {
            decision.requestKind = detection.diaryType;
            if (userRef == null) {
                decision.diaryReason = appendReason(decision.diaryReason, "user_message_missing");
            } else {
                String template = "holiday-diary".equals(detection.diaryType) ? cfg.holidayTemplate : cfg.normalTemplate;
                TemplateVars templateVars = extractTemplateVars(decision.userPreview);
                DiaryConversationResult conversationResult = resolveDiaryConversation(decision.userPreview, messages);
                templateVars.conversation = conversationResult.conversation;
                templateVars.conversationSource = conversationResult.source;
                if (!TextUtils.isEmpty(conversationResult.source)) {
                    decision.diaryReason = appendReason(decision.diaryReason, "conversation_source=" + conversationResult.source);
                }
                if (!TextUtils.isEmpty(templateVars.conversation)) {
                    RenderTemplateResult renderResult = renderDiaryTemplate(template, templateVars);
                    if (renderResult.success) {
                        String rewrittenDiaryUser = ensureDiaryConversationAttached(
                                renderResult.rendered,
                                template,
                                templateVars.conversation
                        );
                        decision.diaryTemplateVars = appendResolvedVarSummary(
                                renderResult.resolvedVarsSummary,
                                buildConversationTemplateSummary(templateVars.conversationSource, templateVars.conversation)
                        );
                        if (!cfg.dryRun) {
                            setMessageText(userRef.message, rewrittenDiaryUser);
                            decision.userPreview = rewrittenDiaryUser;
                            decision.diaryRewritten = true;
                        }
                    } else {
                        decision.diaryReason = appendReason(
                                decision.diaryReason,
                                "template_vars_missing=" + renderResult.missingVars
                        );
                    }
                } else {
                    decision.diaryReason = appendReason(decision.diaryReason, "conversation_missing_keep_original");
                }
            }
            if (!cfg.dryRun && cfg.overrideMaxTokens > 0 && applyConfiguredMaxTokens(payload, cfg, cfg.overrideMaxTokens)) {
                decision.tokenApplied = true;
            }
        } else {
            decision.requestKind = classifyNonDiaryRequest(decision.userPreview, decision.systemPreview, cfg);
            if ("memory-extract".equals(decision.requestKind)) {
                if (userRef == null) {
                    decision.adapterNote = appendReason(decision.adapterNote, "memory_user_missing");
                } else {
                    MemoryExtractRewriteResult memoryResult = rewriteMemoryExtractPrompt(decision.userPreview, cfg);
                    if (memoryResult.applied) {
                        setMessageText(userRef.message, memoryResult.rewrittenPrompt);
                        decision.userPreview = memoryResult.rewrittenPrompt;
                        decision.adapterNote = appendReason(decision.adapterNote, "memory_template_applied");
                        if (!TextUtils.isEmpty(memoryResult.resolvedVarsSummary)) {
                            decision.adapterNote = appendReason(
                                    decision.adapterNote,
                                    "memory_vars=" + clip(memoryResult.resolvedVarsSummary, 80)
                            );
                        }
                    } else if (!TextUtils.isEmpty(memoryResult.reason)) {
                        decision.adapterNote = appendReason(decision.adapterNote, memoryResult.reason);
                    }
                }
            }
        }

        if (shouldAttemptPersonaOverlay(decision.requestKind, decision.systemPreview, decision.userPreview, cfg)
                && systemRef != null) {
            PersonaSupport.PersonaOverlayResult overlay = PersonaSupport.tryOverlay(getMessageText(systemRef.message), cfg);
            decision.personaApplied = overlay.applied;
            decision.personaTier = overlay.tier;
            decision.personaReason = overlay.reason;
            if (overlay.applied) {
                setMessageText(systemRef.message, overlay.rewrittenSystem);
                decision.systemPreview = overlay.rewrittenSystem;
            }
        } else {
            decision.personaReason = "request_kind_skip:" + decision.requestKind;
        }

        SystemSanitizeResult sanitizeResult = sanitizeOutboundSystemMessages(messages, cfg);
        decision.restrictionLineStripped = sanitizeResult.restrictionLineStripped;
        decision.systemTimeStripped = sanitizeResult.systemTimeStripped;
        if (sanitizeResult.changed) {
            decision.systemPreview = collectSystemText(messages);
        }

        applyRequestTypeOverrides(payload, cfg, decision);
        applyConfiguredModel(payload, cfg);
        if (cfg.stripEnableThinkingEnabled && removeEnableThinkingField(payload)) {
            decision.adapterNote = appendReason(decision.adapterNote, "enable_thinking_removed");
        }
        // 日记请求 assistant 前缀续写：在 messages 末尾追加一条 assistant 消息让模型续写。
        // 仅当日记请求 (decision.diaryMatched=true，覆盖 normal-diary + holiday-diary)、
        // 主开关打开、前缀文本非空、且非 dryRun 时生效。
        if (decision.diaryMatched
                && cfg.diaryAssistantPrefixEnabled
                && !TextUtils.isEmpty(cfg.diaryAssistantPrefix)
                && !cfg.dryRun) {
            // 解析字面转义（\n / \t / \\ 等），让用户既可以在多行框里按回车换行，
            // 也可以从外部复制粘贴带字面 \n 的文本。
            String resolvedPrefix = unescapeUserPrefix(cfg.diaryAssistantPrefix);
            // Gemini 适配器特殊处理：Gemini Generate Content API 不像 Anthropic Messages 那样自动续写
            // contents 末尾的 model turn——Gemini 2.x Flash 经常把"【日记】"视作"已完成的回复"，
            // 立即 STOP 一字未写（实测 finish_reason=stop, completion_tokens=0）。
            // 因此对 Gemini 改用 prompt 硬约束：把"必须以前缀开头"追加到最后一条 user 消息末尾，
            // 响应侧 prepend 兜底确保前缀总在最终 content 里。其他 adapter 走原 prefill 路径。
            boolean isGemini = ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset);
            if (isGemini) {
                if (appendDiaryPrefixHintToLastUser(messages, resolvedPrefix)) {
                    decision.adapterNote = appendReason(
                            decision.adapterNote,
                            "diary_prefix=" + clip(resolvedPrefix, 24) + "+gemini_user_hint"
                    );
                }
            } else {
                // 硅基流动续写兼容：与 DeepSeek 不同，硅基流动用顶层 prefix 字段（OpenAI SDK 的 extra_body 在 wire 上就是把字段塞到
                // body 顶层），messages 里不放 assistant 消息。文档：https://docs.siliconflow.cn/cn/userguide/guides/prefix
                // 仅对 OpenAI 兼容 adapter 生效；与 DeepSeek 模式互斥（UI 已强制单选，这里再次防御性优先 SiliconFlow）。
                // v1.5.6+：DPS-16 — 增加 baseUrl 守卫：硅基流动的 prefix 字段是非标 OpenAI 扩展，
                // 用户若把开关误打开但实际把 baseUrl 指向其它 OpenAI 兼容厂商（OpenAI 官方 / DeepSeek / 通义等），
                // 上游会因为不识别顶层 prefix 字段而拒绝或忽略，体感上 prefix 完全不生效却没有报错。
                // 这里 best-effort 检查 baseUrl 是否含 "siliconflow"，不是则降级走通用 prefill 路径。
                String upstreamBaseUrlLower = cfg.upstreamBaseUrl == null
                        ? "" : cfg.upstreamBaseUrl.toLowerCase(Locale.ROOT);
                boolean siliconflowModeActive = cfg.diaryAssistantPrefixSiliconflowMode
                        && ProxyConfig.ADAPTER_OPENAI_COMPATIBLE.equals(cfg.adapterPreset)
                        && upstreamBaseUrlLower.contains("siliconflow");
                if (siliconflowModeActive) {
                    try {
                        // 顶层 prefix 字段；不修改 messages、不追加 assistant 消息。
                        // 响应侧 prepend 兜底逻辑保持不动——如果模型严格以前缀开头就 skip 不重复，没听话就 prepend。
                        payload.put("prefix", resolvedPrefix);
                        decision.adapterNote = appendReason(
                                decision.adapterNote,
                                "diary_prefix=" + clip(resolvedPrefix, 24) + "+siliconflow"
                        );
                    } catch (JSONException ignored) {
                        // payload.put(String,String) 仅在 name=null 时抛出；这里 name 是字面量，理论不会发生。
                        // 即使发生也不应阻塞日记请求，静默回退到无前缀路径。
                    }
                } else {
                    try {
                        JSONObject prefillMsg = new JSONObject();
                        prefillMsg.put("role", "assistant");
                        prefillMsg.put("content", resolvedPrefix);
                        boolean deepseekModeActive = cfg.diaryAssistantPrefixDeepseekMode
                                && ProxyConfig.ADAPTER_OPENAI_COMPATIBLE.equals(cfg.adapterPreset);
                        if (deepseekModeActive) {
                            prefillMsg.put("prefix", true);
                            // 自动升级到 DeepSeek 续写专用 endpoint，用户不必手动把 base_url 改成 /beta。
                            // 仅在 base 包含 api.deepseek.com 时生效；幂等（已含 /beta 不重复追加）；
                            // /v1 后缀会被替换为 /beta；第三方 OpenAI 兼容代理域名不动。
                            String upgraded = upgradeDeepseekBetaIfNeeded(cfg.upstreamBaseUrl);
                            if (!TextUtils.equals(upgraded, cfg.upstreamBaseUrl)) {
                                cfg.upstreamBaseUrl = upgraded;
                                decision.adapterNote = appendReason(decision.adapterNote, "deepseek_beta_auto");
                            }
                        }
                        messages.put(prefillMsg);
                        decision.adapterNote = appendReason(
                                decision.adapterNote,
                                "diary_prefix=" + clip(resolvedPrefix, 24)
                                        + (deepseekModeActive ? "+deepseek" : "")
                        );
                    } catch (JSONException ignored) {
                        // put(String,String/boolean) 仅在 name=null 时抛出；这里参数都是字面量，理论不会发生。
                        // 即使发生也不应阻塞日记请求，静默回退到无前缀路径。
                    }
                }
            }
        }
        applyMessages(payload, cfg, messages);
        decision.modelPreview = resolveRequestModel(payload, cfg);
        return decision;
    }

    private void applyRequestTypeOverrides(JSONObject payload, ProxyConfig cfg, ChatDecision decision) {
        if (payload == null || cfg == null || decision == null) {
            return;
        }
        if ("normal-chat".equals(decision.requestKind) || "interactive-story".equals(decision.requestKind)) {
            if (cfg.chatOverrideMaxTokens > 0 && applyConfiguredMaxTokens(payload, cfg, cfg.chatOverrideMaxTokens)) {
                decision.tokenApplied = true;
                decision.adapterNote = appendReason(decision.adapterNote, "chat_max_tokens=" + cfg.chatOverrideMaxTokens);
            }
            if (applyConfiguredTemperature(payload, cfg.resolvedChatOverrideTemperature())) {
                decision.adapterNote = appendReason(
                        decision.adapterNote,
                        "chat_temperature=" + firstNonEmpty(cfg.chatOverrideTemperature, formatNumber(cfg.resolvedChatOverrideTemperature()))
                );
            }
            if (applyConfiguredEnableThinking(payload, cfg.resolvedChatOverrideEnableThinking())) {
                decision.adapterNote = appendReason(decision.adapterNote, "chat_enable_thinking=" + cfg.chatOverrideEnableThinking);
            }
            return;
        }
        if (decision.diaryMatched) {
            if (applyConfiguredTemperature(payload, cfg.resolvedDiaryOverrideTemperature())) {
                decision.adapterNote = appendReason(
                        decision.adapterNote,
                        "diary_temperature=" + firstNonEmpty(cfg.diaryOverrideTemperature, formatNumber(cfg.resolvedDiaryOverrideTemperature()))
                );
            }
            if (applyConfiguredEnableThinking(payload, cfg.resolvedDiaryOverrideEnableThinking())) {
                decision.adapterNote = appendReason(decision.adapterNote, "diary_enable_thinking=" + cfg.diaryOverrideEnableThinking);
            }
            return;
        }
        if ("memory-extract".equals(decision.requestKind)) {
            if (cfg.memoryExtractOverrideMaxTokens > 0
                    && applyConfiguredMaxTokens(payload, cfg, cfg.memoryExtractOverrideMaxTokens)) {
                decision.tokenApplied = true;
                decision.adapterNote = appendReason(
                        decision.adapterNote,
                        "memory_max_tokens=" + cfg.memoryExtractOverrideMaxTokens
                );
            }
            if (applyConfiguredTemperature(payload, cfg.resolvedMemoryExtractOverrideTemperature())) {
                decision.adapterNote = appendReason(
                        decision.adapterNote,
                        "memory_temperature=" + firstNonEmpty(
                                cfg.memoryExtractOverrideTemperature,
                                formatNumber(cfg.resolvedMemoryExtractOverrideTemperature())
                        )
                );
            }
            if (applyConfiguredEnableThinking(payload, cfg.resolvedMemoryExtractOverrideEnableThinking())) {
                decision.adapterNote = appendReason(
                        decision.adapterNote,
                        "memory_enable_thinking=" + cfg.memoryExtractOverrideEnableThinking
                );
            }
        }
    }

    private MemoryExtractRewriteResult rewriteMemoryExtractPrompt(String originalText, ProxyConfig cfg) {
        MemoryExtractRewriteResult result = new MemoryExtractRewriteResult();
        String source = firstNonEmpty(originalText, "").replace("\r\n", "\n").replace('\r', '\n').trim();
        if (TextUtils.isEmpty(source)) {
            result.reason = "memory_prompt_empty";
            return result;
        }
        MemoryExtractVars vars = extractMemoryExtractVars(source);
        RenderTemplateResult renderResult = renderMemoryExtractTemplate(
                cfg == null ? ProxyConfig.DEFAULT_MEMORY_EXTRACT_TEMPLATE : cfg.memoryExtractTemplate,
                vars
        );
        if (!renderResult.success) {
            result.reason = "memory_template_vars_missing=" + renderResult.missingVars;
            return result;
        }
        String rewritten = cleanupPromptWhitespace(renderResult.rendered);
        if (TextUtils.isEmpty(rewritten)) {
            result.reason = "memory_template_rendered_empty";
            return result;
        }
        result.applied = true;
        result.rewrittenPrompt = rewritten;
        result.resolvedVarsSummary = renderResult.resolvedVarsSummary;
        return result;
    }

    private MemoryExtractVars extractMemoryExtractVars(String text) {
        MemoryExtractVars vars = new MemoryExtractVars();
        String normalized = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        vars.personality = cleanupPromptWhitespace(firstMatch(normalized, MEMORY_EXTRACT_PERSONALITY_PATTERN));
        vars.conversation = cleanupPromptWhitespace(firstMatch(normalized, MEMORY_EXTRACT_CONVERSATION_PATTERN));
        if (TextUtils.isEmpty(vars.conversation)) {
            vars.conversation = extractDialogueLines(normalized);
        }
        vars.genderTerm = sanitizeVar(firstMatch(normalized, MEMORY_EXTRACT_GENDER_TERM_PATTERN));
        if (TextUtils.isEmpty(vars.genderTerm)) {
            vars.genderTerm = inferMemoryExtractGenderTerm(vars.conversation);
        }
        if (TextUtils.isEmpty(vars.genderTerm)) {
            vars.genderTerm = "哥哥";
        }
        return vars;
    }

    private String inferMemoryExtractGenderTerm(String conversation) {
        if (TextUtils.isEmpty(conversation)) {
            return "";
        }
        String[] lines = conversation.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String rawLine : lines) {
            String line = trimTrailingWhitespace(firstNonEmpty(rawLine, "")).trim();
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            int colonIndex = firstColonIndex(line);
            if (colonIndex <= 0) {
                continue;
            }
            String inferred = normalizeInferredGenderTerm(line.substring(0, colonIndex));
            if (!TextUtils.isEmpty(inferred)) {
                return inferred;
            }
        }
        return "";
    }

    private String normalizeInferredGenderTerm(String label) {
        String value = sanitizeVar(label);
        if (TextUtils.isEmpty(value) || looksLikeConversationMetaLabel(value)) {
            return "";
        }
        String normalized = normalizeForMatch(value);
        if (TextUtils.isEmpty(normalized)) {
            return "";
        }
        if (normalized.contains("yuki")
                || normalized.contains("assistant")
                || normalized.contains("model")
                || normalized.contains("回复")
                || normalized.contains("旁白")
                || normalized.contains("narrator")
                || normalized.contains("系统")) {
            return "";
        }
        if ("用户".equals(value)
                || "玩家".equals(value)
                || "你".equals(value)
                || "user".equalsIgnoreCase(value)
                || "player".equalsIgnoreCase(value)) {
            return "";
        }
        return value;
    }

    private RenderTemplateResult renderMemoryExtractTemplate(String template, MemoryExtractVars vars) {
        String source = TextUtils.isEmpty(template) ? ProxyConfig.DEFAULT_MEMORY_EXTRACT_TEMPLATE : template;
        RenderTemplateResult result = new RenderTemplateResult();
        result.rendered = source;

        LinkedHashSet<String> requiredVars = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_VAR_PATTERN.matcher(source);
        while (matcher.find()) {
            requiredVars.add(matcher.group(1));
        }
        for (String varName : requiredVars) {
            String value = resolveMemoryTemplateVar(varName, vars);
            if (TextUtils.isEmpty(value)) {
                result.missingVars.add(varName);
            }
        }
        if (!result.missingVars.isEmpty()) {
            result.success = false;
            return result;
        }

        LinkedHashMap<String, String> resolvedVars = new LinkedHashMap<>();
        for (String varName : requiredVars) {
            String value = resolveMemoryTemplateVar(varName, vars);
            resolvedVars.put(varName, value);
            result.rendered = result.rendered.replace("${" + varName + "}", value);
        }
        result.resolvedVarsSummary = buildResolvedVarSummary(resolvedVars);
        result.success = true;
        return result;
    }

    private String resolveMemoryTemplateVar(String name, MemoryExtractVars vars) {
        if (TextUtils.isEmpty(name) || vars == null) {
            return "";
        }
        if ("personality".equals(name)) {
            return vars.personality;
        }
        if ("genderTerm".equals(name)) {
            return vars.genderTerm;
        }
        if ("conversation".equals(name) || "dialogue".equals(name)) {
            return vars.conversation;
        }
        return "";
    }

    private SystemSanitizeResult sanitizeOutboundSystemMessages(JSONArray messages, ProxyConfig cfg) {
        SystemSanitizeResult result = new SystemSanitizeResult();
        if (messages == null || cfg == null) {
            return result;
        }
        if (!cfg.stripRestrictionLineEnabled && !cfg.stripSystemTimeEnabled) {
            return result;
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            if (!"system".equals(normalizeLocalRole(message.optString("role", "")))) {
                continue;
            }
            String original = getMessageText(message);
            String cleaned = sanitizeOutboundSystemText(original, cfg, result);
            if (!TextUtils.equals(original, cleaned)) {
                setMessageText(message, cleaned);
                result.changed = true;
            }
        }
        return result;
    }

    private String sanitizeOutboundSystemText(String text, ProxyConfig cfg, SystemSanitizeResult result) {
        String cleaned = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        if (TextUtils.isEmpty(cleaned) || cfg == null) {
            return cleaned;
        }
        if (cfg.stripRestrictionLineEnabled) {
            String next = stripRestrictionLines(cleaned);
            if (!TextUtils.equals(cleaned, next)) {
                result.restrictionLineStripped = true;
                cleaned = next;
            }
        }
        if (cfg.stripSystemTimeEnabled) {
            String next = stripSystemTimeMarkers(cleaned);
            if (!TextUtils.equals(cleaned, next)) {
                result.systemTimeStripped = true;
                cleaned = next;
            }
        }
        return cleanupPromptWhitespace(cleaned);
    }

    private String stripRestrictionLines(String text) {
        String normalized = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String rawLine : lines) {
            String line = firstNonEmpty(rawLine, "");
            if (RESTRICTION_LINE_PATTERN.matcher(line.trim()).matches()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String stripSystemTimeMarkers(String text) {
        String normalized = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String rawLine : lines) {
            String line = stripSystemTimeMarkersFromLine(rawLine);
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String stripSystemTimeMarkersFromLine(String line) {
        String cleaned = firstNonEmpty(line, "");
        boolean changed = false;
        String next = CURRENT_TIME_MARKER_PATTERN.matcher(cleaned).replaceAll("");
        if (!TextUtils.equals(cleaned, next)) {
            changed = true;
            cleaned = next;
        }
        next = BRACKET_DATETIME_MARKER_PATTERN.matcher(cleaned).replaceAll("");
        if (!TextUtils.equals(cleaned, next)) {
            changed = true;
            cleaned = next;
        }
        next = BRACKET_DIARY_TIME_MARKER_PATTERN.matcher(cleaned).replaceAll("");
        if (!TextUtils.equals(cleaned, next)) {
            changed = true;
            cleaned = next;
        }
        if (!changed) {
            return line;
        }
        return compactPipeSeparatedLine(cleaned);
    }

    private String compactPipeSeparatedLine(String line) {
        String raw = firstNonEmpty(line, "");
        String[] parts = raw.split("\\|", -1);
        if (parts.length <= 1) {
            return raw.trim();
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String trimmed = firstNonEmpty(part, "").trim();
            if (TextUtils.isEmpty(trimmed)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(trimmed);
        }
        return builder.toString();
    }

    private String cleanupPromptWhitespace(String text) {
        String normalized = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean lastBlank = true;
        for (String rawLine : lines) {
            String line = trimTrailingWhitespace(firstNonEmpty(rawLine, ""));
            boolean blank = TextUtils.isEmpty(line.trim());
            if (blank) {
                if (!lastBlank) {
                    builder.append('\n');
                }
            } else {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                builder.append(line);
            }
            lastBlank = blank;
        }
        return builder.toString().trim();
    }

    private String trimTrailingWhitespace(String text) {
        int end = text == null ? 0 : text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (!Character.isWhitespace(ch)) {
                break;
            }
            end--;
        }
        return end == (text == null ? 0 : text.length()) ? firstNonEmpty(text, "") : text.substring(0, end);
    }

    private String classifyNonDiaryRequest(String userText, String systemText, ProxyConfig cfg) {
        String normalizedUser = normalizeForMatch(userText);
        String normalizedSystem = normalizeForMatch(systemText);
        if (looksLikeHealthCheckRequest(normalizedUser, normalizedSystem)) {
            return "health-check";
        }
        if (looksLikeStandaloneMetaPrompt(userText)) {
            return "meta-prompt";
        }
        if (normalizedUser.contains("reply1") || normalizedUser.contains("reply2") || normalizedUser.contains("json格式输出")) {
            return "preset-reply";
        }
        if (looksLikeTurtleSoupJudgeRequest(normalizedUser, normalizedSystem)) {
            return "turtle-soup-judge";
        }
        if (normalizedUser.contains("长期记忆")
                || normalizedUser.contains("关键事实")
                || normalizedUser.contains("只输出json数组")) {
            return "memory-extract";
        }
        if (looksLikeInteractiveStoryRequest(normalizedUser, normalizedSystem, cfg)) {
            return "interactive-story";
        }
        if (normalizedUser.contains("trpg")
                || normalizedSystem.contains("trpg")
                || normalizedUser.contains("gm")
                || normalizedSystem.contains("gm")
                || normalizedUser.contains("故事模式")
                || normalizedSystem.contains("剧情模式")) {
            return "story-like";
        }
        return "normal-chat";
    }

    private boolean shouldAttemptPersonaOverlay(String requestKind, String systemText, String userText, ProxyConfig cfg) {
        if ("turtle-soup-judge".equals(requestKind)) {
            return false;
        }
        if ("normal-chat".equals(requestKind) || "interactive-story".equals(requestKind)) {
            return true;
        }
        if ("normal-diary".equals(requestKind) || "holiday-diary".equals(requestKind)) {
            return looksLikeMainPersonaPrompt(normalizeForMatch(systemText), cfg);
        }
        if (!"meta-prompt".equals(requestKind)) {
            return false;
        }
        String normalizedUser = normalizeForMatch(userText);
        String normalizedSystem = normalizeForMatch(systemText);
        if (TextUtils.isEmpty(normalizedSystem) || TextUtils.isEmpty(normalizedUser)) {
            return false;
        }
        if (!looksLikeMainPersonaPrompt(normalizedSystem, cfg)) {
            return false;
        }
        if (looksLikeHealthCheckRequest(normalizedUser, normalizedSystem)
                || looksLikeInteractiveStoryRequest(normalizedUser, normalizedSystem, cfg)
                || looksLikeTurtleSoupJudgeRequest(normalizedUser, normalizedSystem)
                || looksLikeDirectDiaryPrompt(userText)
                || normalizedUser.contains("reply1")
                || normalizedUser.contains("reply2")
                || normalizedUser.contains("json")
                || normalizedUser.contains("长期记忆")
                || normalizedUser.contains("关键事实")) {
            return false;
        }
        return true;
    }

    private boolean looksLikeTurtleSoupJudgeRequest(String normalizedUser, String normalizedSystem) {
        boolean judgeSystem = normalizedSystem.contains("海龟汤裁判")
                && (normalizedSystem.contains("verdict")
                || normalizedSystem.contains("hitfacts")
                || normalizedSystem.contains("只需输出一个合法的json对象")
                || normalizedSystem.contains("判定玩家提问"));
        boolean judgeUserPayload = normalizedUser.contains("汤面")
                && normalizedUser.contains("汤底")
                && normalizedUser.contains("关键事实")
                && (normalizedUser.contains("玩家")
                || normalizedUser.contains("提问")
                || normalizedUser.contains("verdict")
                || normalizedUser.contains("判定"));
        return judgeSystem || judgeUserPayload;
    }

    private boolean looksLikeMainPersonaPrompt(String normalizedSystem, ProxyConfig cfg) {
        if (TextUtils.isEmpty(normalizedSystem)) {
            return false;
        }
        String keywordsText = cfg == null ? ProxyConfig.DEFAULT_PERSONA_MATCH_KEYWORDS : cfg.personaMatchKeywordsText;
        List<String> keywords = splitLines(keywordsText);
        if (keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            String normalized = normalizeForMatch(keyword);
            if (TextUtils.isEmpty(normalized)) {
                continue;
            }
            if (!normalizedSystem.contains(normalized)) {
                return false;
            }
        }
        return true;
    }

    private DiaryDetection detectDiary(String content, ProxyConfig cfg) {
        DiaryDetection detection = new DiaryDetection();
        if (!cfg.detectionEnabled) {
            detection.reason = "detection_disabled";
            return detection;
        }

        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            detection.reason = "empty_text";
            return detection;
        }

        List<String> prefixes = splitLines(cfg.prefixesText);
        List<String> keywords = splitLines(cfg.keywordsText);

        boolean prefixHit = false;
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                prefixHit = true;
                break;
            }
        }

        List<String> keywordHits = new ArrayList<>();
        for (String keyword : keywords) {
            if (!TextUtils.isEmpty(keyword) && text.contains(keyword)) {
                keywordHits.add(keyword);
            }
        }
        boolean keywordHit = !keywordHits.isEmpty();
        boolean lengthHit = text.length() >= cfg.minContentLength;
        int dialogueLines = countDialogueLines(text);
        boolean structureHit = dialogueLines >= cfg.minDialogueLines;

        boolean matched;
        if ("balanced".equals(cfg.strictness)) {
            matched = prefixHit && structureHit && (keywordHit || lengthHit);
        } else if ("relaxed".equals(cfg.strictness)) {
            matched = structureHit && (prefixHit || keywordHit) && (keywordHit || lengthHit);
        } else {
            matched = prefixHit && structureHit && keywordHit && lengthHit;
        }

        if (!matched && looksLikeEmbeddedDiaryPrompt(text)) {
            matched = true;
        }
        if (!matched && looksLikeDirectDiaryPrompt(text)) {
            matched = true;
        }

        detection.matched = matched;
        detection.diaryType = matched ? classifyDiaryType(text) : "none";
        if ("none".equals(detection.diaryType) && matched) {
            detection.diaryType = "normal-diary";
        }
        detection.reason = "prefix=" + prefixHit
                + ",keyword=" + keywordHit
                + ",length=" + lengthHit
                + ",structure=" + structureHit
                + ",lines=" + dialogueLines
                + ",hits=" + keywordHits;
        return detection;
    }

    private String classifyDiaryType(String content) {
        String text = normalizeForMatch(content);
        boolean holiday = (text.contains("特别的日记") && text.contains("今天是"))
                || text.contains("日记主题")
                || text.contains("不要使用标签格式")
                || (text.contains("今天是") && text.contains("体现yuki对"));
        return holiday ? "holiday-diary" : "normal-diary";
    }

    private boolean looksLikeHealthCheckRequest(String normalizedUser, String normalizedSystem) {
        if (TextUtils.isEmpty(normalizedUser)) {
            return false;
        }
        boolean okProbe = normalizedUser.contains("replywithok")
                || normalizedUser.contains("请回复ok")
                || normalizedUser.contains("璇峰洖澶峅k")
                || normalizedUser.contains("收到请回复ok")
                || normalizedUser.contains("收到请回复")
                || normalizedUser.equals("ok");
        if (!okProbe) {
            return false;
        }
        return normalizedUser.length() <= 24
                || normalizedSystem.contains("testassistant")
                || normalizedSystem.contains("测试助手");
    }

    private boolean looksLikeInteractiveStoryRequest(String normalizedUser, String normalizedSystem, ProxyConfig cfg) {
        if (TextUtils.isEmpty(normalizedSystem)) {
            return false;
        }
        String keywordsText = cfg == null ? ProxyConfig.DEFAULT_INTERACTIVE_STORY_KEYWORDS : cfg.interactiveStoryKeywordsText;
        for (String keyword : splitLines(keywordsText)) {
            String normalized = normalizeForMatch(keyword);
            if (!TextUtils.isEmpty(normalized) && normalizedSystem.contains(normalized)) {
                return true;
            }
        }
        return (normalizedSystem.contains("旁白") && normalizedSystem.contains("yuki") && normalizedSystem.contains("哥哥"))
                || normalizedUser.contains("开始故事");
    }

    private boolean looksLikeDirectDiaryPrompt(String text) {
        String normalized = normalizeForMatch(text);
        if (TextUtils.isEmpty(normalized) || !normalized.contains("日记")) {
            return false;
        }
        return (normalized.contains("写成一篇") || normalized.contains("写成1篇"))
                && (normalized.contains("不要记录日期")
                || normalized.contains("以第一人称")
                || normalized.contains("日记记录")
                || normalized.contains("这段小剧场")
                || normalized.contains("妹妹的日记"));
    }

    private int countDialogueLines(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        Matcher matcher = DIALOGUE_LINE_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private TemplateVars extractTemplateVars(String content) {
        TemplateVars vars = new TemplateVars();
        if (TextUtils.isEmpty(content)) {
            return vars;
        }

        String theme = firstMatch(content, Pattern.compile("日记主题[:：]\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE));
        if (!TextUtils.isEmpty(theme)) {
            vars.theme = sanitizeVar(theme);
        }

        String occasion = firstMatch(content, Pattern.compile("今天是\\s*([^，。\\n]+)", Pattern.CASE_INSENSITIVE));
        if (!TextUtils.isEmpty(occasion)) {
            vars.occasion = sanitizeVar(occasion);
        }

        String holidayGender = firstMatch(content, Pattern.compile("体现\\s*Yuki\\s*对\\s*([^\\n，。]+?)\\s*的感情", Pattern.CASE_INSENSITIVE));
        if (!TextUtils.isEmpty(holidayGender)) {
            vars.genderTerm = sanitizeVar(holidayGender);
        } else {
            String[] genderPatterns = new String[]{
                    "你和\\s*([^\\n，。]+?)\\s*之间的全部对话内容",
                    "我和你[（(]\\s*([^）)\\n]+?)\\s*[）)]之间",
                    "把刚才我和你[，,]?\\s*([^，。,\\n]+?)\\s*[，,]?之间"
            };
            for (String regex : genderPatterns) {
                String normalGender = firstMatch(content, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                if (!TextUtils.isEmpty(normalGender)) {
                    vars.genderTerm = sanitizeVar(normalGender);
                    break;
                }
            }
        }
        return vars;
    }

    private DiaryConversationResult resolveDiaryConversation(String content, JSONArray messages) {
        DiaryConversationResult result = new DiaryConversationResult();
        result.conversation = extractEmbeddedDiaryConversation(content);
        if (!TextUtils.isEmpty(result.conversation)) {
            result.source = "embedded_prompt";
            return result;
        }

        result.conversation = buildHistoryConversationTranscript(messages);
        if (isUsableDiaryConversation(result.conversation)) {
            result.source = "messages";
            return result;
        }

        if (!shouldUseRecentChatConversationFallback(content)) {
            result.conversation = "";
            result.source = "missing_request_has_conversation";
            return result;
        }

        result.conversation = loadRecentChatConversation();
        if (isUsableDiaryConversation(result.conversation)) {
            result.source = "recent_chat_cache";
            return result;
        }

        result.conversation = "";
        result.source = "missing";
        return result;
    }

    private boolean shouldUseRecentChatConversationFallback(String content) {
        if (TextUtils.isEmpty(content)) {
            return true;
        }
        if (hasDiaryConversationMarkerLine(content) || looksLikeEmbeddedDiaryPrompt(content)) {
            return false;
        }
        return countDialogueLines(content) < 2;
    }

    private boolean hasDiaryConversationMarkerLine(String content) {
        if (TextUtils.isEmpty(content)) {
            return false;
        }
        return DIARY_CONVERSATION_MARKER_LINE_PATTERN.matcher(content.replace("\r\n", "\n").replace('\r', '\n')).find();
    }

    private String extractEmbeddedDiaryConversation(String content) {
        String normalized = firstNonEmpty(content, "").replace("\r\n", "\n").replace('\r', '\n').trim();
        if (TextUtils.isEmpty(normalized)) {
            return "";
        }

        String afterMarker = extractConversationAfterMarker(normalized, "本次对话记录");
        if (!TextUtils.isEmpty(afterMarker)) {
            return afterMarker;
        }

        afterMarker = extractConversationAfterMarker(normalized, "对话记录");
        if (!TextUtils.isEmpty(afterMarker)) {
            return afterMarker;
        }

        return extractDialogueLines(normalized);
    }

    private String extractConversationAfterMarker(String content, String marker) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(marker)) {
            return "";
        }
        int index = content.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String afterMarker = content.substring(index + marker.length());
        String conversationBlock = extractDiaryConversationBlockAfterMarker(afterMarker);
        if (!TextUtils.isEmpty(conversationBlock)) {
            return conversationBlock;
        }
        return extractDialogueLines(afterMarker);
    }

    private String extractDiaryConversationBlockAfterMarker(String text) {
        String normalized = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n');
        if (TextUtils.isEmpty(normalized)) {
            return "";
        }
        normalized = trimDiaryConversationMarkerTail(normalized);
        int instructionIndex = findEmbeddedMetaPromptIndex(normalized);
        if (instructionIndex < 0) {
            return "";
        }
        String conversation = normalized.substring(0, instructionIndex).trim();
        return isUsableDiaryConversation(conversation) ? conversation : "";
    }

    private static String trimDiaryConversationMarkerTail(String text) {
        String value = firstNonEmpty(text, "").trim();
        while (!TextUtils.isEmpty(value)) {
            char first = value.charAt(0);
            if (first == '】' || first == ']' || first == '：' || first == ':') {
                value = value.substring(1).trim();
                continue;
            }
            break;
        }
        return value;
    }

    private String extractDialogueLines(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder builder = new StringBuilder();
        boolean collecting = false;
        for (String rawLine : lines) {
            String line = trimTrailingWhitespace(firstNonEmpty(rawLine, "")).trim();
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            if (looksLikeStandaloneMetaPrompt(line)) {
                continue;
            }

            int colonIndex = firstColonIndex(line);
            if (colonIndex > 0) {
                String label = line.substring(0, colonIndex).trim();
                if (looksLikeConversationSpeakerLabel(label)) {
                    if (builder.length() > 0) {
                        builder.append("\n\n");
                    }
                    builder.append(line);
                    collecting = true;
                    continue;
                }
                if (collecting && looksLikeConversationMetaLabel(label)) {
                    break;
                }
            }

            if (collecting && !looksLikeConversationMetaLine(line)) {
                builder.append("\n").append(line);
            }
        }
        String conversation = builder.toString().trim();
        return isUsableDiaryConversation(conversation) ? conversation : "";
    }

    private boolean looksLikeConversationSpeakerLabel(String label) {
        String normalized = normalizeForMatch(label);
        if (TextUtils.isEmpty(normalized) || looksLikeConversationMetaLabel(label)) {
            return false;
        }
        return normalized.contains("哥哥")
                || normalized.contains("用户")
                || normalized.contains("player")
                || normalized.contains("yuki")
                || normalized.contains("回复")
                || normalized.contains("assistant")
                || normalized.contains("model")
                || normalized.contains("旁白")
                || normalized.contains("narrator");
    }

    private boolean looksLikeConversationMetaLabel(String label) {
        String normalized = normalizeForMatch(label);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        return normalized.contains("system")
                || normalized.contains("要求")
                || normalized.contains("规则")
                || normalized.contains("注意")
                || normalized.contains("说明")
                || normalized.contains("输出格式")
                || normalized.contains("示例")
                || normalized.contains("限制")
                || normalized.contains("日记主题")
                || normalized.contains("今天是")
                || normalized.contains("本次对话记录")
                || normalized.contains("对话记录");
    }

    private boolean looksLikeConversationMetaLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return false;
        }
        String normalized = normalizeForMatch(line);
        return normalized.startsWith("(system:")
                || normalized.contains("[限制]")
                || normalized.contains("【限制】")
                || normalized.startsWith("要求:")
                || normalized.startsWith("规则:")
                || normalized.startsWith("注意:")
                || normalized.startsWith("输出格式:")
                || normalized.startsWith("本次对话记录");
    }

    private boolean isUsableDiaryConversation(String conversation) {
        return !TextUtils.isEmpty(conversation) && countDialogueLines(conversation) >= 2;
    }

    private RenderTemplateResult renderDiaryTemplate(String template, TemplateVars vars) {
        String source = TextUtils.isEmpty(template) ? ProxyConfig.DEFAULT_NORMAL_TEMPLATE : template;
        RenderTemplateResult result = new RenderTemplateResult();
        result.rendered = source;

        LinkedHashSet<String> requiredVars = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_VAR_PATTERN.matcher(source);
        while (matcher.find()) {
            requiredVars.add(matcher.group(1));
        }
        for (String varName : requiredVars) {
            String value = resolveTemplateVar(varName, vars);
            if (TextUtils.isEmpty(value)) {
                result.missingVars.add(varName);
            }
        }
        if (!result.missingVars.isEmpty()) {
            result.success = false;
            return result;
        }

        LinkedHashMap<String, String> resolvedVars = new LinkedHashMap<>();
        for (String varName : requiredVars) {
            String value = resolveTemplateVar(varName, vars);
            resolvedVars.put(varName, value);
            result.rendered = result.rendered.replace("${" + varName + "}", value);
        }
        result.resolvedVarsSummary = buildResolvedVarSummary(resolvedVars);
        result.success = true;
        return result;
    }

    private String ensureDiaryConversationAttached(String rendered, String template, String conversation) {
        String base = firstNonEmpty(rendered, "").trim();
        String transcript = firstNonEmpty(conversation, "").trim();
        if (TextUtils.isEmpty(base) || TextUtils.isEmpty(transcript)) {
            return base;
        }
        if (containsTemplateVar(template, "conversation") || containsTemplateVar(template, "dialogue")) {
            return base;
        }
        if (base.contains(transcript)) {
            return base;
        }
        return base + "\n\n本次对话记录：\n" + transcript;
    }

    private boolean containsTemplateVar(String template, String name) {
        if (TextUtils.isEmpty(template) || TextUtils.isEmpty(name)) {
            return false;
        }
        return template.contains("${" + name + "}");
    }

    private String buildConversationTemplateSummary(String source, String conversation) {
        if (TextUtils.isEmpty(conversation)) {
            return "";
        }
        return "conversation=" + firstNonEmpty(source, "unknown") + "(" + countDialogueLines(conversation) + "行)";
    }

    private String appendResolvedVarSummary(String base, String extra) {
        if (TextUtils.isEmpty(extra)) {
            return base;
        }
        if (TextUtils.isEmpty(base)) {
            return extra;
        }
        return base + "; " + extra;
    }

    private String buildResolvedVarSummary(Map<String, String> resolvedVars) {
        if (resolvedVars == null || resolvedVars.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : resolvedVars.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey())
                    .append("=")
                    .append(clip(entry.getValue(), 36));
        }
        return builder.toString();
    }

    private String firstMatch(String text, Pattern pattern) {
        if (TextUtils.isEmpty(text) || pattern == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && matcher.groupCount() >= 1 ? matcher.group(1) : "";
    }

    private String sanitizeVar(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (!result.isEmpty() && (result.startsWith("\"") || result.startsWith("'"))) {
            result = result.substring(1).trim();
        }
        while (!result.isEmpty() && (result.endsWith("\"") || result.endsWith("'"))) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private String resolveTemplateVar(String name, TemplateVars vars) {
        if (TextUtils.isEmpty(name) || vars == null) {
            return "";
        }
        if ("genderTerm".equals(name)) {
            return vars.genderTerm;
        }
        if ("occasion".equals(name)) {
            return vars.occasion;
        }
        if ("theme".equals(name)) {
            return vars.theme;
        }
        if ("conversation".equals(name) || "dialogue".equals(name)) {
            return vars.conversation;
        }
        return "";
    }

    private void rememberRecentChatConversation(ChatDecision decision, String originalConversation, String forwardedConversation) {
        if (decision == null) {
            return;
        }
        if (!"normal-chat".equals(decision.requestKind) && !"interactive-story".equals(decision.requestKind)) {
            return;
        }
        String transcript = firstNonEmpty(originalConversation, forwardedConversation, "").trim();
        if (!isUsableDiaryConversation(transcript)) {
            return;
        }
        recentChatConversation = transcript;
        recentChatConversationAtMs = System.currentTimeMillis();
    }

    private String loadRecentChatConversation() {
        long savedAt = recentChatConversationAtMs;
        if (savedAt <= 0L) {
            return "";
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - savedAt);
        if (ageMs > RECENT_CHAT_CONVERSATION_MAX_AGE_MS) {
            return "";
        }
        return firstNonEmpty(recentChatConversation, "").trim();
    }

    private static String appendReason(String base, String extra) {
        if (TextUtils.isEmpty(extra)) {
            return base;
        }
        if (TextUtils.isEmpty(base)) {
            return extra;
        }
        return base + "," + extra;
    }

    private static JSONArray resolveMessages(JSONObject payload, ProxyConfig cfg) {
        if (payload == null) {
            return null;
        }
        return payload.optJSONArray("messages");
    }

    private static void applyMessages(JSONObject payload, ProxyConfig cfg, JSONArray messages) {
        if (payload == null || messages == null) {
            return;
        }
        try {
            payload.put("messages", messages);
        } catch (Exception ignored) {
        }
    }

    private static String resolveRequestModel(JSONObject payload, ProxyConfig cfg) {
        if (payload == null) {
            return "";
        }
        return payload.optString("model", "");
    }

    private static void applyConfiguredModel(JSONObject payload, ProxyConfig cfg) {
        if (payload == null || TextUtils.isEmpty(cfg.model)) {
            return;
        }
        try {
            payload.put("model", cfg.model);
        } catch (Exception ignored) {
        }
    }

    private static boolean applyConfiguredMaxTokens(JSONObject payload, ProxyConfig cfg, int value) {
        if (payload == null || value <= 0) {
            return false;
        }
        try {
            payload.put("max_tokens", value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean applyConfiguredTemperature(JSONObject payload, double value) {
        if (payload == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return false;
        }
        try {
            payload.put("temperature", value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean applyConfiguredEnableThinking(JSONObject payload, Boolean value) {
        if (payload == null || value == null) {
            return false;
        }
        try {
            // v1.5.2+：DeepSeek V4 协议把思考开关从 enable_thinking:bool 改成 thinking:{type:enabled|disabled}
            // 旧字段已废弃，发了会被官方静默忽略，导致 v4-flash 默认进思考模式（见 DeepSeek V4 文档）
            payload.remove("enable_thinking");
            payload.put("thinking", new JSONObject()
                    .put("type", value.booleanValue() ? "enabled" : "disabled"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * v1.5.6+：DPS-19 — 把 OpenAI 中间格式 `thinking:{type:enabled|disabled}` 翻译成 Claude
     * 协议字段。仅在用户主动启用思考时写入；关闭时不动（Claude 默认即不思考）。
     * Claude 官方要求 type=enabled 必须带 budget_tokens，这里取 1024 作为安全默认值。
     */
    private static void applyThinkingForClaude(JSONObject upstream, JSONObject localPayload) throws JSONException {
        JSONObject thinking = localPayload == null ? null : localPayload.optJSONObject("thinking");
        if (thinking == null) return;
        String type = thinking.optString("type", "");
        if ("enabled".equalsIgnoreCase(type)) {
            upstream.put("thinking", new JSONObject()
                    .put("type", "enabled")
                    .put("budget_tokens", 1024));
        }
        // disabled → Claude 默认就不思考，不写字段
    }

    /**
     * v1.5.6+：DPS-19 — Gemini 2.5 系列通过 generationConfig.thinkingConfig.thinkingBudget 控制思考。
     * 0=关闭；-1=动态预算（让模型自己决定）。其它非思考型 Gemini 模型会忽略此字段（兼容）。
     */
    private static void applyThinkingForGemini(JSONObject generationConfig, JSONObject localPayload) throws JSONException {
        JSONObject thinking = localPayload == null ? null : localPayload.optJSONObject("thinking");
        if (thinking == null) return;
        String type = thinking.optString("type", "");
        if ("disabled".equalsIgnoreCase(type)) {
            generationConfig.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        } else if ("enabled".equalsIgnoreCase(type)) {
            generationConfig.put("thinkingConfig", new JSONObject().put("thinkingBudget", -1));
        }
    }

    /**
     * v1.5.6+：DPS-19 — OpenAI Responses 协议（o-系列 / GPT-5 等）通过 reasoning.effort 控制思考。
     * enabled → medium；disabled → minimal。非思考模型上游会忽略此字段。
     */
    private static void applyThinkingForResponses(JSONObject upstream, JSONObject localPayload) throws JSONException {
        JSONObject thinking = localPayload == null ? null : localPayload.optJSONObject("thinking");
        if (thinking == null) return;
        String type = thinking.optString("type", "");
        if ("enabled".equalsIgnoreCase(type)) {
            upstream.put("reasoning", new JSONObject().put("effort", "medium"));
        } else if ("disabled".equalsIgnoreCase(type)) {
            upstream.put("reasoning", new JSONObject().put("effort", "minimal"));
        }
    }

    private static boolean removeEnableThinkingField(JSONObject payload) {
        if (payload == null) {
            return false;
        }
        boolean removed = false;
        if (payload.has("enable_thinking")) {
            payload.remove("enable_thinking");
            removed = true;
        }
        // v1.5.2+：同时清掉新协议字段，避免转发到不识别 thinking 对象的上游
        if (payload.has("thinking")) {
            payload.remove("thinking");
            removed = true;
        }
        return removed;
    }

    private static MessageRef findFirstMessage(JSONArray messages, String role) {
        if (messages == null) {
            return null;
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && role.equalsIgnoreCase(message.optString("role", ""))) {
                return new MessageRef(i, message);
            }
        }
        return null;
    }

    private static MessageRef findLastMessage(JSONArray messages, String role) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && role.equalsIgnoreCase(message.optString("role", ""))) {
                return new MessageRef(i, message);
            }
        }
        return null;
    }

    private static String getMessageText(JSONObject message) {
        if (message == null) {
            return "";
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject part = (JSONObject) item;
                    // v1.5.6+：DPS-12 — 不仅识别 type="text"，对 Claude 的 input_text、自定义
                    // markdown 等其它带文本字段的 part 也兜底抓取，避免 buildOpenAiCompatibleContent
                    // 在重写 content 时把原文丢失。
                    String type = part.optString("type", "text");
                    if ("text".equalsIgnoreCase(type)) {
                        builder.append(part.optString("text", ""));
                    } else {
                        // 优先 text，其次 input_text，再次 content（如有）
                        String text = part.optString("text", "");
                        if (TextUtils.isEmpty(text)) {
                            text = part.optString("input_text", "");
                        }
                        if (!TextUtils.isEmpty(text)) {
                            builder.append(text);
                        }
                    }
                } else if (item != null && item != JSONObject.NULL) {
                    builder.append(String.valueOf(item));
                }
            }
            return builder.toString();
        }
        return content == null || content == JSONObject.NULL ? "" : String.valueOf(content);
    }

    private static void setMessageText(JSONObject message, String text) {
        if (message == null) {
            return;
        }
        try {
            message.put("content", text == null ? "" : text);
        } catch (Exception ignored) {
        }
    }

    private static boolean disableStreamingRequest(JSONObject payload) {
        if (payload == null) {
            return false;
        }
        Object streamValue = payload.opt("stream");
        boolean requested = isTruthy(streamValue);
        if (requested || streamValue != null) {
            try {
                payload.put("stream", false);
            } catch (Exception ignored) {
            }
        }
        return requested;
    }

    private String normalizeResponse(String body, ChatDecision decision) {
        if (TextUtils.isEmpty(body)) {
            return body;
        }
        try {
            JSONObject root = new JSONObject(body);
            String text = extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
            // v1.5.0：兼容 GLM-4.5V / Qwen-VL 等把内容放在 reasoning_content 等字段
            if (TextUtils.isEmpty(text)) {
                String fallback = extractFallbackImageReply(body);
                if (!TextUtils.isEmpty(fallback)) {
                    text = fallback;
                }
            }

            ProxyConfig runtimeCfg = config == null ? new ProxyConfig().ensureDefaults() : config.copy();
            boolean diaryPrefixActive = decision != null
                    && decision.diaryMatched
                    && runtimeCfg.diaryAssistantPrefixEnabled
                    && !TextUtils.isEmpty(runtimeCfg.diaryAssistantPrefix);

            if (TextUtils.isEmpty(text)) {
                // 上游确实没回任何文本。典型场景：
                //   1. Gemini SAFETY / RECITATION / PROHIBITED_CONTENT 过滤拦截（敏感角色 / 长篇亲密叙事高发）
                //   2. Gemini prefill 续写不稳定，模型把"【日记】"判断为完整回复立即 STOP
                //   3. max_tokens 过小，模型还没开始写就被截断
                //   4. 网络异常 / 上游 SSE 截断 / 适配器解析未命中
                // 非日记请求保持原透传行为，避免对其他 adapter 产生意外副作用；
                // 日记请求若启用了前缀续写，把"前缀 + 原因提示"拼回 content，避免游戏端看到完全空白且无任何线索。
                if (!diaryPrefixActive) {
                    return body;
                }
                String resolvedPrefix = unescapeUserPrefix(runtimeCfg.diaryAssistantPrefix);
                String reasonHint = describeEmptyDiaryReason(root);
                String placeholder = resolvedPrefix + "（模型未返回内容，" + reasonHint + "）";
                setResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH, placeholder);
                return root.toString();
            }

            String cleaned = stripThinkBlocks(text);
            if (TextUtils.isEmpty(cleaned)) {
                cleaned = "（模型返回空内容，请重试）";
            }
            if (decision != null && decision.diaryMatched && runtimeCfg.truncateEnabled) {
                cleaned = truncateText(cleaned, runtimeCfg.maxChars);
            }
            // 日记前缀续写：上游（Claude/Gemini/DeepSeek prefix=true）只回吐续写内容，不带前缀本身。
            // 把用户配置的前缀拼回 choices[0].message.content，让游戏端拿到 "【日记】今天…" 这样的完整文本。
            // 若上游已自带前缀（部分 OpenAI 兼容端点会回吐），跳过避免双重 【日记】【日记】。
            // 兼容点：
            //   1. 用户在多行 EditText 输入真换行 / 写字面 \n，统一通过 unescapeUserPrefix 解析成实际字符；
            //   2. 模型 echo 回前缀时可能因 tokenization 把末尾空白/换行吞掉，所以匹配时只比"前缀去尾空白"，
            //      而 prepend 时仍用完整解析后的前缀（保证 \n 标题语义不丢）。
            if (diaryPrefixActive) {
                String resolvedPrefix = unescapeUserPrefix(runtimeCfg.diaryAssistantPrefix);
                String prefixCore = resolvedPrefix.replaceAll("\\s+$", "");
                if (TextUtils.isEmpty(prefixCore) || !cleaned.startsWith(prefixCore)) {
                    cleaned = resolvedPrefix + cleaned;
                }
            }

            setResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH, cleaned);
            return root.toString();
        } catch (Exception ignored) {
            return body;
        }
    }

    /**
     * v1.5.x：日记请求 + 前缀续写场景下，从归一化后的 OpenAI 格式 body 推断空响应原因。
     * 入参的 finish_reason 已被 {@link #normalizeFinishReason} 折叠为小写英文短语。
     */
    private static String describeEmptyDiaryReason(JSONObject normalizedRoot) {
        String finishReason = "";
        JSONArray choices = normalizedRoot == null ? null : normalizedRoot.optJSONArray("choices");
        // v1.5.6+：DPS-15 — choices 缺失时不应误判为"模型立即终止"。这种情况通常是
        // 上游响应非标（schema 变了 / 走了错的 adapter / 超时回退到 error 体），单独提示。
        if (choices == null) {
            return "上游响应异常（无 choices 字段）。请检查 adapter / 模型名 / 上游服务可用性，"
                    + "并查看 debug/*.txt 原始响应排查";
        }
        if (choices.length() > 0) {
            JSONObject choice0 = choices.optJSONObject(0);
            if (choice0 != null) {
                finishReason = choice0.optString("finish_reason", "");
            }
        }
        String lower = finishReason.toLowerCase(Locale.ROOT);
        if (lower.contains("safety") || lower.contains("content_filter") || lower.contains("prohibited")) {
            return "可能因上游安全策略拦截 [finish_reason=" + finishReason
                    + "]，建议换 Claude / OpenAI 适配器，或调整人物设定降低敏感度后重试";
        }
        if (lower.contains("recitation")) {
            return "可能因输出与训练数据相似度过高 [finish_reason=" + finishReason + "]，建议调整 prompt 后重试";
        }
        if (lower.contains("length")) {
            return "max_tokens 过小，模型还没开始写就被截断 [finish_reason=" + finishReason
                    + "]，建议调大日记 max_tokens";
        }
        if (lower.equals("stop") || TextUtils.isEmpty(lower)) {
            return "模型立即终止（Gemini prefill 续写有时不稳定，建议换 Claude / OpenAI 适配器，"
                    + "或在前缀文本里多写几个字让模型更明确续写起点）";
        }
        return "finish_reason=" + finishReason;
    }

    private static String normalizeChatResponse(String body, int status, JSONObject localPayload, ProxyConfig cfg) {
        if (TextUtils.isEmpty(body) || cfg == null || status >= 400) {
            return body;
        }
        try {
            JSONObject root = new JSONObject(body);
            if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(cfg.adapterPreset)) {
                return buildLocalChatResponse(
                        extractClaudeResponseText(root),
                        firstNonEmpty(root.optString("model", ""), resolveRequestModel(localPayload, cfg), cfg.model),
                        root.optString("id", ""),
                        normalizeFinishReason(root.optString("stop_reason", "")),
                        buildUsageObject(root.optJSONObject("usage"), "input_tokens", "output_tokens", "input_tokens", "output_tokens", "total_tokens")
                );
            }
            if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(cfg.adapterPreset)) {
                return buildLocalChatResponse(
                        extractOpenAiResponsesText(root),
                        firstNonEmpty(root.optString("model", ""), resolveRequestModel(localPayload, cfg), cfg.model),
                        root.optString("id", ""),
                        normalizeFinishReason(firstNonEmpty(root.optString("finish_reason", ""), root.optString("status", ""))),
                        buildUsageObject(root.optJSONObject("usage"), "input_tokens", "output_tokens", "prompt_tokens", "completion_tokens", "total_tokens")
                );
            }
            if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset)) {
                return buildLocalChatResponse(
                        extractGeminiResponseText(root),
                        firstNonEmpty(resolveRequestModel(localPayload, cfg), cfg.model),
                        root.optString("responseId", ""),
                        normalizeFinishReason(JsonPathUtils.getString(root, "candidates[0].finishReason")),
                        buildUsageObject(root.optJSONObject("usageMetadata"), "promptTokenCount", "candidatesTokenCount", "promptTokenCount", "candidatesTokenCount", "totalTokenCount")
                );
            }
            if (ProxyConfig.ADAPTER_GENERIC_CUSTOM.equals(cfg.adapterPreset)) {
                String text = extractResponseText(root, cfg.resolvedResponseTextPath());
                if (!TextUtils.isEmpty(text)) {
                    return buildLocalChatResponse(
                            text,
                            firstNonEmpty(resolveRequestModel(localPayload, cfg), cfg.model),
                            "",
                            "stop",
                            null
                    );
                }
            }
            return body;
        } catch (Exception ignored) {
            return body;
        }
    }

    private static String normalizeModelsResponse(String body, int status, ProxyConfig cfg) {
        if (TextUtils.isEmpty(body) || status >= 400) {
            return body;
        }
        try {
            LinkedHashSet<String> modelIds = new LinkedHashSet<>();
            collectModelIds(parseJsonValue(body), modelIds, 0);
            if (modelIds.isEmpty()) {
                return body;
            }

            JSONArray data = new JSONArray();
            for (String modelId : modelIds) {
                String normalized = normalizeModelIdForDisplay(modelId, cfg);
                if (TextUtils.isEmpty(normalized)) {
                    continue;
                }
                data.put(new JSONObject()
                        .put("id", normalized)
                        .put("object", "model"));
            }
            if (data.length() == 0) {
                return body;
            }

            return new JSONObject()
                    .put("object", "list")
                    .put("data", data)
                    .toString();
        } catch (Exception ignored) {
            return body;
        }
    }

    private static String extractResponseText(JSONObject root, String configuredPath) {
        String text = JsonPathUtils.getString(root, configuredPath);
        if (!TextUtils.isEmpty(text)) {
            return text;
        }
        text = JsonPathUtils.getString(root, "choices[0].message.content");
        if (!TextUtils.isEmpty(text)) {
            return text;
        }
        text = JsonPathUtils.getString(root, "choices[0].text");
        if (!TextUtils.isEmpty(text)) {
            return text;
        }
        return JsonPathUtils.getString(root, "output_text");
    }

    private static boolean setResponseText(JSONObject root, String path, String text) {
        if (root == null || TextUtils.isEmpty(path)) {
            return false;
        }
        Object existing = JsonPathUtils.getValue(root, path);
        if (existing != null || ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH.equals(path)) {
            return JsonPathUtils.setValue(root, path, text);
        }
        return false;
    }

    private static String stripThinkBlocks(String content) {
        if (TextUtils.isEmpty(content)) {
            return "";
        }
        return THINK_PATTERN.matcher(content)
                .replaceAll("")
                .replaceAll("\\n\\s*\\n\\s*\\n", "\n\n")
                .trim();
    }

    private static String truncateText(String text, int maxChars) {
        if (TextUtils.isEmpty(text) || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.min(maxChars, text.length()))
                .replaceAll("[，。、；：,.!?！？]+$", "")
                + "...";
    }

    private static int extractAssistantLength(String body, ProxyConfig cfg) {
        try {
            JSONObject root = new JSONObject(body);
            return extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String extractAssistantPreview(String body, ProxyConfig cfg) {
        try {
            JSONObject root = new JSONObject(body);
            return extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * v1.5.0：当标准 choices[0].message.content 为空时，依次尝试 GLM-4.5V / Qwen-VL 等模型
     * 常用的备选字段：
     *   - choices[0].message.reasoning_content（GLM 思考链）
     *   - choices[0].message.content[]（OpenAI multimodal 形式 array）
     *   - choices[0].delta.content（流式合并未归一化）
     *   - choices[0].text（旧版）
     *   - 顶层 reasoning / output_text
     *  最后兜底返回原 body 截断。
     */
    private static String extractFallbackImageReply(String body) {
        if (TextUtils.isEmpty(body)) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            JSONObject first = choices == null ? null : choices.optJSONObject(0);
            if (first != null) {
                JSONObject message = first.optJSONObject("message");
                if (message != null) {
                    String reasoning = message.optString("reasoning_content", "");
                    if (!TextUtils.isEmpty(reasoning)) return reasoning;
                    Object content = message.opt("content");
                    if (content instanceof JSONArray) {
                        JSONArray arr = (JSONArray) content;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject part = arr.optJSONObject(i);
                            if (part == null) continue;
                            String text = part.optString("text", "");
                            if (!TextUtils.isEmpty(text)) {
                                if (sb.length() > 0) sb.append(" ");
                                sb.append(text);
                            }
                        }
                        if (sb.length() > 0) return sb.toString();
                    }
                }
                JSONObject delta = first.optJSONObject("delta");
                if (delta != null) {
                    String deltaText = delta.optString("content", "");
                    if (!TextUtils.isEmpty(deltaText)) return deltaText;
                }
                String legacyText = first.optString("text", "");
                if (!TextUtils.isEmpty(legacyText)) return legacyText;
            }
            String topReasoning = root.optString("reasoning", "");
            if (!TextUtils.isEmpty(topReasoning)) return topReasoning;
            String topOutput = root.optString("output_text", "");
            if (!TextUtils.isEmpty(topOutput)) return topOutput;
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String extractHistoryResponseText(String body, ProxyConfig cfg) {
        String text = extractAssistantPreview(body, cfg);
        if (!TextUtils.isEmpty(text)) {
            return text;
        }
        return clip(firstNonEmpty(body, ""), 4000);
    }

    private UpstreamResponse forwardChat(JSONObject payload, ProxyConfig cfg, ForwardTrace trace) throws IOException, JSONException {
        return forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg, trace);
    }

    private UpstreamResponse forwardModels(ProxyConfig cfg) throws IOException {
        return forwardRaw(buildModelsUpstreamRequest(cfg), cfg, null);
    }

    private static UpstreamResponse forwardRaw(UpstreamRequest request, ProxyConfig cfg) throws IOException {
        return forwardRaw(request, cfg, null);
    }

    private static UpstreamResponse forwardRaw(UpstreamRequest request, ProxyConfig cfg, ForwardTrace trace) throws IOException {
        IOException firstError = null;
        if (trace != null) {
            trace.attemptCount = 1;
        }
        try {
            return forwardRawOnce(request, cfg, trace, false);
        } catch (IOException error) {
            if (trace != null) {
                trace.firstError = describeIoError(error);
                trace.lastError = trace.firstError;
            }
            if (!isRetryableTransportError(error)) {
                throw error;
            }
            firstError = error;
        }
        if (trace != null) {
            trace.retriedWithConnectionClose = true;
            trace.attemptCount = 2;
        }
        try {
            return forwardRawOnce(request, cfg, trace, true);
        } catch (IOException retryError) {
            if (trace != null) {
                trace.lastError = describeIoError(retryError);
            }
            if (firstError != null) {
                retryError.addSuppressed(firstError);
            }
            throw retryError;
        }
    }

    private static UpstreamResponse forwardRawOnce(UpstreamRequest request, ProxyConfig cfg, ForwardTrace trace, boolean forceConnectionClose) throws IOException {
        long forwardStartedAt = SystemClock.elapsedRealtime();
        URL url = buildUpstreamUrl(request.path, cfg);
        if (trace != null) {
            trace.url = url.toString();
            trace.method = request == null ? "" : request.method;
            trace.proxyType = cfg == null ? "" : cfg.resolvedUpstreamProxyType();
        }
        long openStartedAt = SystemClock.elapsedRealtime();
        HttpURLConnection conn = hasUpstreamProxy(cfg)
                ? (HttpURLConnection) url.openConnection(buildUpstreamProxy(cfg))
                : (HttpURLConnection) url.openConnection();
        if (trace != null) {
            trace.openMs = SystemClock.elapsedRealtime() - openStartedAt;
        }
        conn.setRequestMethod(request.method);
        conn.setConnectTimeout(cfg.timeoutMs);
        conn.setReadTimeout(cfg.timeoutMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", forceConnectionClose ? "close" : "keep-alive");
        if (request.headers != null) {
            for (Map.Entry<String, String> entry : request.headers.entrySet()) {
                if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        if ("POST".equals(request.method) && request.body != null) {
            conn.setDoOutput(true);
            byte[] out = request.body.getBytes(StandardCharsets.UTF_8);
            if (trace != null) {
                trace.requestBytes = out.length;
            }
            conn.setFixedLengthStreamingMode(out.length);
            long writeStartedAt = SystemClock.elapsedRealtime();
            try (OutputStream outputStream = new BufferedOutputStream(conn.getOutputStream())) {
                outputStream.write(out);
            }
            if (trace != null) {
                trace.writeMs = SystemClock.elapsedRealtime() - writeStartedAt;
            }
        }

        long waitStartedAt = SystemClock.elapsedRealtime();
        int status = conn.getResponseCode();
        if (trace != null) {
            trace.waitResponseMs = SystemClock.elapsedRealtime() - waitStartedAt;
            trace.statusCode = status;
        }
        String contentType = conn.getHeaderField("Content-Type");
        if (TextUtils.isEmpty(contentType)) {
            contentType = "application/json";
        }
        if (isStreamingContentType(contentType)) {
            if (trace != null) {
                trace.totalMs = SystemClock.elapsedRealtime() - forwardStartedAt;
            }
            conn.disconnect();
            return new UpstreamResponse(status, contentType, "", "UTF-8", 0, true);
        }
        InputStream inputStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        long readStartedAt = SystemClock.elapsedRealtime();
        byte[] raw = readAllBytes(inputStream);
        if (trace != null) {
            trace.readBodyMs = SystemClock.elapsedRealtime() - readStartedAt;
            trace.responseBytes = raw == null ? 0 : raw.length;
            trace.totalMs = SystemClock.elapsedRealtime() - forwardStartedAt;
        }
        DecodedBody decoded = decodeBody(raw, contentType);
        // v1.5.5+：DPS-13 — body 已完整读取，让 HttpURLConnection 把底层 socket 归还 keep-alive
        // 池给下次同 host 请求复用；早先无条件 disconnect() 与请求头 Connection:keep-alive 自相矛盾，
        // 实际让每次请求都重做 TLS 握手。流式早返路径仍需 disconnect()（见 line 2282）。
        String normalizedContentType = ensureCharsetInContentType(contentType, decoded.charset);
        return new UpstreamResponse(
                status,
                normalizedContentType,
                decoded.text,
                decoded.charset,
                decoded.replacementCount,
                looksLikeStreamingBody(decoded.text)
        );
    }

    private static boolean isRetryableTransportError(IOException error) {
        if (error == null) {
            return false;
        }
        String message = firstNonEmpty(error.getMessage(), "").toLowerCase(Locale.ROOT);
        return message.contains("unexpected end of stream")
                || message.contains("connection reset")
                || message.contains("broken pipe")
                || message.contains("software caused connection abort")
                || message.contains("connection aborted")
                || message.contains("eof");
    }

    private static String describeIoError(IOException error) {
        if (error == null) {
            return "";
        }
        String simpleName = error.getClass().getSimpleName();
        String message = firstNonEmpty(error.getMessage(), "");
        return TextUtils.isEmpty(message) ? simpleName : simpleName + ":" + message;
    }

    public static String validateUpstreamConfig(ProxyConfig cfg) {
        if (cfg == null) {
            return "当前配置为空。";
        }
        String baseUrlError = cfg.validateUpstreamBaseUrl();
        if (!TextUtils.isEmpty(baseUrlError)) {
            return baseUrlError;
        }
        String proxyType = cfg.resolvedUpstreamProxyType();
        if (!ProxyConfig.UPSTREAM_PROXY_DIRECT.equals(proxyType)) {
            if (TextUtils.isEmpty(cfg.upstreamProxyHost) || cfg.upstreamProxyPort <= 0) {
                return "已启用上游代理，请填写完整的代理主机和端口。";
            }
        }
        return "";
    }

    private static URL buildUpstreamUrl(String path, ProxyConfig cfg) throws IOException {
        String validationError = validateUpstreamConfig(cfg);
        if (!TextUtils.isEmpty(validationError)) {
            throw new IOException(validationError);
        }
        String base = cfg.normalizedUpstreamBaseUrl();
        String resolvedPath = resolvePathTemplate(path, cfg);
        try {
            return new URL(base + resolvedPath);
        } catch (Exception error) {
            throw new IOException("拼接上游 URL 失败：" + error.getMessage(), error);
        }
    }

    private static boolean hasUpstreamProxy(ProxyConfig cfg) {
        return cfg != null && cfg.hasEnabledUpstreamProxy();
    }

    private static Proxy buildUpstreamProxy(ProxyConfig cfg) {
        return new Proxy(
                ProxyConfig.UPSTREAM_PROXY_SOCKS5.equals(cfg == null ? "" : cfg.resolvedUpstreamProxyType())
                        ? Proxy.Type.SOCKS
                        : Proxy.Type.HTTP,
                new InetSocketAddress(cfg.upstreamProxyHost.trim(), cfg.upstreamProxyPort)
        );
    }

    public static String performUpstreamTest(ProxyConfig cfg) {
        try {
            String validationError = validateUpstreamConfig(cfg);
            if (!TextUtils.isEmpty(validationError)) {
                return "error=" + validationError;
            }
            JSONObject payload = new JSONObject();
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "You are a test assistant."));
            messages.put(new JSONObject().put("role", "user").put("content", "Reply with OK"));

            payload.put("messages", messages);
            payload.put("model", cfg.model);
            payload.put("max_tokens", 16);
            payload.put("temperature", 0);

            UpstreamResponse response = forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg);
            if (response.streaming) {
                return "error=上游返回了流式响应，当前测试仅支持非流式";
            }
            String localBody = normalizeChatResponse(response.body, response.status, payload, cfg);
            return "status=" + response.status + " preview=" + clip(extractAssistantPreview(localBody, cfg), 160);
        } catch (Exception error) {
            return "error=" + error.getMessage();
        }
    }

    public static class UpstreamFeatureTestResult {
        public static final int STATE_SUCCESS = 0;
        public static final int STATE_FAIL = 1;
        public static final int STATE_UNSUPPORTED = 2;

        public final int state;
        public final int statusCode;
        public final String detail;

        private UpstreamFeatureTestResult(int s, int code, String d) {
            this.state = s;
            this.statusCode = code;
            this.detail = d == null ? "" : d;
        }

        public static UpstreamFeatureTestResult success(int code, String detail) {
            return new UpstreamFeatureTestResult(STATE_SUCCESS, code, detail);
        }

        public static UpstreamFeatureTestResult fail(int code, String detail) {
            return new UpstreamFeatureTestResult(STATE_FAIL, code, detail);
        }

        public static UpstreamFeatureTestResult unsupported(String detail) {
            return new UpstreamFeatureTestResult(STATE_UNSUPPORTED, 0, detail);
        }

        public boolean isSuccess() {
            return state == STATE_SUCCESS;
        }
    }

    public static UpstreamFeatureTestResult performUpstreamTextTest(ProxyConfig cfg) {
        try {
            String validationError = validateUpstreamConfig(cfg);
            if (!TextUtils.isEmpty(validationError)) {
                return UpstreamFeatureTestResult.fail(0, validationError);
            }
            JSONObject payload = new JSONObject();
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "You are a test assistant."));
            messages.put(new JSONObject().put("role", "user").put("content", "Reply with OK"));
            payload.put("messages", messages);
            payload.put("model", cfg.model);
            payload.put("max_tokens", 16);
            payload.put("temperature", 0);

            UpstreamResponse response = forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg);
            if (response.streaming) {
                return UpstreamFeatureTestResult.fail(0, "上游返回流式响应");
            }
            if (response.status >= 200 && response.status < 400) {
                return UpstreamFeatureTestResult.success(response.status, "");
            }
            return UpstreamFeatureTestResult.fail(response.status,
                    "status=" + response.status + " " + clip(response.body, 80));
        } catch (Exception error) {
            return UpstreamFeatureTestResult.fail(0, firstNonEmpty(error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    public static UpstreamFeatureTestResult performUpstreamImageTest(ProxyConfig cfg) {
        try {
            String validationError = validateUpstreamConfig(cfg);
            if (!TextUtils.isEmpty(validationError)) {
                return UpstreamFeatureTestResult.fail(0, validationError);
            }
            String testCode = buildImageTestCode();
            String testImageBase64 = buildImageTestJpegBase64(testCode);
            List<ImageTestVariant> variants = buildImageTestVariants(cfg, testImageBase64);
            UpstreamFeatureTestResult lastResult = null;
            String lastSuccessPreview = null;
            int lastSuccessStatus = 0;
            for (ImageTestVariant variant : variants) {
                JSONObject payload = buildImageTestPayload(cfg, testCode, variant);
                UpstreamResponse response = forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg);
                if (response.streaming) {
                    lastResult = UpstreamFeatureTestResult.fail(0, "上游返回流式响应");
                    continue;
                }
                if (response.status >= 200 && response.status < 400) {
                    String localBody = normalizeChatResponse(response.body, response.status, payload, cfg);
                    String preview = extractAssistantPreview(localBody, cfg);
                    // 兼容 GLM-4.5V 等：把内容放在 reasoning_content / message.reasoning_content / 顶层 reasoning 等字段
                    if (TextUtils.isEmpty(preview)) {
                        preview = extractFallbackImageReply(response.body);
                    }
                    String digitsOnly = safeNonNull(preview).replaceAll("[^0-9]", "");
                    if (digitsOnly.contains(testCode)) {
                        return UpstreamFeatureTestResult.success(response.status,
                                "已识别测试图片中的验证码 " + testCode + "（回复：" + clip(preview, 60) + "）");
                    }
                    // 视觉模型已接受图片但读错/拒答：保留作为最后回退（视为部分通过：图片协议 OK）
                    String rawPreview = TextUtils.isEmpty(preview)
                            ? "（提取空）原始 body：" + clip(response.body, 200)
                            : clip(preview, 160);
                    lastResult = UpstreamFeatureTestResult.fail(
                            response.status,
                            "图片格式被接受但未读出验证码 " + testCode
                                    + "。模型回复：" + rawPreview
                                    + "（如果模型确实分析了图但读错，可能是视觉精度问题，不影响实际多模态使用）"
                    );
                    continue;
                }
                String body = response.body == null ? "" : response.body;
                lastResult = isExplicitUnsupportedImageError(body)
                        ? UpstreamFeatureTestResult.unsupported("上游明确表示该模型不支持图片输入：" + clip(body, 120))
                        : UpstreamFeatureTestResult.fail(response.status,
                        "status=" + response.status + " " + clip(body, 200));
            }
            return lastResult == null
                    ? UpstreamFeatureTestResult.fail(0, "图片测试没有可用请求变体")
                    : lastResult;
        } catch (Exception error) {
            return UpstreamFeatureTestResult.fail(0, firstNonEmpty(error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    private static List<ImageTestVariant> buildImageTestVariants(ProxyConfig cfg, String jpegBase64) {
        ArrayList<ImageTestVariant> variants = new ArrayList<>();
        String model = cfg == null ? "" : firstNonEmpty(cfg.model, "").toLowerCase(Locale.ROOT);
        String baseUrl = cfg == null ? "" : firstNonEmpty(cfg.upstreamBaseUrl, "").toLowerCase(Locale.ROOT);
        boolean preferRaw = shouldUseRawBase64ImageUrl(cfg);
        boolean preferImageFirst = shouldPlaceImagesBeforeText(cfg);
        boolean preferDetail = shouldAddImageDetail(cfg);
        variants.add(new ImageTestVariant(
                preferRaw ? jpegBase64 : "data:image/jpeg;base64," + jpegBase64,
                preferImageFirst,
                preferDetail
        ));
        variants.add(new ImageTestVariant("data:image/jpeg;base64," + jpegBase64, true, true));
        variants.add(new ImageTestVariant("data:image/jpeg;base64," + jpegBase64, false, false));
        if (preferRaw || baseUrl.contains("bigmodel") || model.contains("glm")) {
            variants.add(new ImageTestVariant(jpegBase64, true, false));
            variants.add(new ImageTestVariant(jpegBase64, false, false));
        }
        return variants;
    }

    private static JSONObject buildImageTestPayload(ProxyConfig cfg, String testCode, ImageTestVariant variant) throws JSONException {
        JSONObject payload = new JSONObject();
        JSONArray messages = new JSONArray();
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject().put(
                "type",
                "text"
        ).put(
                "text",
                "The image contains exactly one 4-digit number printed in big black bold font on white background. "
                        + "Output ONLY the 4 digits, nothing else. No explanation, no quotes, no thinking. "
                        + "Just the 4 digits in your final answer."
        );
        JSONObject imageObject = new JSONObject().put("url", variant.imageUrl);
        if (variant.detailLow) {
            imageObject.put("detail", "low");
        }
        JSONObject imagePart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", imageObject);
        if (variant.imageFirst) {
            content.put(imagePart);
            content.put(textPart);
        } else {
            content.put(textPart);
            content.put(imagePart);
        }
        messages.put(new JSONObject().put("role", "user").put("content", content));
        payload.put("messages", messages);
        payload.put("model", cfg.model);
        // v1.5.0：256 给 GLM-4.5V 等带思考链的视觉模型留余地（之前 16 token 被截断在 reasoning 里）
        payload.put("max_tokens", 256);
        payload.put("temperature", 0);
        return payload;
    }

    private static final class ImageTestVariant {
        final String imageUrl;
        final boolean imageFirst;
        final boolean detailLow;

        ImageTestVariant(String imageUrl, boolean imageFirst, boolean detailLow) {
            this.imageUrl = imageUrl;
            this.imageFirst = imageFirst;
            this.detailLow = detailLow;
        }
    }

    private static String buildImageTestCode() {
        long seed = System.nanoTime() ^ System.currentTimeMillis();
        int code = 1000 + (int) Math.abs(seed % 9000L);
        return String.valueOf(code);
    }

    private static boolean isExplicitUnsupportedImageError(String body) {
        if (TextUtils.isEmpty(body)) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return (lower.contains("does not support") || lower.contains("not support") || lower.contains("unsupported"))
                && (lower.contains("image") || lower.contains("vision") || lower.contains("multimodal")
                || lower.contains("modality") || lower.contains("图片") || lower.contains("视觉")
                || lower.contains("图像") || lower.contains("multi-modal"));
    }

    private static String buildImageTestJpegBase64(String code) {
        // 放大到 640x320 + 加粗字号，避免视觉模型对小图识别失败
        Bitmap bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(16f);
        borderPaint.setColor(Color.rgb(37, 99, 235));
        canvas.drawRect(20f, 20f, 620f, 300f, borderPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(144f);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        Rect bounds = new Rect();
        textPaint.getTextBounds(code, 0, code.length(), bounds);
        float y = 160f - bounds.exactCenterY();
        canvas.drawText(code, 320f, y, textPaint);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
        bitmap.recycle();
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    public static UpstreamFeatureTestResult performUpstreamToolTest(ProxyConfig cfg) {
        try {
            String validationError = validateUpstreamConfig(cfg);
            if (!TextUtils.isEmpty(validationError)) {
                return UpstreamFeatureTestResult.fail(0, validationError);
            }
            JSONObject payload = new JSONObject();
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user")
                    .put("content", "What's the weather in Beijing? Use the tool to get it."));
            payload.put("messages", messages);
            payload.put("model", cfg.model);
            payload.put("max_tokens", 64);
            payload.put("temperature", 0);

            JSONArray tools = new JSONArray();
            JSONObject function = new JSONObject();
            function.put("name", "get_weather");
            function.put("description", "Get current weather for a city");
            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONObject cityProp = new JSONObject();
            cityProp.put("type", "string");
            cityProp.put("description", "City name");
            properties.put("city", cityProp);
            parameters.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("city");
            parameters.put("required", required);
            function.put("parameters", parameters);
            JSONObject toolEntry = new JSONObject();
            toolEntry.put("type", "function");
            toolEntry.put("function", function);
            tools.put(toolEntry);
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");

            UpstreamResponse response = forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg);
            if (response.streaming) {
                return UpstreamFeatureTestResult.fail(0, "上游返回流式响应");
            }
            if (response.status >= 200 && response.status < 400) {
                return UpstreamFeatureTestResult.success(response.status, "");
            }
            String body = response.body == null ? "" : response.body.toLowerCase();
            if (body.contains("tool") || body.contains("function call") || body.contains("function_call")
                    || body.contains("工具") || body.contains("function calling")) {
                return UpstreamFeatureTestResult.unsupported("该模型不支持工具调用");
            }
            return UpstreamFeatureTestResult.fail(response.status,
                    "status=" + response.status + " " + clip(response.body, 80));
        } catch (Exception error) {
            return UpstreamFeatureTestResult.fail(0, firstNonEmpty(error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    public static ModelFetchResult fetchModels(ProxyConfig cfg) {
        try {
            String validationError = validateUpstreamConfig(cfg);
            if (!TextUtils.isEmpty(validationError)) {
                return ModelFetchResult.failure(validationError);
            }
            UpstreamResponse response = forwardRaw(buildModelsUpstreamRequest(cfg), cfg);
            if (response.streaming) {
                return ModelFetchResult.failure("上游 models 接口返回了流式响应，当前版本不支持");
            }
            if (response.status >= 400) {
                return ModelFetchResult.failure(
                        "上游返回错误，status=" + response.status + "，内容预览：" + clip(response.body, 200)
                );
            }

            LinkedHashSet<String> modelIds = new LinkedHashSet<>();
            collectModelIds(parseJsonValue(response.body), modelIds, 0);
            List<String> normalized = new ArrayList<>();
            for (String modelId : modelIds) {
                String value = normalizeModelIdForDisplay(modelId, cfg);
                if (!TextUtils.isEmpty(value) && !normalized.contains(value)) {
                    normalized.add(value);
                }
            }
            return ModelFetchResult.success(
                    normalized,
                    "status=" + response.status + "，内容预览：" + clip(response.body, 200)
            );
        } catch (Exception error) {
            return ModelFetchResult.failure("请求异常：" + error.getMessage());
        }
    }

    public static ReplayResult replayLastDiaryRequest(Context context, ProxyConfig cfg, ProxyLogSink sink) {
        String validationError = validateUpstreamConfig(cfg);
        if (!TextUtils.isEmpty(validationError)) {
            return ReplayResult.failure(validationError, 0);
        }
        try {
            ProxyStorageHelper.CachedDiaryRequest cached = ProxyStorageHelper.loadLastDiaryRequest(context);
            if (cached == null) {
                return ReplayResult.failure("暂无可重发的日记请求缓存。", 0);
            }
            DiaryProxyServer helper = new DiaryProxyServer(context, cfg, sink);
            ChatExecutionResult result = helper.executeChatRequest(
                    cached.requestBody,
                    cached.uri,
                    helper.requestCounter.getAndIncrement(),
                    cfg,
                    true,
                    0L
            );
            if (result.success) {
                return ReplayResult.success(
                        "重发日记成功，status=" + result.statusCode + "，预览=" + clip(result.assistantText, 160),
                        result.statusCode
                );
            }
            String detail = firstNonEmpty(result.errorMessage, result.assistantText, "上游返回失败");
            return ReplayResult.failure("重发日记失败，status=" + result.statusCode + "，" + clip(detail, 200), result.statusCode);
        } catch (IOException error) {
            return ReplayResult.failure(error.getMessage(), 0);
        } catch (Exception error) {
            return ReplayResult.failure("重发日记失败：" + error.getMessage(), 0);
        }
    }

    private AttachmentInjectionResult injectAttachmentsIntoPayload(JSONObject payload, ChatDecision decision, ProxyConfig cfg) {
        AttachmentInjectionResult result = new AttachmentInjectionResult();
        if (payload == null || appContext == null || cfg == null) {
            return result;
        }
        List<ProxyStorageHelper.AttachmentRef> attachments = ProxyStorageHelper.listAttachmentDrafts(appContext);
        if (attachments == null || attachments.isEmpty()) {
            return result;
        }
        String requestKind = decision == null ? "" : firstNonEmpty(decision.requestKind, "");
        AttachmentSupport.AttachmentDecision attachDecision =
                AttachmentSupport.decide(requestKind, attachments, cfg);
        long total = 0L;
        for (ProxyStorageHelper.AttachmentRef ref : attachments) {
            if (ref != null) total += ref.byteSize;
        }
        result.totalBytes = total;
        if (!attachDecision.willInject) {
            result.dropReason = attachDecision.dropReason;
            result.adapterNote = "attach_skipped=" + clip(attachDecision.dropReason, 64);
            return result;
        }
        // 区分图片 / 文档
        List<ProxyStorageHelper.AttachmentRef> images = new ArrayList<>();
        List<ProxyStorageHelper.AttachmentRef> docs = new ArrayList<>();
        for (ProxyStorageHelper.AttachmentRef ref : attachDecision.accepted) {
            if (ref == null) continue;
            if (AttachmentSupport.isImageMime(ref.mime)) {
                images.add(ref);
            } else {
                docs.add(ref);
            }
        }
        ProxyConfig.ProviderProfile activeProvider = cfg.getActiveProviderProfile();
        boolean adapterCanCarryImage = AttachmentSupport.isMultimodalUpstream(cfg.adapterPreset, activeProvider, cfg.model);
        String strategy = ProxyConfig.normalizeCaptionStrategy(cfg.captionStrategy);
        // v1.5.1+：副模型策略主导。captionStrategy=off → 走 adapter 多模态直传；
        //          captionStrategy=inject/tool → 用副模型，不直传图。
        //          但 GENERIC_CUSTOM 协议本身装不下图，永远走副模型；off 时只能丢图。
        boolean useDirectMultimodal = "off".equals(strategy) && adapterCanCarryImage;
        List<ProxyStorageHelper.AttachmentRef> consumedRefs = new ArrayList<>();
        // 找 last user message
        JSONArray messages = resolveMessages(payload, cfg);
        if (messages == null) {
            result.dropReason = "无 messages";
            result.adapterNote = "attach_no_msgs";
            return result;
        }
        int lastUserIndex = -1;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            if ("user".equals(normalizeLocalRole(msg.optString("role", "")))) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex < 0) {
            result.dropReason = "未找到 user 消息";
            result.adapterNote = "attach_no_user";
            return result;
        }
        try {
            JSONObject lastUser = messages.getJSONObject(lastUserIndex);
            String mode;
            if (images.isEmpty()) {
                // 仅文档：注入文本
                injectDocumentTextOnly(lastUser, docs, cfg);
                consumedRefs.addAll(docs);
                mode = "docs_only";
            } else if (useDirectMultimodal) {
                // 副模型策略=关闭 + adapter 能装图 → 直传
                JSONArray content = buildOpenAiCompatibleContent(lastUser, attachDecision.accepted, attachments, cfg);
                lastUser.put("content", content);
                consumedRefs.addAll(attachDecision.accepted);
                mode = "multimodal";
            } else if ("inject".equals(strategy)) {
                // A 方案：先 caption 再注入纯文本
                String captionBlock = CaptionSupport.preInjectCaptions(payload, images, cfg, appContext);
                injectDocumentTextOnly(lastUser, docs, cfg);
                if (TextUtils.isEmpty(captionBlock)) {
                    consumedRefs.addAll(docs);
                    // v1.5.6+：AS-2 — 提示文案中明确说明草稿是否保留 + 用户应该做什么
                    result.dropReason = "图片附件未发送（副模型 caption 调用失败或未配置视觉模型）。"
                            + "草稿已保留，配置「视觉模型 / 副模型」后请重发对话。";
                    result.sizeWarning = appendReason(
                            result.sizeWarning,
                            "图片未发送；请配置视觉模型，或将策略切换到「关闭」让接口层直传"
                    );
                    mode = docs.isEmpty() ? "caption_missing" : "docs_only_caption_missing";
                } else {
                    consumedRefs.addAll(attachDecision.accepted);
                    mode = "caption_inject";
                }
            } else if ("tool".equals(strategy)) {
                // B 方案：占位文本 [图1: img_id]，actual describe_image tool 由 buildChatUpstreamRequest 后注入
                injectImagePlaceholders(lastUser, images);
                injectDocumentTextOnly(lastUser, docs, cfg);
                consumedRefs.addAll(attachDecision.accepted);
                mode = "caption_tool";
            } else {
                // 副模型策略=关闭 但 adapter 装不下图（GENERIC_CUSTOM 等）→ 丢图 + 警告
                injectDocumentTextOnly(lastUser, docs, cfg);
                consumedRefs.addAll(docs);
                if (TextUtils.isEmpty(result.sizeWarning)) {
                    result.sizeWarning = "当前接口格式无法装载图片且副模型已关闭，已跳过 " + images.size() + " 张图";
                }
                mode = "images_dropped";
            }
            result.adapterNote = "attach=" + attachDecision.accepted.size()
                    + ",img=" + images.size()
                    + ",doc=" + docs.size()
                    + ",mode=" + mode
                    + ",total=" + AttachmentSupport.formatSize(total);
        } catch (Exception error) {
            result.dropReason = "附件注入异常：" + error.getMessage();
            result.adapterNote = "attach_inject_error";
            return result;
        }
        result.injected = !consumedRefs.isEmpty();
        result.injectedCount = consumedRefs.size();
        if (TextUtils.isEmpty(result.sizeWarning)) {
            result.sizeWarning = attachDecision.sizeWarning;
        }
        for (ProxyStorageHelper.AttachmentRef ref : consumedRefs) {
            if (ref != null && !TextUtils.isEmpty(ref.id)) {
                result.consumedIds.add(ref.id);
            }
        }
        return result;
    }

    /** 文档（非图片）→ 拼成纯文本附在 last user 原文末尾。 */
    private static void injectDocumentTextOnly(JSONObject lastUser, List<ProxyStorageHelper.AttachmentRef> docs, ProxyConfig cfg) throws JSONException {
        if (lastUser == null || docs == null || docs.isEmpty()) return;
        StringBuilder builder = new StringBuilder(safeNonNull(getMessageText(lastUser)));
        for (ProxyStorageHelper.AttachmentRef ref : docs) {
            if (ref == null) continue;
            try {
                byte[] bytes = ProxyStorageHelper.readAttachmentBytes(ref);
                if (bytes == null || bytes.length == 0) continue;
                // v1.5.1+：PDF 走 pdfbox-android 文本提取，其他文档走 UTF-8 直解
                String text = PdfTextExtractor.decodeAttachmentText(ref.mime, bytes);
                if (TextUtils.isEmpty(text)) continue;
                if (builder.length() > 0) builder.append("\n\n");
                builder.append("[附件: ").append(ref.displayName).append("]\n");
                builder.append(applyDocumentTruncation(text, cfg));
            } catch (Exception ignored) {
            }
        }
        lastUser.put("content", builder.toString());
    }

    /**
     * v1.5.4+：DPS-8 — 文档文本截断 helper。
     * cfg.documentTruncationEnabled=false 时直接返回原文；true 时按 documentTruncationMaxChars 截断，
     * 并附 "[已省略 N 字]" 提示，让 LLM 知道并非全文。
     */
    private static String applyDocumentTruncation(String text, ProxyConfig cfg) {
        if (TextUtils.isEmpty(text)) return text == null ? "" : text;
        if (cfg == null || !cfg.documentTruncationEnabled) return text;
        int max = cfg.documentTruncationMaxChars > 0 ? cfg.documentTruncationMaxChars : 4096;
        if (text.length() <= max) return text;
        int omitted = text.length() - max;
        return text.substring(0, max) + "\n…[已省略 " + omitted + " 字]";
    }

    /** B 方案占位：把图片附件转成 [内置图: imgX, mime, KB] 文本，原文本保留。 */
    private static void injectImagePlaceholders(JSONObject lastUser, List<ProxyStorageHelper.AttachmentRef> images) throws JSONException {
        if (lastUser == null || images == null || images.isEmpty()) return;
        StringBuilder builder = new StringBuilder(safeNonNull(getMessageText(lastUser)));
        if (builder.length() > 0) builder.append("\n\n");
        builder.append("[已附 ").append(images.size()).append(" 张图，可用 describe_image tool 查询]:");
        for (ProxyStorageHelper.AttachmentRef ref : images) {
            if (ref == null) continue;
            builder.append("\n  - image_id=").append(ref.id)
                    .append(", name=").append(ref.displayName)
                    .append(", size=").append(AttachmentSupport.formatSize(ref.byteSize));
        }
        lastUser.put("content", builder.toString());
    }

    /**
     * 把 last user message 的 content 转成 OpenAI 兼容 content array：
     * - 原文本（如有）作为第一个 {type:"text"} 块
     * - 文档附件文本拼到首块文本末尾
     * - 图片附件作为 {type:"image_url"} 块
     */
    private JSONArray buildOpenAiCompatibleContent(
            JSONObject lastUser,
            List<ProxyStorageHelper.AttachmentRef> accepted,
            List<ProxyStorageHelper.AttachmentRef> all,
            ProxyConfig cfg) throws JSONException {
        JSONArray content = new JSONArray();
        String originalText = "";
        Object oldContent = lastUser.opt("content");
        if (oldContent instanceof String) {
            originalText = (String) oldContent;
        } else if (oldContent instanceof JSONArray) {
            originalText = getMessageText(lastUser);
        }
        // 文档（非图片）→ 拼成文本附在原文本后面
        StringBuilder textBuilder = new StringBuilder(safeNonNull(originalText));
        for (ProxyStorageHelper.AttachmentRef ref : accepted) {
            if (ref == null || AttachmentSupport.isImageMime(ref.mime)) continue;
            try {
                byte[] bytes = ProxyStorageHelper.readAttachmentBytes(ref);
                if (bytes == null || bytes.length == 0) continue;
                // v1.5.4+：原直接 `new String(bytes, UTF_8)` 在 PDF 这类二进制附件上是乱码。
                // 改走 PdfTextExtractor.decodeAttachmentText 与 injectDocumentTextOnly 一致：
                // 二进制 PDF → PDFBox 文本提取；纯文本附件 → UTF-8 解码。解决 DPS-5。
                String docText = PdfTextExtractor.decodeAttachmentText(ref.mime, bytes);
                if (TextUtils.isEmpty(docText)) continue;
                if (textBuilder.length() > 0) textBuilder.append("\n\n");
                textBuilder.append("[附件: ").append(ref.displayName).append("]\n");
                textBuilder.append(applyDocumentTruncation(docText, cfg));
            } catch (Exception ignored) {
            }
        }
        JSONArray imageParts = new JSONArray();
        for (ProxyStorageHelper.AttachmentRef ref : accepted) {
            if (ref == null || !AttachmentSupport.isImageMime(ref.mime)) continue;
            try {
                byte[] bytes = ProxyStorageHelper.readAttachmentBytes(ref);
                if (bytes == null || bytes.length == 0) continue;
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                imageParts.put(buildOpenAiCompatibleImagePart(ref.mime, b64, cfg));
            } catch (Exception ignored) {
            }
        }
        if (shouldPlaceImagesBeforeText(cfg)) {
            appendJsonArray(content, imageParts);
            if (textBuilder.length() > 0) {
                content.put(new JSONObject().put("type", "text").put("text", textBuilder.toString()));
            }
        } else {
            if (textBuilder.length() > 0) {
                content.put(new JSONObject().put("type", "text").put("text", textBuilder.toString()));
            }
            appendJsonArray(content, imageParts);
        }
        return content;
    }

    private static void appendJsonArray(JSONArray target, JSONArray values) throws JSONException {
        if (target == null || values == null) {
            return;
        }
        for (int i = 0; i < values.length(); i++) {
            target.put(values.get(i));
        }
    }

    private static JSONObject buildOpenAiCompatibleImagePart(String mime, String base64, ProxyConfig cfg) throws JSONException {
        return new JSONObject()
                .put("type", "image_url")
                .put("image_url", buildOpenAiCompatibleImageObject(mime, base64, cfg));
    }

    private static JSONObject buildOpenAiCompatibleImageObject(String mime, String base64, ProxyConfig cfg) throws JSONException {
        JSONObject imageObject = new JSONObject()
                .put("url", buildOpenAiCompatibleImageUrl(mime, base64, cfg));
        if (shouldAddImageDetail(cfg)) {
            imageObject.put("detail", "low");
        }
        return imageObject;
    }

    private static String buildOpenAiCompatibleImageUrl(String mime, String base64, ProxyConfig cfg) {
        String safeBase64 = firstNonEmpty(base64, "");
        if (shouldUseRawBase64ImageUrl(cfg)) {
            return safeBase64;
        }
        return "data:" + firstNonEmpty(mime, "image/png") + ";base64," + safeBase64;
    }

    private static boolean shouldAddImageDetail(ProxyConfig cfg) {
        if (cfg == null) {
            return false;
        }
        String model = firstNonEmpty(cfg.model, "").toLowerCase(Locale.ROOT);
        String baseUrl = firstNonEmpty(cfg.upstreamBaseUrl, "").toLowerCase(Locale.ROOT);
        if (baseUrl.contains("siliconflow")) {
            return true;
        }
        return model.contains("qwen/") || model.contains("qwen3.6") || model.contains("qwen3-")
                || model.contains("qwen3_") || model.contains("qwen-vl") || model.contains("qwen3-vl");
    }

    private static boolean shouldPlaceImagesBeforeText(ProxyConfig cfg) {
        if (cfg == null) {
            return false;
        }
        String model = firstNonEmpty(cfg.model, "").toLowerCase(Locale.ROOT);
        String baseUrl = firstNonEmpty(cfg.upstreamBaseUrl, "").toLowerCase(Locale.ROOT);
        return baseUrl.contains("siliconflow")
                || model.contains("qwen/")
                || model.contains("qwen3.6")
                || model.contains("qwen-vl")
                || model.contains("qwen3-vl");
    }

    private static boolean shouldUseRawBase64ImageUrl(ProxyConfig cfg) {
        if (cfg == null) {
            return false;
        }
        String model = firstNonEmpty(cfg.model, "").toLowerCase(Locale.ROOT).trim();
        String baseUrl = firstNonEmpty(cfg.upstreamBaseUrl, "").toLowerCase(Locale.ROOT);
        if (isGlmVisionModel(model)) {
            return true;
        }
        return baseUrl.contains("bigmodel.cn") && (model.contains("glm-") || model.contains("glm_"));
    }

    private static boolean isGlmVisionModel(String model) {
        if (TextUtils.isEmpty(model)) {
            return false;
        }
        return model.startsWith("glm-5v")
                || model.startsWith("glm-4v")
                || model.startsWith("glm-4.5v")
                || model.contains("glm-4.1v")
                || (model.startsWith("glm-") && model.contains("-v"));
    }

    private static String safeNonNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * v1.5.0：把代理 tools（web_search / describe_image）注入到 upstream body。
     * - OpenAI 兼容 / Responses：标准 tools=[{type:function, function:{...}}]
     * - Claude：tools=[{name, description, input_schema}]
     * - Gemini：tools=[{functionDeclarations:[...]}]
     * - GENERIC_CUSTOM / 已 game-sent tools：fallback 让位，不动
     */
    private static UpstreamRequest applyProxyToolsToUpstreamRequest(
            UpstreamRequest request,
            ProxyConfig cfg,
            ChatDecision decision,
            AttachmentInjectionResult attachmentInjection) {
        if (request == null || cfg == null) return request;
        if (decision == null || !AttachmentSupport.WHITELISTED_REQUEST_KINDS.contains(firstNonEmpty(decision.requestKind, ""))) {
            return request;
        }
        if (ProxyConfig.ADAPTER_GENERIC_CUSTOM.equals(cfg.adapterPreset)) {
            return request;
        }
        String webSearchMode = ProxyConfig.normalizeWebSearchToolEnabled(cfg.webSearchToolEnabled);
        boolean injectWebSearch = !"off".equals(webSearchMode);
        String captionStrategy = ProxyConfig.normalizeCaptionStrategy(cfg.captionStrategy);
        boolean hasCaptionedImages = attachmentInjection != null && attachmentInjection.injected
                && attachmentInjection.adapterNote != null
                && attachmentInjection.adapterNote.contains("mode=caption_tool");
        boolean injectDescribe = "tool".equals(captionStrategy) && hasCaptionedImages;
        if (!injectWebSearch && !injectDescribe) {
            return request;
        }
        try {
            JSONObject upstreamBody = new JSONObject(TextUtils.isEmpty(request.body) ? "{}" : request.body);
            // 检测游戏侧已发的 tools (fallback 让位)
            boolean upstreamHasTools = hasUpstreamTools(upstreamBody, cfg.adapterPreset);
            if (upstreamHasTools && "fallback".equals(webSearchMode)) {
                injectWebSearch = false;
                if (!injectDescribe) return request;
            }
            // 收集 OpenAI 格式 tools
            JSONArray openAiTools = new JSONArray();
            if (injectWebSearch) {
                JSONArray ws = WebSearchSupport.buildToolList(cfg);
                for (int i = 0; i < ws.length(); i++) openAiTools.put(ws.opt(i));
            }
            if (injectDescribe) {
                JSONArray dt = CaptionSupport.buildDescribeImageToolList();
                for (int i = 0; i < dt.length(); i++) openAiTools.put(dt.opt(i));
            }
            if (openAiTools.length() == 0) return request;
            // 转 adapter 格式 + 写入 upstreamBody
            mergeToolsIntoUpstreamBody(upstreamBody, openAiTools, cfg.adapterPreset);
            if (injectWebSearch && shouldForceWebSearchToolChoice(upstreamBody, cfg, decision)) {
                forceWebSearchToolChoice(upstreamBody, cfg.adapterPreset);
                if (decision != null) {
                    decision.adapterNote = appendReason(decision.adapterNote, "web_search_tool_choice=forced");
                }
            }
            return new UpstreamRequest(request.method, request.path, upstreamBody.toString(), request.headers);
        } catch (Exception error) {
            return request;
        }
    }

    private static boolean shouldForceWebSearchToolChoice(JSONObject upstreamBody, ProxyConfig cfg, ChatDecision decision) {
        // v1.5.2+：DeepSeek V4 思考模式只接受 string 形式的 tool_choice（none/auto/required），
        // 不接受 NamedToolChoice 对象形式（{"type":"function","function":{"name":"..."}}），
        // 强行升级会被回 400 "deepseek-reasoner does not support this tool_choice"。
        // 同时，auto 模式下模型在用户明示意图（"搜一下"）时基本都会主动调用 web_search，
        // 体验差异不大，但兼容性显著提升。所以默认禁用 forced 升级路径。
        return false;
    }

    private static String currentToolChoiceName(JSONObject upstreamBody) {
        if (upstreamBody == null) return "";
        Object raw = upstreamBody.opt("tool_choice");
        if (raw instanceof String) {
            String value = ((String) raw).trim();
            return "auto".equalsIgnoreCase(value) ? "" : value;
        }
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            String name = object.optString("name", "");
            if (!TextUtils.isEmpty(name)) return name;
            JSONObject function = object.optJSONObject("function");
            if (function != null) return function.optString("name", "");
        }
        return "";
    }

    // v1.5.4+：DPS-4 — 已移除死代码 hasPriorProxyToolResult / hasPriorProxyToolResult(node, depth)。
    // 该方法定义但从未被调用（grep 验证）。原本可能用于检测对话历史里是否已经有过 tool result，
    // 避免重复执行；但 ToolCallLoop 的去重已通过 messages 历史 + tool_call_id 完成，无需此扫描。

    private static void forceWebSearchToolChoice(JSONObject upstreamBody, String adapterPreset) throws JSONException {
        if (upstreamBody == null) return;
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)) {
            upstreamBody.put("tool_choice", new JSONObject()
                    .put("type", "tool")
                    .put("name", WebSearchSupport.TOOL_NAME));
        } else if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)) {
            upstreamBody.put("tool_choice", new JSONObject()
                    .put("type", "function")
                    .put("name", WebSearchSupport.TOOL_NAME));
        } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
            upstreamBody.put("toolConfig", new JSONObject()
                    .put("functionCallingConfig", new JSONObject()
                            .put("mode", "ANY")
                            .put("allowedFunctionNames", new JSONArray().put(WebSearchSupport.TOOL_NAME))));
        } else {
            upstreamBody.put("tool_choice", new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject().put("name", WebSearchSupport.TOOL_NAME)));
        }
    }

    private static boolean hasUpstreamTools(JSONObject upstreamBody, String adapterPreset) {
        if (upstreamBody == null) return false;
        if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
            JSONArray tools = upstreamBody.optJSONArray("tools");
            return tools != null && tools.length() > 0;
        }
        // OpenAI / Claude / Responses 都用 tools[]
        JSONArray tools = upstreamBody.optJSONArray("tools");
        return tools != null && tools.length() > 0;
    }

    private static void mergeToolsIntoUpstreamBody(JSONObject upstreamBody, JSONArray openAiTools, String adapterPreset) throws JSONException {
        if (openAiTools == null || openAiTools.length() == 0) return;
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)) {
            JSONArray claudeTools = upstreamBody.optJSONArray("tools");
            if (claudeTools == null) {
                claudeTools = new JSONArray();
                upstreamBody.put("tools", claudeTools);
            }
            for (int i = 0; i < openAiTools.length(); i++) {
                JSONObject openTool = openAiTools.optJSONObject(i);
                if (openTool == null) continue;
                JSONObject function = openTool.optJSONObject("function");
                if (function == null) continue;
                JSONObject claudeTool = new JSONObject();
                claudeTool.put("name", function.optString("name", ""));
                claudeTool.put("description", function.optString("description", ""));
                JSONObject params = function.optJSONObject("parameters");
                claudeTool.put("input_schema", params == null ? new JSONObject().put("type", "object") : params);
                claudeTools.put(claudeTool);
            }
        } else if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)) {
            JSONArray tools = upstreamBody.optJSONArray("tools");
            if (tools == null) {
                tools = new JSONArray();
                upstreamBody.put("tools", tools);
            }
            for (int i = 0; i < openAiTools.length(); i++) {
                JSONObject openTool = openAiTools.optJSONObject(i);
                if (openTool == null) continue;
                JSONObject function = openTool.optJSONObject("function");
                if (function == null) continue;
                JSONObject responseTool = new JSONObject();
                responseTool.put("type", "function");
                responseTool.put("name", function.optString("name", ""));
                responseTool.put("description", function.optString("description", ""));
                JSONObject params = function.optJSONObject("parameters");
                if (params != null) {
                    responseTool.put("parameters", params);
                }
                tools.put(responseTool);
            }
            if (!upstreamBody.has("tool_choice")) {
                upstreamBody.put("tool_choice", "auto");
            }
        } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
            JSONArray geminiTools = upstreamBody.optJSONArray("tools");
            if (geminiTools == null) {
                geminiTools = new JSONArray();
                upstreamBody.put("tools", geminiTools);
            }
            JSONArray functionDeclarations = new JSONArray();
            for (int i = 0; i < openAiTools.length(); i++) {
                JSONObject openTool = openAiTools.optJSONObject(i);
                if (openTool == null) continue;
                JSONObject function = openTool.optJSONObject("function");
                if (function == null) continue;
                JSONObject declaration = new JSONObject();
                declaration.put("name", function.optString("name", ""));
                declaration.put("description", function.optString("description", ""));
                JSONObject params = function.optJSONObject("parameters");
                if (params != null) declaration.put("parameters", params);
                functionDeclarations.put(declaration);
            }
            geminiTools.put(new JSONObject().put("functionDeclarations", functionDeclarations));
        } else {
            // OpenAI compatible / Responses
            JSONArray tools = upstreamBody.optJSONArray("tools");
            if (tools == null) {
                tools = new JSONArray();
                upstreamBody.put("tools", tools);
            }
            for (int i = 0; i < openAiTools.length(); i++) {
                tools.put(openAiTools.opt(i));
            }
            if (!upstreamBody.has("tool_choice")) {
                upstreamBody.put("tool_choice", "auto");
            }
        }
    }

    /**
     * v1.5.0：代理 tool_call 回流。
     * 对所有内置 tool-capable adapter 做循环；payload.messages 内部统一使用 OpenAI 兼容中间格式，
     * build*RequestPayload 再翻译成 Claude/Gemini/Responses 原生 tool 上下文。
     *
     * @param turnsCollector 若非 null，每轮的模型 tool_call 入参 + 代理执行结果会被附加进来供调试导出使用。
     */
    private UpstreamResponse runToolCallLoopIfNeeded(
            UpstreamResponse initial,
            JSONObject originalPayload,
            ProxyConfig cfg,
            ChatDecision decision,
            ForwardTrace forwardTrace,
            AttachmentInjectionResult attachmentInjection,
            List<ProxyStorageHelper.DebugToolCallTurn> turnsCollector) {
        if (initial == null || initial.streaming) return initial;
        if (cfg == null || originalPayload == null) return initial;
        if (!supportsProxyToolLoopAdapter(cfg.adapterPreset)) {
            return initial;
        }
        if (initial.status >= 400 || TextUtils.isEmpty(initial.body)) {
            return initial;
        }
        UpstreamResponse current = initial;
        int depth = 0;
        try {
            while (depth < ToolCallLoop.MAX_DEPTH) {
                List<ToolCallLoop.ToolCall> all = ToolCallLoop.extractToolCalls(current.body, cfg.adapterPreset);
                if (all.isEmpty()) {
                    return current;
                }
                List<ToolCallLoop.ToolCall> proxy = ToolCallLoop.filterProxyHandled(all);
                // v1.5.4+：上游响应同时含白名单 tool（如 web_search）和非白名单 tool（如游戏 fight_tool）时，
                // 进入回流会让第二轮 messages 里出现"assistant.tool_calls 全量但 tool result 仅 proxy 部分"
                // 的不平衡，OpenAI 严格校验下第二轮 400。
                // v1.5.5+：DPS-11 — 仅在 fallback / off 模式下才 skip 让前端处理；always 模式下用户
                // 已明确"无论游戏侧是否发 tools 都强制注入 web_search"，此时直接 skip 会让客户端拿到
                // 孤立的 web_search tool_calls。改为给非白名单 tool 填占位结果保持 messages 平衡，
                // 让模型继续推理（前端没法处理代理强注入的工具，由代理负责清场更合理）。
                boolean mixedScenario = !proxy.isEmpty() && all.size() != proxy.size();
                boolean alwaysMode = "always".equals(
                        ProxyConfig.normalizeWebSearchToolEnabled(cfg.webSearchToolEnabled));
                if (mixedScenario && !alwaysMode) {
                    log("tool_loop skip mixed depth=" + depth
                            + " all=" + formatToolCallNames(all)
                            + " proxy=" + formatToolCallNames(proxy));
                    return current;
                }
                // 决定要写到第二轮 messages 的"tool result"对应的 calls 列表：
                //   - 普通路径（非 mixed）：仅 proxy
                //   - always-mixed：all（非白名单的填占位结果）
                List<ToolCallLoop.ToolCall> resultsForCalls = (mixedScenario && alwaysMode) ? all : proxy;
                List<ToolCallLoop.ToolExecResult> proxyResultsDetailed = null;
                List<String> proxyResults = null;
                if (!proxy.isEmpty()) {
                    log("tool_loop exec depth=" + depth + " calls=" + formatToolCallNames(proxy));
                    List<ToolCallLoop.ToolExecResult> realExec = ToolCallLoop.executeToolBatchDetailed(proxy, cfg, appContext);
                    if (resultsForCalls == proxy) {
                        proxyResultsDetailed = realExec;
                        proxyResults = new ArrayList<>(realExec.size());
                        for (ToolCallLoop.ToolExecResult r : realExec) {
                            proxyResults.add(r == null || r.text == null ? "" : r.text);
                        }
                    } else {
                        // v1.5.5+：DPS-11 — always-mixed 路径。按 all 顺序拼，非 proxy 的填占位文本。
                        java.util.Map<String, ToolCallLoop.ToolExecResult> byId = new java.util.HashMap<>();
                        for (int i = 0; i < proxy.size(); i++) {
                            byId.put(proxy.get(i).id, realExec.get(i));
                        }
                        proxyResultsDetailed = new ArrayList<>(all.size());
                        proxyResults = new ArrayList<>(all.size());
                        for (ToolCallLoop.ToolCall c : all) {
                            ToolCallLoop.ToolExecResult r = byId.get(c.id);
                            if (r != null) {
                                proxyResultsDetailed.add(r);
                                proxyResults.add(r.text == null ? "" : r.text);
                            } else {
                                ToolCallLoop.ToolExecResult placeholder = new ToolCallLoop.ToolExecResult();
                                placeholder.text = "(代理未处理：" + c.name
                                        + " 不在代理工具白名单。always 模式下代理强制注入了 web_search，请前端按需实现 "
                                        + c.name + " 后再发起请求；本轮以占位结果继续推理)";
                                proxyResultsDetailed.add(placeholder);
                                proxyResults.add(placeholder.text);
                            }
                        }
                        log("tool_loop always-mixed depth=" + depth + " filled placeholder for "
                                + (all.size() - proxy.size()) + " non-whitelist call(s)");
                    }
                    log("tool_loop done depth=" + depth + " calls=" + formatToolCallNames(proxy));
                }
                if (turnsCollector != null) {
                    turnsCollector.add(buildDebugToolCallTurn(depth + 1, all, resultsForCalls, proxyResultsDetailed));
                }
                if (proxy.isEmpty()) {
                    return current;
                }
                JSONArray currentMessages = originalPayload.optJSONArray("messages");
                JSONArray updated = ToolCallLoop.appendToolCallTurn(currentMessages, all, resultsForCalls, proxyResults, current.body, cfg.adapterPreset);
                originalPayload.put("messages", updated);
                UpstreamRequest nextRequest = buildChatUpstreamRequest(originalPayload, cfg);
                CustomFieldsApplyResult cfr = applyCustomRequestFieldsToUpstreamRequest(nextRequest, cfg, decision);
                nextRequest = cfr.request;
                nextRequest = applyProxyToolsToUpstreamRequest(nextRequest, cfg, decision, attachmentInjection);
                current = forwardRaw(nextRequest, cfg, forwardTrace);
                if (current == null || current.streaming || current.status >= 400 || TextUtils.isEmpty(current.body)) {
                    break;
                }
                depth++;
            }
        } catch (Exception error) {
            log("tool_loop error depth=" + depth + " err=" + clip(error.getMessage(), 100));
        }
        return current == null ? initial : current;
    }

    private static ProxyStorageHelper.DebugToolCallTurn buildDebugToolCallTurn(
            int humanDepth,
            List<ToolCallLoop.ToolCall> all,
            List<ToolCallLoop.ToolCall> proxy,
            List<ToolCallLoop.ToolExecResult> proxyResults) {
        ProxyStorageHelper.DebugToolCallTurn turn = new ProxyStorageHelper.DebugToolCallTurn();
        turn.depth = humanDepth;
        if (all == null) {
            return turn;
        }
        for (ToolCallLoop.ToolCall call : all) {
            if (call == null) {
                continue;
            }
            ProxyStorageHelper.DebugToolCallEntry entry = new ProxyStorageHelper.DebugToolCallEntry();
            entry.id = call.id == null ? "" : call.id;
            entry.name = call.name == null ? "" : call.name;
            entry.argumentsJson = call.argumentsJson == null ? "" : call.argumentsJson;
            int proxyIdx = proxy == null ? -1 : proxy.indexOf(call);
            entry.proxyHandled = proxyIdx >= 0;
            if (entry.proxyHandled && proxyResults != null && proxyIdx < proxyResults.size()) {
                ToolCallLoop.ToolExecResult r = proxyResults.get(proxyIdx);
                if (r != null) {
                    entry.resultText = r.text == null ? "" : r.text;
                    entry.toolDebugRaw = r.debugRaw == null ? "" : r.debugRaw;
                }
            }
            turn.entries.add(entry);
        }
        return turn;
    }

    private static String formatToolCallNames(List<ToolCallLoop.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (ToolCallLoop.ToolCall call : calls) {
            if (call == null) continue;
            if (builder.length() > 0) builder.append(",");
            builder.append(firstNonEmpty(call.name, "unknown"));
        }
        return builder.toString();
    }

    private static boolean supportsProxyToolLoopAdapter(String adapterPreset) {
        return ProxyConfig.ADAPTER_OPENAI_COMPATIBLE.equals(adapterPreset)
                || ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)
                || ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)
                || ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset);
    }

    private static UpstreamRequest buildChatUpstreamRequest(JSONObject localPayload, ProxyConfig cfg) throws JSONException {
        JSONObject upstreamPayload;
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(cfg.adapterPreset)) {
            upstreamPayload = buildClaudeRequestPayload(localPayload, cfg);
        } else if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(cfg.adapterPreset)) {
            upstreamPayload = buildOpenAiResponsesRequestPayload(localPayload, cfg);
        } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset)) {
            upstreamPayload = buildGeminiRequestPayload(localPayload, cfg);
        } else if (ProxyConfig.ADAPTER_GENERIC_CUSTOM.equals(cfg.adapterPreset)) {
            upstreamPayload = buildGenericCustomRequestPayload(localPayload, cfg);
        } else {
            upstreamPayload = new JSONObject(localPayload.toString());
        }
        return new UpstreamRequest("POST", cfg.resolvedUpstreamChatPath(), upstreamPayload.toString(), buildAdapterHeaders(cfg));
    }

    private CustomFieldsApplyResult applyCustomRequestFieldsToUpstreamRequest(
            UpstreamRequest request,
            ProxyConfig cfg,
            ChatDecision decision
    ) {
        if (request == null) {
            return new CustomFieldsApplyResult(null, "");
        }
        CustomRequestFieldsSpec spec = selectCustomRequestFields(cfg, decision);
        if (spec == null || TextUtils.isEmpty(spec.json) || TextUtils.isEmpty(request.body)) {
            return new CustomFieldsApplyResult(request, "");
        }
        try {
            JSONObject customFields = new JSONObject(spec.json);
            if (customFields.length() == 0) {
                return new CustomFieldsApplyResult(request, "");
            }
            JSONObject upstreamBody = new JSONObject(request.body);
            deepMergeJsonObject(upstreamBody, customFields);
            UpstreamRequest merged = new UpstreamRequest(request.method, request.path, upstreamBody.toString(), request.headers);
            return new CustomFieldsApplyResult(merged, "custom_fields=" + spec.noteName + "(" + customFields.length() + ")");
        } catch (Exception error) {
            String detail = clip(firstNonEmpty(error.getMessage(), error.getClass().getSimpleName()), 48);
            return new CustomFieldsApplyResult(request, "custom_fields_invalid=" + spec.noteName + ":" + detail);
        }
    }

    private static CustomRequestFieldsSpec selectCustomRequestFields(ProxyConfig cfg, ChatDecision decision) {
        if (cfg == null || decision == null) {
            return null;
        }
        if ("normal-chat".equals(decision.requestKind) || "interactive-story".equals(decision.requestKind)) {
            return new CustomRequestFieldsSpec("chat", cfg.chatCustomRequestFieldsJson);
        }
        if ("normal-diary".equals(decision.requestKind) || "holiday-diary".equals(decision.requestKind)) {
            return new CustomRequestFieldsSpec("diary", cfg.diaryCustomRequestFieldsJson);
        }
        if ("memory-extract".equals(decision.requestKind)) {
            return new CustomRequestFieldsSpec("memory", cfg.memoryExtractCustomRequestFieldsJson);
        }
        return null;
    }

    private static void deepMergeJsonObject(JSONObject target, JSONObject source) throws JSONException {
        if (target == null || source == null) {
            return;
        }
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object sourceValue = source.get(key);
            Object targetValue = target.opt(key);
            if (sourceValue instanceof JSONObject && targetValue instanceof JSONObject) {
                deepMergeJsonObject((JSONObject) targetValue, (JSONObject) sourceValue);
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    private static UpstreamRequest buildModelsUpstreamRequest(ProxyConfig cfg) {
        return new UpstreamRequest("GET", cfg.resolvedUpstreamModelsPath(), null, buildAdapterHeaders(cfg));
    }

    private static JSONObject buildGenericCustomRequestPayload(JSONObject localPayload, ProxyConfig cfg) throws JSONException {
        JSONObject upstream = new JSONObject();
        JSONArray messages = resolveMessages(localPayload, cfg);
        if (messages != null) {
            JsonPathUtils.setValue(upstream, cfg.resolvedRequestMessagesPath(), new JSONArray(messages.toString()));
        }
        JsonPathUtils.setValue(upstream, cfg.resolvedRequestUserTextPath(), extractLastUserText(messages));
        JsonPathUtils.setValue(upstream, cfg.resolvedRequestModelPath(), firstNonEmpty(cfg.model, resolveRequestModel(localPayload, cfg)));
        int maxTokens = resolveMaxTokensValue(localPayload);
        if (maxTokens > 0) {
            JsonPathUtils.setValue(upstream, cfg.resolvedRequestMaxTokensPath(), maxTokens);
        }
        double temperature = resolveTemperatureValue(localPayload);
        if (!Double.isNaN(temperature)) {
            JsonPathUtils.setValue(upstream, cfg.resolvedRequestTemperaturePath(), temperature);
        }
        Object enableThinking = localPayload == null ? null : localPayload.opt("enable_thinking");
        if (enableThinking != null && enableThinking != JSONObject.NULL) {
            JsonPathUtils.setValue(upstream, cfg.resolvedRequestEnableThinkingPath(), enableThinking);
        }
        return upstream;
    }

    private static JSONObject buildClaudeRequestPayload(JSONObject localPayload, ProxyConfig cfg) throws JSONException {
        JSONObject upstream = new JSONObject();
        JSONArray sourceMessages = resolveMessages(localPayload, cfg);
        JSONArray messages = new JSONArray();
        String systemText = collectSystemText(sourceMessages);
        for (int i = 0; i < (sourceMessages == null ? 0 : sourceMessages.length()); i++) {
            JSONObject message = sourceMessages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = normalizeLocalRole(message.optString("role", ""));
            if ("system".equals(role)) {
                continue;
            }
            // v1.5.0：保留 multimodal blocks（如 image），不再单纯拍平
            JSONArray blocks = convertContentToClaudeBlocks(message);
            if (blocks.length() == 0) {
                continue;
            }
            String mappedRole = "assistant".equals(role) ? "assistant" : "user";
            JSONObject last = messages.length() > 0 ? messages.optJSONObject(messages.length() - 1) : null;
            if (last != null && mappedRole.equals(last.optString("role", ""))) {
                JSONArray prevContent = last.optJSONArray("content");
                if (prevContent == null) {
                    prevContent = new JSONArray();
                    Object prevContentObj = last.opt("content");
                    if (prevContentObj instanceof String && !TextUtils.isEmpty((String) prevContentObj)) {
                        prevContent.put(new JSONObject().put("type", "text").put("text", (String) prevContentObj));
                    }
                    last.put("content", prevContent);
                }
                for (int j = 0; j < blocks.length(); j++) {
                    prevContent.put(blocks.opt(j));
                }
            } else {
                messages.put(new JSONObject().put("role", mappedRole).put("content", blocks));
            }
        }
        upstream.put("model", firstNonEmpty(cfg.model, resolveRequestModel(localPayload, cfg)));
        upstream.put("messages", messages);
        if (!TextUtils.isEmpty(systemText)) {
            upstream.put("system", systemText);
        }
        int maxTokens = resolveMaxTokensValue(localPayload);
        upstream.put("max_tokens", maxTokens > 0 ? maxTokens : 1024);
        copyIfPresent(localPayload, upstream, "temperature");
        copyIfPresent(localPayload, upstream, "top_p");
        Object stop = localPayload.opt("stop");
        if (stop != null && stop != JSONObject.NULL) {
            upstream.put("stop_sequences", stop);
        }
        // v1.5.6+：DPS-19 — 把 OpenAI 中间格式 thinking 字段翻译到 Claude 协议
        applyThinkingForClaude(upstream, localPayload);
        return upstream;
    }

    private static JSONObject buildOpenAiResponsesRequestPayload(JSONObject localPayload, ProxyConfig cfg) throws JSONException {
        JSONObject upstream = new JSONObject();
        JSONArray sourceMessages = resolveMessages(localPayload, cfg);
        JSONArray input = new JSONArray();
        String systemText = collectSystemText(sourceMessages);
        for (int i = 0; i < (sourceMessages == null ? 0 : sourceMessages.length()); i++) {
            JSONObject message = sourceMessages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String rawRole = firstNonEmpty(message.optString("role", ""), "user").trim().toLowerCase(Locale.ROOT);
            if ("tool".equals(rawRole)) {
                JSONObject output = buildOpenAiResponsesFunctionCallOutput(message);
                if (output.length() > 0) {
                    input.put(output);
                }
                continue;
            }
            String role = normalizeLocalRole(rawRole);
            if ("system".equals(role)) {
                continue;
            }
            String mappedRole = "assistant".equals(role) ? "assistant" : "user";
            // v1.5.0：保留 multimodal blocks
            JSONArray content = convertContentToOpenAiResponsesContent(message, mappedRole);
            if (content.length() > 0) {
                input.put(new JSONObject()
                        .put("role", mappedRole)
                        .put("content", content));
            }
            if ("assistant".equals(mappedRole)) {
                JSONArray toolCalls = message.optJSONArray("tool_calls");
                for (int j = 0; toolCalls != null && j < toolCalls.length(); j++) {
                    JSONObject call = buildOpenAiResponsesFunctionCall(toolCalls.optJSONObject(j));
                    if (call.length() > 0) {
                        input.put(call);
                    }
                }
            }
        }
        upstream.put("model", firstNonEmpty(cfg.model, resolveRequestModel(localPayload, cfg)));
        if (!TextUtils.isEmpty(systemText)) {
            upstream.put("instructions", systemText);
        }
        upstream.put("input", input);
        int maxTokens = resolveMaxTokensValue(localPayload);
        if (maxTokens > 0) {
            upstream.put("max_output_tokens", maxTokens);
        }
        copyIfPresent(localPayload, upstream, "temperature");
        // v1.5.6+：DPS-19 — Responses 思考开关（reasoning.effort）
        applyThinkingForResponses(upstream, localPayload);
        return upstream;
    }

    private static JSONObject buildGeminiRequestPayload(JSONObject localPayload, ProxyConfig cfg) throws JSONException {
        JSONObject upstream = new JSONObject();
        JSONArray sourceMessages = resolveMessages(localPayload, cfg);
        JSONArray contents = new JSONArray();
        String systemText = collectSystemText(sourceMessages);
        for (int i = 0; i < (sourceMessages == null ? 0 : sourceMessages.length()); i++) {
            JSONObject message = sourceMessages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = normalizeLocalRole(message.optString("role", ""));
            if ("system".equals(role)) {
                continue;
            }
            String mappedRole = "assistant".equals(role) ? "model" : "user";
            // v1.5.0：保留 multimodal parts
            JSONArray parts = convertContentToGeminiParts(message);
            if (parts.length() == 0) {
                continue;
            }
            JSONObject last = contents.length() > 0 ? contents.optJSONObject(contents.length() - 1) : null;
            if (last != null && mappedRole.equals(last.optString("role", ""))) {
                JSONArray prevParts = last.optJSONArray("parts");
                if (prevParts == null) {
                    prevParts = new JSONArray();
                    last.put("parts", prevParts);
                }
                for (int j = 0; j < parts.length(); j++) {
                    prevParts.put(parts.opt(j));
                }
            } else {
                contents.put(new JSONObject().put("role", mappedRole).put("parts", parts));
            }
        }
        if (!TextUtils.isEmpty(systemText)) {
            upstream.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemText))));
        }
        upstream.put("contents", contents);

        JSONObject generationConfig = new JSONObject();
        int maxTokens = resolveMaxTokensValue(localPayload);
        if (maxTokens > 0) {
            generationConfig.put("maxOutputTokens", maxTokens);
        }
        copyIfPresent(localPayload, generationConfig, "temperature");
        copyIfPresent(localPayload, generationConfig, "topP", "top_p");
        // v1.5.6+：DPS-19 — Gemini 2.5 系思考开关（thinkingConfig.thinkingBudget）
        applyThinkingForGemini(generationConfig, localPayload);
        if (generationConfig.length() > 0) {
            upstream.put("generationConfig", generationConfig);
        }
        return upstream;
    }

    private static Map<String, String> buildAdapterHeaders(ProxyConfig cfg) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (cfg == null || TextUtils.isEmpty(cfg.apiKey)) {
            return headers;
        }
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(cfg.adapterPreset)) {
            headers.put("x-api-key", cfg.apiKey);
            headers.put("anthropic-version", "2023-06-01");
        } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset)) {
            headers.put("x-goog-api-key", cfg.apiKey);
        } else {
            headers.put("Authorization", "Bearer " + cfg.apiKey);
        }
        return headers;
    }

    private static String resolvePathTemplate(String path, ProxyConfig cfg) {
        String resolved = ProxyConfig.normalizePath(path);
        if (cfg == null) {
            return resolved;
        }
        String rawModel = firstNonEmpty(cfg.model, "");
        String modelForPath = rawModel;
        if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset) && modelForPath.startsWith("models/")) {
            modelForPath = modelForPath.substring("models/".length());
        }
        return resolved
                .replace("${rawModel}", rawModel)
                .replace("${model}", modelForPath);
    }

    private static String buildLocalChatResponse(String text, String model, String id, String finishReason, JSONObject usage) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("id", TextUtils.isEmpty(id) ? "proxy-" + System.currentTimeMillis() : id);
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000L);
        root.put("model", firstNonEmpty(model, ""));

        JSONArray choices = new JSONArray();
        choices.put(new JSONObject()
                .put("index", 0)
                .put("finish_reason", firstNonEmpty(finishReason, "stop"))
                .put("message", new JSONObject()
                        .put("role", "assistant")
                        .put("content", firstNonEmpty(text, ""))));
        root.put("choices", choices);
        if (usage != null) {
            root.put("usage", usage);
        }
        return root.toString();
    }

    private static JSONObject buildUsageObject(JSONObject usage, String promptKey, String completionKey, String fallbackPromptKey, String fallbackCompletionKey, String totalKey) throws JSONException {
        if (usage == null) {
            return null;
        }
        int promptTokens = firstPositive(
                intValue(usage.opt(promptKey)),
                intValue(usage.opt(fallbackPromptKey)),
                intValue(usage.opt("prompt_tokens"))
        );
        int completionTokens = firstPositive(
                intValue(usage.opt(completionKey)),
                intValue(usage.opt(fallbackCompletionKey)),
                intValue(usage.opt("completion_tokens"))
        );
        int totalTokens = firstPositive(
                intValue(usage.opt(totalKey)),
                intValue(usage.opt("total_tokens")),
                promptTokens + completionTokens
        );
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return null;
        }
        return new JSONObject()
                .put("prompt_tokens", Math.max(promptTokens, 0))
                .put("completion_tokens", Math.max(completionTokens, 0))
                .put("total_tokens", Math.max(totalTokens, 0));
    }

    private static String extractClaudeResponseText(JSONObject root) {
        return firstNonEmpty(joinTextFragments(root.opt("content")), extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH));
    }

    private static String extractOpenAiResponsesText(JSONObject root) {
        return firstNonEmpty(root.optString("output_text", ""), joinTextFragments(root.opt("output")), extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH));
    }

    private static String extractGeminiResponseText(JSONObject root) {
        return firstNonEmpty(
                joinTextFragments(JsonPathUtils.getValue(root, "candidates[0].content.parts")),
                joinTextFragments(root.opt("candidates")),
                extractResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH)
        );
    }

    private static String joinTextFragments(Object node) {
        StringBuilder builder = new StringBuilder();
        appendTextFragments(node, builder, 0);
        return builder.toString().trim();
    }

    private static void appendTextFragments(Object node, StringBuilder builder, int depth) {
        if (node == null || node == JSONObject.NULL || depth > 8) {
            return;
        }
        if (node instanceof String) {
            builder.append((String) node);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                appendTextFragments(array.opt(i), builder, depth + 1);
            }
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String direct = firstNonEmpty(object.optString("text", ""), object.optString("output_text", ""), object.optString("value", ""));
            if (!TextUtils.isEmpty(direct)) {
                builder.append(direct);
            }
            appendTextFragments(object.opt("parts"), builder, depth + 1);
            appendTextFragments(object.opt("content"), builder, depth + 1);
            appendTextFragments(object.opt("output"), builder, depth + 1);
        }
    }

    private static void appendTextMessage(JSONArray target, String role, String text) throws JSONException {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        JSONObject last = target.length() > 0 ? target.optJSONObject(target.length() - 1) : null;
        if (last != null && role.equals(last.optString("role", ""))) {
            String merged = firstNonEmpty(last.optString("content", ""), "");
            last.put("content", TextUtils.isEmpty(merged) ? text : merged + "\n\n" + text);
            return;
        }
        target.put(new JSONObject()
                .put("role", role)
                .put("content", text));
    }

    private static void appendGeminiMessage(JSONArray target, String role, String text) throws JSONException {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        JSONObject last = target.length() > 0 ? target.optJSONObject(target.length() - 1) : null;
        if (last != null && role.equals(last.optString("role", ""))) {
            JSONArray parts = last.optJSONArray("parts");
            if (parts == null) {
                parts = new JSONArray();
                last.put("parts", parts);
            }
            parts.put(new JSONObject().put("text", text));
            return;
        }
        target.put(new JSONObject()
                .put("role", role)
                .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
    }

    /**
     * v1.5.0：解析 data: URI（form: "data:image/png;base64,XXXX..."）→ {mime, base64Data}。
     * 不合法返回 null。
     */
    private static String[] parseDataUri(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("data:")) {
            return null;
        }
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String header = url.substring(5, comma);
        String body = url.substring(comma + 1);
        if (!header.contains(";base64")) {
            return null;
        }
        String mime = header.substring(0, header.indexOf(";base64"));
        if (TextUtils.isEmpty(mime)) mime = "application/octet-stream";
        return new String[]{mime, body};
    }

    /**
     * v1.5.0：把游戏侧（OpenAI 兼容格式）message.content 转成 Claude 的 content blocks。
     * - String → [{type:"text", text:"..."}]
     * - Array of {type:"text"|"image_url"} → [{type:"text"|"image", ...}]
     * 返回为空列表表示无可用内容（caller 可跳过）。
     */
    private static JSONArray convertContentToClaudeBlocks(JSONObject message) throws JSONException {
        JSONArray blocks = new JSONArray();
        if (message == null) return blocks;
        String rawRole = firstNonEmpty(message.optString("role", ""), "").trim().toLowerCase(Locale.ROOT);
        if ("tool".equals(rawRole)) {
            String toolCallId = firstNonEmpty(message.optString("tool_call_id", ""), message.optString("id", ""));
            String text = getToolMessageContentText(message);
            if (!TextUtils.isEmpty(toolCallId)) {
                blocks.put(new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", toolCallId)
                        .put("content", firstNonEmpty(text, "")));
            } else if (!TextUtils.isEmpty(text)) {
                blocks.put(new JSONObject().put("type", "text").put("text", text));
            }
            return blocks;
        }
        // v1.5.4+：Claude 协议下 assistant 第二轮回流时，ToolCallLoop.appendToolCallTurn 已把
        // 原 Claude content 数组（含 thinking{signature} + tool_use + text 完整 block）写入
        // _originalAssistantBlocks 字段。优先使用以保留 signature（解决 TCL-3/4/6 + DPS-2）。
        String savedBlocks = message.optString("_originalAssistantBlocks", "");
        if (!TextUtils.isEmpty(savedBlocks)) {
            try {
                return new JSONArray(savedBlocks);
            } catch (JSONException ignored) {
                // 解析失败时走下面的 fallback
            }
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (!TextUtils.isEmpty(text)) {
                blocks.put(new JSONObject().put("type", "text").put("text", text));
            }
        } else if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                JSONObject part = array.optJSONObject(i);
                if (part == null) continue;
                String type = part.optString("type", "text");
                if ("text".equalsIgnoreCase(type)) {
                    String text = part.optString("text", "");
                    if (!TextUtils.isEmpty(text)) {
                        blocks.put(new JSONObject().put("type", "text").put("text", text));
                    }
                } else if ("image_url".equalsIgnoreCase(type)) {
                    JSONObject imageUrl = part.optJSONObject("image_url");
                    String url = imageUrl == null ? null : imageUrl.optString("url", "");
                    String[] parsed = parseDataUri(url);
                    if (parsed != null) {
                        JSONObject source = new JSONObject();
                        source.put("type", "base64");
                        source.put("media_type", parsed[0]);
                        source.put("data", parsed[1]);
                        blocks.put(new JSONObject().put("type", "image").put("source", source));
                    }
                }
            }
        }
        appendClaudeToolUseBlocks(blocks, message.optJSONArray("tool_calls"));
        return blocks;
    }

    /** v1.5.0：转 Gemini parts（每张图 inlineData {mimeType, data}） */
    private static JSONArray convertContentToGeminiParts(JSONObject message) throws JSONException {
        JSONArray parts = new JSONArray();
        if (message == null) return parts;
        String rawRole = firstNonEmpty(message.optString("role", ""), "").trim().toLowerCase(Locale.ROOT);
        if ("tool".equals(rawRole)) {
            String name = message.optString("name", "");
            String text = getToolMessageContentText(message);
            if (!TextUtils.isEmpty(name)) {
                parts.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", name)
                        .put("response", new JSONObject().put("result", firstNonEmpty(text, "")))));
            } else if (!TextUtils.isEmpty(text)) {
                parts.put(new JSONObject().put("text", text));
            }
            return parts;
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (!TextUtils.isEmpty(text)) {
                parts.put(new JSONObject().put("text", text));
            }
        } else if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                JSONObject part = array.optJSONObject(i);
                if (part == null) continue;
                String type = part.optString("type", "text");
                if ("text".equalsIgnoreCase(type)) {
                    String text = part.optString("text", "");
                    if (!TextUtils.isEmpty(text)) {
                        parts.put(new JSONObject().put("text", text));
                    }
                } else if ("image_url".equalsIgnoreCase(type)) {
                    JSONObject imageUrl = part.optJSONObject("image_url");
                    String url = imageUrl == null ? null : imageUrl.optString("url", "");
                    String[] parsed = parseDataUri(url);
                    if (parsed != null) {
                        JSONObject inline = new JSONObject();
                        inline.put("mimeType", parsed[0]);
                        inline.put("data", parsed[1]);
                        parts.put(new JSONObject().put("inlineData", inline));
                    }
                }
            }
        }
        appendGeminiFunctionCallParts(parts, message.optJSONArray("tool_calls"));
        return parts;
    }

    /** v1.5.0：转 OpenAI Responses content（input_text / input_image） */
    private static JSONArray convertContentToOpenAiResponsesContent(JSONObject message, String role) throws JSONException {
        JSONArray result = new JSONArray();
        if (message == null) return result;
        boolean isAssistant = "assistant".equals(role);
        String textTag = isAssistant ? "output_text" : "input_text";
        String imageTag = "input_image";  // assistant 一般不发 image
        Object content = message.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (!TextUtils.isEmpty(text)) {
                result.put(new JSONObject().put("type", textTag).put("text", text));
            }
            return result;
        }
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                JSONObject part = array.optJSONObject(i);
                if (part == null) continue;
                String type = part.optString("type", "text");
                if ("text".equalsIgnoreCase(type)) {
                    String text = part.optString("text", "");
                    if (!TextUtils.isEmpty(text)) {
                        result.put(new JSONObject().put("type", textTag).put("text", text));
                    }
                } else if ("image_url".equalsIgnoreCase(type) && !isAssistant) {
                    JSONObject imageUrl = part.optJSONObject("image_url");
                    String url = imageUrl == null ? null : imageUrl.optString("url", "");
                    if (!TextUtils.isEmpty(url)) {
                        result.put(new JSONObject().put("type", imageTag).put("image_url", url));
                    }
                }
            }
        }
        return result;
    }

    private static void appendClaudeToolUseBlocks(JSONArray blocks, JSONArray toolCalls) throws JSONException {
        for (int i = 0; toolCalls != null && i < toolCalls.length(); i++) {
            JSONObject item = toolCalls.optJSONObject(i);
            if (item == null) continue;
            JSONObject function = item.optJSONObject("function");
            if (function == null) continue;
            String name = function.optString("name", "");
            if (TextUtils.isEmpty(name)) continue;
            blocks.put(new JSONObject()
                    .put("type", "tool_use")
                    .put("id", firstNonEmpty(item.optString("id", ""), "proxy-tool-" + i))
                    .put("name", name)
                    .put("input", parseToolArgumentsObject(function.optString("arguments", ""))));
        }
    }

    private static void appendGeminiFunctionCallParts(JSONArray parts, JSONArray toolCalls) throws JSONException {
        for (int i = 0; toolCalls != null && i < toolCalls.length(); i++) {
            JSONObject item = toolCalls.optJSONObject(i);
            if (item == null) continue;
            JSONObject function = item.optJSONObject("function");
            if (function == null) continue;
            String name = function.optString("name", "");
            if (TextUtils.isEmpty(name)) continue;
            parts.put(new JSONObject().put("functionCall", new JSONObject()
                    .put("name", name)
                    .put("args", parseToolArgumentsObject(function.optString("arguments", "")))));
        }
    }

    private static JSONObject buildOpenAiResponsesFunctionCall(JSONObject toolCall) throws JSONException {
        JSONObject result = new JSONObject();
        if (toolCall == null) return result;
        JSONObject function = toolCall.optJSONObject("function");
        if (function == null) return result;
        String name = function.optString("name", "");
        if (TextUtils.isEmpty(name)) return result;
        result.put("type", "function_call");
        result.put("call_id", firstNonEmpty(toolCall.optString("id", ""), "proxy-call"));
        result.put("name", name);
        result.put("arguments", firstNonEmpty(function.optString("arguments", ""), "{}"));
        return result;
    }

    private static JSONObject buildOpenAiResponsesFunctionCallOutput(JSONObject toolMessage) throws JSONException {
        JSONObject result = new JSONObject();
        if (toolMessage == null) return result;
        String callId = firstNonEmpty(toolMessage.optString("tool_call_id", ""), toolMessage.optString("id", ""));
        if (TextUtils.isEmpty(callId)) return result;
        result.put("type", "function_call_output");
        result.put("call_id", callId);
        result.put("output", getToolMessageContentText(toolMessage));
        return result;
    }

    private static JSONObject parseToolArgumentsObject(String rawArguments) {
        if (TextUtils.isEmpty(rawArguments)) {
            return new JSONObject();
        }
        try {
            Object parsed = new JSONTokener(rawArguments).nextValue();
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("value", rawArguments);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static String getToolMessageContentText(JSONObject message) {
        return firstNonEmpty(getMessageText(message), message == null ? "" : message.optString("output", ""));
    }

    private static String collectSystemText(JSONArray messages) {
        if (messages == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            if ("system".equals(normalizeLocalRole(message.optString("role", "")))) {
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(getMessageText(message));
            }
        }
        return builder.toString().trim();
    }

    private static String extractLastUserText(JSONArray messages) {
        MessageRef lastUser = findLastMessage(messages, "user");
        return lastUser == null ? "" : getMessageText(lastUser.message);
    }

    private static String buildHistoryConversationTranscript(JSONArray messages) {
        if (messages == null || messages.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = normalizeHistoryRole(message.optString("role", ""));
            if (TextUtils.isEmpty(role)) {
                continue;
            }
            String text = sanitizeHistoryMessageText(getMessageText(message), role);
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(historyRoleLabel(role)).append("：\n").append(text);
        }
        return builder.toString().trim();
    }

    private static String historyRoleLabel(String role) {
        if ("assistant".equals(role)) {
            return "yuki回复";
        }
        return "用户";
    }

    private static String normalizeHistoryRole(String role) {
        String normalized = firstNonEmpty(role, "").trim().toLowerCase(Locale.ROOT);
        if ("user".equals(normalized)) {
            return "user";
        }
        if ("assistant".equals(normalized) || "model".equals(normalized)) {
            return "assistant";
        }
        return "";
    }

    private static String sanitizeHistoryMessageText(String text, String role) {
        String value = firstNonEmpty(text, "").replace("\r\n", "\n").replace('\r', '\n').trim();
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        if ("user".equals(role)) {
            int metaIndex = findEmbeddedMetaPromptIndex(value);
            if (metaIndex == 0) {
                return "";
            }
            if (metaIndex > 0) {
                value = value.substring(0, metaIndex).trim();
            }
        }
        return value;
    }

    private static int findEmbeddedMetaPromptIndex(String text) {
        if (TextUtils.isEmpty(text)) {
            return -1;
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("（", "(")
                .replace("）", ")")
                .replace("：", ":")
                .toLowerCase(Locale.ROOT);
        int index = normalized.indexOf("(system:");
        if (index == 0) {
            return 0;
        }
        if (index > 0 && normalized.charAt(index - 1) == '\n') {
            return index;
        }
        return -1;
    }

    private static boolean looksLikeStandaloneMetaPrompt(String text) {
        return findEmbeddedMetaPromptIndex(firstNonEmpty(text, "").trim()) == 0;
    }

    private static boolean looksLikeEmbeddedDiaryPrompt(String text) {
        String normalized = normalizeForMatch(text);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        return normalized.contains("本次对话记录")
                && normalized.contains("(system:")
                && normalized.contains("日记")
                && (normalized.contains("写成一篇") || normalized.contains("不要记录日期"));
    }

    private static String normalizeLocalRole(String role) {
        String normalized = firstNonEmpty(role, "user").trim().toLowerCase(Locale.ROOT);
        if ("system".equals(normalized) || "developer".equals(normalized)) {
            return "system";
        }
        if ("assistant".equals(normalized) || "model".equals(normalized)) {
            return "assistant";
        }
        return "user";
    }

    private static int resolveMaxTokensValue(JSONObject payload) {
        if (payload == null) {
            return 0;
        }
        int value = intValue(payload.opt("max_tokens"));
        if (value > 0) {
            return value;
        }
        value = intValue(payload.opt("max_completion_tokens"));
        if (value > 0) {
            return value;
        }
        return intValue(payload.opt("max_output_tokens"));
    }

    private static double resolveTemperatureValue(JSONObject payload) {
        if (payload == null) {
            return Double.NaN;
        }
        return doubleValue(payload.opt("temperature"));
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String targetKey) throws JSONException {
        copyIfPresent(source, target, targetKey, targetKey);
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String targetKey, String sourceKey) throws JSONException {
        if (source == null || target == null || TextUtils.isEmpty(targetKey) || TextUtils.isEmpty(sourceKey)) {
            return;
        }
        Object value = source.opt(sourceKey);
        if (value != null && value != JSONObject.NULL) {
            target.put(targetKey, value);
        }
    }

    private static int intValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double doubleValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return Double.NaN;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        long longValue = (long) value;
        if (Double.compare(value, (double) longValue) == 0) {
            return String.valueOf(longValue);
        }
        return String.valueOf(value);
    }

    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return values.length == 0 ? 0 : values[values.length - 1];
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

    private static String normalizeFinishReason(String raw) {
        String normalized = firstNonEmpty(raw, "stop").trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("max")) {
            return "length";
        }
        if (normalized.contains("stop") || normalized.contains("end") || normalized.contains("complete")) {
            return "stop";
        }
        if (normalized.contains("tool")) {
            return "tool_calls";
        }
        return normalized;
    }

    private static String normalizeModelIdForDisplay(String modelId, ProxyConfig cfg) {
        String value = modelId == null ? "" : modelId.trim();
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        if (cfg != null && ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(cfg.adapterPreset) && value.startsWith("models/")) {
            return value.substring("models/".length());
        }
        return value;
    }

    private static Object parseJsonValue(String body) throws JSONException {
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        return new JSONTokener(body).nextValue();
    }

    private static void collectModelIds(Object node, LinkedHashSet<String> out, int depth) {
        if (node == null || depth > 6) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectModelIds(array.opt(i), out, depth + 1);
            }
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            addModelCandidate(out, object.optString("id", ""));
            addModelCandidate(out, object.optString("model", ""));
            if (looksLikeModelContainer(object)) {
                addModelCandidate(out, object.optString("name", ""));
            }
            collectModelIds(object.opt("data"), out, depth + 1);
            collectModelIds(object.opt("models"), out, depth + 1);
            collectModelIds(object.opt("items"), out, depth + 1);
            collectModelIds(object.opt("result"), out, depth + 1);
            collectModelIds(object.opt("results"), out, depth + 1);
            return;
        }
        if (node instanceof String) {
            addModelCandidate(out, String.valueOf(node));
        }
    }

    private static boolean looksLikeModelContainer(JSONObject object) {
        if (object == null) {
            return false;
        }
        String objectType = object.optString("object", "");
        return "model".equalsIgnoreCase(objectType)
                || object.has("owned_by")
                || object.has("permission")
                || object.has("created")
                || object.has("supportedGenerationMethods")
                || object.has("displayName")
                || object.has("baseModelId")
                || object.has("input_token_limit")
                || object.has("output_token_limit");
    }

    private static void addModelCandidate(LinkedHashSet<String> out, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (value.length() > 120 || value.contains("\n") || value.contains("{") || value.contains("[")) {
            return;
        }
        if ("list".equalsIgnoreCase(value) || "model".equalsIgnoreCase(value)) {
            return;
        }
        out.add(value);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream in = new BufferedInputStream(inputStream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String readRequestBodyTextSafely(IHTTPSession session) throws IOException {
        byte[] bytes = readRequestBodyBytes(session);
        String contentType = headerValue(session, "content-type");
        return decodeBody(bytes, contentType).text;
    }

    private static byte[] readRequestBodyBytes(IHTTPSession session) throws IOException {
        long bodySize = resolveRequestBodySize(session);
        if (bodySize > 0) {
            InputStream inputStream = session.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(bodySize, 8192L));
            byte[] buffer = new byte[4096];
            long remaining = bodySize;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int count = inputStream.read(buffer, 0, toRead);
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (output.size() > 0) {
                return output.toByteArray();
            }
        }

        Map<String, String> files = new java.util.HashMap<>();
        try {
            session.parseBody(files);
            String postData = files.get("postData");
            if (!TextUtils.isEmpty(postData)) {
                return postData.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return new byte[0];
    }

    private static long resolveRequestBodySize(IHTTPSession session) {
        try {
            return Long.parseLong(headerValue(session, "content-length"));
        } catch (Exception ignored) {
        }
        try {
            return (Long) session.getClass().getMethod("getBodySize").invoke(session);
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String headerValue(IHTTPSession session, String name) {
        if (session == null || TextUtils.isEmpty(name)) {
            return null;
        }
        Map<String, String> headers = session.getHeaders();
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (!TextUtils.isEmpty(direct)) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static DecodedBody decodeBody(byte[] raw, String contentType) {
        if (raw == null || raw.length == 0) {
            return new DecodedBody("", "UTF-8", 0);
        }
        String hinted = extractCharset(contentType);
        DecodedBody best = tryDecode(raw, hinted);
        DecodedBody utf8 = tryDecode(raw, "UTF-8");
        if (best == null || (utf8 != null && utf8.replacementCount < best.replacementCount)) {
            best = utf8;
        }
        DecodedBody gb18030 = tryDecode(raw, "GB18030");
        if (best == null || (gb18030 != null && gb18030.replacementCount < best.replacementCount)) {
            best = gb18030;
        }
        if (best == null) {
            String text = new String(raw, StandardCharsets.UTF_8);
            return new DecodedBody(text, "UTF-8", countReplacement(text));
        }
        return best;
    }

    private static DecodedBody tryDecode(byte[] raw, String charsetName) {
        if (TextUtils.isEmpty(charsetName)) {
            return null;
        }
        try {
            Charset charset = Charset.forName(charsetName);
            String text = new String(raw, charset);
            return new DecodedBody(text, charset.name(), countReplacement(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int countReplacement(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\ufffd') {
                count++;
            }
        }
        return count;
    }

    private static String extractCharset(String contentType) {
        if (TextUtils.isEmpty(contentType)) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("charset=");
        if (index < 0) {
            return null;
        }
        String charset = contentType.substring(index + 8).trim();
        int semi = charset.indexOf(';');
        if (semi >= 0) {
            charset = charset.substring(0, semi);
        }
        charset = charset.replace("\"", "").replace("'", "").trim();
        return TextUtils.isEmpty(charset) ? null : charset;
    }

    private static String ensureCharsetInContentType(String contentType, String charset) {
        String normalized = TextUtils.isEmpty(contentType) ? "application/json" : contentType;
        if (normalized.toLowerCase(Locale.ROOT).contains("charset=")) {
            return normalized;
        }
        return normalized + "; charset=" + (TextUtils.isEmpty(charset) ? "UTF-8" : charset);
    }

    private static boolean isStreamingContentType(String contentType) {
        if (TextUtils.isEmpty(contentType)) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("text/event-stream")
                || normalized.contains("application/x-ndjson")
                || normalized.contains("application/stream+json");
    }

    private static boolean looksLikeStreamingBody(String body) {
        if (TextUtils.isEmpty(body)) {
            return false;
        }
        String normalized = body.trim();
        return normalized.startsWith("data:")
                || normalized.startsWith("event:")
                || normalized.contains("\n\ndata:")
                || normalized.contains("\r\n\r\ndata:");
    }

    private static boolean isTruthy(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    private Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        return response;
    }

    private Response jsonResponse(Response.Status status, String body) {
        return newFixedLengthResponse(status, "application/json; charset=UTF-8", body);
    }

    private String errorJson(String code, String message) {
        try {
            return new JSONObject()
                    .put("error", new JSONObject()
                            .put("code", code)
                            .put("message", TextUtils.isEmpty(message) ? code : message))
                    .toString();
        } catch (Exception ignored) {
            return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + (TextUtils.isEmpty(message) ? code : message) + "\"}}";
        }
    }

    private String healthJson(ProxyConfig cfg) {
        try {
            return new JSONObject().put("ok", true).put("port", cfg.port).toString();
        } catch (Exception ignored) {
            return "{\"ok\":true,\"port\":" + cfg.port + "}";
        }
    }

    private Response.Status toStatus(int code) {
        for (Response.Status status : Response.Status.values()) {
            if (status.getRequestStatus() == code) {
                return status;
            }
        }
        if (code >= 500) {
            return Response.Status.INTERNAL_ERROR;
        }
        if (code >= 400) {
            return Response.Status.BAD_REQUEST;
        }
        return Response.Status.OK;
    }

    private void log(String line) {
        try {
            sink.log(line);
        } catch (Exception ignored) {
        }
    }

    private static int firstColonIndex(String line) {
        if (TextUtils.isEmpty(line)) {
            return -1;
        }
        int fullWidth = line.indexOf('：');
        int halfWidth = line.indexOf(':');
        if (fullWidth < 0) {
            return halfWidth;
        }
        if (halfWidth < 0) {
            return fullWidth;
        }
        return Math.min(fullWidth, halfWidth);
    }

    private static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("（", "(")
                .replace("）", ")")
                .replace("：", ":")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("‘", "")
                .replace("’", "")
                .replaceAll("\\s+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static List<String> splitLines(String raw) {
        List<String> values = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return values;
        }
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static boolean matchesPath(String uri, List<String> paths) {
        if (TextUtils.isEmpty(uri)) {
            return false;
        }
        String normalized = ProxyConfig.normalizePath(uri);
        for (String candidate : paths) {
            if (normalized.equals(ProxyConfig.normalizePath(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String text, int maxLen) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        if (oneLine.length() <= maxLen) {
            return oneLine;
        }
        return oneLine.substring(0, Math.max(0, maxLen)) + "...";
    }

    /**
     * v1.5.x：日记 + DeepSeek 兼容模式专用——把 base_url 自动升级到 DeepSeek 续写 endpoint。
     * <ul>
     *   <li>仅当 base 包含 {@code api.deepseek.com} 时生效，第三方 OpenAI 兼容代理域名不动；</li>
     *   <li>已含 {@code /beta} 时直接返回（幂等）；</li>
     *   <li>裸域 {@code https://api.deepseek.com} 末尾追加 {@code /beta}；</li>
     *   <li>{@code /v1} 后缀替换为 {@code /beta}（DeepSeek 文档给的另一种合法写法）。</li>
     * </ul>
     * 仅在每请求 cfg 副本上调用，不影响持久化或其他并发请求。
     */
    private static String upgradeDeepseekBetaIfNeeded(String base) {
        if (TextUtils.isEmpty(base)) {
            return base;
        }
        String trimmed = base;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.contains("api.deepseek.com")) {
            return base;
        }
        if (lower.endsWith("/beta") || lower.contains("/beta/")) {
            return base;
        }
        if (lower.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - 3) + "/beta";
        }
        return trimmed + "/beta";
    }

    /**
     * v1.5.x：日记前缀续写——把用户输入里的字面转义序列解析成真控制字符。
     * <p>EditText 不会做反斜杠转义；用户在多行框里按回车产生的真 LF 已能直接生效，
     * 但为了兼容"从其他地方复制粘贴时带字面 {@code \n}"的场景，这里做一次轻量解析：
     * <ul>
     *   <li>{@code \n} → LF (U+000A)</li>
     *   <li>{@code \r} → CR (U+000D)</li>
     *   <li>{@code \t} → HT (U+0009)</li>
     *   <li>{@code \\} → 单个反斜杠</li>
     * </ul>
     * 其它 {@code \x} 序列按字面保留（避免误吞用户真想要的反斜杠）。无反斜杠时直接返回原串，零开销。
     */
    private static String unescapeUserPrefix(String raw) {
        if (TextUtils.isEmpty(raw) || raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        i += 2;
                        continue;
                    case 'r':
                        sb.append('\r');
                        i += 2;
                        continue;
                    case 't':
                        sb.append('\t');
                        i += 2;
                        continue;
                    case '\\':
                        sb.append('\\');
                        i += 2;
                        continue;
                    default:
                        break;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * v1.5.x：Gemini 适配器专用——把"必须以前缀开头"的硬约束追加到 messages 中最后一条 user 消息末尾。
     * <p>Gemini Generate Content API 不像 Anthropic Messages 那样自动续写 contents 末尾的 model turn，
     * 实测 Gemini 2.x Flash 收到末尾是 {@code role: "model"} 的请求时，会把"【日记】"视作
     * 已完成的回复，立即 STOP 一字未写（{@code finish_reason="stop"}, {@code completion_tokens=0}）。
     * 因此对 Gemini 改用 prompt 引导：在最后一条 user 消息末尾追加一行硬约束指令，
     * 配合响应侧的 {@code prepend} 兜底逻辑，确保前缀总能出现在最终 content 里。
     *
     * @param messages       OpenAI 风格的 messages 数组（rewriteRequest 内部表示，applyMessages 写回 payload）
     * @param resolvedPrefix 已通过 {@link #unescapeUserPrefix} 解析过的前缀文本
     * @return 是否成功追加（找不到 user 消息或 messages 为空时返回 false）
     */
    private static boolean appendDiaryPrefixHintToLastUser(JSONArray messages, String resolvedPrefix) {
        if (messages == null || messages.length() == 0 || TextUtils.isEmpty(resolvedPrefix)) {
            return false;
        }
        MessageRef ref = findLastMessage(messages, "user");
        if (ref == null || ref.message == null) {
            return false;
        }
        // 把前缀末尾空白剥掉用于 hint 文本（避免引号里出现裸换行影响可读性），
        // 真正的前缀完整版仍由响应侧的 prepend 兜底逻辑提供。
        String prefixForHint = resolvedPrefix.replaceAll("\\s+$", "");
        if (TextUtils.isEmpty(prefixForHint)) {
            return false;
        }
        String hint = "\n\n（重要格式约束：请严格以\"" + prefixForHint
                + "\"作为开头开始你的回复，作为日记的标题独占一行，不要有任何前置解释或总结性引导。）";
        try {
            String content = getMessageText(ref.message);
            if (content == null) {
                content = "";
            }
            // 防止重发请求场景下重复追加同一段 hint
            if (content.endsWith(hint)) {
                return true;
            }
            setMessageText(ref.message, content + hint);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class ChatDecision {
        String requestKind = "pass-through";
        String originalSystemPreview = "";
        String originalUserPreview = "";
        String userPreview = "";
        String systemPreview = "";
        String modelPreview = "";
        boolean streamRequested = false;
        boolean streamForcedOff = false;
        boolean diaryMatched = false;
        boolean diaryRewritten = false;
        String diaryType = "none";
        String diaryReason = "";
        String diaryTemplateVars = "";
        boolean tokenApplied = false;
        boolean personaApplied = false;
        String personaTier = "";
        String personaReason = "";
        boolean restrictionLineStripped = false;
        boolean systemTimeStripped = false;
        String adapterNote = "";
    }

    private static final class SystemSanitizeResult {
        boolean changed = false;
        boolean restrictionLineStripped = false;
        boolean systemTimeStripped = false;
    }

    private static final class ForwardTrace {
        String method = "";
        String url = "";
        String proxyType = "";
        int attemptCount = 0;
        boolean retriedWithConnectionClose = false;
        long openMs = 0L;
        long writeMs = 0L;
        long waitResponseMs = 0L;
        long readBodyMs = 0L;
        long totalMs = 0L;
        int requestBytes = 0;
        int responseBytes = 0;
        int statusCode = 0;
        String firstError = "";
        String lastError = "";
    }

    private static final class DiaryDetection {
        boolean matched = false;
        String diaryType = "none";
        String reason = "";
    }

    private static final class RenderTemplateResult {
        boolean success = false;
        String rendered = "";
        String resolvedVarsSummary = "";
        final List<String> missingVars = new ArrayList<>();
    }

    private static final class TemplateVars {
        String genderTerm = "";
        String occasion = "";
        String theme = "";
        String conversation = "";
        String conversationSource = "";
    }

    private static final class MemoryExtractVars {
        String personality = "";
        String genderTerm = "";
        String conversation = "";
    }

    private static final class DiaryConversationResult {
        String conversation = "";
        String source = "";
    }

    private static final class MemoryExtractRewriteResult {
        boolean applied = false;
        String rewrittenPrompt = "";
        String resolvedVarsSummary = "";
        String reason = "";
    }

    public static final class ModelFetchResult {
        public final boolean success;
        public final List<String> models;
        public final String message;

        private ModelFetchResult(boolean success, List<String> models, String message) {
            this.success = success;
            this.models = models;
            this.message = message;
        }

        static ModelFetchResult success(List<String> models, String message) {
            return new ModelFetchResult(true, models == null ? new ArrayList<>() : models, message);
        }

        static ModelFetchResult failure(String message) {
            return new ModelFetchResult(false, new ArrayList<>(), message);
        }
    }

    public static final class ReplayResult {
        public final boolean success;
        public final String message;
        public final int statusCode;

        private ReplayResult(boolean success, String message, int statusCode) {
            this.success = success;
            this.message = message;
            this.statusCode = statusCode;
        }

        static ReplayResult success(String message, int statusCode) {
            return new ReplayResult(true, message, statusCode);
        }

        static ReplayResult failure(String message, int statusCode) {
            return new ReplayResult(false, message, statusCode);
        }
    }

    private static final class MessageRef {
        final int index;
        final JSONObject message;

        MessageRef(int index, JSONObject message) {
            this.index = index;
            this.message = message;
        }
    }

    private static final class CustomRequestFieldsSpec {
        final String noteName;
        final String json;

        CustomRequestFieldsSpec(String noteName, String json) {
            this.noteName = noteName;
            this.json = json;
        }
    }

    private static final class CustomFieldsApplyResult {
        final UpstreamRequest request;
        final String adapterNote;

        CustomFieldsApplyResult(UpstreamRequest request, String adapterNote) {
            this.request = request;
            this.adapterNote = adapterNote;
        }
    }

    private static final class UpstreamRequest {
        final String method;
        final String path;
        final String body;
        final Map<String, String> headers;

        UpstreamRequest(String method, String path, String body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }
    }

    private static final class UpstreamResponse {
        final int status;
        final String contentType;
        final String body;
        final String decodedCharset;
        final int replacementCount;
        final boolean streaming;

        UpstreamResponse(int status, String contentType, String body, String decodedCharset, int replacementCount, boolean streaming) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.decodedCharset = decodedCharset;
            this.replacementCount = replacementCount;
            this.streaming = streaming;
        }
    }

    private static final class ChatExecutionResult {
        ChatDecision decision = new ChatDecision();
        String requestBody = "";
        String uri = "";
        String originalConversation = "";
        String forwardedConversation = "";
        boolean replayed = false;
        boolean success = false;
        boolean requestWakeLockHeld = false;
        int requestBytes = 0;
        int statusCode = 500;
        long readBodyMs = 0L;
        long parseMs = 0L;
        long rewriteMs = 0L;
        long forwardMs = 0L;
        long totalMs = 0L;
        String contentType = "application/json; charset=UTF-8";
        String responseBody = "";
        String assistantText = "";
        String errorCode = "";
        String errorMessage = "";
        String decodedCharset = "";
        String runtimeSummary = "";
        int replacementCount = 0;
        ForwardTrace forwardTrace = null;
        AttachmentInjectionResult attachmentInjection = null;
        /** 发往上游的初始 request 快照，供调试导出用。仅在通过 buildChatUpstreamRequest 后才会被赋值。 */
        UpstreamRequest debugUpstreamRequest = null;
        /** runToolCallLoopIfNeeded 每轮收集的 tool_call 入参与代理执行结果。 */
        List<ProxyStorageHelper.DebugToolCallTurn> toolCallTurns = new ArrayList<>();
    }

    /** v1.5.0：附件注入返回值。供后续日志/UI/清理使用。 */
    static final class AttachmentInjectionResult {
        boolean injected = false;
        int injectedCount = 0;
        long totalBytes = 0L;
        String adapterNote = "";
        String dropReason = "";
        String sizeWarning = "";
        java.util.List<String> consumedIds = new java.util.ArrayList<>();
    }

    private static final class DecodedBody {
        final String text;
        final String charset;
        final int replacementCount;

        DecodedBody(String text, String charset, int replacementCount) {
            this.text = text;
            this.charset = charset;
            this.replacementCount = replacementCount;
        }
    }
}
