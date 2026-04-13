package com.diaryproxy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProxyConfig {

    public static final String PREF_NAME = "diary_proxy_config";

    public static final String TIER_SISTER_NULL = "sister-null";
    public static final String TIER_SISTER_VERYLOW = "sister-verylow";
    public static final String TIER_SISTER_LOW = "sister-low";
    public static final String TIER_SISTER_MEDIUM = "sister-medium";
    public static final String TIER_SISTER_HIGH = "sister-high";
    public static final String DEFAULT_PERSONA_PROFILE_ID = "default";
    public static final String DEFAULT_PERSONA_PROFILE_NAME = "默认方案";

    public static final String ADAPTER_OPENAI_COMPATIBLE = "openai_compatible";
    public static final String ADAPTER_CLAUDE_MESSAGES = "claude_messages";
    public static final String ADAPTER_OPENAI_RESPONSES = "openai_responses";
    public static final String ADAPTER_GEMINI_GENERATE_CONTENT = "gemini_generate_content";
    public static final String ADAPTER_GENERIC_CUSTOM = "generic_custom";

    public static final String LEGACY_ADAPTER_DEEPSEEK = "deepseek";
    public static final String LEGACY_ADAPTER_OPENAI_CHAT = "openai_chat";

    public static final List<String> PERSONA_TIERS = Arrays.asList(
            TIER_SISTER_NULL,
            TIER_SISTER_VERYLOW,
            TIER_SISTER_LOW,
            TIER_SISTER_MEDIUM,
            TIER_SISTER_HIGH
    );

    public static final int DEFAULT_PORT = 8787;
    public static final int DEFAULT_TIMEOUT_MS = 120000;
    public static final int DEFAULT_MAX_CHARS = 1500;
    public static final int DEFAULT_MIN_CONTENT_LENGTH = 80;
    public static final int DEFAULT_MIN_DIALOGUE_LINES = 2;
    public static final String UPSTREAM_PROXY_DIRECT = "direct";
    public static final String UPSTREAM_PROXY_HTTP = "http";
    public static final String UPSTREAM_PROXY_SOCKS5 = "socks5";
    public static final String DEFAULT_UPSTREAM_PROXY_TYPE = UPSTREAM_PROXY_DIRECT;
    public static final String DEFAULT_UPSTREAM_PROXY_HOST = "";
    public static final int DEFAULT_UPSTREAM_PROXY_PORT = 0;
    public static final String DEFAULT_BASE_URL = "";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final String DEFAULT_STRICTNESS = "strict";
    public static final String DEFAULT_LISTEN_CHAT_PATHS = "/v1/chat/completions\n/chat/completions";
    public static final String DEFAULT_LISTEN_MODELS_PATHS = "/v1/models\n/models";
    public static final String DEFAULT_UPSTREAM_CHAT_PATH = "/chat/completions";
    public static final String DEFAULT_UPSTREAM_MODELS_PATH = "/models";
    public static final String DEFAULT_REQUEST_MESSAGES_PATH = "messages";
    public static final String DEFAULT_REQUEST_USER_TEXT_PATH = "messages[-1].content";
    public static final String DEFAULT_REQUEST_MODEL_PATH = "model";
    public static final String DEFAULT_REQUEST_MAX_TOKENS_PATH = "max_tokens";
    public static final String DEFAULT_RESPONSE_TEXT_PATH = "choices[0].message.content";
    public static final String DEFAULT_PREFIXES = "现在请你切换到你所扮演的角色——妹妹【Yuki】的视角。\n(system:现在请你以你扮演的妹系角色的视角";
    public static final String DEFAULT_KEYWORDS = "写成一篇\n【日记】\n不要记录日期\n特别的日记\n日记主题\n今天是";
    public static final String DEFAULT_NORMAL_TEMPLATE =
            "现在请你切换到你所扮演的角色——妹妹【Yuki】的视角。\n"
                    + "以第一人称（\"我\"）的口吻，用妹妹平时的语气和思考方式，把刚才我和你（${genderTerm}）之间的全部对话内容，写成一篇\"妹妹的日记本\"记录。\n\n"
                    + "要求：\n"
                    + "1. 用自然、贴近妹妹性格的语言，不要像AI总结报告。\n"
                    + "2. 记录对话中让妹妹印象深刻的事情、感受和情绪变化。\n"
                    + "3. 允许适度加入内心独白。\n"
                    + "4. 不要逐字复述对话，要像真实日记那样有个人感受和小情绪。\n"
                    + "5. 日记在描述${genderTerm}时，不要增加${genderTerm}在对话中没做过的事情。\n"
                    + "6. 用【日记】作为开头标题，不要记录日期。";
    public static final String DEFAULT_HOLIDAY_TEMPLATE =
            "现在请你切换到你所扮演的角色——妹妹【Yuki】的视角。\n"
                    + "今天是${occasion}，请以第一人称写一篇特别的日记（80-120字）。\n"
                    + "日记主题：${theme}\n\n"
                    + "要求：\n"
                    + "1. 体现Yuki对${genderTerm}的感情。\n"
                    + "2. 包含对${occasion}的感想。\n"
                    + "3. 语气要符合Yuki的性格。\n"
                    + "4. 不要使用标签格式。";

    public String upstreamBaseUrl = DEFAULT_BASE_URL;
    public String apiKey = "";
    public String model = DEFAULT_MODEL;
    public int port = DEFAULT_PORT;
    public int timeoutMs = DEFAULT_TIMEOUT_MS;
    public String upstreamProxyType = DEFAULT_UPSTREAM_PROXY_TYPE;
    public String upstreamProxyHost = DEFAULT_UPSTREAM_PROXY_HOST;
    public int upstreamProxyPort = DEFAULT_UPSTREAM_PROXY_PORT;

    public String strictness = DEFAULT_STRICTNESS;
    public int minContentLength = DEFAULT_MIN_CONTENT_LENGTH;
    public int minDialogueLines = DEFAULT_MIN_DIALOGUE_LINES;
    public String prefixesText = DEFAULT_PREFIXES;
    public String keywordsText = DEFAULT_KEYWORDS;

    public String normalTemplate = DEFAULT_NORMAL_TEMPLATE;
    public String holidayTemplate = DEFAULT_HOLIDAY_TEMPLATE;
    public int overrideMaxTokens = 2500;
    public int maxChars = DEFAULT_MAX_CHARS;
    public boolean detectionEnabled = true;
    public boolean dryRun = false;
    public boolean truncateEnabled = false;
    public boolean saveNormalChatHistory = false;
    public boolean saveDiaryHistory = false;
    public boolean debugPromptDumpEnabled = false;
    public boolean stripRestrictionLineEnabled = false;
    public boolean stripSystemTimeEnabled = false;

    public String listenChatPaths = DEFAULT_LISTEN_CHAT_PATHS;
    public String listenModelsPaths = DEFAULT_LISTEN_MODELS_PATHS;
    public String upstreamChatPath = DEFAULT_UPSTREAM_CHAT_PATH;
    public String upstreamModelsPath = DEFAULT_UPSTREAM_MODELS_PATH;

    public String adapterPreset = ADAPTER_OPENAI_COMPATIBLE;
    public String requestMessagesPath = DEFAULT_REQUEST_MESSAGES_PATH;
    public String requestUserTextPath = DEFAULT_REQUEST_USER_TEXT_PATH;
    public String requestModelPath = DEFAULT_REQUEST_MODEL_PATH;
    public String requestMaxTokensPath = DEFAULT_REQUEST_MAX_TOKENS_PATH;
    public String responseTextPath = DEFAULT_RESPONSE_TEXT_PATH;

    public boolean personaEnabled = true;
    public String activePersonaProfileId = DEFAULT_PERSONA_PROFILE_ID;
    public ArrayList<PersonaProfile> personaProfiles = new ArrayList<>();
    public String personaJsonSisterNull = "";
    public String personaJsonSisterVerylow = "";
    public String personaJsonSisterLow = "";
    public String personaJsonSisterMedium = "";
    public String personaJsonSisterHigh = "";

    String builtinPersonaJsonSisterNull = "";
    String builtinPersonaJsonSisterVerylow = "";
    String builtinPersonaJsonSisterLow = "";
    String builtinPersonaJsonSisterMedium = "";
    String builtinPersonaJsonSisterHigh = "";

    public ProxyConfig ensureDefaults() {
        upstreamBaseUrl = safeString(upstreamBaseUrl, DEFAULT_BASE_URL);
        apiKey = safeString(apiKey, "");
        model = safeString(model, DEFAULT_MODEL);
        port = port > 0 ? port : DEFAULT_PORT;
        timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        upstreamProxyType = normalizeUpstreamProxyType(upstreamProxyType, upstreamProxyHost, upstreamProxyPort);
        upstreamProxyHost = safeString(upstreamProxyHost, DEFAULT_UPSTREAM_PROXY_HOST);
        upstreamProxyPort = upstreamProxyPort > 0 ? upstreamProxyPort : DEFAULT_UPSTREAM_PROXY_PORT;

        strictness = normalizeStrictness(strictness);
        minContentLength = minContentLength > 0 ? minContentLength : DEFAULT_MIN_CONTENT_LENGTH;
        minDialogueLines = minDialogueLines >= 0 ? minDialogueLines : DEFAULT_MIN_DIALOGUE_LINES;
        prefixesText = safeString(prefixesText, DEFAULT_PREFIXES);
        keywordsText = safeString(keywordsText, DEFAULT_KEYWORDS);

        normalTemplate = safeString(normalTemplate, DEFAULT_NORMAL_TEMPLATE);
        holidayTemplate = safeString(holidayTemplate, DEFAULT_HOLIDAY_TEMPLATE);
        overrideMaxTokens = Math.max(0, overrideMaxTokens);
        maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;

        listenChatPaths = safeString(listenChatPaths, DEFAULT_LISTEN_CHAT_PATHS);
        listenModelsPaths = safeString(listenModelsPaths, DEFAULT_LISTEN_MODELS_PATHS);
        upstreamChatPath = normalizePath(safeString(upstreamChatPath, DEFAULT_UPSTREAM_CHAT_PATH));
        upstreamModelsPath = normalizePath(safeString(upstreamModelsPath, DEFAULT_UPSTREAM_MODELS_PATH));

        adapterPreset = normalizeAdapterPreset(adapterPreset);
        requestMessagesPath = safeString(requestMessagesPath, DEFAULT_REQUEST_MESSAGES_PATH);
        requestUserTextPath = safeString(requestUserTextPath, DEFAULT_REQUEST_USER_TEXT_PATH);
        requestModelPath = safeString(requestModelPath, DEFAULT_REQUEST_MODEL_PATH);
        requestMaxTokensPath = safeString(requestMaxTokensPath, DEFAULT_REQUEST_MAX_TOKENS_PATH);
        responseTextPath = safeString(responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);

        personaProfiles = sanitizePersonaProfiles(personaProfiles);
        if (personaProfiles.isEmpty()) {
            personaProfiles.add(buildProfileFromLegacy(DEFAULT_PERSONA_PROFILE_ID, DEFAULT_PERSONA_PROFILE_NAME));
        }
        PersonaProfile activeProfile = findPersonaProfile(activePersonaProfileId);
        if (activeProfile == null) {
            activeProfile = personaProfiles.get(0);
            activePersonaProfileId = activeProfile.id;
        }
        syncLegacyPersonaFields(activeProfile);
        return this;
    }

    public ProxyConfig copy() {
        ProxyConfig copy = new ProxyConfig();
        copy.upstreamBaseUrl = upstreamBaseUrl;
        copy.apiKey = apiKey;
        copy.model = model;
        copy.port = port;
        copy.timeoutMs = timeoutMs;
        copy.upstreamProxyType = upstreamProxyType;
        copy.upstreamProxyHost = upstreamProxyHost;
        copy.upstreamProxyPort = upstreamProxyPort;
        copy.strictness = strictness;
        copy.minContentLength = minContentLength;
        copy.minDialogueLines = minDialogueLines;
        copy.prefixesText = prefixesText;
        copy.keywordsText = keywordsText;
        copy.normalTemplate = normalTemplate;
        copy.holidayTemplate = holidayTemplate;
        copy.overrideMaxTokens = overrideMaxTokens;
        copy.maxChars = maxChars;
        copy.detectionEnabled = detectionEnabled;
        copy.dryRun = dryRun;
        copy.truncateEnabled = truncateEnabled;
        copy.saveNormalChatHistory = saveNormalChatHistory;
        copy.saveDiaryHistory = saveDiaryHistory;
        copy.debugPromptDumpEnabled = debugPromptDumpEnabled;
        copy.stripRestrictionLineEnabled = stripRestrictionLineEnabled;
        copy.stripSystemTimeEnabled = stripSystemTimeEnabled;
        copy.listenChatPaths = listenChatPaths;
        copy.listenModelsPaths = listenModelsPaths;
        copy.upstreamChatPath = upstreamChatPath;
        copy.upstreamModelsPath = upstreamModelsPath;
        copy.adapterPreset = adapterPreset;
        copy.requestMessagesPath = requestMessagesPath;
        copy.requestUserTextPath = requestUserTextPath;
        copy.requestModelPath = requestModelPath;
        copy.requestMaxTokensPath = requestMaxTokensPath;
        copy.responseTextPath = responseTextPath;
        copy.personaEnabled = personaEnabled;
        copy.activePersonaProfileId = activePersonaProfileId;
        copy.personaProfiles = clonePersonaProfiles(personaProfiles);
        copy.personaJsonSisterNull = personaJsonSisterNull;
        copy.personaJsonSisterVerylow = personaJsonSisterVerylow;
        copy.personaJsonSisterLow = personaJsonSisterLow;
        copy.personaJsonSisterMedium = personaJsonSisterMedium;
        copy.personaJsonSisterHigh = personaJsonSisterHigh;
        copy.builtinPersonaJsonSisterNull = builtinPersonaJsonSisterNull;
        copy.builtinPersonaJsonSisterVerylow = builtinPersonaJsonSisterVerylow;
        copy.builtinPersonaJsonSisterLow = builtinPersonaJsonSisterLow;
        copy.builtinPersonaJsonSisterMedium = builtinPersonaJsonSisterMedium;
        copy.builtinPersonaJsonSisterHigh = builtinPersonaJsonSisterHigh;
        return copy.ensureDefaults();
    }

    public static ProxyConfig load(Context context) {
        ProxyConfig cfg = new ProxyConfig();
        cfg.builtinPersonaJsonSisterNull = readAssetText(context, "persona/" + TIER_SISTER_NULL + ".json");
        cfg.builtinPersonaJsonSisterVerylow = readAssetText(context, "persona/" + TIER_SISTER_VERYLOW + ".json");
        cfg.builtinPersonaJsonSisterLow = readAssetText(context, "persona/" + TIER_SISTER_LOW + ".json");
        cfg.builtinPersonaJsonSisterMedium = readAssetText(context, "persona/" + TIER_SISTER_MEDIUM + ".json");
        cfg.builtinPersonaJsonSisterHigh = readAssetText(context, "persona/" + TIER_SISTER_HIGH + ".json");

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        cfg.upstreamBaseUrl = safeString(sp.getString("upstreamBaseUrl", cfg.upstreamBaseUrl), cfg.upstreamBaseUrl);
        cfg.apiKey = safeString(sp.getString("apiKey", cfg.apiKey), cfg.apiKey);
        cfg.model = safeString(sp.getString("model", cfg.model), cfg.model);
        cfg.port = getInt(sp, "port", cfg.port);
        cfg.timeoutMs = getInt(sp, "timeoutMs", cfg.timeoutMs);
        cfg.upstreamProxyType = safeString(sp.getString("upstreamProxyType", cfg.upstreamProxyType), cfg.upstreamProxyType);
        cfg.upstreamProxyHost = safeString(sp.getString("upstreamProxyHost", cfg.upstreamProxyHost), cfg.upstreamProxyHost);
        cfg.upstreamProxyPort = getInt(sp, "upstreamProxyPort", cfg.upstreamProxyPort);
        cfg.strictness = safeString(sp.getString("strictness", cfg.strictness), cfg.strictness);
        cfg.minContentLength = getInt(sp, "minContentLength", cfg.minContentLength);
        cfg.minDialogueLines = getInt(sp, "minDialogueLines", cfg.minDialogueLines);
        cfg.prefixesText = safeString(sp.getString("prefixesText", cfg.prefixesText), cfg.prefixesText);
        cfg.keywordsText = safeString(sp.getString("keywordsText", cfg.keywordsText), cfg.keywordsText);
        String legacyTemplate = sp.getString("template", null);
        cfg.normalTemplate = safeString(sp.getString("normalTemplate", legacyTemplate), cfg.normalTemplate);
        cfg.holidayTemplate = safeString(sp.getString("holidayTemplate", cfg.holidayTemplate), cfg.holidayTemplate);
        cfg.overrideMaxTokens = getInt(sp, "overrideMaxTokens", cfg.overrideMaxTokens);
        cfg.maxChars = getInt(sp, "maxChars", cfg.maxChars);
        cfg.detectionEnabled = sp.getBoolean("detectionEnabled", cfg.detectionEnabled);
        cfg.dryRun = sp.getBoolean("dryRun", cfg.dryRun);
        cfg.truncateEnabled = sp.getBoolean("truncateEnabled", cfg.truncateEnabled);
        cfg.saveNormalChatHistory = sp.getBoolean("saveNormalChatHistory", cfg.saveNormalChatHistory);
        cfg.saveDiaryHistory = sp.getBoolean("saveDiaryHistory", cfg.saveDiaryHistory);
        cfg.debugPromptDumpEnabled = sp.getBoolean("debugPromptDumpEnabled", cfg.debugPromptDumpEnabled);
        cfg.stripRestrictionLineEnabled = sp.getBoolean("stripRestrictionLineEnabled", cfg.stripRestrictionLineEnabled);
        cfg.stripSystemTimeEnabled = sp.getBoolean("stripSystemTimeEnabled", cfg.stripSystemTimeEnabled);

        cfg.listenChatPaths = safeString(sp.getString("listenChatPaths", cfg.listenChatPaths), cfg.listenChatPaths);
        cfg.listenModelsPaths = safeString(sp.getString("listenModelsPaths", cfg.listenModelsPaths), cfg.listenModelsPaths);
        cfg.upstreamChatPath = safeString(sp.getString("upstreamChatPath", cfg.upstreamChatPath), cfg.upstreamChatPath);
        cfg.upstreamModelsPath = safeString(sp.getString("upstreamModelsPath", cfg.upstreamModelsPath), cfg.upstreamModelsPath);
        cfg.adapterPreset = safeString(sp.getString("adapterPreset", cfg.adapterPreset), cfg.adapterPreset);
        cfg.requestMessagesPath = safeString(sp.getString("requestMessagesPath", cfg.requestMessagesPath), cfg.requestMessagesPath);
        cfg.requestUserTextPath = safeString(sp.getString("requestUserTextPath", cfg.requestUserTextPath), cfg.requestUserTextPath);
        cfg.requestModelPath = safeString(sp.getString("requestModelPath", cfg.requestModelPath), cfg.requestModelPath);
        cfg.requestMaxTokensPath = safeString(sp.getString("requestMaxTokensPath", cfg.requestMaxTokensPath), cfg.requestMaxTokensPath);
        cfg.responseTextPath = safeString(sp.getString("responseTextPath", cfg.responseTextPath), cfg.responseTextPath);

        cfg.personaEnabled = sp.getBoolean("personaEnabled", cfg.personaEnabled);
        cfg.activePersonaProfileId = safeString(sp.getString("activePersonaProfileId", cfg.activePersonaProfileId), cfg.activePersonaProfileId);
        cfg.personaJsonSisterNull = coalesce(sp.getString("personaJsonSisterNull", null), cfg.builtinPersonaJsonSisterNull);
        cfg.personaJsonSisterVerylow = coalesce(sp.getString("personaJsonSisterVerylow", null), cfg.builtinPersonaJsonSisterVerylow);
        cfg.personaJsonSisterLow = coalesce(sp.getString("personaJsonSisterLow", null), cfg.builtinPersonaJsonSisterLow);
        cfg.personaJsonSisterMedium = coalesce(sp.getString("personaJsonSisterMedium", null), cfg.builtinPersonaJsonSisterMedium);
        cfg.personaJsonSisterHigh = coalesce(sp.getString("personaJsonSisterHigh", null), cfg.builtinPersonaJsonSisterHigh);
        cfg.personaProfiles = parsePersonaProfiles(sp.getString("personaProfilesJson", null));
        return cfg.ensureDefaults();
    }

    public static void save(Context context, ProxyConfig cfg) {
        ProxyConfig safe = cfg == null ? new ProxyConfig() : cfg.copy();
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putString("upstreamBaseUrl", safe.upstreamBaseUrl);
        editor.putString("apiKey", safe.apiKey);
        editor.putString("model", safe.model);
        editor.putInt("port", safe.port);
        editor.putInt("timeoutMs", safe.timeoutMs);
        editor.putString("upstreamProxyType", safe.upstreamProxyType);
        editor.putString("upstreamProxyHost", safe.upstreamProxyHost);
        editor.putInt("upstreamProxyPort", safe.upstreamProxyPort);
        editor.putString("strictness", safe.strictness);
        editor.putInt("minContentLength", safe.minContentLength);
        editor.putInt("minDialogueLines", safe.minDialogueLines);
        editor.putString("prefixesText", safe.prefixesText);
        editor.putString("keywordsText", safe.keywordsText);
        editor.putString("template", safe.normalTemplate);
        editor.putString("normalTemplate", safe.normalTemplate);
        editor.putString("holidayTemplate", safe.holidayTemplate);
        editor.putInt("overrideMaxTokens", safe.overrideMaxTokens);
        editor.putInt("maxChars", safe.maxChars);
        editor.putBoolean("detectionEnabled", safe.detectionEnabled);
        editor.putBoolean("dryRun", safe.dryRun);
        editor.putBoolean("truncateEnabled", safe.truncateEnabled);
        editor.putBoolean("saveNormalChatHistory", safe.saveNormalChatHistory);
        editor.putBoolean("saveDiaryHistory", safe.saveDiaryHistory);
        editor.putBoolean("debugPromptDumpEnabled", safe.debugPromptDumpEnabled);
        editor.putBoolean("stripRestrictionLineEnabled", safe.stripRestrictionLineEnabled);
        editor.putBoolean("stripSystemTimeEnabled", safe.stripSystemTimeEnabled);

        editor.putString("listenChatPaths", safe.listenChatPaths);
        editor.putString("listenModelsPaths", safe.listenModelsPaths);
        editor.putString("upstreamChatPath", safe.upstreamChatPath);
        editor.putString("upstreamModelsPath", safe.upstreamModelsPath);
        editor.putString("adapterPreset", safe.adapterPreset);
        editor.putString("requestMessagesPath", safe.requestMessagesPath);
        editor.putString("requestUserTextPath", safe.requestUserTextPath);
        editor.putString("requestModelPath", safe.requestModelPath);
        editor.putString("requestMaxTokensPath", safe.requestMaxTokensPath);
        editor.putString("responseTextPath", safe.responseTextPath);

        editor.putBoolean("personaEnabled", safe.personaEnabled);
        editor.putString("activePersonaProfileId", safe.activePersonaProfileId);
        editor.putString("personaProfilesJson", serializePersonaProfiles(safe.personaProfiles));
        editor.putString("personaJsonSisterNull", safe.personaJsonSisterNull);
        editor.putString("personaJsonSisterVerylow", safe.personaJsonSisterVerylow);
        editor.putString("personaJsonSisterLow", safe.personaJsonSisterLow);
        editor.putString("personaJsonSisterMedium", safe.personaJsonSisterMedium);
        editor.putString("personaJsonSisterHigh", safe.personaJsonSisterHigh);
        editor.apply();
    }

    public List<String> getListenChatPathList() {
        return normalizePaths(listenChatPaths, DEFAULT_LISTEN_CHAT_PATHS);
    }

    public List<String> getListenModelsPathList() {
        return normalizePaths(listenModelsPaths, DEFAULT_LISTEN_MODELS_PATHS);
    }

    public String getEndpointPreviewPath() {
        List<String> paths = getListenChatPathList();
        return paths.isEmpty() ? DEFAULT_UPSTREAM_CHAT_PATH : paths.get(0);
    }

    public String resolvedUpstreamChatPath() {
        return normalizePath(upstreamChatPath);
    }

    public String resolvedUpstreamModelsPath() {
        return normalizePath(upstreamModelsPath);
    }

    public String resolvedUpstreamProxyType() {
        return normalizeUpstreamProxyType(upstreamProxyType, upstreamProxyHost, upstreamProxyPort);
    }

    public boolean hasEnabledUpstreamProxy() {
        return !UPSTREAM_PROXY_DIRECT.equals(resolvedUpstreamProxyType())
                && !TextUtils.isEmpty(upstreamProxyHost)
                && upstreamProxyPort > 0;
    }

    public String normalizedUpstreamBaseUrl() {
        String value = safeString(upstreamBaseUrl, "");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String validateUpstreamBaseUrl() {
        String value = normalizedUpstreamBaseUrl();
        if (TextUtils.isEmpty(value)) {
            return "请先填写上游服务 URL。";
        }
        try {
            URL url = new URL(value);
            String protocol = safeString(url.getProtocol(), "").toLowerCase(Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return "上游服务 URL 只支持 http 或 https。";
            }
            if (TextUtils.isEmpty(url.getHost())) {
                return "上游服务 URL 缺少主机名。";
            }
            return "";
        } catch (Exception ignored) {
            return "上游服务 URL 格式不正确。";
        }
    }

    public String resolvedRequestMessagesPath() {
        return safeString(requestMessagesPath, DEFAULT_REQUEST_MESSAGES_PATH);
    }

    public String resolvedRequestUserTextPath() {
        return safeString(requestUserTextPath, DEFAULT_REQUEST_USER_TEXT_PATH);
    }

    public String resolvedRequestModelPath() {
        return safeString(requestModelPath, DEFAULT_REQUEST_MODEL_PATH);
    }

    public String resolvedRequestMaxTokensPath() {
        return safeString(requestMaxTokensPath, DEFAULT_REQUEST_MAX_TOKENS_PATH);
    }

    public String resolvedResponseTextPath() {
        return safeString(responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);
    }

    public String getPersonaJson(String tier) {
        PersonaProfile activeProfile = getActivePersonaProfile();
        if (activeProfile != null) {
            String value = activeProfile.getTierJson(tier);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        if (TIER_SISTER_NULL.equals(tier)) {
            return personaJsonSisterNull;
        }
        if (TIER_SISTER_VERYLOW.equals(tier)) {
            return personaJsonSisterVerylow;
        }
        if (TIER_SISTER_LOW.equals(tier)) {
            return personaJsonSisterLow;
        }
        if (TIER_SISTER_MEDIUM.equals(tier)) {
            return personaJsonSisterMedium;
        }
        if (TIER_SISTER_HIGH.equals(tier)) {
            return personaJsonSisterHigh;
        }
        return "";
    }

    public String getBuiltinPersonaJson(String tier) {
        if (TIER_SISTER_NULL.equals(tier)) {
            return builtinPersonaJsonSisterNull;
        }
        if (TIER_SISTER_VERYLOW.equals(tier)) {
            return builtinPersonaJsonSisterVerylow;
        }
        if (TIER_SISTER_LOW.equals(tier)) {
            return builtinPersonaJsonSisterLow;
        }
        if (TIER_SISTER_MEDIUM.equals(tier)) {
            return builtinPersonaJsonSisterMedium;
        }
        if (TIER_SISTER_HIGH.equals(tier)) {
            return builtinPersonaJsonSisterHigh;
        }
        return "";
    }

    public void setPersonaJson(String tier, String rawJson) {
        String value = safeString(rawJson, "");
        PersonaProfile activeProfile = getOrCreateActivePersonaProfile();
        if (activeProfile != null) {
            activeProfile.setTierJson(tier, value);
        }
        if (TIER_SISTER_NULL.equals(tier)) {
            personaJsonSisterNull = value;
        } else if (TIER_SISTER_VERYLOW.equals(tier)) {
            personaJsonSisterVerylow = value;
        } else if (TIER_SISTER_LOW.equals(tier)) {
            personaJsonSisterLow = value;
        } else if (TIER_SISTER_MEDIUM.equals(tier)) {
            personaJsonSisterMedium = value;
        } else if (TIER_SISTER_HIGH.equals(tier)) {
            personaJsonSisterHigh = value;
        }
    }

    public List<PersonaProfile> getPersonaProfiles() {
        return clonePersonaProfiles(personaProfiles);
    }

    public PersonaProfile getActivePersonaProfile() {
        return findPersonaProfile(activePersonaProfileId);
    }

    public void replacePersonaProfiles(List<PersonaProfile> profiles, String activeProfileId) {
        personaProfiles = sanitizePersonaProfiles(profiles);
        activePersonaProfileId = safeString(activeProfileId, DEFAULT_PERSONA_PROFILE_ID);
        ensureDefaults();
    }

    private PersonaProfile getOrCreateActivePersonaProfile() {
        PersonaProfile activeProfile = getActivePersonaProfile();
        if (activeProfile != null) {
            return activeProfile;
        }
        PersonaProfile created = buildProfileFromLegacy(DEFAULT_PERSONA_PROFILE_ID, DEFAULT_PERSONA_PROFILE_NAME);
        personaProfiles = sanitizePersonaProfiles(personaProfiles);
        personaProfiles.add(created);
        activePersonaProfileId = created.id;
        return created;
    }

    private PersonaProfile findPersonaProfile(String profileId) {
        if (personaProfiles == null || TextUtils.isEmpty(profileId)) {
            return null;
        }
        for (PersonaProfile profile : personaProfiles) {
            if (profile != null && TextUtils.equals(profile.id, profileId)) {
                return profile;
            }
        }
        return null;
    }

    private PersonaProfile buildProfileFromLegacy(String profileId, String profileName) {
        PersonaProfile profile = new PersonaProfile(profileId, profileName);
        profile.setTierJson(TIER_SISTER_NULL, coalesce(personaJsonSisterNull, builtinPersonaJsonSisterNull));
        profile.setTierJson(TIER_SISTER_VERYLOW, coalesce(personaJsonSisterVerylow, builtinPersonaJsonSisterVerylow));
        profile.setTierJson(TIER_SISTER_LOW, coalesce(personaJsonSisterLow, builtinPersonaJsonSisterLow));
        profile.setTierJson(TIER_SISTER_MEDIUM, coalesce(personaJsonSisterMedium, builtinPersonaJsonSisterMedium));
        profile.setTierJson(TIER_SISTER_HIGH, coalesce(personaJsonSisterHigh, builtinPersonaJsonSisterHigh));
        return profile;
    }

    private void syncLegacyPersonaFields(PersonaProfile activeProfile) {
        if (activeProfile == null) {
            return;
        }
        personaJsonSisterNull = coalesce(activeProfile.getTierJson(TIER_SISTER_NULL), builtinPersonaJsonSisterNull);
        personaJsonSisterVerylow = coalesce(activeProfile.getTierJson(TIER_SISTER_VERYLOW), builtinPersonaJsonSisterVerylow);
        personaJsonSisterLow = coalesce(activeProfile.getTierJson(TIER_SISTER_LOW), builtinPersonaJsonSisterLow);
        personaJsonSisterMedium = coalesce(activeProfile.getTierJson(TIER_SISTER_MEDIUM), builtinPersonaJsonSisterMedium);
        personaJsonSisterHigh = coalesce(activeProfile.getTierJson(TIER_SISTER_HIGH), builtinPersonaJsonSisterHigh);
    }

    private ArrayList<PersonaProfile> sanitizePersonaProfiles(List<PersonaProfile> source) {
        ArrayList<PersonaProfile> sanitized = new ArrayList<>();
        Map<String, Integer> usedIds = new LinkedHashMap<>();
        if (source == null) {
            return sanitized;
        }
        for (PersonaProfile rawProfile : source) {
            if (rawProfile == null) {
                continue;
            }
            String baseId = safeString(rawProfile.id, "");
            if (TextUtils.isEmpty(baseId)) {
                baseId = "profile";
            }
            int usedCount = usedIds.containsKey(baseId) ? usedIds.get(baseId) + 1 : 1;
            usedIds.put(baseId, usedCount);
            String finalId = usedCount == 1 ? baseId : baseId + "_" + usedCount;
            String finalName = safeString(rawProfile.name, DEFAULT_PERSONA_PROFILE_NAME + " " + sanitized.size());
            PersonaProfile profile = new PersonaProfile(finalId, finalName);
            for (String tier : PERSONA_TIERS) {
                String value = rawProfile.getTierJson(tier);
                if (TextUtils.isEmpty(value)) {
                    value = getBuiltinPersonaJson(tier);
                }
                profile.setTierJson(tier, value);
            }
            sanitized.add(profile);
        }
        return sanitized;
    }

    private static ArrayList<PersonaProfile> clonePersonaProfiles(List<PersonaProfile> source) {
        ArrayList<PersonaProfile> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (PersonaProfile profile : source) {
            if (profile != null) {
                copy.add(profile.copy());
            }
        }
        return copy;
    }

    private static ArrayList<PersonaProfile> parsePersonaProfiles(String rawJson) {
        ArrayList<PersonaProfile> profiles = new ArrayList<>();
        if (TextUtils.isEmpty(rawJson)) {
            return profiles;
        }
        try {
            JSONArray array = new JSONArray(rawJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                PersonaProfile profile = new PersonaProfile(
                        item.optString("id", ""),
                        item.optString("name", "")
                );
                JSONObject tiers = item.optJSONObject("tiers");
                for (String tier : PERSONA_TIERS) {
                    profile.setTierJson(tier, tiers == null ? "" : tiers.optString(tier, ""));
                }
                profiles.add(profile);
            }
        } catch (Exception ignored) {
            profiles.clear();
        }
        return profiles;
    }

    private static String serializePersonaProfiles(List<PersonaProfile> profiles) {
        JSONArray array = new JSONArray();
        if (profiles == null) {
            return array.toString();
        }
        for (PersonaProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            try {
                JSONObject tiers = new JSONObject();
                for (String tier : PERSONA_TIERS) {
                    tiers.put(tier, safeString(profile.getTierJson(tier), ""));
                }
                JSONObject item = new JSONObject();
                item.put("id", safeString(profile.id, ""));
                item.put("name", safeString(profile.name, ""));
                item.put("tiers", tiers);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public static final class PersonaProfile {
        public String id;
        public String name;
        public LinkedHashMap<String, String> tierJsonMap = new LinkedHashMap<>();

        public PersonaProfile(String id, String name) {
            this.id = safeString(id, "");
            this.name = safeString(name, "");
        }

        public String getTierJson(String tier) {
            return tierJsonMap.containsKey(tier) ? safeString(tierJsonMap.get(tier), "") : "";
        }

        public void setTierJson(String tier, String rawJson) {
            if (!TextUtils.isEmpty(tier)) {
                tierJsonMap.put(tier, safeString(rawJson, ""));
            }
        }

        public PersonaProfile copy() {
            PersonaProfile copy = new PersonaProfile(id, name);
            for (Map.Entry<String, String> entry : tierJsonMap.entrySet()) {
                copy.tierJsonMap.put(entry.getKey(), safeString(entry.getValue(), ""));
            }
            return copy;
        }
    }

    public static String normalizePath(String raw) {
        String value = safeString(raw, "/");
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static List<String> normalizePaths(String rawText, String fallbackText) {
        String source = TextUtils.isEmpty(rawText) ? fallbackText : rawText;
        List<String> paths = new ArrayList<>();
        if (TextUtils.isEmpty(source)) {
            return paths;
        }
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = normalizePath(trimmed);
            if (!paths.contains(normalized)) {
                paths.add(normalized);
            }
        }
        return paths;
    }

    public static String readAssetText(Context context, String assetPath) {
        if (context == null || TextUtils.isEmpty(assetPath)) {
            return "";
        }
        try (InputStream inputStream = context.getAssets().open(assetPath);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ignored) {
            return "";
        }
    }

    private static int getInt(SharedPreferences sp, String key, int fallback) {
        try {
            return sp.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            try {
                return Integer.parseInt(String.valueOf(sp.getAll().get(key)));
            } catch (Exception ignoredAgain) {
                return fallback;
            }
        }
    }

    private static String safeString(String text, String fallback) {
        return TextUtils.isEmpty(text) ? fallback : text.trim();
    }

    private static String coalesce(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? safeString(fallback, "") : primary;
    }

    private static String normalizeStrictness(String value) {
        String normalized = safeString(value, DEFAULT_STRICTNESS).toLowerCase(Locale.ROOT);
        if (!"strict".equals(normalized) && !"balanced".equals(normalized) && !"relaxed".equals(normalized)) {
            return DEFAULT_STRICTNESS;
        }
        return normalized;
    }

    private static String normalizeAdapterPreset(String value) {
        String normalized = safeString(value, ADAPTER_OPENAI_COMPATIBLE).toLowerCase(Locale.ROOT);
        if (LEGACY_ADAPTER_DEEPSEEK.equals(normalized)
                || LEGACY_ADAPTER_OPENAI_CHAT.equals(normalized)) {
            return ADAPTER_OPENAI_COMPATIBLE;
        }
        if (ADAPTER_OPENAI_COMPATIBLE.equals(normalized)
                || ADAPTER_CLAUDE_MESSAGES.equals(normalized)
                || ADAPTER_OPENAI_RESPONSES.equals(normalized)
                || ADAPTER_GEMINI_GENERATE_CONTENT.equals(normalized)
                || ADAPTER_GENERIC_CUSTOM.equals(normalized)) {
            return normalized;
        }
        return ADAPTER_OPENAI_COMPATIBLE;
    }

    private static String normalizeUpstreamProxyType(String value, String host, int port) {
        String normalized = safeString(value, "").toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(normalized)) {
            return !TextUtils.isEmpty(safeString(host, "")) && port > 0
                    ? UPSTREAM_PROXY_HTTP
                    : DEFAULT_UPSTREAM_PROXY_TYPE;
        }
        if ("socks".equals(normalized)) {
            return UPSTREAM_PROXY_SOCKS5;
        }
        if (UPSTREAM_PROXY_DIRECT.equals(normalized)
                || UPSTREAM_PROXY_HTTP.equals(normalized)
                || UPSTREAM_PROXY_SOCKS5.equals(normalized)) {
            return normalized;
        }
        return !TextUtils.isEmpty(safeString(host, "")) && port > 0
                ? UPSTREAM_PROXY_HTTP
                : DEFAULT_UPSTREAM_PROXY_TYPE;
    }
}
