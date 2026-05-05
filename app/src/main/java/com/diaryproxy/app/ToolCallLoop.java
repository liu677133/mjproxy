package com.diaryproxy.app;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.5.0：tool_call 回流支持（不依赖任何 adapter 内部，纯 JSON 操作）。
 *
 * <p>主流程（DiaryProxyServer.executeChatRequest）：
 * <pre>
 *  for (int depth = 1; depth <= MAX_DEPTH; depth++) {
 *      List<ToolCall> calls = extractToolCalls(responseBody, adapterPreset);
 *      List<ToolCall> proxyCalls = filterProxyHandled(calls);
 *      if (proxyCalls.isEmpty()) break;
 *      List<String> results = executeToolBatch(proxyCalls, cfg, context);
 *      payload.messages = appendToolCallTurn(payload.messages, calls, results, ...);
 *      responseBody = forwardRaw(buildChatUpstreamRequest(payload, cfg), cfg);
 *  }
 * </pre>
 *
 * <p>白名单：仅 {@link WebSearchSupport#TOOL_NAME web_search} 和 describe_image。
 * 其他 tool_call 直接跳过（让游戏自己处理 / 直接 return 给客户端）。
 */
final class ToolCallLoop {

    private ToolCallLoop() {
    }

    static final String TOOL_DESCRIBE_IMAGE = "describe_image";
    static final int MAX_DEPTH = 3;

    private static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
            WebSearchSupport.TOOL_NAME,
            TOOL_DESCRIBE_IMAGE
    ));

    /** 从一次响应中提取 tool_calls。三家协议不同字段位置都覆盖。 */
    static List<ToolCall> extractToolCalls(String responseBody, String adapterPreset) {
        List<ToolCall> list = new ArrayList<>();
        if (TextUtils.isEmpty(responseBody)) return list;
        try {
            JSONObject root = new JSONObject(responseBody);
            if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)) {
                JSONArray content = root.optJSONArray("content");
                if (content != null) {
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject part = content.optJSONObject(i);
                        if (part == null || !"tool_use".equalsIgnoreCase(part.optString("type", ""))) continue;
                        ToolCall call = new ToolCall();
                        call.id = part.optString("id", "");
                        call.name = part.optString("name", "");
                        call.argumentsJson = part.opt("input") == null ? "" : part.opt("input").toString();
                        list.add(call);
                    }
                }
            } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
                JSONArray candidates = root.optJSONArray("candidates");
                JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
                JSONObject contentObj = first == null ? null : first.optJSONObject("content");
                JSONArray parts = contentObj == null ? null : contentObj.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        JSONObject fc = part.optJSONObject("functionCall");
                        if (fc == null) continue;
                        ToolCall call = new ToolCall();
                        // v1.5.6+：TCL-13 — 多轮 Gemini 工具调用同一 i 会产生重复 id "gemini-fc-0"，
                        // debug 日志难以区分。这里加上 nanoTime 高位后缀（同一次调用循环内 nanoTime
                        // 单调递增），让每条 call.id 都唯一。仅用于调试可读性，无功能性影响。
                        call.id = "gemini-fc-" + (System.nanoTime() & 0xFFFFFFL) + "-" + i;
                        call.name = fc.optString("name", "");
                        call.argumentsJson = fc.opt("args") == null ? "" : fc.opt("args").toString();
                        list.add(call);
                    }
                }
            } else if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)) {
                JSONArray output = root.optJSONArray("output");
                if (output != null) {
                    for (int i = 0; i < output.length(); i++) {
                        JSONObject item = output.optJSONObject(i);
                        if (item == null || !"function_call".equalsIgnoreCase(item.optString("type", ""))) continue;
                        ToolCall call = new ToolCall();
                        call.id = firstNonEmpty(item.optString("call_id", ""), item.optString("id", ""));
                        call.name = item.optString("name", "");
                        call.argumentsJson = item.optString("arguments", "");
                        list.add(call);
                    }
                }
            } else {
                // OpenAI Chat Completions 兼容格式。
                JSONArray choices = root.optJSONArray("choices");
                JSONObject firstChoice = choices == null ? null : choices.optJSONObject(0);
                JSONObject message = firstChoice == null ? null : firstChoice.optJSONObject("message");
                JSONArray toolCalls = message == null ? null : message.optJSONArray("tool_calls");
                if (toolCalls != null) {
                    for (int i = 0; i < toolCalls.length(); i++) {
                        JSONObject item = toolCalls.optJSONObject(i);
                        if (item == null) continue;
                        ToolCall call = new ToolCall();
                        call.id = item.optString("id", "");
                        JSONObject function = item.optJSONObject("function");
                        if (function == null) continue;
                        call.name = function.optString("name", "");
                        call.argumentsJson = function.optString("arguments", "");
                        list.add(call);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    static List<ToolCall> filterProxyHandled(List<ToolCall> all) {
        List<ToolCall> proxy = new ArrayList<>();
        if (all == null) return proxy;
        for (ToolCall call : all) {
            if (call != null && WHITELIST.contains(call.name)) {
                proxy.add(call);
            }
        }
        return proxy;
    }

    /** 同步执行所有 proxy-handled tool calls，返回与 calls 同序的结果文本。 */
    static List<String> executeToolBatch(List<ToolCall> calls, ProxyConfig cfg, Context context) {
        List<ToolExecResult> detailed = executeToolBatchDetailed(calls, cfg, context);
        List<String> results = new ArrayList<>();
        for (ToolExecResult r : detailed) {
            results.add(r == null ? "" : r.text);
        }
        return results;
    }

    /**
     * 同 {@link #executeToolBatch}，但额外回传每个 tool 的调试附加信息（例如 describe_image 的
     * 副模型原始 HTTP 响应）。供"调试提示词导出"使用，方便排查"返回为空"等问题。
     */
    static List<ToolExecResult> executeToolBatchDetailed(List<ToolCall> calls, ProxyConfig cfg, Context context) {
        List<ToolExecResult> results = new ArrayList<>();
        if (calls == null) return results;
        for (ToolCall call : calls) {
            ToolExecResult result = new ToolExecResult();
            if (call == null) {
                results.add(result);
                continue;
            }
            try {
                if (WebSearchSupport.TOOL_NAME.equals(call.name)) {
                    String query = parseQuery(call.argumentsJson);
                    if (TextUtils.isEmpty(query)) {
                        result.text = "(invalid query)";
                    } else {
                        List<WebSearchSupport.SearchResult> sr = WebSearchSupport.performSearch(query, cfg);
                        result.text = WebSearchSupport.formatResultsForTool(sr);
                    }
                } else if (TOOL_DESCRIBE_IMAGE.equals(call.name)) {
                    String imageId = parseImageId(call.argumentsJson);
                    String question = parseQuestion(call.argumentsJson);
                    if (TextUtils.isEmpty(imageId)) {
                        result.text = "(missing image_id)";
                    } else {
                        CaptionSupport.CaptionExecResult cr =
                                CaptionSupport.describeImageDetailed(imageId, question, cfg, context);
                        if (cr != null) {
                            result.text = cr.text == null ? "" : cr.text;
                            result.debugRaw = cr.debugRaw == null ? "" : cr.debugRaw;
                        }
                    }
                } else {
                    result.text = "(tool not implemented)";
                }
            } catch (Exception error) {
                result.text = "(tool error: " + clip(error.getMessage(), 80) + ")";
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 在原 messages 后追加：assistant tool_calls 占位 + 一组 tool results（OpenAI 兼容格式）。
     * 三家适配器都先用 OpenAI 兼容形式写入 payload.messages，build*RequestPayload 再各自转换。
     *
     * <p>v1.5.1+：从原响应里抠出 reasoning_content / content，回填到 assistant 占位。
     * <b>DeepSeek-V4 thinking 模式硬要求：原响应里有 reasoning_content 字段（即使值是空字符串），
     * 第二轮也必须原样回传，不能丢字段。</b>karminski-牙医 复现概率 59%，过滤掉就 400。
     */
    static JSONArray appendToolCallTurn(
            JSONArray messages,
            List<ToolCall> allCalls,
            List<ToolCall> proxyCalls,
            List<String> proxyResults,
            String upstreamResponseBody,
            String adapterPreset) throws JSONException {
        JSONArray updated = messages == null ? new JSONArray() : new JSONArray(messages.toString());
        // assistant message with tool_calls
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        // 抠原响应的 reasoning_content / content
        AssistantMeta meta = extractAssistantMeta(upstreamResponseBody, adapterPreset);
        assistant.put("content", TextUtils.isEmpty(meta.content) ? JSONObject.NULL : meta.content);
        // 关键：原响应只要 *存在* reasoning_content 字段就要回传，即使值是 ""
        if (meta.reasoningContentPresent) {
            assistant.put("reasoning_content", meta.reasoningContent == null ? "" : meta.reasoningContent);
        }
        // v1.5.4+：Claude 协议下保留原 content 数组（带 thinking{signature}），第二轮
        // convertContentToClaudeBlocks 在 assistant 路径优先用此字段重建（解决 TCL-4/6 + DPS-2）。
        // 下划线开头字段在 OpenAI / Gemini 翻译路径会被忽略，仅 Claude 路径感知。
        if (!TextUtils.isEmpty(meta.originalContentBlocks)) {
            assistant.put("_originalAssistantBlocks", meta.originalContentBlocks);
        }
        JSONArray toolCallsArr;
        // v1.5.6+：TCL-11 — OpenAI 兼容路径优先用原始 tool_calls JSON 保真重建，
        // 避免上游附带的自定义字段在第二轮丢失。Claude / Gemini 协议没有 OpenAI 形式的
        // tool_calls 数组，会保持 originalToolCallsJson="" 走回兜底重建分支。
        if (!TextUtils.isEmpty(meta.originalToolCallsJson)) {
            try {
                toolCallsArr = new JSONArray(meta.originalToolCallsJson);
            } catch (JSONException ex) {
                toolCallsArr = null; // 回落到兜底重建
            }
        } else {
            toolCallsArr = null;
        }
        if (toolCallsArr == null) {
            toolCallsArr = new JSONArray();
            for (ToolCall call : (allCalls == null ? new ArrayList<ToolCall>() : allCalls)) {
                if (call == null) continue;
                JSONObject tc = new JSONObject();
                tc.put("id", call.id);
                tc.put("type", "function");
                JSONObject function = new JSONObject();
                function.put("name", call.name);
                function.put("arguments", call.argumentsJson == null ? "" : call.argumentsJson);
                tc.put("function", function);
                toolCallsArr.put(tc);
            }
        }
        assistant.put("tool_calls", toolCallsArr);
        updated.put(assistant);
        // tool result messages（仅 proxy-handled 的有结果；未处理的 tool_call 没有结果，会让上游下一轮放弃或填空）
        for (int i = 0; i < (proxyCalls == null ? 0 : proxyCalls.size()); i++) {
            ToolCall call = proxyCalls.get(i);
            String text = i < (proxyResults == null ? 0 : proxyResults.size())
                    ? proxyResults.get(i)
                    : "";
            JSONObject toolMsg = new JSONObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", call == null ? "" : call.id);
            toolMsg.put("name", call == null ? "" : call.name);
            toolMsg.put("content", text == null ? "" : text);
            updated.put(toolMsg);
        }
        return updated;
    }

    /** 从原响应抽 assistant 的 reasoning_content + content，三家协议都覆盖。 */
    static AssistantMeta extractAssistantMeta(String responseBody, String adapterPreset) {
        AssistantMeta meta = new AssistantMeta();
        if (TextUtils.isEmpty(responseBody)) return meta;
        try {
            JSONObject root = new JSONObject(responseBody);
            if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)) {
                JSONArray content = root.optJSONArray("content");
                if (content != null) {
                    // v1.5.4+：保留 Claude content 数组原文（带 thinking{signature} + tool_use 等完整 block）。
                    // 第二轮 convertContentToClaudeBlocks 优先用此字段重建，避免丢 signature（TCL-4/6 + DPS-2）。
                    meta.originalContentBlocks = content.toString();
                    StringBuilder text = new StringBuilder();
                    StringBuilder thinking = new StringBuilder();
                    boolean hasThinking = false;
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject part = content.optJSONObject(i);
                        if (part == null) continue;
                        String type = part.optString("type", "");
                        if ("text".equalsIgnoreCase(type)) {
                            if (text.length() > 0) text.append("\n");
                            text.append(part.optString("text", ""));
                        } else if ("thinking".equalsIgnoreCase(type)) {
                            hasThinking = true;
                            if (thinking.length() > 0) thinking.append("\n");
                            thinking.append(part.optString("thinking", ""));
                        }
                    }
                    meta.content = text.toString();
                    meta.reasoningContent = thinking.toString();
                    meta.reasoningContentPresent = hasThinking;
                }
            } else if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
                // Gemini 没有 reasoning_content 概念，content 走 parts[].text 拼接
                JSONArray candidates = root.optJSONArray("candidates");
                JSONObject firstCand = candidates == null ? null : candidates.optJSONObject(0);
                JSONObject contentObj = firstCand == null ? null : firstCand.optJSONObject("content");
                JSONArray parts = contentObj == null ? null : contentObj.optJSONArray("parts");
                if (parts != null) {
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        String t = part.optString("text", "");
                        if (!TextUtils.isEmpty(t)) {
                            if (text.length() > 0) text.append("\n");
                            text.append(t);
                        }
                    }
                    meta.content = text.toString();
                }
            } else if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)) {
                meta.content = extractResponsesText(root);
            } else {
                // OpenAI 兼容（含 DeepSeek thinking 模式）
                JSONArray choices = root.optJSONArray("choices");
                JSONObject firstChoice = choices == null ? null : choices.optJSONObject(0);
                JSONObject message = firstChoice == null ? null : firstChoice.optJSONObject("message");
                if (message != null) {
                    Object content = message.opt("content");
                    if (content instanceof String) {
                        meta.content = (String) content;
                    } else if (content instanceof JSONArray) {
                        JSONArray arr = (JSONArray) content;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject part = arr.optJSONObject(i);
                            if (part != null && "text".equalsIgnoreCase(part.optString("type", ""))) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(part.optString("text", ""));
                            }
                        }
                        meta.content = sb.toString();
                    }
                    // 关键：用 has() 判断字段是否 *存在*，区分"字段缺失"与"字段为空字符串"
                    if (message.has("reasoning_content")) {
                        meta.reasoningContentPresent = true;
                        meta.reasoningContent = message.optString("reasoning_content", "");
                    }
                    // v1.5.6+：TCL-11 — 保留原始 tool_calls JSON 用于第二轮保真重建
                    JSONArray origToolCalls = message.optJSONArray("tool_calls");
                    if (origToolCalls != null) {
                        meta.originalToolCallsJson = origToolCalls.toString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return meta;
    }

    private static String extractResponsesText(JSONObject root) {
        if (root == null) return "";
        String direct = root.optString("output_text", "");
        if (!TextUtils.isEmpty(direct)) return direct;
        StringBuilder builder = new StringBuilder();
        appendResponsesText(root.opt("output"), builder, 0);
        return builder.toString().trim();
    }

    private static void appendResponsesText(Object node, StringBuilder builder, int depth) {
        if (node == null || node == JSONObject.NULL || builder == null || depth > 8) return;
        if (node instanceof String) {
            builder.append((String) node);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                appendResponsesText(array.opt(i), builder, depth + 1);
            }
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String type = object.optString("type", "");
            // v1.5.4+：跳过 OpenAI Responses 协议的 reasoning items（解决 TCL-10）。
            // 这些是模型内部 reasoning summary，不应回传给第二轮 assistant.content。
            if ("reasoning".equalsIgnoreCase(type)) return;
            if ("output_text".equalsIgnoreCase(type) || "text".equalsIgnoreCase(type) || TextUtils.isEmpty(type)) {
                String text = firstNonEmpty(object.optString("text", ""), object.optString("content", ""));
                if (!TextUtils.isEmpty(text)) {
                    builder.append(text);
                }
            }
            appendResponsesText(object.opt("content"), builder, depth + 1);
            appendResponsesText(object.opt("output"), builder, depth + 1);
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return !TextUtils.isEmpty(first) ? first : (second == null ? "" : second);
    }

    static final class AssistantMeta {
        String content = "";
        String reasoningContent = "";
        /** 原响应里是否存在 reasoning_content 字段。空字符串也算存在。 */
        boolean reasoningContentPresent = false;
        /**
         * v1.5.4+：Claude 协议下保留原响应 content 数组的 JSON 字符串。
         * 含完整的 thinking block（带 signature） + tool_use block + text block。
         * 第二轮 buildClaudeRequestPayload → convertContentToClaudeBlocks 在 assistant 路径
         * 优先用此字段重建 Claude content（不丢 signature）。解决 TCL-4/6 + DPS-2。
         * 仅 Claude 分支会写入；其他协议保持空字符串。
         */
        String originalContentBlocks = "";
        /**
         * v1.5.6+：TCL-11 — OpenAI 兼容协议下保留原响应 message.tool_calls JSON 字符串。
         * appendToolCallTurn 重建 tool_calls 时只复制 id/type/function.name/function.arguments，
         * 会丢失某些上游附带的字段（如部分供应商在 function 上的自定义元数据）。第二轮重建时优先
         * 用此字段保真重放，避免上游下一轮拒绝。仅 OpenAI 兼容分支会写入。
         */
        String originalToolCallsJson = "";
    }

    private static String parseQuery(String args) {
        try {
            JSONObject obj = new JSONObject(TextUtils.isEmpty(args) ? "{}" : args);
            return obj.optString("query", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parseImageId(String args) {
        try {
            JSONObject obj = new JSONObject(TextUtils.isEmpty(args) ? "{}" : args);
            return obj.optString("image_id", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parseQuestion(String args) {
        try {
            JSONObject obj = new JSONObject(TextUtils.isEmpty(args) ? "{}" : args);
            return obj.optString("question", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    static final class ToolCall {
        String id = "";
        String name = "";
        String argumentsJson = "";
    }

    /**
     * tool 执行结果。{@code text} 用于回传上游模型 / 写聊天记录；{@code debugRaw} 用于"调试
     * 提示词导出"展示工具内部细节（例如 describe_image 的副模型原始 HTTP 响应）。
     */
    static final class ToolExecResult {
        String text = "";
        String debugRaw = "";
    }
}
