package com.diaryproxy.app;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;

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
    private static final long REQUEST_WAKE_TIMEOUT_MS = 180000L;

    private final AtomicLong requestCounter = new AtomicLong(1L);
    private final Context appContext;
    private final ProxyLogSink sink;
    private volatile ProxyConfig config;

    public DiaryProxyServer(Context context, ProxyConfig config, ProxyLogSink sink) {
        super((config == null ? new ProxyConfig() : config).copy().port);
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
            result.rewriteMs = SystemClock.elapsedRealtime() - rewriteStartedAt;

            if (!replayed && result.decision != null && result.decision.diaryMatched) {
                cacheLastDiaryRequest(uri, requestBody, result.decision, result.originalConversation);
            }

            ForwardTrace forwardTrace = new ForwardTrace();
            result.forwardTrace = forwardTrace;
            UpstreamRequest upstreamRequest = buildChatUpstreamRequest(payload, cfg);
            persistDebugPromptDumpIfNeeded(cfg, result.decision, upstreamRequest);
            UpstreamResponse upstream = forwardRaw(upstreamRequest, cfg, forwardTrace);
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

        persistHistoryIfNeeded(cfg, result);
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

    private void persistDebugPromptDumpIfNeeded(ProxyConfig cfg, ChatDecision decision, UpstreamRequest request) {
        if (appContext == null
                || cfg == null
                || !cfg.debugPromptDumpEnabled
                || request == null
                || TextUtils.isEmpty(request.body)) {
            return;
        }
        ProxyStorageHelper.DebugPromptRecord record = new ProxyStorageHelper.DebugPromptRecord();
        record.occurredAtMs = System.currentTimeMillis();
        record.requestKind = decision == null ? "" : decision.requestKind;
        record.diaryType = decision == null ? "" : decision.diaryType;
        record.adapterPreset = cfg.adapterPreset;
        record.model = decision == null ? firstNonEmpty(cfg.model, "") : firstNonEmpty(decision.modelPreview, cfg.model);
        record.personaApplied = decision != null && decision.personaApplied;
        record.personaTier = decision == null ? "" : firstNonEmpty(decision.personaTier, "");
        record.personaReason = decision == null ? "" : firstNonEmpty(decision.personaReason, "");
        record.upstreamPath = resolvePathTemplate(request.path, cfg);
        record.systemText = decision == null ? "" : firstNonEmpty(decision.systemPreview, "").trim();
        record.userText = decision == null ? "" : firstNonEmpty(decision.userPreview, "").trim();
        record.finalRequestBody = request.body;
        try {
            ProxyStorageHelper.appendDebugPromptRecord(appContext, record);
        } catch (IOException error) {
            log("proxy debug dump save failed: " + error.getMessage());
        }
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
            return decision;
        }

        MessageRef systemRef = findFirstMessage(messages, "system");
        MessageRef userRef = findLastMessage(messages, "user");

        decision.systemPreview = systemRef == null ? "" : getMessageText(systemRef.message);
        decision.userPreview = userRef == null ? "" : getMessageText(userRef.message);
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
                RenderTemplateResult renderResult = renderDiaryTemplate(template, extractTemplateVars(decision.userPreview));
                if (renderResult.success) {
                    decision.diaryTemplateVars = renderResult.resolvedVarsSummary;
                    if (!cfg.dryRun) {
                        setMessageText(userRef.message, renderResult.rendered);
                        decision.userPreview = renderResult.rendered;
                        decision.diaryRewritten = true;
                    }
                } else {
                    decision.diaryReason = appendReason(
                            decision.diaryReason,
                            "template_vars_missing=" + renderResult.missingVars
                    );
                }
            }
            if (!cfg.dryRun && cfg.overrideMaxTokens > 0 && applyConfiguredMaxTokens(payload, cfg, cfg.overrideMaxTokens)) {
                decision.tokenApplied = true;
            }
        } else {
            decision.requestKind = classifyNonDiaryRequest(decision.userPreview, decision.systemPreview);
            if (shouldAttemptPersonaOverlay(decision.requestKind, decision.systemPreview, decision.userPreview)
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
        }

        SystemSanitizeResult sanitizeResult = sanitizeOutboundSystemMessages(messages, cfg);
        decision.restrictionLineStripped = sanitizeResult.restrictionLineStripped;
        decision.systemTimeStripped = sanitizeResult.systemTimeStripped;
        if (sanitizeResult.changed) {
            decision.systemPreview = collectSystemText(messages);
        }

        applyConfiguredModel(payload, cfg);
        applyMessages(payload, cfg, messages);
        decision.modelPreview = resolveRequestModel(payload, cfg);
        return decision;
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

    private String classifyNonDiaryRequest(String userText, String systemText) {
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
        if (normalizedUser.contains("长期记忆")
                || normalizedUser.contains("关键事实")
                || normalizedUser.contains("只输出json数组")) {
            return "memory-extract";
        }
        if (looksLikeInteractiveStoryRequest(normalizedUser, normalizedSystem)) {
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

    private boolean shouldAttemptPersonaOverlay(String requestKind, String systemText, String userText) {
        if ("normal-chat".equals(requestKind) || "interactive-story".equals(requestKind)) {
            return true;
        }
        if (!"meta-prompt".equals(requestKind)) {
            return false;
        }
        String normalizedUser = normalizeForMatch(userText);
        String normalizedSystem = normalizeForMatch(systemText);
        if (TextUtils.isEmpty(normalizedSystem) || TextUtils.isEmpty(normalizedUser)) {
            return false;
        }
        if (!looksLikeMainPersonaPrompt(normalizedSystem)) {
            return false;
        }
        if (looksLikeHealthCheckRequest(normalizedUser, normalizedSystem)
                || looksLikeInteractiveStoryRequest(normalizedUser, normalizedSystem)
                || looksLikeDirectDiaryPrompt(userText)
                || normalizedUser.contains("reply1")
                || normalizedUser.contains("reply2")
                || normalizedUser.contains("json")
                || normalizedUser.contains("长期记忆")
                || normalizedUser.contains("关键事实")) {
            return false;
        }
        return normalizedUser.contains("请以妹妹")
                || normalizedUser.contains("对话氛围")
                || normalizedUser.contains("延续")
                || normalizedUser.contains("自然地")
                || normalizedUser.contains("身份");
    }

    private boolean looksLikeMainPersonaPrompt(String normalizedSystem) {
        if (TextUtils.isEmpty(normalizedSystem)) {
            return false;
        }
        return normalizedSystem.contains("你是yuki")
                && normalizedSystem.contains("性格特征")
                && normalizedSystem.contains("场景设定")
                && normalizedSystem.contains("重要提示");
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

    private boolean looksLikeInteractiveStoryRequest(String normalizedUser, String normalizedSystem) {
        if (TextUtils.isEmpty(normalizedSystem)) {
            return false;
        }
        return normalizedSystem.contains("互动式短剧生成器")
                || normalizedSystem.contains("小剧场")
                || normalizedSystem.contains("story_end")
                || normalizedSystem.contains("剧情生成规则")
                || normalizedSystem.contains("角色人设")
                || (normalizedSystem.contains("旁白") && normalizedSystem.contains("yuki") && normalizedSystem.contains("哥哥"))
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
        return "";
    }

    private String appendReason(String base, String extra) {
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
                    if ("text".equalsIgnoreCase(part.optString("type", "text"))) {
                        builder.append(part.optString("text", ""));
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
            if (TextUtils.isEmpty(text)) {
                return body;
            }

            String cleaned = stripThinkBlocks(text);
            if (TextUtils.isEmpty(cleaned)) {
                cleaned = "（模型返回空内容，请重试）";
            }
            ProxyConfig runtimeCfg = config == null ? new ProxyConfig().ensureDefaults() : config.copy();
            if (decision != null && decision.diaryMatched && runtimeCfg.truncateEnabled) {
                cleaned = truncateText(cleaned, runtimeCfg.maxChars);
            }

            setResponseText(root, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH, cleaned);
            return root.toString();
        } catch (Exception ignored) {
            return body;
        }
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
            return forwardRawOnce(request, cfg, trace, true);
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
        conn.disconnect();
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
            appendTextMessage(messages, "assistant".equals(role) ? "assistant" : "user", getMessageText(message));
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
            String role = normalizeLocalRole(message.optString("role", ""));
            if ("system".equals(role)) {
                continue;
            }
            String text = getMessageText(message);
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            input.put(new JSONObject()
                    .put("role", "assistant".equals(role) ? "assistant" : "user")
                    .put("content", text));
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
            appendGeminiMessage(contents, "assistant".equals(role) ? "model" : "user", getMessageText(message));
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

    private static final class ChatDecision {
        String requestKind = "pass-through";
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
