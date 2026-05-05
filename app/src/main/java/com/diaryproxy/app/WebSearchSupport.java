package com.diaryproxy.app;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * v1.5.0：联网搜索工具（参考 LangChain4j 的 WebSearchEngine 接口设计，但 Android 上自己写实现）。
 * 国内 / 国外 双轨：
 *   - 国内可访问：博查 AI（bochaai）、百度千帆（qianfan_ai_search）、火山联网搜索（volcengine_web_search）、必应中国（bing_cn）
 *   - 国外可访问：Tavily、Serper、DuckDuckGo HTML
 *
 * <p>v1.5.4+：原 "auto" 模式已弃用（语义混乱、key 兜底链路不可靠，详见 BUG_TRACKING_1.5.4.md
 * WSS-2/3/14 + PC-1）。改为按用户在 UI 选定的具体 engine 执行。新的默认 engine 是 bochaai。
 */
final class WebSearchSupport {

    private WebSearchSupport() {
    }

    static final String TOOL_NAME = "web_search";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /**
     * v1.5.1+：按 (proxyType, host, port) 缓存 OkHttpClient。
     * 不同 engine 可能配不同 per-engine proxy → 共享 sharedClient 不够，但每次新建又浪费连接池 / 线程池。
     * key 格式：proxy==null → "_direct"；否则 "type:host:port"
     *
     * <p>v1.5.4+：WSS-12 — 改为 LRU LinkedHashMap，上限 8 条。淘汰时 shutdown dispatcher
     * executor + evict 连接池，防止用户频繁切 proxy 导致线程池泄漏。读写都加锁
     * （LinkedHashMap accessOrder 在 get 时也修改链表）。
     */
    private static final int MAX_CLIENT_CACHE = 8;
    private static final Map<String, OkHttpClient> clientCache =
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

    /**
     * v1.5.1+：按 engineName 拿对应的 OkHttpClient。proxy 来自 cfg.webSearchProxiesJson 的该 engine 槽。
     */
    private static OkHttpClient client(ProxyConfig cfg, String engineName) {
        Proxy proxy = buildProxy(cfg, engineName);
        String key = proxy == null
                ? "_direct"
                : proxy.type().name() + ":" + ((InetSocketAddress) proxy.address()).getHostString()
                        + ":" + ((InetSocketAddress) proxy.address()).getPort();
        synchronized (clientCache) {
            OkHttpClient cached = clientCache.get(key);
            if (cached != null) return cached;
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(12, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            if (proxy != null) builder.proxy(proxy);
            OkHttpClient created = builder.build();
            clientCache.put(key, created);
            return created;
        }
    }

    private static Proxy buildProxy(ProxyConfig cfg, String engineName) {
        if (cfg == null) {
            return null;
        }
        ProxyConfig.WebSearchProxyEntry entry = ProxyConfig.getWebSearchProxy(cfg.webSearchProxiesJson, engineName);
        if (entry == null) return null;
        String type = ProxyConfig.normalizeUpstreamProxyType(entry.type, entry.host, entry.port);
        if ("direct".equals(type) || TextUtils.isEmpty(entry.host) || entry.port <= 0) {
            return null;
        }
        Proxy.Type proxyType = "socks5".equals(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(proxyType, new InetSocketAddress(entry.host, entry.port));
    }

    /** v1.5.1+：取 per-engine 端点；空则用各 engine 默认端点。 */
    private static String endpointForEngine(ProxyConfig cfg, String engineName, String defaultEndpoint) {
        if (cfg == null) return defaultEndpoint;
        String endpoint = ProxyConfig.getWebSearchEndpoint(cfg.webSearchEndpointsJson, engineName);
        return TextUtils.isEmpty(endpoint) ? defaultEndpoint : endpoint;
    }

    static final class SearchResult {
        String title = "";
        String url = "";
        String snippet = "";
        String content = "";
    }

    interface WebSearchEngine {
        String name();
        boolean requiresApiKey();
        boolean availableInChina();
        List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException;
    }

    /**
     * v1.5.4+：按用户在 UI 选定的具体 engine 返回单元素列表。原 auto 模式已删除。
     * 未识别值（包括旧 SP 中残留的 "auto"）兜底返回 bochaai（国内默认）。
     */
    static List<WebSearchEngine> resolveEngines(ProxyConfig cfg) {
        List<WebSearchEngine> list = new ArrayList<>();
        String provider = ProxyConfig.normalizeWebSearchProvider(cfg == null ? "" : cfg.webSearchProvider);
        switch (provider) {
            case "tavily":
                list.add(new TavilyEngine());
                return list;
            case "serper":
                list.add(new SerperEngine());
                return list;
            case "qianfan_ai_search":
                list.add(new QianfanAiSearchEngine());
                return list;
            case "volcengine_web_search":
                list.add(new VolcengineWebSearchEngine());
                return list;
            case "bing_cn":
                list.add(new BingCnEngine());
                return list;
            case "duckduckgo_html":
                list.add(new DuckDuckGoHtmlEngine());
                return list;
            case "bochaai":
            default:
                list.add(new BochaaiEngine());
                return list;
        }
    }

    /** 构造 OpenAI 兼容 tools[] 列表。 */
    static JSONArray buildToolList(ProxyConfig cfg) {
        JSONArray tools = new JSONArray();
        try {
            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONObject queryProp = new JSONObject();
            queryProp.put("type", "string");
            queryProp.put("description", "The search query string. Should be specific and concise.");
            properties.put("query", queryProp);
            parameters.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("query");
            parameters.put("required", required);
            JSONObject function = new JSONObject();
            function.put("name", TOOL_NAME);
            function.put("description", "Search the web for up-to-date information. Use it when the user asks about news, current events, or facts you don't know.");
            function.put("parameters", parameters);
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", function);
            tools.put(tool);
        } catch (Exception ignored) {
        }
        return tools;
    }

    /** 执行搜索，返回首个成功的 engine 的结果。所有失败时返回 throw 包装的 IOException。 */
    static List<SearchResult> performSearch(String query, ProxyConfig cfg) throws IOException {
        if (TextUtils.isEmpty(query)) {
            return new ArrayList<>();
        }
        int maxResults = cfg == null || cfg.webSearchMaxResults <= 0 ? 5 : cfg.webSearchMaxResults;
        List<WebSearchEngine> engines = resolveEngines(cfg);
        IOException lastError = null;
        for (WebSearchEngine engine : engines) {
            if (engine.requiresApiKey() && TextUtils.isEmpty(apiKeyForEngine(cfg, engine.name()))) {
                // v1.5.4+：auto 模式已删除，resolveEngines 现在返回单元素列表，
                // 用户显式选定的 engine 无 key 时直接抛 IOException 而不是静默跳过（解决 WSS-1）。
                throw new IOException(engine.name() + " 引擎需要 API Key 但未配置");
            }
            try {
                List<SearchResult> results = engine.search(query, maxResults, cfg);
                if (results != null && !results.isEmpty()) return results;
            } catch (IOException error) {
                lastError = error;
            }
        }
        if (lastError != null) throw lastError;
        return new ArrayList<>();
    }

    /** 把 SearchResult 列表序列化为 LLM 友好文本（喂给 tool result）。 */
    static String formatResultsForTool(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "(no search results)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            if (r == null) continue;
            builder.append("[").append(i + 1).append("] ");
            if (!TextUtils.isEmpty(r.title)) builder.append(r.title);
            if (!TextUtils.isEmpty(r.url)) builder.append("\nURL: ").append(r.url);
            String body = !TextUtils.isEmpty(r.content) ? r.content : r.snippet;
            if (!TextUtils.isEmpty(body)) {
                builder.append("\n").append(body.length() > 800 ? body.substring(0, 800) + "…" : body);
            }
            builder.append("\n\n");
        }
        return builder.toString().trim();
    }

    // ===== Engine 实现 =====

    private static String apiKeyForEngine(ProxyConfig cfg, String engine) {
        if (cfg == null) {
            return "";
        }
        // v1.5.4+：auto 模式已删除，不再有顶层 cfg.webSearchApiKey 跨槽兜底（原本是为 v1.5.0 老用户写的 fallback）。
        // v1.5.0 → v1.5.4 老用户由 migrateWebSearchToGlobal 把旧 key 写入 bochaai 槽，新版只读 keysJson 即可。
        return ProxyConfig.getWebSearchApiKey(cfg.webSearchApiKeysJson, engine);
    }

    static final class TavilyEngine implements WebSearchEngine {
        @Override public String name() { return "tavily"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return false; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(), "https://api.tavily.com/search");
            try {
                JSONObject body = new JSONObject();
                body.put("api_key", apiKeyForEngine(cfg, name()));
                body.put("query", query);
                body.put("max_results", maxResults);
                body.put("include_answer", false);
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(body.toString(), JSON))
                        .header("Content-Type", "application/json")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Tavily HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(text);
                    JSONArray results = root.optJSONArray("results");
                    return parseTavilyResults(results, maxResults);
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Tavily 搜索失败：" + error.getMessage(), error);
            }
        }
    }

    static final class SerperEngine implements WebSearchEngine {
        @Override public String name() { return "serper"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return false; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(), "https://google.serper.dev/search");
            try {
                JSONObject body = new JSONObject();
                body.put("q", query);
                body.put("num", maxResults);
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(body.toString(), JSON))
                        .header("X-API-KEY", apiKeyForEngine(cfg, name()))
                        .header("Content-Type", "application/json")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Serper HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(text);
                    JSONArray organic = root.optJSONArray("organic");
                    List<SearchResult> list = new ArrayList<>();
                    for (int i = 0; organic != null && i < organic.length() && i < maxResults; i++) {
                        JSONObject item = organic.optJSONObject(i);
                        if (item == null) continue;
                        SearchResult sr = new SearchResult();
                        sr.title = item.optString("title", "");
                        sr.url = item.optString("link", "");
                        sr.snippet = item.optString("snippet", "");
                        list.add(sr);
                    }
                    return list;
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Serper 搜索失败：" + error.getMessage(), error);
            }
        }
    }

    static final class BochaaiEngine implements WebSearchEngine {
        @Override public String name() { return "bochaai"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return true; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(), "https://api.bochaai.com/v1/web-search");
            try {
                JSONObject body = new JSONObject();
                body.put("query", query);
                body.put("count", maxResults);
                body.put("summary", true);
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(body.toString(), JSON))
                        .header("Authorization", "Bearer " + apiKeyForEngine(cfg, name()))
                        .header("Content-Type", "application/json")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Bochaai HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(text);
                    JSONObject data = root.optJSONObject("data");
                    JSONObject webPages = data == null ? null : data.optJSONObject("webPages");
                    JSONArray value = webPages == null ? null : webPages.optJSONArray("value");
                    List<SearchResult> list = new ArrayList<>();
                    for (int i = 0; value != null && i < value.length() && i < maxResults; i++) {
                        JSONObject item = value.optJSONObject(i);
                        if (item == null) continue;
                        SearchResult sr = new SearchResult();
                        sr.title = item.optString("name", "");
                        sr.url = item.optString("url", "");
                        sr.snippet = item.optString("snippet", "");
                        sr.content = item.optString("summary", "");
                        list.add(sr);
                    }
                    return list;
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("博查 AI 搜索失败：" + error.getMessage(), error);
            }
        }
    }

    static final class QianfanAiSearchEngine implements WebSearchEngine {
        @Override public String name() { return "qianfan_ai_search"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return true; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(),
                    "https://qianfan.baidubce.com/v2/ai_search/web_search");
            try {
                int topK = Math.max(1, Math.min(maxResults, 50));
                JSONObject body = new JSONObject();
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject()
                        .put("role", "user")
                        .put("content", safe(query)));
                body.put("messages", messages);
                body.put("search_source", "baidu_search_v2");
                body.put("resource_type_filter", new JSONArray()
                        .put(new JSONObject().put("type", "web").put("top_k", topK)));
                String auth = bearerHeader(apiKeyForEngine(cfg, name()));
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(body.toString(), JSON))
                        .header("Authorization", auth)
                        .header("X-Appbuilder-Authorization", auth)
                        .header("Content-Type", "application/json")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Qianfan AI Search HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(text);
                    List<SearchResult> results = parseQianfanReferences(root.optJSONArray("references"), topK);
                    if (!results.isEmpty()) return results;
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        results = parseQianfanReferences(data.optJSONArray("references"), topK);
                    }
                    return results;
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("百度千帆 AI 搜索失败：" + error.getMessage(), error);
            }
        }
    }

    static final class VolcengineWebSearchEngine implements WebSearchEngine {
        @Override public String name() { return "volcengine_web_search"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return true; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(),
                    "https://open.feedcoopapi.com/search_api/web_search");
            try {
                int count = Math.max(1, Math.min(maxResults, 50));
                JSONObject filter = new JSONObject()
                        .put("NeedContent", true)
                        .put("NeedUrl", true)
                        .put("Sites", "")
                        .put("BlockHosts", "")
                        .put("AuthInfoLevel", 0);
                JSONObject body = new JSONObject()
                        .put("Query", safe(query))
                        .put("SearchType", "web")
                        .put("Count", count)
                        .put("Filter", filter)
                        .put("NeedSummary", true)
                        .put("TimeRange", "")
                        .put("QueryControl", new JSONObject().put("QueryRewrite", false));
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(body.toString(), JSON))
                        .header("Authorization", bearerHeader(apiKeyForEngine(cfg, name())))
                        .header("Content-Type", "application/json")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("火山联网搜索 HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    if (text.contains("invalid_request")) {
                        throw new IOException("火山联网搜索请求无效：" + clipText(text, 300));
                    }
                    return parseVolcengineWebSearchResponse(text, count);
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("火山联网搜索失败：" + error.getMessage(), error);
            }
        }
    }

    static final class BingCnEngine implements WebSearchEngine {
        @Override public String name() { return "bing_cn"; }
        @Override public boolean requiresApiKey() { return true; }
        @Override public boolean availableInChina() { return true; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            String endpoint = endpointForEngine(cfg, name(), "https://api.bing.microsoft.com/v7.0/search");
            try {
                // v1.5.4+：endpoint 自定义反代时可能已带 query string，分别用 & 或 ? 分隔（解决 WSS-9）。
                String separator = endpoint.contains("?") ? "&" : "?";
                String url = endpoint + separator + "q=" + java.net.URLEncoder.encode(query, "UTF-8")
                        + "&count=" + maxResults + "&mkt=zh-CN";
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .header("Ocp-Apim-Subscription-Key", apiKeyForEngine(cfg, name()))
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Bing HTTP " + response.code() + ": " + clip(response));
                    }
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(text);
                    JSONObject webPages = root.optJSONObject("webPages");
                    JSONArray value = webPages == null ? null : webPages.optJSONArray("value");
                    List<SearchResult> list = new ArrayList<>();
                    for (int i = 0; value != null && i < value.length() && i < maxResults; i++) {
                        JSONObject item = value.optJSONObject(i);
                        if (item == null) continue;
                        SearchResult sr = new SearchResult();
                        sr.title = item.optString("name", "");
                        sr.url = item.optString("url", "");
                        sr.snippet = item.optString("snippet", "");
                        list.add(sr);
                    }
                    return list;
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Bing CN 搜索失败：" + error.getMessage(), error);
            }
        }
    }

    /**
     * DuckDuckGo HTML：抓 https://html.duckduckgo.com/html/?q=... 解析 <a class="result__a">。脆弱兜底。
     */
    static final class DuckDuckGoHtmlEngine implements WebSearchEngine {
        private static final Pattern RESULT_PATTERN = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern SNIPPET_PATTERN = Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        @Override public String name() { return "duckduckgo_html"; }
        @Override public boolean requiresApiKey() { return false; }
        @Override public boolean availableInChina() { return false; }

        @Override
        public List<SearchResult> search(String query, int maxResults, ProxyConfig cfg) throws IOException {
            try {
                String url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                        .build();
                try (Response response = client(cfg, name()).newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("DuckDuckGo HTTP " + response.code());
                    }
                    String html = response.body() == null ? "" : response.body().string();
                    return parseDuckDuckGoHtml(html, maxResults);
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("DuckDuckGo 搜索失败：" + error.getMessage(), error);
            }
        }

        private static List<SearchResult> parseDuckDuckGoHtml(String html, int maxResults) {
            List<SearchResult> list = new ArrayList<>();
            if (TextUtils.isEmpty(html)) return list;
            Matcher resultMatcher = RESULT_PATTERN.matcher(html);
            Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);
            while (resultMatcher.find() && list.size() < maxResults) {
                SearchResult sr = new SearchResult();
                sr.url = decodeDdgUrl(resultMatcher.group(1));
                sr.title = stripHtml(resultMatcher.group(2));
                if (snippetMatcher.find()) {
                    sr.snippet = stripHtml(snippetMatcher.group(1));
                }
                if (!TextUtils.isEmpty(sr.title) || !TextUtils.isEmpty(sr.url)) {
                    list.add(sr);
                }
            }
            return list;
        }

        private static String decodeDdgUrl(String raw) {
            // DuckDuckGo wraps real URLs as //duckduckgo.com/l/?uddg=ENCODED
            if (TextUtils.isEmpty(raw)) return "";
            try {
                int idx = raw.indexOf("uddg=");
                if (idx >= 0) {
                    int end = raw.indexOf("&", idx + 5);
                    String encoded = end > 0 ? raw.substring(idx + 5, end) : raw.substring(idx + 5);
                    return java.net.URLDecoder.decode(encoded, "UTF-8");
                }
                return raw.startsWith("//") ? "https:" + raw : raw;
            } catch (Exception ignored) {
                return raw;
            }
        }

        private static String stripHtml(String html) {
            if (TextUtils.isEmpty(html)) return "";
            return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        }
    }

    private static List<SearchResult> parseTavilyResults(JSONArray results, int maxResults) {
        List<SearchResult> list = new ArrayList<>();
        if (results == null) return list;
        for (int i = 0; i < results.length() && i < maxResults; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            SearchResult sr = new SearchResult();
            sr.title = item.optString("title", "");
            sr.url = item.optString("url", "");
            sr.snippet = item.optString("content", "");
            sr.content = item.optString("raw_content", "");
            list.add(sr);
        }
        return list;
    }

    private static List<SearchResult> parseQianfanReferences(JSONArray references, int maxResults) {
        List<SearchResult> list = new ArrayList<>();
        if (references == null) return list;
        for (int i = 0; i < references.length() && i < maxResults; i++) {
            JSONObject item = references.optJSONObject(i);
            if (item == null) continue;
            SearchResult sr = new SearchResult();
            sr.title = firstNonEmpty(item.optString("title", ""), item.optString("web_anchor", ""));
            sr.url = item.optString("url", "");
            String date = item.optString("date", "");
            String content = item.optString("content", "");
            sr.content = TextUtils.isEmpty(date) ? content : ("日期：" + date + "\n" + content);
            sr.snippet = item.optString("summary", "");
            if (!TextUtils.isEmpty(sr.title) || !TextUtils.isEmpty(sr.url) || !TextUtils.isEmpty(sr.content)) {
                list.add(sr);
            }
        }
        return list;
    }

    private static List<SearchResult> parseGenericReferences(JSONArray references, int maxResults) {
        List<SearchResult> list = new ArrayList<>();
        if (references == null) return list;
        for (int i = 0; i < references.length() && i < maxResults; i++) {
            JSONObject item = references.optJSONObject(i);
            if (item == null) continue;
            SearchResult sr = new SearchResult();
            sr.title = firstNonEmpty(item.optString("title", ""), item.optString("name", ""));
            sr.url = firstNonEmpty(item.optString("url", ""), item.optString("link", ""));
            sr.snippet = firstNonEmpty(item.optString("snippet", ""), item.optString("summary", ""));
            sr.content = item.optString("content", "");
            if (!TextUtils.isEmpty(sr.title) || !TextUtils.isEmpty(sr.url) || !TextUtils.isEmpty(sr.content)) {
                list.add(sr);
            }
        }
        return list;
    }

    private static List<SearchResult> parseVolcengineWebSearchResponse(String text, int maxResults) {
        List<SearchResult> list = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return list;
        List<JSONObject> roots = new ArrayList<>();
        boolean parsedWhole = false;
        try {
            roots.add(new JSONObject(text));
            parsedWhole = true;
        } catch (Exception ignored) {
        }
        if (!parsedWhole || text.indexOf('\n') >= 0) {
            String[] lines = text.split("\\r?\\n");
            for (String rawLine : lines) {
                String line = normalizeJsonLine(rawLine);
                if (TextUtils.isEmpty(line)) continue;
                try {
                    roots.add(new JSONObject(line));
                } catch (Exception ignored) {
                }
            }
        }
        for (JSONObject root : roots) {
            collectVolcengineResults(root, list, maxResults, 0);
            if (list.size() >= maxResults) break;
        }
        if (list.isEmpty()) {
            for (JSONObject root : roots) {
                String summary = findFirstStringDeep(root, 0,
                        "Summary", "summary", "Answer", "answer", "Content", "content",
                        "Output", "output", "Message", "message", "Text", "text");
                if (!TextUtils.isEmpty(summary)) {
                    SearchResult sr = new SearchResult();
                    sr.title = "火山联网搜索摘要";
                    sr.content = clipText(summary, 4000);
                    list.add(sr);
                    break;
                }
            }
        }
        return list;
    }

    private static String normalizeJsonLine(String rawLine) {
        String line = safe(rawLine).trim();
        if (TextUtils.isEmpty(line) || "[DONE]".equals(line) || line.startsWith("event:")) {
            return "";
        }
        if (line.startsWith("data:")) {
            line = line.substring("data:".length()).trim();
        }
        return "[DONE]".equals(line) ? "" : line;
    }

    private static void collectVolcengineResults(Object value, List<SearchResult> list, int maxResults, int depth) {
        if (value == null || list == null || list.size() >= maxResults || depth > 8) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && list.size() < maxResults; i++) {
                collectVolcengineResults(array.opt(i), list, maxResults, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        if (appendVolcengineResultFromObject(object, list, maxResults)) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext() && list.size() < maxResults) {
            Object child = object.opt(keys.next());
            if (child instanceof JSONObject || child instanceof JSONArray) {
                collectVolcengineResults(child, list, maxResults, depth + 1);
            }
        }
    }

    private static boolean appendVolcengineResultFromObject(JSONObject item, List<SearchResult> list, int maxResults) {
        if (item == null || list == null || list.size() >= maxResults) return false;
        String title = firstNonEmptyAny(item, "Title", "title", "Name", "name", "WebAnchor", "web_anchor");
        String url = firstNonEmptyAny(item, "Url", "URL", "url", "Link", "link", "SourceUrl", "source_url");
        String snippet = firstNonEmptyAny(item, "Snippet", "snippet", "Summary", "summary",
                "Description", "description", "Abstract", "abstract");
        String content = firstNonEmptyAny(item, "Content", "content", "FullContent", "full_content",
                "Text", "text", "Markdown", "markdown");
        boolean resultShape = !TextUtils.isEmpty(url)
                || (!TextUtils.isEmpty(title) && (!TextUtils.isEmpty(snippet) || !TextUtils.isEmpty(content)));
        if (!resultShape) return false;
        SearchResult sr = new SearchResult();
        sr.title = TextUtils.isEmpty(title) ? url : title;
        sr.url = url;
        sr.snippet = snippet;
        sr.content = content;
        list.add(sr);
        return true;
    }

    private static String firstNonEmptyAny(JSONObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (String key : keys) {
            Object raw = object.opt(key);
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (!TextUtils.isEmpty(text)) return text;
            }
        }
        return "";
    }

    private static String findFirstStringDeep(Object value, int depth, String... keys) {
        if (value == null || depth > 8) return "";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct = firstNonEmptyAny(object, keys);
            if (!TextUtils.isEmpty(direct)) return direct;
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                Object child = object.opt(names.next());
                String nested = findFirstStringDeep(child, depth + 1, keys);
                if (!TextUtils.isEmpty(nested)) return nested;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String nested = findFirstStringDeep(array.opt(i), depth + 1, keys);
                if (!TextUtils.isEmpty(nested)) return nested;
            }
        }
        return "";
    }

    private static String bearerHeader(String apiKey) {
        String value = safe(apiKey).trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return value;
        }
        return "Bearer " + value;
    }

    private static String firstNonEmpty(String first, String second) {
        return !TextUtils.isEmpty(first) ? first : safe(second);
    }

    private static String clipText(String text, int maxChars) {
        String value = safe(text);
        int limit = Math.max(32, maxChars);
        return value.length() > limit ? value.substring(0, limit) + "…" : value;
    }

    private static String clip(Response response) {
        try {
            if (response.body() == null) return "";
            String text = response.peekBody(512).string();
            return text.length() > 200 ? text.substring(0, 200) + "…" : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
