package com.diaryproxy.app;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * v1.5.0：副模型 caption 管线（A 注入 / B tool 模式共用）。
 * 输入图片 byte[] → 调用副模型 provider → 返回描述文本。
 *
 * <p>当前仅支持副模型走 OpenAI 兼容格式（image_url base64 dataUri）。Claude/Gemini 副模型
 * 留作 v1.5.1，因为大多数玩家用文本主模型 + OpenAI-compatible 副模型（gpt-4o-mini 之类）
 * 已能覆盖。
 */
final class CaptionSupport {

    private CaptionSupport() {
    }

    private static final String CAPTION_MODEL_SEPARATOR = "\u001F";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * v1.5.4+：CS-1 — 按 provider 的 upstreamProxy 配置缓存 OkHttpClient。
     * key = "direct" / "http|host|port" / "socks5|host|port"。
     * 之前单例 sharedClient 不含 proxy，墙内用户配了墙外副模型时副模型直连失败。
     * 现在副模型复用其所属 ProviderProfile 的 upstreamProxy 配置。
     */
    private static final int MAX_CLIENT_CACHE = 8;

    /**
     * v1.5.6+：CS-8 — 并行 caption 共享线程池。原本 performCaptionRequestsParallel 每次新建
     * newFixedThreadPool 用完即 shutdown，每张图都付出建线程开销。改为 cached pool 复用：
     * <ul>
     *   <li>核心 0 / 最大 64（足以应付任何 concurrency 配置）；</li>
     *   <li>空闲 60s 自动回收，闲置时无线程占用；</li>
     *   <li>单次调用的并发上限由 caller 持有的 Semaphore 控制，不依赖池本身的 max。</li>
     * </ul>
     */
    private static final java.util.concurrent.ExecutorService CAPTION_PARALLEL_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    0, 64, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    runnable -> {
                        Thread t = new Thread(runnable, "CaptionParallel");
                        t.setDaemon(true);
                        return t;
                    });

    private static final Map<String, OkHttpClient> CLIENT_CACHE =
            new LinkedHashMap<String, OkHttpClient>(MAX_CLIENT_CACHE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, OkHttpClient> eldest) {
                    if (size() > MAX_CLIENT_CACHE && eldest != null && eldest.getValue() != null) {
                        try {
                            eldest.getValue().dispatcher().executorService().shutdown();
                        } catch (Throwable ignored) {
                        }
                        try {
                            eldest.getValue().connectionPool().evictAll();
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    return false;
                }
            };

    private static OkHttpClient client(ProxyConfig.ProviderProfile provider) {
        String key = proxyCacheKey(provider);
        synchronized (CLIENT_CACHE) {
            OkHttpClient cached = CLIENT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            Proxy proxy = buildProxyIfEnabled(provider);
            if (proxy != null) {
                builder.proxy(proxy);
            }
            OkHttpClient fresh = builder.build();
            CLIENT_CACHE.put(key, fresh);
            return fresh;
        }
    }

    /**
     * v1.5.4+：CS-1 — 为 provider 构造 java.net.Proxy。direct 或配置不全时返回 null（走直连）。
     */
    private static Proxy buildProxyIfEnabled(ProxyConfig.ProviderProfile provider) {
        if (provider == null) return null;
        String type = provider.upstreamProxyType == null
                ? ProxyConfig.UPSTREAM_PROXY_DIRECT
                : provider.upstreamProxyType.trim().toLowerCase(Locale.ROOT);
        if (ProxyConfig.UPSTREAM_PROXY_DIRECT.equals(type)) return null;
        if (TextUtils.isEmpty(provider.upstreamProxyHost) || provider.upstreamProxyPort <= 0) {
            return null;
        }
        Proxy.Type proxyType = ProxyConfig.UPSTREAM_PROXY_SOCKS5.equals(type)
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        return new Proxy(proxyType, new InetSocketAddress(
                provider.upstreamProxyHost.trim(),
                provider.upstreamProxyPort));
    }

    private static String proxyCacheKey(ProxyConfig.ProviderProfile provider) {
        if (provider == null) return "direct";
        String type = provider.upstreamProxyType == null
                ? ProxyConfig.UPSTREAM_PROXY_DIRECT
                : provider.upstreamProxyType.trim().toLowerCase(Locale.ROOT);
        if (ProxyConfig.UPSTREAM_PROXY_DIRECT.equals(type)
                || TextUtils.isEmpty(provider.upstreamProxyHost)
                || provider.upstreamProxyPort <= 0) {
            return "direct";
        }
        return type + "|" + provider.upstreamProxyHost.trim() + "|" + provider.upstreamProxyPort;
    }

    /**
     * 调副模型为单张图片生成描述文本。
     * @param imageId AttachmentRef.id（用于查 attachment）
     * @param question 可选追问，描述要求关注什么；空则用通用 prompt
     * @param cfg 主请求 cfg（其中 captionProviderId 指向副模型 provider）
     * @param context Android Context（用于 ProxyStorageHelper 读 blob）
     * @return 文本描述（成功）或 "(图像识别失败：...)"（失败）
     */
    static String describeImage(String imageId, String question, ProxyConfig cfg, Context context) {
        return describeImageDetailed(imageId, question, cfg, context).text;
    }

    /**
     * 详细版：在 {@link #describeImage} 基础上额外回传副模型的原始 HTTP 响应。
     *
     * <p>调试导出（开启"调试提示词导出"后）会把 {@link CaptionExecResult#debugRaw} 写到
     * 工具调用记录段，方便排查"返回为空"等问题（例如副模型走 thinking 模式把正文写到
     * reasoning_content、HTTP 非 2xx、字段格式不识别等）。
     */
    static CaptionExecResult describeImageDetailed(String imageId, String question, ProxyConfig cfg, Context context) {
        CaptionExecResult result = new CaptionExecResult();
        if (cfg == null) {
            result.text = "(no config)";
            return result;
        }
        if (TextUtils.isEmpty(cfg.captionProviderId)) {
            result.text = "(no caption provider configured)";
            return result;
        }
        ProxyConfig.ProviderProfile captionProvider = resolveCaptionProvider(cfg);
        if (captionProvider == null) {
            result.text = "(caption model not found: " + cfg.captionProviderId + ")";
            return result;
        }
        ProxyStorageHelper.AttachmentRef target = null;
        if (context != null) {
            for (ProxyStorageHelper.AttachmentRef ref : ProxyStorageHelper.listAttachmentDrafts(context)) {
                if (ref != null && TextUtils.equals(ref.id, imageId)) {
                    target = ref;
                    break;
                }
            }
        }
        if (target == null) {
            result.text = "(image not found: " + imageId + ")";
            return result;
        }
        try {
            byte[] bytes = ProxyStorageHelper.readAttachmentBytes(target);
            performCaptionRequestDetailed(captionProvider, bytes, target.mime, question, result);
        } catch (Exception error) {
            result.text = "(图像识别失败：" + clip(error.getMessage(), 100) + ")";
            if (TextUtils.isEmpty(result.debugRaw)) {
                String msg = error.getMessage();
                result.debugRaw = "exception (no HTTP response captured): "
                        + (TextUtils.isEmpty(msg) ? error.getClass().getSimpleName() : msg);
            }
        }
        return result;
    }

    /**
     * A 方案：在主请求前对所有 accepted image 跑副模型，把 caption 拼成纯文本注入到 last user msg。
     * 修改 payload in-place，返回 caption 文本（用于日志）。
     *
     * <p>v1.5.4+：DPS-6 — 当 cfg.captionConcurrency &gt; 1 时并行调副模型（最多 N 张图同时请求），
     * 上游限速时用户应保持默认值 1。请求顺序保持原 imageRefs 顺序（idx 通过 future 携带回拼接）。
     */
    static String preInjectCaptions(
            JSONObject payload,
            List<ProxyStorageHelper.AttachmentRef> imageRefs,
            ProxyConfig cfg,
            Context context) {
        if (payload == null || imageRefs == null || imageRefs.isEmpty()) return "";
        if (cfg == null || TextUtils.isEmpty(cfg.captionProviderId)) return "";
        ProxyConfig.ProviderProfile captionProvider = resolveCaptionProvider(cfg);
        if (captionProvider == null) return "";

        // 先过滤出真正要 caption 的 ref，并记录其在 imageRefs 中的展示索引（idx 从 1 开始，用于 "[图N 描述：…]"）。
        List<ProxyStorageHelper.AttachmentRef> targets = new ArrayList<>();
        List<Integer> displayIdx = new ArrayList<>();
        int counter = 0;
        for (ProxyStorageHelper.AttachmentRef ref : imageRefs) {
            if (ref == null || !AttachmentSupport.isImageMime(ref.mime)) continue;
            counter++;
            targets.add(ref);
            displayIdx.add(counter);
        }
        if (targets.isEmpty()) return "";

        int concurrency = cfg.captionConcurrency > 0 ? cfg.captionConcurrency : 1;
        // captionMaxImagesPerRequest 已在上游裁剪 imageRefs；这里 concurrency 上限再受 targets 数量限制。
        if (concurrency > targets.size()) concurrency = targets.size();

        String[] captions = new String[targets.size()];
        if (concurrency <= 1) {
            // 串行路径（旧行为）
            for (int i = 0; i < targets.size(); i++) {
                captions[i] = captionOneImage(captionProvider, targets.get(i));
            }
        } else {
            // v1.5.4+：DPS-6 — 并行路径。固定大小线程池 + Future，保证按 i 还原顺序。
            // v1.5.6+：CS-8 — 改为复用共享 cached pool（CAPTION_PARALLEL_POOL）。原本每次新建
            // newFixedThreadPool + 用完 shutdown，每次切图任务都开 N 条线程再回收，开销不必要。
            // 共享池依靠 Semaphore 控制并发上限 = concurrency；空闲 60s 自动回收。
            final ProxyConfig.ProviderProfile providerForExec = captionProvider;
            final java.util.concurrent.Semaphore limiter = new java.util.concurrent.Semaphore(concurrency);
            java.util.List<java.util.concurrent.Future<String>> futures = new ArrayList<>(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                final ProxyStorageHelper.AttachmentRef ref = targets.get(i);
                futures.add(CAPTION_PARALLEL_POOL.submit(() -> {
                    limiter.acquire();
                    try {
                        return captionOneImage(providerForExec, ref);
                    } finally {
                        limiter.release();
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    captions[i] = futures.get(i).get();
                } catch (Exception future) {
                    Throwable cause = future.getCause() != null ? future.getCause() : future;
                    // v1.5.5+：CS-5 — 失败置空（caller 跳过此图），不再注入"识别失败 …"到 prompt。
                    android.util.Log.w("CaptionSupport",
                            "parallel caption failed: " + (cause == null ? "?" : cause.getMessage()));
                    captions[i] = "";
                }
            }
        }

        StringBuilder block = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            String caption = captions[i];
            if (TextUtils.isEmpty(caption)) continue;
            if (block.length() > 0) block.append("\n");
            block.append("[图").append(displayIdx.get(i)).append(" 描述：").append(caption).append("]");
        }
        if (block.length() == 0) return "";

        // 注入到 last user message
        try {
            JSONArray messages = payload.optJSONArray("messages");
            if (messages == null) return block.toString();
            int lastUserIdx = -1;
            for (int i = messages.length() - 1; i >= 0; i--) {
                JSONObject msg = messages.optJSONObject(i);
                if (msg == null) continue;
                if ("user".equalsIgnoreCase(msg.optString("role", ""))) {
                    lastUserIdx = i;
                    break;
                }
            }
            if (lastUserIdx < 0) return block.toString();
            JSONObject lastUser = messages.getJSONObject(lastUserIdx);
            Object content = lastUser.opt("content");
            String existingText;
            if (content instanceof String) {
                existingText = (String) content;
            } else if (content instanceof JSONArray) {
                StringBuilder builder = new StringBuilder();
                JSONArray array = (JSONArray) content;
                for (int j = 0; j < array.length(); j++) {
                    JSONObject part = array.optJSONObject(j);
                    if (part != null && "text".equalsIgnoreCase(part.optString("type", ""))) {
                        if (builder.length() > 0) builder.append("\n");
                        builder.append(part.optString("text", ""));
                    }
                }
                existingText = builder.toString();
            } else {
                existingText = "";
            }
            String merged = TextUtils.isEmpty(existingText)
                    ? block.toString()
                    : existingText + "\n\n" + block.toString();
            // 把 content 还原为纯文本（剥离图片 blocks，因为已 caption 化）
            lastUser.put("content", merged);
        } catch (Exception ignored) {
        }
        return block.toString();
    }

    /**
     * v1.5.5+：CS-5 — 失败统一返回 ""（caller 视为"忽略此图"），不再把"识别失败 …"
     * 字面注入到主 prompt（旧行为会让主模型把错误文本当成图像内容描述，污染推理）。
     * 失败信息仍会通过 performCaptionRequestDetailed 内部的 debugRaw 落到调试导出。
     */
    private static String captionOneImage(ProxyConfig.ProviderProfile provider,
                                          ProxyStorageHelper.AttachmentRef ref) {
        try {
            byte[] bytes = ProxyStorageHelper.readAttachmentBytes(ref);
            CaptionExecResult tmp = new CaptionExecResult();
            performCaptionRequestDetailed(provider, bytes, ref.mime, "", tmp);
            return TextUtils.isEmpty(tmp.text) ? "" : tmp.text;
        } catch (Exception error) {
            android.util.Log.w("CaptionSupport",
                    "captionOneImage failed for " + (ref == null ? "?" : ref.id) + ": " + error.getMessage());
            return "";
        }
    }

    /**
     * 给主请求 payload 注入 describe_image tool 描述符（B 方案）。
     */
    static JSONArray buildDescribeImageToolList() {
        JSONArray tools = new JSONArray();
        try {
            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONObject imageId = new JSONObject();
            imageId.put("type", "string");
            // v1.5.6+：TCL-12 — 举例与实际 image_id 格式对齐。实际由 ProxyStorageHelper.appendAttachmentDraft
            // 生成 att_<timestamp>_<hash> 格式。模型如果照 img1/img2 写 image_id，调用必然找不到附件。
            imageId.put("description",
                    "Image identifier from the user message attachment list "
                            + "(e.g., att_1730000000000_12345). 具体可用 image_id 见上文 "
                            + "[已附 N 张图...] 列表中的 image_id 字段。");
            properties.put("image_id", imageId);
            JSONObject question = new JSONObject();
            question.put("type", "string");
            question.put("description", "Optional follow-up question about the image. Empty for a generic description.");
            properties.put("question", question);
            parameters.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("image_id");
            parameters.put("required", required);
            JSONObject function = new JSONObject();
            function.put("name", ToolCallLoop.TOOL_DESCRIBE_IMAGE);
            function.put("description", "Describe the contents of an attached image. Use it when the user asks about something visual in the conversation.");
            function.put("parameters", parameters);
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", function);
            tools.put(tool);
        } catch (Exception ignored) {
        }
        return tools;
    }

    /**
     * 真正调副模型。当前只支持 OpenAI 兼容格式。
     *
     * <p>无论 HTTP 成功 / 失败，都会把 status + url + model + 原始 response body 写入
     * {@code out.debugRaw}；text 为副模型解析后的描述文本（或失败时的 "(...)" 提示）。
     */
    private static void performCaptionRequestDetailed(
            ProxyConfig.ProviderProfile provider,
            byte[] bytes,
            String mime,
            String question,
            CaptionExecResult out) throws IOException {
        if (out == null) {
            // 不应发生；防御一下避免 NPE
            out = new CaptionExecResult();
        }
        if (bytes == null || bytes.length == 0) {
            throw new IOException("空附件");
        }
        if (provider == null || TextUtils.isEmpty(provider.upstreamBaseUrl) || TextUtils.isEmpty(provider.apiKey)) {
            throw new IOException("副模型 provider 未配置 base url / api key");
        }
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        String imageUrl = buildImageUrlForProvider(provider, TextUtils.isEmpty(mime) ? "image/png" : mime, b64);
        try {
            JSONArray content = new JSONArray();
            String prompt = TextUtils.isEmpty(question)
                    ? "Describe the image briefly. Focus on the main subject, scene, and any visible text. Output 1-3 sentences in Chinese."
                    : question;
            JSONObject textPart = new JSONObject().put("type", "text").put("text", prompt);
            JSONObject imageObject = new JSONObject().put("url", imageUrl);
            if (shouldAddImageDetail(provider)) {
                imageObject.put("detail", "low");
            }
            JSONObject imagePart = new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", imageObject);
            if (shouldPlaceImagesBeforeText(provider)) {
                content.put(imagePart);
                content.put(textPart);
            } else {
                content.put(textPart);
                content.put(imagePart);
            }
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", content));
            JSONObject body = new JSONObject();
            body.put("model", provider.getActiveModelName());
            body.put("messages", messages);
            // 注意：副模型若是 thinking 模式（GLM-4.5V / DeepSeek-V4 / Qwen3-VL-Thinking 等），
            // reasoning 段本身会消耗 200~600 token；max_tokens 设小了会撞 finish_reason=length，
            // content 空白、reasoning_content 才是真正的输出。给正文留够余地，默认 1024。
            // v1.5.5+：CS-4 — 用户可在 ProviderProfile.captionMaxTokens 自行调整。
            int maxTokens = provider.captionMaxTokens > 0 ? provider.captionMaxTokens : 1024;
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.2);
            String url = provider.upstreamBaseUrl;
            if (!url.endsWith("/")) url += "/";
            String chatPath = TextUtils.isEmpty(provider.upstreamChatPath) ? "/chat/completions" : provider.upstreamChatPath;
            if (chatPath.startsWith("/")) chatPath = chatPath.substring(1);
            String fullUrl = url + chatPath;
            Request request = new Request.Builder()
                    .url(fullUrl)
                    .post(RequestBody.create(body.toString(), JSON))
                    .header("Authorization", "Bearer " + provider.apiKey)
                    .header("Content-Type", "application/json")
                    .build();
            try (Response response = client(provider).newCall(request).execute()) {
                int status = response.code();
                String text = response.body() == null ? "" : response.body().string();
                // 关键：无论解析能不能成功，先把 HTTP 现场快照写进 debugRaw，
                // 调试导出能看到"副模型实际返回了什么"
                out.debugRaw = buildCaptionDebugRaw(status, fullUrl, provider.getActiveModelName(), text);
                // v1.5.4+：CS-2 — 若 resolveCaptionProvider 检测到 captionProviderId 缺 modelName，
                // 这里把警告前置到 debugRaw，方便调试导出一眼看到 fallback。
                String missingModelNote = MISSING_MODEL_NOTES.remove(provider);
                if (!TextUtils.isEmpty(missingModelNote)) {
                    out.debugRaw = "[警告] " + missingModelNote + "\n" + out.debugRaw;
                }
                if (!response.isSuccessful()) {
                    throw new IOException("副模型 HTTP " + status + " " + clip(text, 120));
                }
                JSONObject root;
                try {
                    root = new JSONObject(TextUtils.isEmpty(text) ? "{}" : text);
                } catch (Exception parseError) {
                    out.text = "(non-json response)";
                    return;
                }
                JSONArray choices = root.optJSONArray("choices");
                JSONObject firstChoice = choices == null ? null : choices.optJSONObject(0);
                JSONObject message = firstChoice == null ? null : firstChoice.optJSONObject("message");
                String finishReason = firstChoice == null ? "" : firstChoice.optString("finish_reason", "");
                if (!TextUtils.isEmpty(finishReason)) {
                    out.debugRaw = appendFinishReasonToDebugRaw(out.debugRaw, finishReason);
                }
                if (message == null) {
                    out.text = "(empty response)";
                    return;
                }
                String parsed = "";
                Object contentObj = message.opt("content");
                if (contentObj instanceof String) {
                    parsed = ((String) contentObj).trim();
                } else if (contentObj instanceof JSONArray) {
                    JSONArray arr = (JSONArray) contentObj;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject part = arr.optJSONObject(i);
                        if (part != null && "text".equalsIgnoreCase(part.optString("type", ""))) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(part.optString("text", ""));
                        }
                    }
                    parsed = sb.toString().trim();
                }
                if (!TextUtils.isEmpty(parsed)) {
                    out.text = parsed;
                    return;
                }
                // content 为空：thinking 模型常见情况——max_tokens 在 reasoning 段就用尽，
                // finish_reason=length，正文未输出。回退取 reasoning_content 作为兜底描述。
                String reasoning = message.optString("reasoning_content", "").trim();
                if (!TextUtils.isEmpty(reasoning)) {
                    if ("length".equalsIgnoreCase(finishReason)) {
                        out.text = reasoning
                                + "\n（注：副模型 finish_reason=length，max_tokens 已用尽，"
                                + "正文未输出；以上为推理段。建议增大副模型 max_tokens 或换非 thinking 模型。）";
                    } else {
                        out.text = reasoning;
                    }
                    return;
                }
                if ("length".equalsIgnoreCase(finishReason)) {
                    out.text = "(图像识别失败：finish_reason=length，max_tokens 已用尽且无 reasoning_content 可回退；"
                            + "建议增大副模型 max_tokens 或换非 thinking 模型)";
                } else {
                    out.text = "(unrecognized response)";
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("副模型调用异常：" + error.getMessage(), error);
        }
    }

    private static String buildCaptionDebugRaw(int status, String url, String model, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(status).append('\n');
        sb.append("URL: ").append(url == null ? "" : url).append('\n');
        sb.append("model: ").append(model == null ? "" : model).append('\n');
        sb.append("response body:\n");
        if (TextUtils.isEmpty(body)) {
            sb.append("(empty)");
        } else {
            sb.append(body);
        }
        return sb.toString();
    }

    /**
     * 把 finish_reason 插到 debugRaw 的 header 段（"response body:" 之前），方便排查。
     * length 表示副模型 max_tokens 撞顶；stop 是正常结束。
     */
    private static String appendFinishReasonToDebugRaw(String raw, String finishReason) {
        if (TextUtils.isEmpty(raw)) {
            return "finish_reason: " + finishReason;
        }
        int marker = raw.indexOf("response body:");
        if (marker < 0) {
            return raw + "\nfinish_reason: " + finishReason;
        }
        return raw.substring(0, marker) + "finish_reason: " + finishReason + "\n" + raw.substring(marker);
    }

    /**
     * v1.5.4+：CS-2 — resolveCaptionProvider 在 captionProviderId 缺 modelName 时，
     * 把"警告 note"挂到返回的 ProviderProfile copy 上，由 performCaptionRequestDetailed
     * 在 debugRaw 头部追加一条提醒。用 WeakHashMap 避免对 copy 的强引用泄露。
     * UI 正常路径会写 `providerId\u001F modelName`，这里只在旧配置升级 / 第三方写入时触发。
     * 用 IdentityHashMap 语义：key 是引用相等（同一个 copy 对象）。
     *
     * <p>v1.5.6+：CS-7 — 这里 WeakHashMap 的语义依赖："{@link ProxyConfig.ProviderProfile}
     * 不重写 equals/hashCode"，即默认走 Object 的引用相等。如果未来 ProviderProfile 改为
     * 重写 equals（按字段值相等），WeakHashMap 仍是 key 引用相等（WeakReference 的语义），
     * 但行为会因弱引用回收时机不一致而变得难以预测。所以两种安全做法二选一：
     * <ul>
     *   <li>保持现状 + 在 ProviderProfile 上加注释「禁止重写 equals」；</li>
     *   <li>或改成 {@code Collections.synchronizedMap(new IdentityHashMap<>())} +
     *       手动清理（找到 copy 不再被持有的入口主动 remove）。</li>
     * </ul>
     * 当前选保持现状（极小内存占用，最多 N 条 caption 失败 note 在 GC 前残留）。</p>
     */
    private static final java.util.Map<ProxyConfig.ProviderProfile, String> MISSING_MODEL_NOTES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static ProxyConfig.ProviderProfile resolveCaptionProvider(ProxyConfig cfg) {
        if (cfg == null || TextUtils.isEmpty(cfg.captionProviderId)) {
            return null;
        }
        String raw = cfg.captionProviderId;
        String providerId = raw;
        String modelName = "";
        int split = raw.indexOf(CAPTION_MODEL_SEPARATOR);
        if (split >= 0) {
            providerId = raw.substring(0, split);
            modelName = raw.substring(split + CAPTION_MODEL_SEPARATOR.length());
        }
        ProxyConfig.ProviderProfile provider = cfg.findProviderProfileById(providerId);
        if (provider == null) {
            return null;
        }
        ProxyConfig.ProviderProfile copy = provider.copy();
        if (!TextUtils.isEmpty(modelName)) {
            copy.setActiveModelName(modelName);
        } else {
            // v1.5.4+：CS-2 — captionProviderId 缺 modelName，副模型将回落到 provider.activeModelId，
            // 可能不是 vision 模型。记警告给 debugRaw，方便排查"副模型突然失败"。
            String fallback = copy.getActiveModelName();
            MISSING_MODEL_NOTES.put(copy,
                    "captionProviderId 缺失 modelName，副模型回落到 provider activeModel="
                            + (TextUtils.isEmpty(fallback) ? "(空)" : fallback)
                            + "。请在主页\"视觉模型\"下拉重新选择精确的 provider/model。");
        }
        return copy;
    }

    private static String buildImageUrlForProvider(ProxyConfig.ProviderProfile provider, String mime, String base64) {
        if (shouldUseRawBase64ImageUrl(provider)) {
            return base64 == null ? "" : base64;
        }
        return "data:" + (TextUtils.isEmpty(mime) ? "image/png" : mime) + ";base64," + (base64 == null ? "" : base64);
    }

    private static boolean shouldAddImageDetail(ProxyConfig.ProviderProfile provider) {
        if (provider == null) return false;
        // v1.5.5+：CS-3 — 三态优先；auto 走启发式。
        String mode = ProxyConfig.normalizeCaptionImageDetailMode(provider.captionImageDetailMode);
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        String model = provider.getActiveModelName();
        model = model == null ? "" : model.toLowerCase(Locale.ROOT);
        String baseUrl = provider.upstreamBaseUrl == null ? "" : provider.upstreamBaseUrl.toLowerCase(Locale.ROOT);
        return baseUrl.contains("siliconflow")
                || model.contains("qwen/")
                || model.contains("qwen3.6")
                || model.contains("qwen3-")
                || model.contains("qwen3_")
                || model.contains("qwen-vl")
                || model.contains("qwen3-vl");
    }

    private static boolean shouldPlaceImagesBeforeText(ProxyConfig.ProviderProfile provider) {
        if (provider == null) return false;
        // v1.5.5+：CS-3 — 三态优先；auto 走启发式。
        String mode = ProxyConfig.normalizeCaptionImagePlacementMode(provider.captionImagePlacementMode);
        if ("image_first".equals(mode)) return true;
        if ("text_first".equals(mode)) return false;
        String model = provider.getActiveModelName();
        model = model == null ? "" : model.toLowerCase(Locale.ROOT);
        String baseUrl = provider.upstreamBaseUrl == null ? "" : provider.upstreamBaseUrl.toLowerCase(Locale.ROOT);
        return baseUrl.contains("siliconflow")
                || model.contains("qwen/")
                || model.contains("qwen3.6")
                || model.contains("qwen-vl")
                || model.contains("qwen3-vl");
    }

    private static boolean shouldUseRawBase64ImageUrl(ProxyConfig.ProviderProfile provider) {
        if (provider == null) return false;
        // v1.5.5+：CS-3 — 三态优先；auto 走启发式。
        String mode = ProxyConfig.normalizeCaptionImageUrlFormat(provider.captionImageUrlFormat);
        if ("raw_base64".equals(mode)) return true;
        if ("data_url".equals(mode)) return false;
        String model = provider.getActiveModelName();
        model = model == null ? "" : model.toLowerCase(Locale.ROOT).trim();
        String baseUrl = provider.upstreamBaseUrl == null ? "" : provider.upstreamBaseUrl.toLowerCase(Locale.ROOT);
        if (isGlmVisionModel(model)) {
            return true;
        }
        return baseUrl.contains("bigmodel.cn") && (model.contains("glm-") || model.contains("glm_"));
    }

    private static boolean isGlmVisionModel(String model) {
        if (TextUtils.isEmpty(model)) return false;
        // v1.5.5+：CS-6 — 旧实现 `startsWith("glm-") && contains("-v")` 会把 glm-4-voice / glm-4-vision-disabled
        // 等带 -v 的非视觉模型误判。改成显式版本号匹配 glm-{N}v / glm-{N}.{M}v（如 glm-4v / glm-4.5v / glm-5v）。
        String lower = model.toLowerCase(Locale.ROOT).trim();
        // 用正则匹配 glm- 后跟 数字（可带小数点）+ "v" + 词边界（v 后面是非字母数字或字符串结尾）。
        return lower.matches("^glm-\\d+(\\.\\d+)?v(?![a-z0-9].*).*");
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /** 副模型一次调用的完整结果：text 给主模型 / 日志看，debugRaw 给"调试提示词导出"看。 */
    static final class CaptionExecResult {
        /** 解析后的文本描述。失败时是 "(...)" 形式的提示。 */
        String text = "";
        /** 副模型原始 HTTP 响应（含状态码、URL、model 摘要）。无论成败都尽量填上。 */
        String debugRaw = "";
    }
}
