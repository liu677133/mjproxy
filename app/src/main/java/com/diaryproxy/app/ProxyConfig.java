package com.diaryproxy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProxyConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String PREF_NAME = "diary_proxy_config";
    public static final String SECURE_PREF_NAME = "diary_proxy_secure";
    private static final String SECURE_KEY_API_KEY = "apiKey";
    private static final String LEGACY_KEY_API_KEY = "apiKey";
    private static final String TAG = "ProxyConfig";

    public static final String TIER_SISTER_NULL = "sister-null";
    public static final String TIER_SISTER_VERYLOW = "sister-verylow";
    public static final String TIER_SISTER_LOW = "sister-low";
    public static final String TIER_SISTER_MEDIUM = "sister-medium";
    public static final String TIER_SISTER_HIGH = "sister-high";
    public static final String TIER_SISTER_KEMONOMIMI = "sister-kemonomimi";
    public static final String TIER_SISTER_KEMONOMIMI_CAT = "sister-kemonomimi-cat";
    public static final String TIER_SISTER_TUTOR = "sister-tutor";
    public static final String TIER_SISTER_DILEI = "sister-dilei";
    public static final String TIER_SISTER_KINDERGARTEN = "sister-kindergarten";
    public static final String DEFAULT_PERSONA_PROFILE_ID = "default";
    public static final String DEFAULT_PERSONA_PROFILE_NAME = "默认方案";
    public static final String DEFAULT_GLOBAL_PERSONA_PROFILE_ID = "global_default";
    public static final String DEFAULT_GLOBAL_PERSONA_PROFILE_NAME = "默认全局人设";
    public static final String DEFAULT_PROVIDER_ID = "default_provider";
    public static final String DEFAULT_PROVIDER_NAME = "默认提供商";
    public static final String DEFAULT_MODEL_ID = "default_model";

    public static final String ADAPTER_OPENAI_COMPATIBLE = "openai_compatible";
    public static final String ADAPTER_CLAUDE_MESSAGES = "claude_messages";
    public static final String ADAPTER_OPENAI_RESPONSES = "openai_responses";
    public static final String ADAPTER_GEMINI_GENERATE_CONTENT = "gemini_generate_content";
    public static final String ADAPTER_GENERIC_CUSTOM = "generic_custom";

    public static final String LEGACY_ADAPTER_DEEPSEEK = "deepseek";
    public static final String LEGACY_ADAPTER_OPENAI_CHAT = "openai_chat";

    /** v1.5.6+：debug 提示词导出文件的两个详细程度档位。 */
    public static final String DEBUG_PROMPT_LEVEL_VERBOSE = "verbose";
    public static final String DEBUG_PROMPT_LEVEL_SIMPLIFIED = "simplified";

    public static final List<String> PERSONA_TIERS = Arrays.asList(
            TIER_SISTER_NULL,
            TIER_SISTER_VERYLOW,
            TIER_SISTER_LOW,
            TIER_SISTER_MEDIUM,
            TIER_SISTER_HIGH,
            TIER_SISTER_KEMONOMIMI,
            TIER_SISTER_KEMONOMIMI_CAT,
            TIER_SISTER_TUTOR,
            TIER_SISTER_DILEI,
            TIER_SISTER_KINDERGARTEN
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
    public static final String DEFAULT_REQUEST_TEMPERATURE_PATH = "temperature";
    public static final String DEFAULT_REQUEST_ENABLE_THINKING_PATH = "enable_thinking";
    public static final String DEFAULT_RESPONSE_TEXT_PATH = "choices[0].message.content";
    public static final String DEFAULT_FEEDBACK_WEBHOOK_URL = "https://2178281.xyz/api/feedback";
    public static final String DEFAULT_UPDATE_MANIFEST_URLS =
            "https://2178281.xyz/update.json\n"
                    + "https://raw.githubusercontent.com/liu677133/mjproxy/main/update.json";
    public static final String DEFAULT_UPDATE_MANIFEST_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAltFjKUTKXJN5D05r5+qn0NF0BVoMs6RJm1GEfe/zpxqtAQ+5H1HajoffgvLRBjzjHGzCHwus/UfcXO42fS698dtPo+sFadstcJgei/eH0Zr5n3e86JNU36UBN423nHWTbIR8BBeL/IsS5ix5lwbp5FWeQX4ke7taxXG1mOSX8gRbwMXvMpn8BbyhhQ5Oo08qHIGB4OJLfIQLpjuTSmX4klmbNWuLB6VpoppyYnvAttlmeJZcgCxJWsnsIvUDGPMr8vNntJm14KGRADOKI6opMyA1iVXXXpUMl+i4ZD+3d92NQlC/HOiTbDO8ut1z6sf2s0z2GwQhGG5P+8Jfyp11DwIDAQAB";
    public static final int DEFAULT_MEMORY_EXTRACT_MAX_TOKENS = 1024;
    public static final String DEFAULT_MEMORY_EXTRACT_TEMPERATURE = "0.3";
    public static final String DEFAULT_PREFIXES = "现在请你切换到你所扮演的角色——妹妹【Yuki】的视角。\n(system:现在请你以你扮演的妹系角色的视角";
    public static final String DEFAULT_KEYWORDS = "写成一篇\n【日记】\n不要记录日期\n特别的日记\n日记主题\n今天是";
    public static final String DEFAULT_PERSONA_MATCH_KEYWORDS = "你是yuki\n性格特征\n场景设定\n重要提示";
    public static final String DEFAULT_INTERACTIVE_STORY_KEYWORDS = "互动式短剧生成器\n小剧场\nstory_end\n剧情生成规则\n角色人设";
    public static final String DEFAULT_NORMAL_TEMPLATE =
            "(system:现在请你以你扮演的妹系角色的视角，以第一人称的口吻，用你所扮演的妹系角色的的语气和思维，把刚才你和${genderTerm}之间的全部对话内容，写成一篇\"日记\"记录。\n\n"
                    + "要求：\n"
                    + "1.用自然、贴近你（妹妹系角色）性格的语言，不要像AI总结报告。\n"
                    + "2.记录对话中让你（妹妹系角色）印象深刻的事情、感受和情绪。\n"
                    + "3.允许适度加入内心独白。\n"
                    + "4.不要逐字复述对话，要像妹妹的真实日记那样有个人感受和小情绪。\n"
                    + "5.日记在描述${genderTerm}时，不要增加${genderTerm}在对话中没说过的事情。\n"
                    + "6.用【日记】作为开头标题，但不要记录日期。\n"
                    + "7.日记内容不要太长，字数100字到1000字之间)";
    public static final String DEFAULT_HOLIDAY_TEMPLATE =
            "现在请你切换到你所扮演的角色——妹妹【Yuki】的视角。\n"
                    + "今天是${occasion}，请以第一人称写一篇特别的日记（80-120字）。\n"
                    + "日记主题：${theme}\n\n"
                    + "要求：\n"
                    + "1. 体现Yuki对${genderTerm}的感情\n"
                    + "2. 包含对${occasion}的感想\n"
                    + "3. 语气要符合Yuki的性格\n"
                    + "4. 不要使用标签格式";

    public static final String DEFAULT_MEMORY_EXTRACT_TEMPLATE =
            "你是Yuki，然后现在你需要处理长期记忆相关的事情，你需要从以下对话记录中提取所有对Yuki有长期记忆价值的关键事实。\n"
                    + "你的性格特征：${personality}\n\n"
                    + "规则：\n"
                    + "1. 每条事实是独立完整的陈述句（15-60字）\n"
                    + "2. 主语固定：玩家用\"${genderTerm}\"，我用\"Yuki\"\n"
                    + "3. 重点提取有价值内容：偏好/厌恶、约定/承诺、情感表达、以及从你的角度觉得很重要或者需要特别记忆的事件，其它的可以简要提取但不需要太详细\n"
                    + "4. 问候、无实质内容直接忽略，这个属于无价值内容\n"
                    + "5. 有价值内容全部输出，不限条数；没有有价值内容就不要输出关于这个事件的数组\n\n"
                    + "对话记录：\n"
                    + "${conversation}\n\n"
                    + "只输出 JSON 数组，例如：[\"Yuki喜欢草莓味冰淇淋\", \"${genderTerm}答应周末陪Yuki去游乐园\"]";

    public String upstreamBaseUrl = DEFAULT_BASE_URL;
    public String apiKey = "";
    public String model = "";
    public String activeProviderId = DEFAULT_PROVIDER_ID;
    public ArrayList<ProviderProfile> providerProfiles = new ArrayList<>();
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
    public String personaMatchKeywordsText = DEFAULT_PERSONA_MATCH_KEYWORDS;
    public String interactiveStoryKeywordsText = DEFAULT_INTERACTIVE_STORY_KEYWORDS;

    public String normalTemplate = DEFAULT_NORMAL_TEMPLATE;
    public String holidayTemplate = DEFAULT_HOLIDAY_TEMPLATE;
    public int overrideMaxTokens = 2500;
    public int chatOverrideMaxTokens = 1500;
    public String chatOverrideTemperature = "0.9";
    public String chatOverrideEnableThinking = "false";
    public String diaryOverrideTemperature = "0.9";
    public String diaryOverrideEnableThinking = "false";
    // v1.5.x 新增：日记请求 assistant 前缀续写（仅日记，不影响普通聊天/长期记忆/互动小剧场）
    public boolean diaryAssistantPrefixEnabled = false;
    public String diaryAssistantPrefix = "【日记】";
    public boolean diaryAssistantPrefixDeepseekMode = false;
    // v1.5.x：硅基流动续写兼容——把前缀作为顶层 prefix 字段（extra_body 在 SDK 里就是把字段塞到 body 顶层），
    // messages 末尾不放 assistant 消息。仅对 OpenAI 兼容 adapter 生效，与 DeepSeek 模式互斥。
    public boolean diaryAssistantPrefixSiliconflowMode = false;
    public String chatCustomRequestFieldsJson = "";
    public String diaryCustomRequestFieldsJson = "";
    public String memoryExtractCustomRequestFieldsJson = "";
    public String memoryExtractTemplate = DEFAULT_MEMORY_EXTRACT_TEMPLATE;
    public int memoryExtractOverrideMaxTokens = DEFAULT_MEMORY_EXTRACT_MAX_TOKENS;
    public String memoryExtractOverrideTemperature = DEFAULT_MEMORY_EXTRACT_TEMPERATURE;
    public String memoryExtractOverrideEnableThinking = "false";
    public int maxChars = DEFAULT_MAX_CHARS;
    public boolean detectionEnabled = true;
    public boolean dryRun = false;
    public boolean truncateEnabled = false;
    public boolean saveNormalChatHistory = false;
    public boolean saveDiaryHistory = false;
    public boolean debugPromptDumpEnabled = false;
    /**
     * v1.5.6+：调试提示词导出文件的详细程度。
     * <ul>
     *   <li>{@link #DEBUG_PROMPT_LEVEL_VERBOSE}（默认）：完整保留改写前 system / user / 请求体 +
     *       改写后 system / user + 最终发送请求体 + 工具调用记录。</li>
     *   <li>{@link #DEBUG_PROMPT_LEVEL_SIMPLIFIED}：去掉「改写前」「改写后」5 个 system/user/请求体块，
     *       仅保留头部元信息 + 最终发送请求体 + 工具调用记录。便于"只看模型实际收到了什么"。</li>
     * </ul>
     */
    public String debugPromptDetailLevel = DEBUG_PROMPT_LEVEL_VERBOSE;
    public boolean stripEnableThinkingEnabled = false;
    public boolean stripRestrictionLineEnabled = false;
    public boolean stripSystemTimeEnabled = false;

    /**
     * v1.5.4+：DPS-8 — 文档处理（pdf/txt/md/csv 附件转纯文本注入）。
     * documentTruncationEnabled=true：超长文档截断到 documentTruncationMaxChars 字符 + "[已省略 N 字]" 提示。
     * documentTruncationEnabled=false：完整注入（用户自负 token 风险）。
     * 默认沿用 v1.5.x 的 4096 上限 + 截断。
     */
    public boolean documentTruncationEnabled = true;
    public int documentTruncationMaxChars = 4096;

    // v1.5.0 新增：附件 / 多模态 / 副模型 / 联网搜索（per-provider，下面字段从 active provider 镜像）
    public String multimodalCapability = "auto";          // "auto" | "yes" | "no"
    public String captionProviderId = "";                 // 副模型 provider id，空=不启用
    public String captionStrategy = "inject";             // "inject"(A) | "tool"(B) | "off"
    public int captionMaxImagesPerRequest = 4;
    /**
     * v1.5.4+：DPS-6 — 副模型 A 方案（inject）并发度。默认 1（保守串行）。
     * 用户根据副模型 provider 的 rate-limit / TPM 自行调整。值<=1 时退化为串行。
     */
    public int captionConcurrency = 1;
    /**
     * v1.5.5+：CS-4 — 副模型 max_tokens（OpenAI 兼容 body 字段）。默认 1024。
     * thinking 副模型（GLM-4.5V / DeepSeek-V4 / Qwen3-VL-Thinking 等）reasoning 段会消耗 200~600 token，
     * 用户可调到 2048+ 避免 finish_reason=length。
     */
    public int captionMaxTokens = 1024;
    /**
     * v1.5.5+：CS-3 — 副模型图像格式三态 quirk。
     * 三个字段独立：detail 字段 / 图文顺序 / image_url 形式。
     * "auto" = 走 CaptionSupport 启发式（substring 匹配 model name）；"on" / "off" = 用户强制。
     */
    public String captionImageDetailMode = "auto";        // "auto" | "on" | "off"
    public String captionImagePlacementMode = "auto";     // "auto" | "image_first" | "text_first"
    public String captionImageUrlFormat = "auto";         // "auto" | "data_url" | "raw_base64"
    public String webSearchToolEnabled = "fallback";      // "off" | "always" | "fallback"
    public String webSearchProvider = "bochaai";          // v1.5.4+：默认 bochaai，原 "auto" 已弃用（详见 BUG_TRACKING_1.5.4.md）。"tavily"|"serper"|"bochaai"|"qianfan_ai_search"|"volcengine_web_search"|"bing_cn"|"duckduckgo_html"
    public String webSearchApiKey = "";
    public String webSearchApiKeysJson = "";
    public String webSearchEndpoint = "";                 // 用户自定义端点，空=用默认
    public String webSearchEndpointsJson = "";            // v1.5.1+：per-engine 端点
    public int webSearchMaxResults = 5;
    public String webSearchProxyType = DEFAULT_UPSTREAM_PROXY_TYPE;
    public String webSearchProxyHost = DEFAULT_UPSTREAM_PROXY_HOST;
    public int webSearchProxyPort = DEFAULT_UPSTREAM_PROXY_PORT;
    public String webSearchProxiesJson = "";              // v1.5.1+：per-engine 出站代理 JSON

    public String listenChatPaths = DEFAULT_LISTEN_CHAT_PATHS;
    public String listenModelsPaths = DEFAULT_LISTEN_MODELS_PATHS;
    public String upstreamChatPath = DEFAULT_UPSTREAM_CHAT_PATH;
    public String upstreamModelsPath = DEFAULT_UPSTREAM_MODELS_PATH;

    public String adapterPreset = ADAPTER_OPENAI_COMPATIBLE;
    public String requestMessagesPath = DEFAULT_REQUEST_MESSAGES_PATH;
    public String requestUserTextPath = DEFAULT_REQUEST_USER_TEXT_PATH;
    public String requestModelPath = DEFAULT_REQUEST_MODEL_PATH;
    public String requestMaxTokensPath = DEFAULT_REQUEST_MAX_TOKENS_PATH;
    public String requestTemperaturePath = DEFAULT_REQUEST_TEMPERATURE_PATH;
    public String requestEnableThinkingPath = DEFAULT_REQUEST_ENABLE_THINKING_PATH;
    public String responseTextPath = DEFAULT_RESPONSE_TEXT_PATH;
    public String feedbackWebhookUrl = DEFAULT_FEEDBACK_WEBHOOK_URL;
    public String updateManifestUrls = DEFAULT_UPDATE_MANIFEST_URLS;
    public String updateManifestPublicKey = DEFAULT_UPDATE_MANIFEST_PUBLIC_KEY;

    public boolean personaEnabled = true;
    public boolean personaIgnoreAffinityEnabled = false;
    public String activePersonaProfileId = DEFAULT_PERSONA_PROFILE_ID;
    public ArrayList<PersonaProfile> personaProfiles = new ArrayList<>();
    public String activeGlobalPersonaProfileId = DEFAULT_GLOBAL_PERSONA_PROFILE_ID;
    public ArrayList<GlobalPersonaProfile> globalPersonaProfiles = new ArrayList<>();
    public String personaJsonSisterNull = "";
    public String personaJsonSisterVerylow = "";
    public String personaJsonSisterLow = "";
    public String personaJsonSisterMedium = "";
    public String personaJsonSisterHigh = "";
    public String personaJsonSisterKemonomimi = "";
    public String personaJsonSisterKemonomimiCat = "";
    public String personaJsonSisterTutor = "";
    public String personaJsonSisterDilei = "";
    public String personaJsonSisterKindergarten = "";
    public String globalPersonaJson = "";

    String builtinPersonaJsonSisterNull = "";
    String builtinPersonaJsonSisterVerylow = "";
    String builtinPersonaJsonSisterLow = "";
    String builtinPersonaJsonSisterMedium = "";
    String builtinPersonaJsonSisterHigh = "";
    String builtinPersonaJsonSisterKemonomimi = "";
    String builtinPersonaJsonSisterKemonomimiCat = "";
    String builtinPersonaJsonSisterTutor = "";
    String builtinPersonaJsonSisterDilei = "";
    String builtinPersonaJsonSisterKindergarten = "";

    public ProxyConfig ensureDefaults() {
        upstreamBaseUrl = safeString(upstreamBaseUrl, DEFAULT_BASE_URL);
        apiKey = safeString(apiKey, "");
        model = normalizeOptionalText(model);
        port = port > 0 ? port : DEFAULT_PORT;
        documentTruncationMaxChars = documentTruncationMaxChars > 0 ? documentTruncationMaxChars : 4096;
        captionMaxTokens = captionMaxTokens > 0 ? captionMaxTokens : 1024;
        captionImageDetailMode = normalizeCaptionImageDetailMode(captionImageDetailMode);
        captionImagePlacementMode = normalizeCaptionImagePlacementMode(captionImagePlacementMode);
        captionImageUrlFormat = normalizeCaptionImageUrlFormat(captionImageUrlFormat);
        timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        upstreamProxyType = normalizeUpstreamProxyType(upstreamProxyType, upstreamProxyHost, upstreamProxyPort);
        upstreamProxyHost = safeString(upstreamProxyHost, DEFAULT_UPSTREAM_PROXY_HOST);
        upstreamProxyPort = upstreamProxyPort > 0 ? upstreamProxyPort : DEFAULT_UPSTREAM_PROXY_PORT;

        strictness = normalizeStrictness(strictness);
        minContentLength = minContentLength > 0 ? minContentLength : DEFAULT_MIN_CONTENT_LENGTH;
        minDialogueLines = minDialogueLines >= 0 ? minDialogueLines : DEFAULT_MIN_DIALOGUE_LINES;
        prefixesText = safeString(prefixesText, DEFAULT_PREFIXES);
        keywordsText = safeString(keywordsText, DEFAULT_KEYWORDS);
        personaMatchKeywordsText = safeString(personaMatchKeywordsText, DEFAULT_PERSONA_MATCH_KEYWORDS);
        interactiveStoryKeywordsText = safeString(interactiveStoryKeywordsText, DEFAULT_INTERACTIVE_STORY_KEYWORDS);

        normalTemplate = safeString(normalTemplate, DEFAULT_NORMAL_TEMPLATE);
        holidayTemplate = safeString(holidayTemplate, DEFAULT_HOLIDAY_TEMPLATE);
        overrideMaxTokens = Math.max(0, overrideMaxTokens);
        chatOverrideMaxTokens = Math.max(0, chatOverrideMaxTokens);
        chatOverrideTemperature = normalizeOptionalText(chatOverrideTemperature);
        chatOverrideEnableThinking = normalizeOptionalBooleanText(chatOverrideEnableThinking);
        diaryOverrideTemperature = normalizeOptionalText(diaryOverrideTemperature);
        diaryOverrideEnableThinking = normalizeOptionalBooleanText(diaryOverrideEnableThinking);
        diaryAssistantPrefix = safeString(diaryAssistantPrefix, "【日记】");
        chatCustomRequestFieldsJson = normalizeOptionalText(chatCustomRequestFieldsJson);
        diaryCustomRequestFieldsJson = normalizeOptionalText(diaryCustomRequestFieldsJson);
        memoryExtractCustomRequestFieldsJson = normalizeOptionalText(memoryExtractCustomRequestFieldsJson);
        memoryExtractTemplate = safeString(memoryExtractTemplate, DEFAULT_MEMORY_EXTRACT_TEMPLATE);
        memoryExtractOverrideMaxTokens = Math.max(0, memoryExtractOverrideMaxTokens);
        memoryExtractOverrideTemperature = normalizeOptionalText(memoryExtractOverrideTemperature);
        memoryExtractOverrideEnableThinking = normalizeOptionalBooleanText(memoryExtractOverrideEnableThinking);
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
        requestTemperaturePath = safeString(requestTemperaturePath, DEFAULT_REQUEST_TEMPERATURE_PATH);
        requestEnableThinkingPath = safeString(requestEnableThinkingPath, DEFAULT_REQUEST_ENABLE_THINKING_PATH);
        responseTextPath = safeString(responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);
        feedbackWebhookUrl = defaultIfNull(feedbackWebhookUrl, DEFAULT_FEEDBACK_WEBHOOK_URL);
        updateManifestUrls = defaultIfNull(updateManifestUrls, DEFAULT_UPDATE_MANIFEST_URLS);
        updateManifestPublicKey = defaultIfNull(updateManifestPublicKey, DEFAULT_UPDATE_MANIFEST_PUBLIC_KEY);

        providerProfiles = sanitizeProviderProfiles(providerProfiles);
        if (providerProfiles.isEmpty()) {
            providerProfiles.add(buildProviderFromLegacy(DEFAULT_PROVIDER_ID, DEFAULT_PROVIDER_NAME));
        }
        ProviderProfile activeProvider = findProviderProfile(activeProviderId);
        if (activeProvider == null) {
            activeProvider = providerProfiles.get(0);
            activeProviderId = activeProvider.id;
        }
        if (TextUtils.isEmpty(activeProvider.apiKey) && !TextUtils.isEmpty(apiKey)) {
            activeProvider.apiKey = apiKey;
        }
        syncLegacyProviderFields(activeProvider);

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

        globalPersonaProfiles = sanitizeGlobalPersonaProfiles(globalPersonaProfiles);
        if (globalPersonaProfiles.isEmpty()) {
            globalPersonaProfiles.add(buildGlobalProfileFromLegacy(
                    DEFAULT_GLOBAL_PERSONA_PROFILE_ID,
                    DEFAULT_GLOBAL_PERSONA_PROFILE_NAME,
                    resolveDefaultGlobalPersonaJson()
            ));
        }
        GlobalPersonaProfile activeGlobalProfile = findGlobalPersonaProfile(activeGlobalPersonaProfileId);
        if (activeGlobalProfile == null) {
            activeGlobalProfile = globalPersonaProfiles.get(0);
            activeGlobalPersonaProfileId = activeGlobalProfile.id;
        }
        syncLegacyGlobalPersonaField(activeGlobalProfile);
        return this;
    }

    public ProxyConfig copy() {
        ProxyConfig copy = new ProxyConfig();
        copy.upstreamBaseUrl = upstreamBaseUrl;
        copy.apiKey = apiKey;
        copy.model = model;
        copy.activeProviderId = activeProviderId;
        copy.providerProfiles = cloneProviderProfiles(providerProfiles);
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
        copy.personaMatchKeywordsText = personaMatchKeywordsText;
        copy.interactiveStoryKeywordsText = interactiveStoryKeywordsText;
        copy.normalTemplate = normalTemplate;
        copy.holidayTemplate = holidayTemplate;
        copy.overrideMaxTokens = overrideMaxTokens;
        copy.chatOverrideMaxTokens = chatOverrideMaxTokens;
        copy.chatOverrideTemperature = chatOverrideTemperature;
        copy.chatOverrideEnableThinking = chatOverrideEnableThinking;
        copy.diaryOverrideTemperature = diaryOverrideTemperature;
        copy.diaryOverrideEnableThinking = diaryOverrideEnableThinking;
        copy.diaryAssistantPrefixEnabled = diaryAssistantPrefixEnabled;
        copy.diaryAssistantPrefix = diaryAssistantPrefix;
        copy.diaryAssistantPrefixDeepseekMode = diaryAssistantPrefixDeepseekMode;
        copy.diaryAssistantPrefixSiliconflowMode = diaryAssistantPrefixSiliconflowMode;
        copy.chatCustomRequestFieldsJson = chatCustomRequestFieldsJson;
        copy.diaryCustomRequestFieldsJson = diaryCustomRequestFieldsJson;
        copy.memoryExtractCustomRequestFieldsJson = memoryExtractCustomRequestFieldsJson;
        copy.memoryExtractTemplate = memoryExtractTemplate;
        copy.memoryExtractOverrideMaxTokens = memoryExtractOverrideMaxTokens;
        copy.memoryExtractOverrideTemperature = memoryExtractOverrideTemperature;
        copy.memoryExtractOverrideEnableThinking = memoryExtractOverrideEnableThinking;
        copy.maxChars = maxChars;
        copy.detectionEnabled = detectionEnabled;
        copy.dryRun = dryRun;
        copy.truncateEnabled = truncateEnabled;
        copy.saveNormalChatHistory = saveNormalChatHistory;
        copy.saveDiaryHistory = saveDiaryHistory;
        copy.debugPromptDumpEnabled = debugPromptDumpEnabled;
        copy.debugPromptDetailLevel = normalizeDebugPromptDetailLevel(debugPromptDetailLevel);
        copy.stripEnableThinkingEnabled = stripEnableThinkingEnabled;
        copy.stripRestrictionLineEnabled = stripRestrictionLineEnabled;
        copy.stripSystemTimeEnabled = stripSystemTimeEnabled;
        copy.documentTruncationEnabled = documentTruncationEnabled;
        copy.documentTruncationMaxChars = documentTruncationMaxChars;
        // v1.5.0 新增字段（顶层镜像）
        copy.multimodalCapability = multimodalCapability;
        copy.captionProviderId = captionProviderId;
        copy.captionStrategy = captionStrategy;
        copy.captionMaxImagesPerRequest = captionMaxImagesPerRequest;
        copy.captionConcurrency = captionConcurrency;
        copy.captionMaxTokens = captionMaxTokens;
        copy.captionImageDetailMode = captionImageDetailMode;
        copy.captionImagePlacementMode = captionImagePlacementMode;
        copy.captionImageUrlFormat = captionImageUrlFormat;
        copy.webSearchToolEnabled = webSearchToolEnabled;
        copy.webSearchProvider = webSearchProvider;
        copy.webSearchApiKey = webSearchApiKey;
        copy.webSearchApiKeysJson = webSearchApiKeysJson;
        copy.webSearchEndpoint = webSearchEndpoint;
        copy.webSearchEndpointsJson = webSearchEndpointsJson;
        copy.webSearchMaxResults = webSearchMaxResults;
        copy.webSearchProxyType = webSearchProxyType;
        copy.webSearchProxyHost = webSearchProxyHost;
        copy.webSearchProxyPort = webSearchProxyPort;
        copy.webSearchProxiesJson = webSearchProxiesJson;
        copy.listenChatPaths = listenChatPaths;
        copy.listenModelsPaths = listenModelsPaths;
        copy.upstreamChatPath = upstreamChatPath;
        copy.upstreamModelsPath = upstreamModelsPath;
        copy.adapterPreset = adapterPreset;
        copy.requestMessagesPath = requestMessagesPath;
        copy.requestUserTextPath = requestUserTextPath;
        copy.requestModelPath = requestModelPath;
        copy.requestMaxTokensPath = requestMaxTokensPath;
        copy.requestTemperaturePath = requestTemperaturePath;
        copy.requestEnableThinkingPath = requestEnableThinkingPath;
        copy.responseTextPath = responseTextPath;
        copy.feedbackWebhookUrl = feedbackWebhookUrl;
        copy.updateManifestUrls = updateManifestUrls;
        copy.updateManifestPublicKey = updateManifestPublicKey;
        copy.personaEnabled = personaEnabled;
        copy.personaIgnoreAffinityEnabled = personaIgnoreAffinityEnabled;
        copy.activePersonaProfileId = activePersonaProfileId;
        copy.personaProfiles = clonePersonaProfiles(personaProfiles);
        copy.activeGlobalPersonaProfileId = activeGlobalPersonaProfileId;
        copy.globalPersonaProfiles = cloneGlobalPersonaProfiles(globalPersonaProfiles);
        copy.personaJsonSisterNull = personaJsonSisterNull;
        copy.personaJsonSisterVerylow = personaJsonSisterVerylow;
        copy.personaJsonSisterLow = personaJsonSisterLow;
        copy.personaJsonSisterMedium = personaJsonSisterMedium;
        copy.personaJsonSisterHigh = personaJsonSisterHigh;
        copy.personaJsonSisterKemonomimi = personaJsonSisterKemonomimi;
        copy.personaJsonSisterKemonomimiCat = personaJsonSisterKemonomimiCat;
        copy.personaJsonSisterTutor = personaJsonSisterTutor;
        copy.personaJsonSisterDilei = personaJsonSisterDilei;
        copy.personaJsonSisterKindergarten = personaJsonSisterKindergarten;
        copy.globalPersonaJson = globalPersonaJson;
        copy.builtinPersonaJsonSisterNull = builtinPersonaJsonSisterNull;
        copy.builtinPersonaJsonSisterVerylow = builtinPersonaJsonSisterVerylow;
        copy.builtinPersonaJsonSisterLow = builtinPersonaJsonSisterLow;
        copy.builtinPersonaJsonSisterMedium = builtinPersonaJsonSisterMedium;
        copy.builtinPersonaJsonSisterHigh = builtinPersonaJsonSisterHigh;
        copy.builtinPersonaJsonSisterKemonomimi = builtinPersonaJsonSisterKemonomimi;
        copy.builtinPersonaJsonSisterKemonomimiCat = builtinPersonaJsonSisterKemonomimiCat;
        copy.builtinPersonaJsonSisterTutor = builtinPersonaJsonSisterTutor;
        copy.builtinPersonaJsonSisterDilei = builtinPersonaJsonSisterDilei;
        copy.builtinPersonaJsonSisterKindergarten = builtinPersonaJsonSisterKindergarten;
        return copy.ensureDefaults();
    }

    public static ProxyConfig load(Context context) {
        ProxyConfig cfg = new ProxyConfig();
        cfg.builtinPersonaJsonSisterNull = readAssetText(context, "persona/" + TIER_SISTER_NULL + ".json");
        cfg.builtinPersonaJsonSisterVerylow = readAssetText(context, "persona/" + TIER_SISTER_VERYLOW + ".json");
        cfg.builtinPersonaJsonSisterLow = readAssetText(context, "persona/" + TIER_SISTER_LOW + ".json");
        cfg.builtinPersonaJsonSisterMedium = readAssetText(context, "persona/" + TIER_SISTER_MEDIUM + ".json");
        cfg.builtinPersonaJsonSisterHigh = readAssetText(context, "persona/" + TIER_SISTER_HIGH + ".json");
        cfg.builtinPersonaJsonSisterKemonomimi = coalesce(
                readAssetText(context, "persona/" + TIER_SISTER_KEMONOMIMI + ".json"),
                cfg.builtinPersonaJsonSisterHigh
        );
        cfg.builtinPersonaJsonSisterKemonomimiCat = coalesce(
                readAssetText(context, "persona/" + TIER_SISTER_KEMONOMIMI_CAT + ".json"),
                cfg.builtinPersonaJsonSisterHigh
        );
        cfg.builtinPersonaJsonSisterTutor = coalesce(
                readAssetText(context, "persona/" + TIER_SISTER_TUTOR + ".json"),
                cfg.builtinPersonaJsonSisterHigh
        );
        cfg.builtinPersonaJsonSisterDilei = coalesce(
                readAssetText(context, "persona/" + TIER_SISTER_DILEI + ".json"),
                cfg.builtinPersonaJsonSisterHigh
        );
        cfg.builtinPersonaJsonSisterKindergarten = coalesce(
                readAssetText(context, "persona/" + TIER_SISTER_KINDERGARTEN + ".json"),
                cfg.builtinPersonaJsonSisterHigh
        );

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        cfg.upstreamBaseUrl = safeString(sp.getString("upstreamBaseUrl", cfg.upstreamBaseUrl), cfg.upstreamBaseUrl);
        cfg.apiKey = safeString(readSecureApiKey(context, sp), cfg.apiKey);
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
        cfg.personaMatchKeywordsText = safeString(sp.getString("personaMatchKeywordsText", cfg.personaMatchKeywordsText), cfg.personaMatchKeywordsText);
        cfg.interactiveStoryKeywordsText = safeString(sp.getString("interactiveStoryKeywordsText", cfg.interactiveStoryKeywordsText), cfg.interactiveStoryKeywordsText);
        String legacyTemplate = sp.getString("template", null);
        cfg.normalTemplate = safeString(sp.getString("normalTemplate", legacyTemplate), cfg.normalTemplate);
        cfg.holidayTemplate = safeString(sp.getString("holidayTemplate", cfg.holidayTemplate), cfg.holidayTemplate);
        cfg.overrideMaxTokens = getInt(sp, "overrideMaxTokens", cfg.overrideMaxTokens);
        cfg.chatOverrideMaxTokens = getInt(sp, "chatOverrideMaxTokens", cfg.chatOverrideMaxTokens);
        cfg.chatOverrideTemperature = normalizeOptionalText(sp.getString("chatOverrideTemperature", cfg.chatOverrideTemperature));
        cfg.chatOverrideEnableThinking = normalizeOptionalBooleanText(
                sp.getString("chatOverrideEnableThinking", cfg.chatOverrideEnableThinking)
        );
        cfg.diaryOverrideTemperature = normalizeOptionalText(sp.getString("diaryOverrideTemperature", cfg.diaryOverrideTemperature));
        cfg.diaryOverrideEnableThinking = normalizeOptionalBooleanText(
                sp.getString("diaryOverrideEnableThinking", cfg.diaryOverrideEnableThinking)
        );
        cfg.diaryAssistantPrefixEnabled = sp.getBoolean("diaryAssistantPrefixEnabled", cfg.diaryAssistantPrefixEnabled);
        cfg.diaryAssistantPrefix = safeString(sp.getString("diaryAssistantPrefix", cfg.diaryAssistantPrefix), "【日记】");
        cfg.diaryAssistantPrefixDeepseekMode = sp.getBoolean("diaryAssistantPrefixDeepseekMode", cfg.diaryAssistantPrefixDeepseekMode);
        cfg.diaryAssistantPrefixSiliconflowMode = sp.getBoolean("diaryAssistantPrefixSiliconflowMode", cfg.diaryAssistantPrefixSiliconflowMode);
        cfg.chatCustomRequestFieldsJson = normalizeOptionalText(
                sp.getString("chatCustomRequestFieldsJson", cfg.chatCustomRequestFieldsJson)
        );
        cfg.diaryCustomRequestFieldsJson = normalizeOptionalText(
                sp.getString("diaryCustomRequestFieldsJson", cfg.diaryCustomRequestFieldsJson)
        );
        cfg.memoryExtractCustomRequestFieldsJson = normalizeOptionalText(
                sp.getString("memoryExtractCustomRequestFieldsJson", cfg.memoryExtractCustomRequestFieldsJson)
        );
        cfg.memoryExtractTemplate = safeString(sp.getString("memoryExtractTemplate", cfg.memoryExtractTemplate), cfg.memoryExtractTemplate);
        cfg.memoryExtractOverrideMaxTokens = getInt(sp, "memoryExtractOverrideMaxTokens", cfg.memoryExtractOverrideMaxTokens);
        cfg.memoryExtractOverrideTemperature = normalizeOptionalText(
                sp.getString("memoryExtractOverrideTemperature", cfg.memoryExtractOverrideTemperature)
        );
        cfg.memoryExtractOverrideEnableThinking = normalizeOptionalBooleanText(
                sp.getString("memoryExtractOverrideEnableThinking", cfg.memoryExtractOverrideEnableThinking)
        );
        cfg.maxChars = getInt(sp, "maxChars", cfg.maxChars);
        cfg.detectionEnabled = sp.getBoolean("detectionEnabled", cfg.detectionEnabled);
        cfg.dryRun = sp.getBoolean("dryRun", cfg.dryRun);
        cfg.truncateEnabled = sp.getBoolean("truncateEnabled", cfg.truncateEnabled);
        cfg.saveNormalChatHistory = sp.getBoolean("saveNormalChatHistory", cfg.saveNormalChatHistory);
        cfg.saveDiaryHistory = sp.getBoolean("saveDiaryHistory", cfg.saveDiaryHistory);
        cfg.debugPromptDumpEnabled = sp.getBoolean("debugPromptDumpEnabled", cfg.debugPromptDumpEnabled);
        cfg.debugPromptDetailLevel = normalizeDebugPromptDetailLevel(
                sp.getString("debugPromptDetailLevel", cfg.debugPromptDetailLevel));
        cfg.stripEnableThinkingEnabled = sp.getBoolean("stripEnableThinkingEnabled", cfg.stripEnableThinkingEnabled);
        cfg.stripRestrictionLineEnabled = sp.getBoolean("stripRestrictionLineEnabled", cfg.stripRestrictionLineEnabled);
        cfg.stripSystemTimeEnabled = sp.getBoolean("stripSystemTimeEnabled", cfg.stripSystemTimeEnabled);
        cfg.documentTruncationEnabled = sp.getBoolean("documentTruncationEnabled", cfg.documentTruncationEnabled);
        cfg.documentTruncationMaxChars = getInt(sp, "documentTruncationMaxChars", cfg.documentTruncationMaxChars);
        cfg.captionMaxTokens = getInt(sp, "captionMaxTokens", cfg.captionMaxTokens);
        cfg.captionImageDetailMode = normalizeCaptionImageDetailMode(
                sp.getString("captionImageDetailMode", cfg.captionImageDetailMode));
        cfg.captionImagePlacementMode = normalizeCaptionImagePlacementMode(
                sp.getString("captionImagePlacementMode", cfg.captionImagePlacementMode));
        cfg.captionImageUrlFormat = normalizeCaptionImageUrlFormat(
                sp.getString("captionImageUrlFormat", cfg.captionImageUrlFormat));

        cfg.listenChatPaths = safeString(sp.getString("listenChatPaths", cfg.listenChatPaths), cfg.listenChatPaths);
        cfg.listenModelsPaths = safeString(sp.getString("listenModelsPaths", cfg.listenModelsPaths), cfg.listenModelsPaths);
        cfg.upstreamChatPath = safeString(sp.getString("upstreamChatPath", cfg.upstreamChatPath), cfg.upstreamChatPath);
        cfg.upstreamModelsPath = safeString(sp.getString("upstreamModelsPath", cfg.upstreamModelsPath), cfg.upstreamModelsPath);
        cfg.adapterPreset = safeString(sp.getString("adapterPreset", cfg.adapterPreset), cfg.adapterPreset);
        cfg.requestMessagesPath = safeString(sp.getString("requestMessagesPath", cfg.requestMessagesPath), cfg.requestMessagesPath);
        cfg.requestUserTextPath = safeString(sp.getString("requestUserTextPath", cfg.requestUserTextPath), cfg.requestUserTextPath);
        cfg.requestModelPath = safeString(sp.getString("requestModelPath", cfg.requestModelPath), cfg.requestModelPath);
        cfg.requestMaxTokensPath = safeString(sp.getString("requestMaxTokensPath", cfg.requestMaxTokensPath), cfg.requestMaxTokensPath);
        cfg.requestTemperaturePath = safeString(sp.getString("requestTemperaturePath", cfg.requestTemperaturePath), cfg.requestTemperaturePath);
        cfg.requestEnableThinkingPath = safeString(sp.getString("requestEnableThinkingPath", cfg.requestEnableThinkingPath), cfg.requestEnableThinkingPath);
        cfg.responseTextPath = safeString(sp.getString("responseTextPath", cfg.responseTextPath), cfg.responseTextPath);
        cfg.feedbackWebhookUrl = getOptionalString(sp, "feedbackWebhookUrl", cfg.feedbackWebhookUrl);
        cfg.updateManifestUrls = getOptionalString(sp, "updateManifestUrls", cfg.updateManifestUrls);
        cfg.updateManifestPublicKey = getOptionalString(sp, "updateManifestPublicKey", cfg.updateManifestPublicKey);

        cfg.personaEnabled = sp.getBoolean("personaEnabled", cfg.personaEnabled);
        cfg.personaIgnoreAffinityEnabled = sp.getBoolean("personaIgnoreAffinityEnabled", cfg.personaIgnoreAffinityEnabled);
        cfg.activePersonaProfileId = safeString(sp.getString("activePersonaProfileId", cfg.activePersonaProfileId), cfg.activePersonaProfileId);
        cfg.personaJsonSisterNull = coalesce(sp.getString("personaJsonSisterNull", null), cfg.builtinPersonaJsonSisterNull);
        cfg.personaJsonSisterVerylow = coalesce(sp.getString("personaJsonSisterVerylow", null), cfg.builtinPersonaJsonSisterVerylow);
        cfg.personaJsonSisterLow = coalesce(sp.getString("personaJsonSisterLow", null), cfg.builtinPersonaJsonSisterLow);
        cfg.personaJsonSisterMedium = coalesce(sp.getString("personaJsonSisterMedium", null), cfg.builtinPersonaJsonSisterMedium);
        cfg.personaJsonSisterHigh = coalesce(sp.getString("personaJsonSisterHigh", null), cfg.builtinPersonaJsonSisterHigh);
        cfg.personaJsonSisterKemonomimi = coalesce(sp.getString("personaJsonSisterKemonomimi", null), cfg.builtinPersonaJsonSisterKemonomimi);
        cfg.personaJsonSisterKemonomimiCat = coalesce(sp.getString("personaJsonSisterKemonomimiCat", null), cfg.builtinPersonaJsonSisterKemonomimiCat);
        cfg.personaJsonSisterTutor = coalesce(sp.getString("personaJsonSisterTutor", null), cfg.builtinPersonaJsonSisterTutor);
        cfg.personaJsonSisterDilei = coalesce(sp.getString("personaJsonSisterDilei", null), cfg.builtinPersonaJsonSisterDilei);
        cfg.personaJsonSisterKindergarten = coalesce(sp.getString("personaJsonSisterKindergarten", null), cfg.builtinPersonaJsonSisterKindergarten);
        cfg.personaProfiles = parsePersonaProfiles(sp.getString("personaProfilesJson", null));
        cfg.activeGlobalPersonaProfileId = safeString(
                sp.getString("activeGlobalPersonaProfileId", cfg.activeGlobalPersonaProfileId),
                cfg.activeGlobalPersonaProfileId
        );
        cfg.globalPersonaJson = coalesce(sp.getString("globalPersonaJson", null), cfg.personaJsonSisterHigh);
        cfg.globalPersonaProfiles = parseGlobalPersonaProfiles(sp.getString("globalPersonaProfilesJson", null));
        cfg.activeProviderId = safeString(sp.getString("activeProviderId", cfg.activeProviderId), cfg.activeProviderId);
        cfg.providerProfiles = parseProviderProfiles(sp.getString("providerProfilesJson", null));
        loadProviderApiKeys(context, sp, cfg.providerProfiles, cfg.apiKey);
        migrateLegacyProtocolFieldsToActiveProvider(sp, cfg);
        // v1.5.1+：联网搜索字段独立持久化（不再 per-provider）
        cfg.webSearchToolEnabled = normalizeWebSearchToolEnabled(sp.getString("webSearchToolEnabled", cfg.webSearchToolEnabled));
        cfg.webSearchProvider = normalizeWebSearchProvider(sp.getString("webSearchProvider", cfg.webSearchProvider));
        cfg.webSearchApiKeysJson = normalizeWebSearchApiKeysJson(sp.getString("webSearchApiKeysJson", cfg.webSearchApiKeysJson));
        cfg.webSearchApiKey = getWebSearchApiKey(cfg.webSearchApiKeysJson, cfg.webSearchProvider);
        cfg.webSearchEndpointsJson = normalizeWebSearchEndpointsJson(sp.getString("webSearchEndpointsJson", cfg.webSearchEndpointsJson));
        cfg.webSearchEndpoint = getWebSearchEndpoint(cfg.webSearchEndpointsJson, cfg.webSearchProvider);
        cfg.webSearchMaxResults = getInt(sp, "webSearchMaxResults", cfg.webSearchMaxResults);
        cfg.webSearchProxiesJson = normalizeWebSearchProxiesJson(sp.getString("webSearchProxiesJson", cfg.webSearchProxiesJson));
        WebSearchProxyEntry currentProxy = getWebSearchProxy(cfg.webSearchProxiesJson, cfg.webSearchProvider);
        cfg.webSearchProxyType = currentProxy.type;
        cfg.webSearchProxyHost = currentProxy.host;
        cfg.webSearchProxyPort = currentProxy.port;
        migrateWebSearchToGlobal(sp, cfg);
        return cfg.ensureDefaults();
    }

    /**
     * v1.5.1+ V4 迁移：把 active provider 的 webSearch* 字段提升到 cfg 顶层独立持久化。
     * 老用户首次启动新版时跑一次：从 active provider 读 → 写 cfg 顶层 → 持久化 → 设 flag。
     * 之后联网搜索是全局配置，不再随 provider 切换。
     *
     * <p>v1.5.4+：原 active.webSearchProvider="auto" 路径已不存在（normalizeWebSearchProvider
     * 把 "auto" 兜底成 "bochaai"），并且 withWebSearchApiKey 已删 auto 守卫，因此
     * v1.5.0 老用户的 active.webSearchApiKey 会自动写入 bochaai 槽（解决 PC-1）。
     */
    private static void migrateWebSearchToGlobal(SharedPreferences sp, ProxyConfig cfg) {
        if (sp == null || cfg == null) return;
        if (sp.getBoolean("webSearchGlobalMigratedV4", false)) return;
        ProviderProfile active = cfg.getActiveProviderProfile();
        if (active != null) {
            // 仅当顶层尚未持久化（旧版没存）才用 provider 兜底
            if (TextUtils.isEmpty(sp.getString("webSearchProvider", null))
                    && !TextUtils.isEmpty(active.webSearchProvider)) {
                cfg.webSearchToolEnabled = normalizeWebSearchToolEnabled(active.webSearchToolEnabled);
                // v1.5.5+：PC-2 — 老用户 active.webSearchProvider="auto" 时，原 key 用途不可推断。
                // 把 oldKey 同时写入所有需 key 的 engine 槽（tavily/serper/bochaai/qianfan_ai_search/
                // volcengine_web_search/bing_cn），让用户切换 engine 时仍能直接用原 key——比统一塞到
                // bochaai 槽（导致海外 tavily 用户 401）更稳妥。
                String rawProvider = safeString(active.webSearchProvider, "").toLowerCase(Locale.ROOT);
                cfg.webSearchProvider = normalizeWebSearchProvider(active.webSearchProvider);
                String legacyKey = safeString(active.webSearchApiKey, "");
                String migratedKeysJson = active.webSearchApiKeysJson;
                if ("auto".equals(rawProvider) && !TextUtils.isEmpty(legacyKey)) {
                    for (String engine : WEB_SEARCH_KEY_REQUIRING_ENGINES) {
                        // 仅在该 engine 槽尚未持有 key 时写入，避免覆盖用户已显式配过的 per-engine key
                        if (TextUtils.isEmpty(getWebSearchApiKey(migratedKeysJson, engine))) {
                            migratedKeysJson = withWebSearchApiKey(migratedKeysJson, engine, legacyKey);
                        }
                    }
                } else {
                    migratedKeysJson = withWebSearchApiKey(migratedKeysJson, cfg.webSearchProvider, legacyKey);
                }
                cfg.webSearchApiKeysJson = normalizeWebSearchApiKeysJson(migratedKeysJson);
                cfg.webSearchApiKey = getWebSearchApiKey(cfg.webSearchApiKeysJson, cfg.webSearchProvider);
                cfg.webSearchEndpointsJson = normalizeWebSearchEndpointsJson(withWebSearchEndpoint(
                        cfg.webSearchEndpointsJson, cfg.webSearchProvider, safeString(active.webSearchEndpoint, "")));
                cfg.webSearchEndpoint = getWebSearchEndpoint(cfg.webSearchEndpointsJson, cfg.webSearchProvider);
                cfg.webSearchMaxResults = active.webSearchMaxResults > 0 ? active.webSearchMaxResults : 5;
                cfg.webSearchProxiesJson = normalizeWebSearchProxiesJson(withWebSearchProxy(
                        cfg.webSearchProxiesJson, cfg.webSearchProvider,
                        active.webSearchProxyType, active.webSearchProxyHost, active.webSearchProxyPort));
                WebSearchProxyEntry migratedProxy = getWebSearchProxy(cfg.webSearchProxiesJson, cfg.webSearchProvider);
                cfg.webSearchProxyType = migratedProxy.type;
                cfg.webSearchProxyHost = migratedProxy.host;
                cfg.webSearchProxyPort = migratedProxy.port;
                sp.edit()
                        .putString("webSearchToolEnabled", cfg.webSearchToolEnabled)
                        .putString("webSearchProvider", cfg.webSearchProvider)
                        .putString("webSearchApiKeysJson", cfg.webSearchApiKeysJson)
                        .putString("webSearchEndpointsJson", cfg.webSearchEndpointsJson)
                        .putInt("webSearchMaxResults", cfg.webSearchMaxResults)
                        .putString("webSearchProxiesJson", cfg.webSearchProxiesJson)
                        .apply();
            }
        }
        sp.edit().putBoolean("webSearchGlobalMigratedV4", true).apply();
    }

    /** v1.5.5+：PC-2 — 老用户 auto 模式 key 兜底覆盖的 engine 列表（即所有需 key 的 engine）。 */
    private static final String[] WEB_SEARCH_KEY_REQUIRING_ENGINES = new String[] {
            "tavily", "serper", "bochaai", "qianfan_ai_search", "volcengine_web_search", "bing_cn"
    };

    /**
     * 旧版 cfg 顶层的协议相关参数(自定义请求字段 / enable_thinking 覆盖 / max_tokens / temperature
     * / 转发时移除 enable_thinking 开关)一次性迁移到当前 active provider，让多 provider 切换时不再
     * 串协议。靠 SharedPreferences flag 保证只跑一次。
     */
    private static void migrateLegacyProtocolFieldsToActiveProvider(SharedPreferences sp, ProxyConfig cfg) {
        if (sp == null || cfg == null || cfg.providerProfiles == null || cfg.providerProfiles.isEmpty()) {
            return;
        }
        if (sp.getBoolean("protocolFieldsMigratedV2", false)) {
            return;
        }
        ProviderProfile active = null;
        if (!TextUtils.isEmpty(cfg.activeProviderId)) {
            for (ProviderProfile candidate : cfg.providerProfiles) {
                if (candidate != null && TextUtils.equals(candidate.id, cfg.activeProviderId)) {
                    active = candidate;
                    break;
                }
            }
        }
        if (active == null) {
            active = cfg.providerProfiles.get(0);
        }
        if (active == null) {
            sp.edit().putBoolean("protocolFieldsMigratedV2", true).apply();
            return;
        }
        // 自定义请求字段 JSON：cfg 顶层有值就 dump 进 active 的对应字段
        if (!TextUtils.isEmpty(cfg.chatCustomRequestFieldsJson)) {
            active.chatCustomRequestFieldsJson = cfg.chatCustomRequestFieldsJson;
        }
        if (!TextUtils.isEmpty(cfg.diaryCustomRequestFieldsJson)) {
            active.diaryCustomRequestFieldsJson = cfg.diaryCustomRequestFieldsJson;
        }
        if (!TextUtils.isEmpty(cfg.memoryExtractCustomRequestFieldsJson)) {
            active.memoryExtractCustomRequestFieldsJson = cfg.memoryExtractCustomRequestFieldsJson;
        }
        // enable_thinking 三档：旧版默认是 "false"，迁移后 active 直接覆盖一次
        active.chatOverrideEnableThinking = safeString(cfg.chatOverrideEnableThinking, "false");
        active.diaryOverrideEnableThinking = safeString(cfg.diaryOverrideEnableThinking, "false");
        active.memoryExtractOverrideEnableThinking = safeString(cfg.memoryExtractOverrideEnableThinking, "false");
        // max_tokens / temperature 三档：cfg 顶层值复制过去（cfg.overrideMaxTokens 是 diary 的旧名）
        if (cfg.chatOverrideMaxTokens > 0) {
            active.chatOverrideMaxTokens = cfg.chatOverrideMaxTokens;
        }
        if (!TextUtils.isEmpty(cfg.chatOverrideTemperature)) {
            active.chatOverrideTemperature = cfg.chatOverrideTemperature;
        }
        if (cfg.overrideMaxTokens > 0) {
            active.diaryOverrideMaxTokens = cfg.overrideMaxTokens;
        }
        if (!TextUtils.isEmpty(cfg.diaryOverrideTemperature)) {
            active.diaryOverrideTemperature = cfg.diaryOverrideTemperature;
        }
        if (cfg.memoryExtractOverrideMaxTokens > 0) {
            active.memoryExtractOverrideMaxTokens = cfg.memoryExtractOverrideMaxTokens;
        }
        if (!TextUtils.isEmpty(cfg.memoryExtractOverrideTemperature)) {
            active.memoryExtractOverrideTemperature = cfg.memoryExtractOverrideTemperature;
        }
        // 转发时移除 enable_thinking
        active.stripEnableThinkingEnabled = cfg.stripEnableThinkingEnabled;
        sp.edit().putBoolean("protocolFieldsMigratedV2", true).apply();
    }

    public static void save(Context context, ProxyConfig cfg) {
        ProxyConfig safe = cfg == null ? new ProxyConfig() : cfg.copy();
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putString("upstreamBaseUrl", safe.upstreamBaseUrl);
        editor.remove("apiKey");
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
        editor.putString("personaMatchKeywordsText", safe.personaMatchKeywordsText);
        editor.putString("interactiveStoryKeywordsText", safe.interactiveStoryKeywordsText);
        editor.putString("template", safe.normalTemplate);
        editor.putString("normalTemplate", safe.normalTemplate);
        editor.putString("holidayTemplate", safe.holidayTemplate);
        editor.putInt("overrideMaxTokens", safe.overrideMaxTokens);
        editor.putInt("chatOverrideMaxTokens", safe.chatOverrideMaxTokens);
        editor.putString("chatOverrideTemperature", safe.chatOverrideTemperature);
        editor.putString("chatOverrideEnableThinking", safe.chatOverrideEnableThinking);
        editor.putString("diaryOverrideTemperature", safe.diaryOverrideTemperature);
        editor.putString("diaryOverrideEnableThinking", safe.diaryOverrideEnableThinking);
        editor.putBoolean("diaryAssistantPrefixEnabled", safe.diaryAssistantPrefixEnabled);
        editor.putString("diaryAssistantPrefix", safeString(safe.diaryAssistantPrefix, "【日记】"));
        editor.putBoolean("diaryAssistantPrefixDeepseekMode", safe.diaryAssistantPrefixDeepseekMode);
        editor.putBoolean("diaryAssistantPrefixSiliconflowMode", safe.diaryAssistantPrefixSiliconflowMode);
        editor.putString("chatCustomRequestFieldsJson", safe.chatCustomRequestFieldsJson);
        editor.putString("diaryCustomRequestFieldsJson", safe.diaryCustomRequestFieldsJson);
        editor.putString("memoryExtractCustomRequestFieldsJson", safe.memoryExtractCustomRequestFieldsJson);
        editor.putString("memoryExtractTemplate", safe.memoryExtractTemplate);
        editor.putInt("memoryExtractOverrideMaxTokens", safe.memoryExtractOverrideMaxTokens);
        editor.putString("memoryExtractOverrideTemperature", safe.memoryExtractOverrideTemperature);
        editor.putString("memoryExtractOverrideEnableThinking", safe.memoryExtractOverrideEnableThinking);
        editor.putInt("maxChars", safe.maxChars);
        editor.putBoolean("detectionEnabled", safe.detectionEnabled);
        editor.putBoolean("dryRun", safe.dryRun);
        editor.putBoolean("truncateEnabled", safe.truncateEnabled);
        editor.putBoolean("saveNormalChatHistory", safe.saveNormalChatHistory);
        editor.putBoolean("saveDiaryHistory", safe.saveDiaryHistory);
        editor.putBoolean("debugPromptDumpEnabled", safe.debugPromptDumpEnabled);
        editor.putString("debugPromptDetailLevel", normalizeDebugPromptDetailLevel(safe.debugPromptDetailLevel));
        editor.putBoolean("stripEnableThinkingEnabled", safe.stripEnableThinkingEnabled);
        editor.putBoolean("stripRestrictionLineEnabled", safe.stripRestrictionLineEnabled);
        editor.putBoolean("stripSystemTimeEnabled", safe.stripSystemTimeEnabled);
        editor.putBoolean("documentTruncationEnabled", safe.documentTruncationEnabled);
        editor.putInt("documentTruncationMaxChars", safe.documentTruncationMaxChars > 0 ? safe.documentTruncationMaxChars : 4096);
        editor.putInt("captionMaxTokens", safe.captionMaxTokens > 0 ? safe.captionMaxTokens : 1024);
        editor.putString("captionImageDetailMode", normalizeCaptionImageDetailMode(safe.captionImageDetailMode));
        editor.putString("captionImagePlacementMode", normalizeCaptionImagePlacementMode(safe.captionImagePlacementMode));
        editor.putString("captionImageUrlFormat", normalizeCaptionImageUrlFormat(safe.captionImageUrlFormat));

        editor.putString("listenChatPaths", safe.listenChatPaths);
        editor.putString("listenModelsPaths", safe.listenModelsPaths);
        editor.putString("upstreamChatPath", safe.upstreamChatPath);
        editor.putString("upstreamModelsPath", safe.upstreamModelsPath);
        editor.putString("adapterPreset", safe.adapterPreset);
        editor.putString("requestMessagesPath", safe.requestMessagesPath);
        editor.putString("requestUserTextPath", safe.requestUserTextPath);
        editor.putString("requestModelPath", safe.requestModelPath);
        editor.putString("requestMaxTokensPath", safe.requestMaxTokensPath);
        editor.putString("requestTemperaturePath", safe.requestTemperaturePath);
        editor.putString("requestEnableThinkingPath", safe.requestEnableThinkingPath);
        editor.putString("responseTextPath", safe.responseTextPath);
        editor.putString("feedbackWebhookUrl", safe.feedbackWebhookUrl);
        editor.putString("updateManifestUrls", safe.updateManifestUrls);
        editor.putString("updateManifestPublicKey", safe.updateManifestPublicKey);
        editor.putString("activeProviderId", safe.activeProviderId);
        editor.putString("providerProfilesJson", serializeProviderProfiles(safe.providerProfiles));

        editor.putBoolean("personaEnabled", safe.personaEnabled);
        editor.putBoolean("personaIgnoreAffinityEnabled", safe.personaIgnoreAffinityEnabled);
        editor.putString("activePersonaProfileId", safe.activePersonaProfileId);
        editor.putString("personaProfilesJson", serializePersonaProfiles(safe.personaProfiles));
        editor.putString("activeGlobalPersonaProfileId", safe.activeGlobalPersonaProfileId);
        editor.putString("globalPersonaProfilesJson", serializeGlobalPersonaProfiles(safe.globalPersonaProfiles));
        editor.putString("personaJsonSisterNull", safe.personaJsonSisterNull);
        editor.putString("personaJsonSisterVerylow", safe.personaJsonSisterVerylow);
        editor.putString("personaJsonSisterLow", safe.personaJsonSisterLow);
        editor.putString("personaJsonSisterMedium", safe.personaJsonSisterMedium);
        editor.putString("personaJsonSisterHigh", safe.personaJsonSisterHigh);
        editor.putString("personaJsonSisterKemonomimi", safe.personaJsonSisterKemonomimi);
        editor.putString("personaJsonSisterKemonomimiCat", safe.personaJsonSisterKemonomimiCat);
        editor.putString("personaJsonSisterTutor", safe.personaJsonSisterTutor);
        editor.putString("personaJsonSisterDilei", safe.personaJsonSisterDilei);
        editor.putString("personaJsonSisterKindergarten", safe.personaJsonSisterKindergarten);
        editor.putString("globalPersonaJson", safe.globalPersonaJson);
        // v1.5.1+：联网搜索字段独立全局持久化
        editor.putString("webSearchToolEnabled", normalizeWebSearchToolEnabled(safe.webSearchToolEnabled));
        editor.putString("webSearchProvider", normalizeWebSearchProvider(safe.webSearchProvider));
        editor.putString("webSearchApiKeysJson", normalizeWebSearchApiKeysJson(safe.webSearchApiKeysJson));
        editor.putString("webSearchEndpointsJson", normalizeWebSearchEndpointsJson(safe.webSearchEndpointsJson));
        editor.putInt("webSearchMaxResults", safe.webSearchMaxResults > 0 ? safe.webSearchMaxResults : 5);
        editor.putString("webSearchProxiesJson", normalizeWebSearchProxiesJson(safe.webSearchProxiesJson));
        editor.apply();
        writeSecureApiKey(context, safe.apiKey);
        writeProviderApiKeys(context, safe.providerProfiles);
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

    public String resolvedRequestTemperaturePath() {
        return safeString(requestTemperaturePath, DEFAULT_REQUEST_TEMPERATURE_PATH);
    }

    public String resolvedRequestEnableThinkingPath() {
        return safeString(requestEnableThinkingPath, DEFAULT_REQUEST_ENABLE_THINKING_PATH);
    }

    public String resolvedResponseTextPath() {
        return safeString(responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);
    }

    public String normalizedFeedbackWebhookUrl() {
        return normalizeOptionalText(feedbackWebhookUrl);
    }

    public List<String> getUpdateManifestUrlList() {
        return normalizeOptionalLines(updateManifestUrls);
    }

    public String normalizedUpdateManifestPublicKey() {
        return normalizeOptionalText(updateManifestPublicKey);
    }

    public double resolvedChatOverrideTemperature() {
        return parseOptionalDouble(chatOverrideTemperature);
    }

    public Boolean resolvedChatOverrideEnableThinking() {
        return parseOptionalBoolean(chatOverrideEnableThinking);
    }

    public double resolvedDiaryOverrideTemperature() {
        return parseOptionalDouble(diaryOverrideTemperature);
    }

    public Boolean resolvedDiaryOverrideEnableThinking() {
        return parseOptionalBoolean(diaryOverrideEnableThinking);
    }

    public double resolvedMemoryExtractOverrideTemperature() {
        return parseOptionalDouble(memoryExtractOverrideTemperature);
    }

    public Boolean resolvedMemoryExtractOverrideEnableThinking() {
        return parseOptionalBoolean(memoryExtractOverrideEnableThinking);
    }

    public String getPersonaJson(String tier) {
        if (personaIgnoreAffinityEnabled) {
            String global = getGlobalPersonaJson();
            if (!TextUtils.isEmpty(global)) {
                return global;
            }
        }
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
        if (TIER_SISTER_KEMONOMIMI.equals(tier)) {
            return personaJsonSisterKemonomimi;
        }
        if (TIER_SISTER_KEMONOMIMI_CAT.equals(tier)) {
            return personaJsonSisterKemonomimiCat;
        }
        if (TIER_SISTER_TUTOR.equals(tier)) {
            return personaJsonSisterTutor;
        }
        if (TIER_SISTER_DILEI.equals(tier)) {
            return personaJsonSisterDilei;
        }
        if (TIER_SISTER_KINDERGARTEN.equals(tier)) {
            return personaJsonSisterKindergarten;
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
        if (TIER_SISTER_KEMONOMIMI.equals(tier)) {
            return builtinPersonaJsonSisterKemonomimi;
        }
        if (TIER_SISTER_KEMONOMIMI_CAT.equals(tier)) {
            return builtinPersonaJsonSisterKemonomimiCat;
        }
        if (TIER_SISTER_TUTOR.equals(tier)) {
            return builtinPersonaJsonSisterTutor;
        }
        if (TIER_SISTER_DILEI.equals(tier)) {
            return builtinPersonaJsonSisterDilei;
        }
        if (TIER_SISTER_KINDERGARTEN.equals(tier)) {
            return builtinPersonaJsonSisterKindergarten;
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
        } else if (TIER_SISTER_KEMONOMIMI.equals(tier)) {
            personaJsonSisterKemonomimi = value;
        } else if (TIER_SISTER_KEMONOMIMI_CAT.equals(tier)) {
            personaJsonSisterKemonomimiCat = value;
        } else if (TIER_SISTER_TUTOR.equals(tier)) {
            personaJsonSisterTutor = value;
        } else if (TIER_SISTER_DILEI.equals(tier)) {
            personaJsonSisterDilei = value;
        } else if (TIER_SISTER_KINDERGARTEN.equals(tier)) {
            personaJsonSisterKindergarten = value;
        }
    }

    public String getGlobalPersonaJson() {
        GlobalPersonaProfile activeProfile = getActiveGlobalPersonaProfile();
        if (activeProfile != null && !TextUtils.isEmpty(activeProfile.rawJson)) {
            return activeProfile.rawJson;
        }
        return globalPersonaJson;
    }

    public void setGlobalPersonaJson(String rawJson) {
        String value = safeString(rawJson, "");
        GlobalPersonaProfile activeProfile = getOrCreateActiveGlobalPersonaProfile();
        if (activeProfile != null) {
            activeProfile.rawJson = value;
        }
        globalPersonaJson = value;
    }

    public List<ProviderProfile> getProviderProfiles() {
        return cloneProviderProfiles(providerProfiles);
    }

    public ProviderProfile getActiveProviderProfile() {
        return findProviderProfile(activeProviderId);
    }

    public void replaceProviderProfiles(List<ProviderProfile> profiles, String activeProfileId) {
        providerProfiles = sanitizeProviderProfiles(profiles);
        activeProviderId = safeString(activeProfileId, DEFAULT_PROVIDER_ID);
        ensureDefaults();
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

    public List<GlobalPersonaProfile> getGlobalPersonaProfiles() {
        return cloneGlobalPersonaProfiles(globalPersonaProfiles);
    }

    public GlobalPersonaProfile getActiveGlobalPersonaProfile() {
        return findGlobalPersonaProfile(activeGlobalPersonaProfileId);
    }

    public void replaceGlobalPersonaProfiles(List<GlobalPersonaProfile> profiles, String activeProfileId) {
        globalPersonaProfiles = sanitizeGlobalPersonaProfiles(profiles);
        activeGlobalPersonaProfileId = safeString(activeProfileId, DEFAULT_GLOBAL_PERSONA_PROFILE_ID);
        ensureDefaults();
    }

    private ProviderProfile getOrCreateActiveProviderProfile() {
        ProviderProfile activeProvider = getActiveProviderProfile();
        if (activeProvider != null) {
            return activeProvider;
        }
        ProviderProfile created = buildProviderFromLegacy(DEFAULT_PROVIDER_ID, DEFAULT_PROVIDER_NAME);
        providerProfiles = sanitizeProviderProfiles(providerProfiles);
        providerProfiles.add(created);
        activeProviderId = created.id;
        return created;
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

    private GlobalPersonaProfile getOrCreateActiveGlobalPersonaProfile() {
        GlobalPersonaProfile activeProfile = getActiveGlobalPersonaProfile();
        if (activeProfile != null) {
            return activeProfile;
        }
        GlobalPersonaProfile created = buildGlobalProfileFromLegacy(
                DEFAULT_GLOBAL_PERSONA_PROFILE_ID,
                DEFAULT_GLOBAL_PERSONA_PROFILE_NAME,
                resolveDefaultGlobalPersonaJson()
        );
        globalPersonaProfiles = sanitizeGlobalPersonaProfiles(globalPersonaProfiles);
        globalPersonaProfiles.add(created);
        activeGlobalPersonaProfileId = created.id;
        return created;
    }

    private ProviderProfile findProviderProfile(String profileId) {
        if (providerProfiles == null || TextUtils.isEmpty(profileId)) {
            return null;
        }
        for (ProviderProfile profile : providerProfiles) {
            if (profile != null && TextUtils.equals(profile.id, profileId)) {
                return profile;
            }
        }
        return null;
    }

    /** 公开版（v1.5.0：副模型 provider 查找用）。 */
    public ProviderProfile findProviderProfileById(String profileId) {
        return findProviderProfile(profileId);
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

    private GlobalPersonaProfile findGlobalPersonaProfile(String profileId) {
        if (globalPersonaProfiles == null || TextUtils.isEmpty(profileId)) {
            return null;
        }
        for (GlobalPersonaProfile profile : globalPersonaProfiles) {
            if (profile != null && TextUtils.equals(profile.id, profileId)) {
                return profile;
            }
        }
        return null;
    }

    private ProviderProfile buildProviderFromLegacy(String profileId, String profileName) {
        ProviderProfile profile = new ProviderProfile(profileId, profileName);
        profile.adapterPreset = adapterPreset;
        profile.upstreamBaseUrl = upstreamBaseUrl;
        profile.apiKey = apiKey;
        profile.listenChatPaths = listenChatPaths;
        profile.listenModelsPaths = listenModelsPaths;
        profile.upstreamChatPath = upstreamChatPath;
        profile.upstreamModelsPath = upstreamModelsPath;
        profile.upstreamProxyType = upstreamProxyType;
        profile.upstreamProxyHost = upstreamProxyHost;
        profile.upstreamProxyPort = upstreamProxyPort;
        profile.requestMessagesPath = requestMessagesPath;
        profile.requestUserTextPath = requestUserTextPath;
        profile.requestModelPath = requestModelPath;
        profile.requestMaxTokensPath = requestMaxTokensPath;
        profile.requestTemperaturePath = requestTemperaturePath;
        profile.requestEnableThinkingPath = requestEnableThinkingPath;
        profile.responseTextPath = responseTextPath;
        // legacy: cfg 顶层值搬到 provider，default provider 兜底用
        profile.chatCustomRequestFieldsJson = safeString(chatCustomRequestFieldsJson, "");
        profile.diaryCustomRequestFieldsJson = safeString(diaryCustomRequestFieldsJson, "");
        profile.memoryExtractCustomRequestFieldsJson = safeString(memoryExtractCustomRequestFieldsJson, "");
        profile.chatOverrideEnableThinking = safeString(chatOverrideEnableThinking, "false");
        profile.diaryOverrideEnableThinking = safeString(diaryOverrideEnableThinking, "false");
        profile.memoryExtractOverrideEnableThinking = safeString(memoryExtractOverrideEnableThinking, "false");
        profile.chatOverrideMaxTokens = Math.max(0, chatOverrideMaxTokens);
        profile.chatOverrideTemperature = safeString(chatOverrideTemperature, "0.9");
        profile.diaryOverrideMaxTokens = Math.max(0, overrideMaxTokens);
        profile.diaryOverrideTemperature = safeString(diaryOverrideTemperature, "0.9");
        profile.diaryAssistantPrefixEnabled = diaryAssistantPrefixEnabled;
        profile.diaryAssistantPrefix = safeString(diaryAssistantPrefix, "【日记】");
        profile.diaryAssistantPrefixDeepseekMode = diaryAssistantPrefixDeepseekMode;
        profile.diaryAssistantPrefixSiliconflowMode = diaryAssistantPrefixSiliconflowMode;
        profile.memoryExtractOverrideMaxTokens = Math.max(0, memoryExtractOverrideMaxTokens);
        profile.memoryExtractOverrideTemperature = safeString(memoryExtractOverrideTemperature, DEFAULT_MEMORY_EXTRACT_TEMPERATURE);
        profile.stripEnableThinkingEnabled = stripEnableThinkingEnabled;
        // v1.5.0 新增字段：从 cfg 顶层镜像（cfg 顶层默认值已在字段声明处给出）
        profile.multimodalCapability = normalizeMultimodalCapability(multimodalCapability);
        profile.captionProviderId = safeString(captionProviderId, "");
        profile.captionStrategy = normalizeCaptionStrategy(captionStrategy);
        profile.captionMaxImagesPerRequest = captionMaxImagesPerRequest > 0 ? captionMaxImagesPerRequest : 4;
        profile.captionConcurrency = captionConcurrency > 0 ? captionConcurrency : 1;
        profile.captionMaxTokens = captionMaxTokens > 0 ? captionMaxTokens : 1024;
        profile.captionImageDetailMode = normalizeCaptionImageDetailMode(captionImageDetailMode);
        profile.captionImagePlacementMode = normalizeCaptionImagePlacementMode(captionImagePlacementMode);
        profile.captionImageUrlFormat = normalizeCaptionImageUrlFormat(captionImageUrlFormat);
        // v1.5.1+：联网搜索字段已全局化，不再写入 ProviderProfile（旧字段保留兼容）
        profile.setActiveModelName(model);
        return profile;
    }

    private PersonaProfile buildProfileFromLegacy(String profileId, String profileName) {
        PersonaProfile profile = new PersonaProfile(profileId, profileName);
        profile.setTierJson(TIER_SISTER_NULL, coalesce(personaJsonSisterNull, builtinPersonaJsonSisterNull));
        profile.setTierJson(TIER_SISTER_VERYLOW, coalesce(personaJsonSisterVerylow, builtinPersonaJsonSisterVerylow));
        profile.setTierJson(TIER_SISTER_LOW, coalesce(personaJsonSisterLow, builtinPersonaJsonSisterLow));
        profile.setTierJson(TIER_SISTER_MEDIUM, coalesce(personaJsonSisterMedium, builtinPersonaJsonSisterMedium));
        profile.setTierJson(TIER_SISTER_HIGH, coalesce(personaJsonSisterHigh, builtinPersonaJsonSisterHigh));
        profile.setTierJson(TIER_SISTER_KEMONOMIMI, coalesce(personaJsonSisterKemonomimi, builtinPersonaJsonSisterKemonomimi));
        profile.setTierJson(TIER_SISTER_KEMONOMIMI_CAT, coalesce(personaJsonSisterKemonomimiCat, builtinPersonaJsonSisterKemonomimiCat));
        profile.setTierJson(TIER_SISTER_TUTOR, coalesce(personaJsonSisterTutor, builtinPersonaJsonSisterTutor));
        profile.setTierJson(TIER_SISTER_DILEI, coalesce(personaJsonSisterDilei, builtinPersonaJsonSisterDilei));
        profile.setTierJson(TIER_SISTER_KINDERGARTEN, coalesce(personaJsonSisterKindergarten, builtinPersonaJsonSisterKindergarten));
        return profile;
    }

    private GlobalPersonaProfile buildGlobalProfileFromLegacy(String profileId, String profileName, String rawJson) {
        return new GlobalPersonaProfile(profileId, profileName, coalesce(rawJson, builtinPersonaJsonSisterHigh));
    }

    /**
     * 把 activeProvider 的字段平铺到顶层 legacy 字段（upstreamBaseUrl / apiKey / model 等），
     * 让旧路径只读顶层字段的代码继续工作。
     *
     * <p>v1.5.6+：PC-3 — 注意以下三个字段是 <b>全局</b>共享，不挂在 ProviderProfile 上，因此
     * <b>不参与</b>本方法的 sync：</p>
     * <ul>
     *   <li>{@code webSearchEndpointsJson} — per-engine 端点 JSON（v1.5.1+ 引入）</li>
     *   <li>{@code webSearchApiKeysJson} — per-engine API key JSON</li>
     *   <li>{@code webSearchProxiesJson} — per-engine HTTP 代理 JSON</li>
     * </ul>
     * <p>这三个全局字段在 {@link #load(android.content.Context)} 时直接从顶层 SP 读，
     * 与切换 Provider 无关。增删 Provider 字段时勿误把它们移进 ProviderProfile。</p>
     */
    private void syncLegacyProviderFields(ProviderProfile activeProvider) {
        if (activeProvider == null) {
            return;
        }
        activeProvider.ensureModelDefaults();
        activeProviderId = safeString(activeProvider.id, DEFAULT_PROVIDER_ID);
        upstreamBaseUrl = safeString(activeProvider.upstreamBaseUrl, DEFAULT_BASE_URL);
        apiKey = safeString(activeProvider.apiKey, "");
        model = normalizeOptionalText(activeProvider.getActiveModelName());
        listenChatPaths = safeString(activeProvider.listenChatPaths, DEFAULT_LISTEN_CHAT_PATHS);
        listenModelsPaths = safeString(activeProvider.listenModelsPaths, DEFAULT_LISTEN_MODELS_PATHS);
        upstreamChatPath = normalizePath(safeString(activeProvider.upstreamChatPath, DEFAULT_UPSTREAM_CHAT_PATH));
        upstreamModelsPath = normalizePath(safeString(activeProvider.upstreamModelsPath, DEFAULT_UPSTREAM_MODELS_PATH));
        adapterPreset = normalizeAdapterPreset(activeProvider.adapterPreset);
        upstreamProxyType = normalizeUpstreamProxyType(activeProvider.upstreamProxyType, activeProvider.upstreamProxyHost, activeProvider.upstreamProxyPort);
        upstreamProxyHost = safeString(activeProvider.upstreamProxyHost, DEFAULT_UPSTREAM_PROXY_HOST);
        upstreamProxyPort = activeProvider.upstreamProxyPort > 0 ? activeProvider.upstreamProxyPort : DEFAULT_UPSTREAM_PROXY_PORT;
        requestMessagesPath = safeString(activeProvider.requestMessagesPath, DEFAULT_REQUEST_MESSAGES_PATH);
        requestUserTextPath = safeString(activeProvider.requestUserTextPath, DEFAULT_REQUEST_USER_TEXT_PATH);
        requestModelPath = safeString(activeProvider.requestModelPath, DEFAULT_REQUEST_MODEL_PATH);
        requestMaxTokensPath = safeString(activeProvider.requestMaxTokensPath, DEFAULT_REQUEST_MAX_TOKENS_PATH);
        requestTemperaturePath = safeString(activeProvider.requestTemperaturePath, DEFAULT_REQUEST_TEMPERATURE_PATH);
        requestEnableThinkingPath = safeString(activeProvider.requestEnableThinkingPath, DEFAULT_REQUEST_ENABLE_THINKING_PATH);
        responseTextPath = safeString(activeProvider.responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);
        chatCustomRequestFieldsJson = safeString(activeProvider.chatCustomRequestFieldsJson, "");
        diaryCustomRequestFieldsJson = safeString(activeProvider.diaryCustomRequestFieldsJson, "");
        memoryExtractCustomRequestFieldsJson = safeString(activeProvider.memoryExtractCustomRequestFieldsJson, "");
        chatOverrideEnableThinking = safeString(activeProvider.chatOverrideEnableThinking, "false");
        diaryOverrideEnableThinking = safeString(activeProvider.diaryOverrideEnableThinking, "false");
        memoryExtractOverrideEnableThinking = safeString(activeProvider.memoryExtractOverrideEnableThinking, "false");
        chatOverrideMaxTokens = Math.max(0, activeProvider.chatOverrideMaxTokens);
        chatOverrideTemperature = safeString(activeProvider.chatOverrideTemperature, "0.9");
        overrideMaxTokens = Math.max(0, activeProvider.diaryOverrideMaxTokens);
        diaryOverrideTemperature = safeString(activeProvider.diaryOverrideTemperature, "0.9");
        diaryAssistantPrefixEnabled = activeProvider.diaryAssistantPrefixEnabled;
        diaryAssistantPrefix = safeString(activeProvider.diaryAssistantPrefix, "【日记】");
        diaryAssistantPrefixDeepseekMode = activeProvider.diaryAssistantPrefixDeepseekMode;
        diaryAssistantPrefixSiliconflowMode = activeProvider.diaryAssistantPrefixSiliconflowMode;
        memoryExtractOverrideMaxTokens = Math.max(0, activeProvider.memoryExtractOverrideMaxTokens);
        memoryExtractOverrideTemperature = safeString(activeProvider.memoryExtractOverrideTemperature, DEFAULT_MEMORY_EXTRACT_TEMPERATURE);
        stripEnableThinkingEnabled = activeProvider.stripEnableThinkingEnabled;
        // v1.5.0 新增字段：从 active provider 镜像到 cfg 顶层
        multimodalCapability = normalizeMultimodalCapability(activeProvider.multimodalCapability);
        captionProviderId = safeString(activeProvider.captionProviderId, "");
        captionStrategy = normalizeCaptionStrategy(activeProvider.captionStrategy);
        captionMaxImagesPerRequest = activeProvider.captionMaxImagesPerRequest > 0 ? activeProvider.captionMaxImagesPerRequest : 4;
        captionConcurrency = activeProvider.captionConcurrency > 0 ? activeProvider.captionConcurrency : 1;
        captionMaxTokens = activeProvider.captionMaxTokens > 0 ? activeProvider.captionMaxTokens : 1024;
        captionImageDetailMode = normalizeCaptionImageDetailMode(activeProvider.captionImageDetailMode);
        captionImagePlacementMode = normalizeCaptionImagePlacementMode(activeProvider.captionImagePlacementMode);
        captionImageUrlFormat = normalizeCaptionImageUrlFormat(activeProvider.captionImageUrlFormat);
        // v1.5.1+：联网搜索字段已提升到全局，不再从 active provider 镜像
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
        personaJsonSisterKemonomimi = coalesce(activeProfile.getTierJson(TIER_SISTER_KEMONOMIMI), builtinPersonaJsonSisterKemonomimi);
        personaJsonSisterKemonomimiCat = coalesce(activeProfile.getTierJson(TIER_SISTER_KEMONOMIMI_CAT), builtinPersonaJsonSisterKemonomimiCat);
        personaJsonSisterTutor = coalesce(activeProfile.getTierJson(TIER_SISTER_TUTOR), builtinPersonaJsonSisterTutor);
        personaJsonSisterDilei = coalesce(activeProfile.getTierJson(TIER_SISTER_DILEI), builtinPersonaJsonSisterDilei);
        personaJsonSisterKindergarten = coalesce(activeProfile.getTierJson(TIER_SISTER_KINDERGARTEN), builtinPersonaJsonSisterKindergarten);
    }

    private void syncLegacyGlobalPersonaField(GlobalPersonaProfile activeProfile) {
        if (activeProfile == null) {
            return;
        }
        globalPersonaJson = coalesce(activeProfile.rawJson, resolveDefaultGlobalPersonaJson());
    }

    private ArrayList<ProviderProfile> sanitizeProviderProfiles(List<ProviderProfile> source) {
        ArrayList<ProviderProfile> sanitized = new ArrayList<>();
        Map<String, Integer> usedIds = new LinkedHashMap<>();
        if (source == null) {
            return sanitized;
        }
        for (ProviderProfile rawProfile : source) {
            if (rawProfile == null) {
                continue;
            }
            String baseId = safeString(rawProfile.id, "");
            if (TextUtils.isEmpty(baseId)) {
                baseId = "provider";
            }
            int usedCount = usedIds.containsKey(baseId) ? usedIds.get(baseId) + 1 : 1;
            usedIds.put(baseId, usedCount);
            String finalId = usedCount == 1 ? baseId : baseId + "_" + usedCount;
            String finalName = safeString(rawProfile.name, DEFAULT_PROVIDER_NAME + " " + (sanitized.size() + 1));
            ProviderProfile profile = new ProviderProfile(finalId, finalName);
            profile.adapterPreset = normalizeAdapterPreset(rawProfile.adapterPreset);
            profile.upstreamBaseUrl = safeString(rawProfile.upstreamBaseUrl, DEFAULT_BASE_URL);
            profile.apiKey = safeString(rawProfile.apiKey, "");
            profile.listenChatPaths = safeString(rawProfile.listenChatPaths, DEFAULT_LISTEN_CHAT_PATHS);
            profile.listenModelsPaths = safeString(rawProfile.listenModelsPaths, DEFAULT_LISTEN_MODELS_PATHS);
            profile.upstreamChatPath = normalizePath(safeString(rawProfile.upstreamChatPath, DEFAULT_UPSTREAM_CHAT_PATH));
            profile.upstreamModelsPath = normalizePath(safeString(rawProfile.upstreamModelsPath, DEFAULT_UPSTREAM_MODELS_PATH));
            profile.upstreamProxyType = normalizeUpstreamProxyType(
                    rawProfile.upstreamProxyType,
                    rawProfile.upstreamProxyHost,
                    rawProfile.upstreamProxyPort
            );
            profile.upstreamProxyHost = safeString(rawProfile.upstreamProxyHost, DEFAULT_UPSTREAM_PROXY_HOST);
            profile.upstreamProxyPort = rawProfile.upstreamProxyPort > 0 ? rawProfile.upstreamProxyPort : DEFAULT_UPSTREAM_PROXY_PORT;
            profile.requestMessagesPath = safeString(rawProfile.requestMessagesPath, DEFAULT_REQUEST_MESSAGES_PATH);
            profile.requestUserTextPath = safeString(rawProfile.requestUserTextPath, DEFAULT_REQUEST_USER_TEXT_PATH);
            profile.requestModelPath = safeString(rawProfile.requestModelPath, DEFAULT_REQUEST_MODEL_PATH);
            profile.requestMaxTokensPath = safeString(rawProfile.requestMaxTokensPath, DEFAULT_REQUEST_MAX_TOKENS_PATH);
            profile.requestTemperaturePath = safeString(rawProfile.requestTemperaturePath, DEFAULT_REQUEST_TEMPERATURE_PATH);
            profile.requestEnableThinkingPath = safeString(rawProfile.requestEnableThinkingPath, DEFAULT_REQUEST_ENABLE_THINKING_PATH);
            profile.responseTextPath = safeString(rawProfile.responseTextPath, DEFAULT_RESPONSE_TEXT_PATH);
            profile.chatCustomRequestFieldsJson = safeString(rawProfile.chatCustomRequestFieldsJson, "");
            profile.diaryCustomRequestFieldsJson = safeString(rawProfile.diaryCustomRequestFieldsJson, "");
            profile.memoryExtractCustomRequestFieldsJson = safeString(rawProfile.memoryExtractCustomRequestFieldsJson, "");
            profile.chatOverrideEnableThinking = safeString(rawProfile.chatOverrideEnableThinking, "false");
            profile.diaryOverrideEnableThinking = safeString(rawProfile.diaryOverrideEnableThinking, "false");
            profile.memoryExtractOverrideEnableThinking = safeString(rawProfile.memoryExtractOverrideEnableThinking, "false");
            profile.chatOverrideMaxTokens = Math.max(0, rawProfile.chatOverrideMaxTokens);
            profile.chatOverrideTemperature = safeString(rawProfile.chatOverrideTemperature, "0.9");
            profile.diaryOverrideMaxTokens = Math.max(0, rawProfile.diaryOverrideMaxTokens);
            profile.diaryOverrideTemperature = safeString(rawProfile.diaryOverrideTemperature, "0.9");
            profile.diaryAssistantPrefixEnabled = rawProfile.diaryAssistantPrefixEnabled;
            profile.diaryAssistantPrefix = safeString(rawProfile.diaryAssistantPrefix, "【日记】");
            profile.diaryAssistantPrefixDeepseekMode = rawProfile.diaryAssistantPrefixDeepseekMode;
            profile.diaryAssistantPrefixSiliconflowMode = rawProfile.diaryAssistantPrefixSiliconflowMode;
            profile.memoryExtractOverrideMaxTokens = Math.max(0, rawProfile.memoryExtractOverrideMaxTokens);
            profile.memoryExtractOverrideTemperature = safeString(rawProfile.memoryExtractOverrideTemperature, DEFAULT_MEMORY_EXTRACT_TEMPERATURE);
            profile.stripEnableThinkingEnabled = rawProfile.stripEnableThinkingEnabled;
            // v1.5.0 新增字段：透传带兜底默认（避开 v1.4.0 Bug 2 同样陷阱）
            profile.multimodalCapability = normalizeMultimodalCapability(rawProfile.multimodalCapability);
            profile.captionProviderId = safeString(rawProfile.captionProviderId, "");
            profile.captionStrategy = normalizeCaptionStrategy(rawProfile.captionStrategy);
            profile.captionMaxImagesPerRequest = rawProfile.captionMaxImagesPerRequest > 0 ? rawProfile.captionMaxImagesPerRequest : 4;
            profile.captionConcurrency = rawProfile.captionConcurrency > 0 ? rawProfile.captionConcurrency : 1;
            profile.captionMaxTokens = rawProfile.captionMaxTokens > 0 ? rawProfile.captionMaxTokens : 1024;
            profile.captionImageDetailMode = normalizeCaptionImageDetailMode(rawProfile.captionImageDetailMode);
            profile.captionImagePlacementMode = normalizeCaptionImagePlacementMode(rawProfile.captionImagePlacementMode);
            profile.captionImageUrlFormat = normalizeCaptionImageUrlFormat(rawProfile.captionImageUrlFormat);
            profile.webSearchToolEnabled = normalizeWebSearchToolEnabled(rawProfile.webSearchToolEnabled);
            profile.webSearchProvider = normalizeWebSearchProvider(rawProfile.webSearchProvider);
            profile.webSearchApiKeysJson = normalizeWebSearchApiKeysJson(withWebSearchApiKey(
                    rawProfile.webSearchApiKeysJson,
                    profile.webSearchProvider,
                    rawProfile.webSearchApiKey
            ));
            profile.webSearchApiKey = getWebSearchApiKey(profile.webSearchApiKeysJson, profile.webSearchProvider);
            profile.webSearchEndpoint = safeString(rawProfile.webSearchEndpoint, "");
            profile.webSearchMaxResults = rawProfile.webSearchMaxResults > 0 ? rawProfile.webSearchMaxResults : 5;
            profile.webSearchProxyType = normalizeUpstreamProxyType(rawProfile.webSearchProxyType, rawProfile.webSearchProxyHost, rawProfile.webSearchProxyPort);
            profile.webSearchProxyHost = safeString(rawProfile.webSearchProxyHost, DEFAULT_UPSTREAM_PROXY_HOST);
            profile.webSearchProxyPort = rawProfile.webSearchProxyPort > 0 ? rawProfile.webSearchProxyPort : DEFAULT_UPSTREAM_PROXY_PORT;
            profile.models = sanitizeModelProfiles(rawProfile.models);
            profile.activeModelId = safeString(rawProfile.activeModelId, "");
            if (profile.findModel(profile.activeModelId) == null) {
                profile.activeModelId = "";
            }
            sanitized.add(profile);
        }
        return sanitized;
    }

    private ArrayList<ModelProfile> sanitizeModelProfiles(List<ModelProfile> source) {
        ArrayList<ModelProfile> sanitized = new ArrayList<>();
        Map<String, Integer> usedIds = new LinkedHashMap<>();
        List<String> usedNames = new ArrayList<>();
        if (source == null) {
            return sanitized;
        }
        for (ModelProfile rawModel : source) {
            if (rawModel == null) {
                continue;
            }
            String modelName = safeString(rawModel.name, "");
            if (TextUtils.isEmpty(modelName) || usedNames.contains(modelName)) {
                continue;
            }
            String baseId = safeString(rawModel.id, "");
            if (TextUtils.isEmpty(baseId)) {
                baseId = "model";
            }
            int usedCount = usedIds.containsKey(baseId) ? usedIds.get(baseId) + 1 : 1;
            usedIds.put(baseId, usedCount);
            String finalId = usedCount == 1 ? baseId : baseId + "_" + usedCount;
            sanitized.add(new ModelProfile(finalId, modelName));
            usedNames.add(modelName);
        }
        return sanitized;
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

    private ArrayList<GlobalPersonaProfile> sanitizeGlobalPersonaProfiles(List<GlobalPersonaProfile> source) {
        ArrayList<GlobalPersonaProfile> sanitized = new ArrayList<>();
        Map<String, Integer> usedIds = new LinkedHashMap<>();
        if (source == null) {
            return sanitized;
        }
        String fallbackRaw = resolveDefaultGlobalPersonaJson();
        for (GlobalPersonaProfile rawProfile : source) {
            if (rawProfile == null) {
                continue;
            }
            String baseId = safeString(rawProfile.id, "");
            if (TextUtils.isEmpty(baseId)) {
                baseId = "global_profile";
            }
            int usedCount = usedIds.containsKey(baseId) ? usedIds.get(baseId) + 1 : 1;
            usedIds.put(baseId, usedCount);
            String finalId = usedCount == 1 ? baseId : baseId + "_" + usedCount;
            String finalName = safeString(rawProfile.name, DEFAULT_GLOBAL_PERSONA_PROFILE_NAME + " " + sanitized.size());
            String finalRaw = coalesce(rawProfile.rawJson, fallbackRaw);
            sanitized.add(new GlobalPersonaProfile(finalId, finalName, finalRaw));
        }
        return sanitized;
    }

    private static ArrayList<ProviderProfile> cloneProviderProfiles(List<ProviderProfile> source) {
        ArrayList<ProviderProfile> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ProviderProfile profile : source) {
            if (profile != null) {
                copy.add(profile.copy());
            }
        }
        return copy;
    }

    private static ArrayList<ModelProfile> cloneModelProfiles(List<ModelProfile> source) {
        ArrayList<ModelProfile> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ModelProfile profile : source) {
            if (profile != null) {
                copy.add(profile.copy());
            }
        }
        return copy;
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

    private static ArrayList<GlobalPersonaProfile> cloneGlobalPersonaProfiles(List<GlobalPersonaProfile> source) {
        ArrayList<GlobalPersonaProfile> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (GlobalPersonaProfile profile : source) {
            if (profile != null) {
                copy.add(profile.copy());
            }
        }
        return copy;
    }

    private static ArrayList<ProviderProfile> parseProviderProfiles(String rawJson) {
        ArrayList<ProviderProfile> profiles = new ArrayList<>();
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
                ProviderProfile profile = new ProviderProfile(
                        item.optString("id", ""),
                        item.optString("name", "")
                );
                profile.adapterPreset = item.optString("adapterPreset", ADAPTER_OPENAI_COMPATIBLE);
                profile.upstreamBaseUrl = item.optString("upstreamBaseUrl", DEFAULT_BASE_URL);
                profile.listenChatPaths = item.optString("listenChatPaths", DEFAULT_LISTEN_CHAT_PATHS);
                profile.listenModelsPaths = item.optString("listenModelsPaths", DEFAULT_LISTEN_MODELS_PATHS);
                profile.upstreamChatPath = item.optString("upstreamChatPath", DEFAULT_UPSTREAM_CHAT_PATH);
                profile.upstreamModelsPath = item.optString("upstreamModelsPath", DEFAULT_UPSTREAM_MODELS_PATH);
                profile.upstreamProxyType = item.optString("upstreamProxyType", DEFAULT_UPSTREAM_PROXY_TYPE);
                profile.upstreamProxyHost = item.optString("upstreamProxyHost", DEFAULT_UPSTREAM_PROXY_HOST);
                profile.upstreamProxyPort = item.optInt("upstreamProxyPort", DEFAULT_UPSTREAM_PROXY_PORT);
                profile.requestMessagesPath = item.optString("requestMessagesPath", DEFAULT_REQUEST_MESSAGES_PATH);
                profile.requestUserTextPath = item.optString("requestUserTextPath", DEFAULT_REQUEST_USER_TEXT_PATH);
                profile.requestModelPath = item.optString("requestModelPath", DEFAULT_REQUEST_MODEL_PATH);
                profile.requestMaxTokensPath = item.optString("requestMaxTokensPath", DEFAULT_REQUEST_MAX_TOKENS_PATH);
                profile.requestTemperaturePath = item.optString("requestTemperaturePath", DEFAULT_REQUEST_TEMPERATURE_PATH);
                profile.requestEnableThinkingPath = item.optString("requestEnableThinkingPath", DEFAULT_REQUEST_ENABLE_THINKING_PATH);
                profile.responseTextPath = item.optString("responseTextPath", DEFAULT_RESPONSE_TEXT_PATH);
                profile.activeModelId = item.optString("activeModelId", "");
                profile.chatCustomRequestFieldsJson = item.optString("chatCustomRequestFieldsJson", "");
                profile.diaryCustomRequestFieldsJson = item.optString("diaryCustomRequestFieldsJson", "");
                profile.memoryExtractCustomRequestFieldsJson = item.optString("memoryExtractCustomRequestFieldsJson", "");
                profile.chatOverrideEnableThinking = item.optString("chatOverrideEnableThinking", "false");
                profile.diaryOverrideEnableThinking = item.optString("diaryOverrideEnableThinking", "false");
                profile.memoryExtractOverrideEnableThinking = item.optString("memoryExtractOverrideEnableThinking", "false");
                profile.chatOverrideMaxTokens = item.optInt("chatOverrideMaxTokens", 1500);
                profile.chatOverrideTemperature = item.optString("chatOverrideTemperature", "0.9");
                profile.diaryOverrideMaxTokens = item.optInt("diaryOverrideMaxTokens", 2500);
                profile.diaryOverrideTemperature = item.optString("diaryOverrideTemperature", "0.9");
                profile.diaryAssistantPrefixEnabled = item.optBoolean("diaryAssistantPrefixEnabled", false);
                profile.diaryAssistantPrefix = item.optString("diaryAssistantPrefix", "【日记】");
                profile.diaryAssistantPrefixDeepseekMode = item.optBoolean("diaryAssistantPrefixDeepseekMode", false);
                profile.diaryAssistantPrefixSiliconflowMode = item.optBoolean("diaryAssistantPrefixSiliconflowMode", false);
                profile.memoryExtractOverrideMaxTokens = item.optInt("memoryExtractOverrideMaxTokens", DEFAULT_MEMORY_EXTRACT_MAX_TOKENS);
                profile.memoryExtractOverrideTemperature = item.optString("memoryExtractOverrideTemperature", DEFAULT_MEMORY_EXTRACT_TEMPERATURE);
                profile.stripEnableThinkingEnabled = item.optBoolean("stripEnableThinkingEnabled", false);
                // v1.5.0 新增字段
                profile.multimodalCapability = item.optString("multimodalCapability", "auto");
                profile.captionProviderId = item.optString("captionProviderId", "");
                profile.captionStrategy = item.optString("captionStrategy", "inject");
                profile.captionMaxImagesPerRequest = item.optInt("captionMaxImagesPerRequest", 4);
                profile.captionConcurrency = item.optInt("captionConcurrency", 1);
                profile.captionMaxTokens = item.optInt("captionMaxTokens", 1024);
                profile.captionImageDetailMode = normalizeCaptionImageDetailMode(item.optString("captionImageDetailMode", "auto"));
                profile.captionImagePlacementMode = normalizeCaptionImagePlacementMode(item.optString("captionImagePlacementMode", "auto"));
                profile.captionImageUrlFormat = normalizeCaptionImageUrlFormat(item.optString("captionImageUrlFormat", "auto"));
                profile.webSearchToolEnabled = item.optString("webSearchToolEnabled", "fallback");
                profile.webSearchProvider = item.optString("webSearchProvider", "bochaai");
                profile.webSearchApiKey = item.optString("webSearchApiKey", "");
                profile.webSearchApiKeysJson = item.optString("webSearchApiKeysJson", "");
                profile.webSearchEndpoint = item.optString("webSearchEndpoint", "");
                profile.webSearchMaxResults = item.optInt("webSearchMaxResults", 5);
                profile.webSearchProxyType = item.optString("webSearchProxyType", DEFAULT_UPSTREAM_PROXY_TYPE);
                profile.webSearchProxyHost = item.optString("webSearchProxyHost", DEFAULT_UPSTREAM_PROXY_HOST);
                profile.webSearchProxyPort = item.optInt("webSearchProxyPort", DEFAULT_UPSTREAM_PROXY_PORT);
                JSONArray models = item.optJSONArray("models");
                if (models != null) {
                    for (int j = 0; j < models.length(); j++) {
                        JSONObject modelItem = models.optJSONObject(j);
                        if (modelItem == null) {
                            continue;
                        }
                        profile.models.add(new ModelProfile(
                                modelItem.optString("id", ""),
                                modelItem.optString("name", "")
                        ));
                    }
                }
                profiles.add(profile);
            }
        } catch (Exception ignored) {
            profiles.clear();
        }
        return profiles;
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

    private static ArrayList<GlobalPersonaProfile> parseGlobalPersonaProfiles(String rawJson) {
        ArrayList<GlobalPersonaProfile> profiles = new ArrayList<>();
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
                profiles.add(new GlobalPersonaProfile(
                        item.optString("id", ""),
                        item.optString("name", ""),
                        item.optString("rawJson", "")
                ));
            }
        } catch (Exception ignored) {
            profiles.clear();
        }
        return profiles;
    }

    private static String serializeProviderProfiles(List<ProviderProfile> profiles) {
        JSONArray array = new JSONArray();
        if (profiles == null) {
            return array.toString();
        }
        for (ProviderProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("id", safeString(profile.id, ""));
                item.put("name", safeString(profile.name, ""));
                item.put("adapterPreset", normalizeAdapterPreset(profile.adapterPreset));
                item.put("upstreamBaseUrl", safeString(profile.upstreamBaseUrl, ""));
                item.put("listenChatPaths", safeString(profile.listenChatPaths, DEFAULT_LISTEN_CHAT_PATHS));
                item.put("listenModelsPaths", safeString(profile.listenModelsPaths, DEFAULT_LISTEN_MODELS_PATHS));
                item.put("upstreamChatPath", normalizePath(safeString(profile.upstreamChatPath, DEFAULT_UPSTREAM_CHAT_PATH)));
                item.put("upstreamModelsPath", normalizePath(safeString(profile.upstreamModelsPath, DEFAULT_UPSTREAM_MODELS_PATH)));
                item.put("upstreamProxyType", normalizeUpstreamProxyType(profile.upstreamProxyType, profile.upstreamProxyHost, profile.upstreamProxyPort));
                item.put("upstreamProxyHost", safeString(profile.upstreamProxyHost, ""));
                item.put("upstreamProxyPort", Math.max(0, profile.upstreamProxyPort));
                item.put("requestMessagesPath", safeString(profile.requestMessagesPath, DEFAULT_REQUEST_MESSAGES_PATH));
                item.put("requestUserTextPath", safeString(profile.requestUserTextPath, DEFAULT_REQUEST_USER_TEXT_PATH));
                item.put("requestModelPath", safeString(profile.requestModelPath, DEFAULT_REQUEST_MODEL_PATH));
                item.put("requestMaxTokensPath", safeString(profile.requestMaxTokensPath, DEFAULT_REQUEST_MAX_TOKENS_PATH));
                item.put("requestTemperaturePath", safeString(profile.requestTemperaturePath, DEFAULT_REQUEST_TEMPERATURE_PATH));
                item.put("requestEnableThinkingPath", safeString(profile.requestEnableThinkingPath, DEFAULT_REQUEST_ENABLE_THINKING_PATH));
                item.put("responseTextPath", safeString(profile.responseTextPath, DEFAULT_RESPONSE_TEXT_PATH));
                item.put("activeModelId", safeString(profile.activeModelId, ""));
                item.put("chatCustomRequestFieldsJson", safeString(profile.chatCustomRequestFieldsJson, ""));
                item.put("diaryCustomRequestFieldsJson", safeString(profile.diaryCustomRequestFieldsJson, ""));
                item.put("memoryExtractCustomRequestFieldsJson", safeString(profile.memoryExtractCustomRequestFieldsJson, ""));
                item.put("chatOverrideEnableThinking", safeString(profile.chatOverrideEnableThinking, "false"));
                item.put("diaryOverrideEnableThinking", safeString(profile.diaryOverrideEnableThinking, "false"));
                item.put("memoryExtractOverrideEnableThinking", safeString(profile.memoryExtractOverrideEnableThinking, "false"));
                item.put("chatOverrideMaxTokens", Math.max(0, profile.chatOverrideMaxTokens));
                item.put("chatOverrideTemperature", safeString(profile.chatOverrideTemperature, "0.9"));
                item.put("diaryOverrideMaxTokens", Math.max(0, profile.diaryOverrideMaxTokens));
                item.put("diaryOverrideTemperature", safeString(profile.diaryOverrideTemperature, "0.9"));
                item.put("diaryAssistantPrefixEnabled", profile.diaryAssistantPrefixEnabled);
                item.put("diaryAssistantPrefix", safeString(profile.diaryAssistantPrefix, "【日记】"));
                item.put("diaryAssistantPrefixDeepseekMode", profile.diaryAssistantPrefixDeepseekMode);
                item.put("diaryAssistantPrefixSiliconflowMode", profile.diaryAssistantPrefixSiliconflowMode);
                item.put("memoryExtractOverrideMaxTokens", Math.max(0, profile.memoryExtractOverrideMaxTokens));
                item.put("memoryExtractOverrideTemperature", safeString(profile.memoryExtractOverrideTemperature, DEFAULT_MEMORY_EXTRACT_TEMPERATURE));
                item.put("stripEnableThinkingEnabled", profile.stripEnableThinkingEnabled);
                // v1.5.0 新增字段
                item.put("multimodalCapability", normalizeMultimodalCapability(profile.multimodalCapability));
                item.put("captionProviderId", safeString(profile.captionProviderId, ""));
                item.put("captionStrategy", normalizeCaptionStrategy(profile.captionStrategy));
                item.put("captionMaxImagesPerRequest", profile.captionMaxImagesPerRequest > 0 ? profile.captionMaxImagesPerRequest : 4);
                item.put("captionConcurrency", profile.captionConcurrency > 0 ? profile.captionConcurrency : 1);
                item.put("captionMaxTokens", profile.captionMaxTokens > 0 ? profile.captionMaxTokens : 1024);
                item.put("captionImageDetailMode", normalizeCaptionImageDetailMode(profile.captionImageDetailMode));
                item.put("captionImagePlacementMode", normalizeCaptionImagePlacementMode(profile.captionImagePlacementMode));
                item.put("captionImageUrlFormat", normalizeCaptionImageUrlFormat(profile.captionImageUrlFormat));
                // v1.5.1+：联网搜索字段已全局化到 cfg 顶层（独立 SP 持久化），不再 per-provider 序列化。
                // ProviderProfile 中的 webSearch* 字段保留用于 V4 一次性迁移读取，但不主动写入。
                JSONArray models = new JSONArray();
                if (profile.models != null) {
                    for (ModelProfile modelProfile : profile.models) {
                        if (modelProfile == null) {
                            continue;
                        }
                        JSONObject modelItem = new JSONObject();
                        modelItem.put("id", safeString(modelProfile.id, ""));
                        modelItem.put("name", safeString(modelProfile.name, ""));
                        models.put(modelItem);
                    }
                }
                item.put("models", models);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
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

    private static String serializeGlobalPersonaProfiles(List<GlobalPersonaProfile> profiles) {
        JSONArray array = new JSONArray();
        if (profiles == null) {
            return array.toString();
        }
        for (GlobalPersonaProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("id", safeString(profile.id, ""));
                item.put("name", safeString(profile.name, ""));
                item.put("rawJson", safeString(profile.rawJson, ""));
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public static final class ProviderProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        public String id;
        public String name;
        public String adapterPreset = ADAPTER_OPENAI_COMPATIBLE;
        public String upstreamBaseUrl = DEFAULT_BASE_URL;
        public transient String apiKey = "";
        public String listenChatPaths = DEFAULT_LISTEN_CHAT_PATHS;
        public String listenModelsPaths = DEFAULT_LISTEN_MODELS_PATHS;
        public String upstreamChatPath = DEFAULT_UPSTREAM_CHAT_PATH;
        public String upstreamModelsPath = DEFAULT_UPSTREAM_MODELS_PATH;
        public String upstreamProxyType = DEFAULT_UPSTREAM_PROXY_TYPE;
        public String upstreamProxyHost = DEFAULT_UPSTREAM_PROXY_HOST;
        public int upstreamProxyPort = DEFAULT_UPSTREAM_PROXY_PORT;
        public String requestMessagesPath = DEFAULT_REQUEST_MESSAGES_PATH;
        public String requestUserTextPath = DEFAULT_REQUEST_USER_TEXT_PATH;
        public String requestModelPath = DEFAULT_REQUEST_MODEL_PATH;
        public String requestMaxTokensPath = DEFAULT_REQUEST_MAX_TOKENS_PATH;
        public String requestTemperaturePath = DEFAULT_REQUEST_TEMPERATURE_PATH;
        public String requestEnableThinkingPath = DEFAULT_REQUEST_ENABLE_THINKING_PATH;
        public String responseTextPath = DEFAULT_RESPONSE_TEXT_PATH;
        public String activeModelId = "";
        public ArrayList<ModelProfile> models = new ArrayList<>();
        public String chatCustomRequestFieldsJson = "";
        public String diaryCustomRequestFieldsJson = "";
        public String memoryExtractCustomRequestFieldsJson = "";
        public String chatOverrideEnableThinking = "false";
        public String diaryOverrideEnableThinking = "false";
        public String memoryExtractOverrideEnableThinking = "false";
        public int chatOverrideMaxTokens = 1500;
        public String chatOverrideTemperature = "0.9";
        public int diaryOverrideMaxTokens = 2500;
        public String diaryOverrideTemperature = "0.9";
        // v1.5.x：日记 assistant 前缀续写
        public boolean diaryAssistantPrefixEnabled = false;
        public String diaryAssistantPrefix = "【日记】";
        public boolean diaryAssistantPrefixDeepseekMode = false;
        public boolean diaryAssistantPrefixSiliconflowMode = false;
        public int memoryExtractOverrideMaxTokens = DEFAULT_MEMORY_EXTRACT_MAX_TOKENS;
        public String memoryExtractOverrideTemperature = DEFAULT_MEMORY_EXTRACT_TEMPERATURE;
        public boolean stripEnableThinkingEnabled = false;
        // v1.5.0 新增字段：附件 / 多模态 / 副模型 / 联网搜索
        public String multimodalCapability = "auto";          // "auto" | "yes" | "no"
        public String captionProviderId = "";                 // 副模型 provider id；空=不启用
        public String captionStrategy = "inject";             // "inject"(A) | "tool"(B) | "off"
        public int captionMaxImagesPerRequest = 4;
        /** v1.5.4+：DPS-6 — 副模型并发度，默认 1（串行）。 */
        public int captionConcurrency = 1;
        /** v1.5.5+：CS-4 — 副模型 max_tokens，默认 1024。 */
        public int captionMaxTokens = 1024;
        /** v1.5.5+：CS-3 — 副模型图像格式三态 quirk（auto/on/off / auto/image_first/text_first / auto/data_url/raw_base64）。 */
        public String captionImageDetailMode = "auto";
        public String captionImagePlacementMode = "auto";
        public String captionImageUrlFormat = "auto";
        public String webSearchToolEnabled = "fallback";      // "off" | "always" | "fallback"
        public String webSearchProvider = "bochaai";          // v1.5.4+：默认 bochaai，原 "auto" 已弃用
        public String webSearchApiKey = "";
        public String webSearchApiKeysJson = "";
        public String webSearchEndpoint = "";
        public int webSearchMaxResults = 5;
        public String webSearchProxyType = DEFAULT_UPSTREAM_PROXY_TYPE;
        public String webSearchProxyHost = DEFAULT_UPSTREAM_PROXY_HOST;
        public int webSearchProxyPort = DEFAULT_UPSTREAM_PROXY_PORT;

        public ProviderProfile(String id, String name) {
            this.id = safeString(id, "");
            this.name = safeString(name, "");
        }

        public ProviderProfile copy() {
            ProviderProfile copy = new ProviderProfile(id, name);
            copy.adapterPreset = adapterPreset;
            copy.upstreamBaseUrl = upstreamBaseUrl;
            copy.apiKey = apiKey;
            copy.listenChatPaths = listenChatPaths;
            copy.listenModelsPaths = listenModelsPaths;
            copy.upstreamChatPath = upstreamChatPath;
            copy.upstreamModelsPath = upstreamModelsPath;
            copy.upstreamProxyType = upstreamProxyType;
            copy.upstreamProxyHost = upstreamProxyHost;
            copy.upstreamProxyPort = upstreamProxyPort;
            copy.requestMessagesPath = requestMessagesPath;
            copy.requestUserTextPath = requestUserTextPath;
            copy.requestModelPath = requestModelPath;
            copy.requestMaxTokensPath = requestMaxTokensPath;
            copy.requestTemperaturePath = requestTemperaturePath;
            copy.requestEnableThinkingPath = requestEnableThinkingPath;
            copy.responseTextPath = responseTextPath;
            copy.activeModelId = activeModelId;
            copy.models = cloneModelProfiles(models);
            copy.chatCustomRequestFieldsJson = chatCustomRequestFieldsJson;
            copy.diaryCustomRequestFieldsJson = diaryCustomRequestFieldsJson;
            copy.memoryExtractCustomRequestFieldsJson = memoryExtractCustomRequestFieldsJson;
            copy.chatOverrideEnableThinking = chatOverrideEnableThinking;
            copy.diaryOverrideEnableThinking = diaryOverrideEnableThinking;
            copy.memoryExtractOverrideEnableThinking = memoryExtractOverrideEnableThinking;
            copy.chatOverrideMaxTokens = chatOverrideMaxTokens;
            copy.chatOverrideTemperature = chatOverrideTemperature;
            copy.diaryOverrideMaxTokens = diaryOverrideMaxTokens;
            copy.diaryOverrideTemperature = diaryOverrideTemperature;
            copy.diaryAssistantPrefixEnabled = diaryAssistantPrefixEnabled;
            copy.diaryAssistantPrefix = diaryAssistantPrefix;
            copy.diaryAssistantPrefixDeepseekMode = diaryAssistantPrefixDeepseekMode;
            copy.diaryAssistantPrefixSiliconflowMode = diaryAssistantPrefixSiliconflowMode;
            copy.memoryExtractOverrideMaxTokens = memoryExtractOverrideMaxTokens;
            copy.memoryExtractOverrideTemperature = memoryExtractOverrideTemperature;
            copy.stripEnableThinkingEnabled = stripEnableThinkingEnabled;
            // v1.5.0 新增字段
            copy.multimodalCapability = multimodalCapability;
            copy.captionProviderId = captionProviderId;
            copy.captionStrategy = captionStrategy;
            copy.captionMaxImagesPerRequest = captionMaxImagesPerRequest;
            copy.captionConcurrency = captionConcurrency;
            copy.captionMaxTokens = captionMaxTokens;
            copy.captionImageDetailMode = captionImageDetailMode;
            copy.captionImagePlacementMode = captionImagePlacementMode;
            copy.captionImageUrlFormat = captionImageUrlFormat;
            copy.webSearchToolEnabled = webSearchToolEnabled;
            copy.webSearchProvider = webSearchProvider;
            copy.webSearchApiKey = webSearchApiKey;
            copy.webSearchApiKeysJson = webSearchApiKeysJson;
            copy.webSearchEndpoint = webSearchEndpoint;
            copy.webSearchMaxResults = webSearchMaxResults;
            copy.webSearchProxyType = webSearchProxyType;
            copy.webSearchProxyHost = webSearchProxyHost;
            copy.webSearchProxyPort = webSearchProxyPort;
            copy.ensureModelDefaults();
            return copy;
        }

        public void ensureModelDefaults() {
            if (models == null) {
                models = new ArrayList<>();
            }
            if (TextUtils.isEmpty(activeModelId) || findModel(activeModelId) == null) {
                activeModelId = "";
            }
        }

        public ModelProfile findModel(String modelId) {
            if (models == null || TextUtils.isEmpty(modelId)) {
                return null;
            }
            for (ModelProfile model : models) {
                if (model != null && TextUtils.equals(model.id, modelId)) {
                    return model;
                }
            }
            return null;
        }

        public ModelProfile findModelByName(String modelName) {
            if (models == null || TextUtils.isEmpty(modelName)) {
                return null;
            }
            for (ModelProfile model : models) {
                if (model != null && TextUtils.equals(model.name, modelName)) {
                    return model;
                }
            }
            return null;
        }

        public String getActiveModelName() {
            ensureModelDefaults();
            ModelProfile activeModel = findModel(activeModelId);
            return activeModel == null ? "" : safeString(activeModel.name, "");
        }

        public void setActiveModelName(String modelName) {
            String safeName = safeString(modelName, "");
            if (TextUtils.isEmpty(safeName)) {
                activeModelId = "";
                return;
            }
            ModelProfile existing = findModelByName(safeName);
            if (existing == null) {
                existing = new ModelProfile("model_" + Math.max(1, models == null ? 1 : models.size() + 1), safeName);
                if (models == null) {
                    models = new ArrayList<>();
                }
                models.add(existing);
            }
            activeModelId = existing.id;
        }
    }

    public static final class ModelProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        public String id;
        public String name;

        public ModelProfile(String id, String name) {
            this.id = safeString(id, "");
            this.name = safeString(name, "");
        }

        public ModelProfile copy() {
            return new ModelProfile(id, name);
        }
    }

    public static final class PersonaProfile implements Serializable {
        private static final long serialVersionUID = 1L;

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

    public static final class GlobalPersonaProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        public String id;
        public String name;
        public String rawJson;

        public GlobalPersonaProfile(String id, String name, String rawJson) {
            this.id = safeString(id, "");
            this.name = safeString(name, "");
            this.rawJson = safeString(rawJson, "");
        }

        public GlobalPersonaProfile copy() {
            return new GlobalPersonaProfile(id, name, rawJson);
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

    public static List<String> normalizeOptionalLines(String rawText) {
        List<String> values = new ArrayList<>();
        if (rawText == null) {
            return values;
        }
        String[] lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return values;
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

    private static String normalizeOptionalText(String text) {
        return text == null ? "" : text.trim();
    }

    private static String normalizeOptionalBooleanText(String text) {
        Boolean parsed = parseOptionalBoolean(text);
        return parsed == null ? "" : String.valueOf(parsed);
    }

    private static String getOptionalString(SharedPreferences sp, String key, String defaultValue) {
        if (sp == null || TextUtils.isEmpty(key)) {
            return defaultIfNull(defaultValue, "");
        }
        if (!sp.contains(key)) {
            return defaultIfNull(defaultValue, "");
        }
        return defaultIfNull(sp.getString(key, defaultValue), "");
    }

    private static String defaultIfNull(String text, String defaultValue) {
        return text == null ? safeString(defaultValue, "") : text.trim();
    }

    public static double parseOptionalDouble(String text) {
        if (TextUtils.isEmpty(text)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    public static Boolean parseOptionalBoolean(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized)
                || "y".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)
                || "0".equals(normalized)
                || "no".equals(normalized)
                || "off".equals(normalized)
                || "n".equals(normalized)) {
            return false;
        }
        return null;
    }

    private static String coalesce(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? safeString(fallback, "") : primary;
    }

    private String resolveDefaultGlobalPersonaJson() {
        return firstNonEmpty(globalPersonaJson, personaJsonSisterHigh, builtinPersonaJsonSisterHigh, "");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value.trim();
            }
        }
        return "";
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

    static String normalizeUpstreamProxyType(String value, String host, int port) {
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

    static String normalizeMultimodalCapability(String value) {
        String normalized = safeString(value, "auto").toLowerCase(Locale.ROOT);
        if ("yes".equals(normalized) || "true".equals(normalized) || "on".equals(normalized)) {
            return "yes";
        }
        if ("no".equals(normalized) || "false".equals(normalized) || "off".equals(normalized)) {
            return "no";
        }
        return "auto";
    }

    static String normalizeCaptionStrategy(String value) {
        String normalized = safeString(value, "inject").toLowerCase(Locale.ROOT);
        if ("tool".equals(normalized) || "off".equals(normalized) || "inject".equals(normalized)) {
            return normalized;
        }
        return "inject";
    }

    /**
     * v1.5.6+：debug 提示词导出文件的详细程度归一化。
     * 默认 / 不识别值都回落到 verbose（保持向后兼容）。
     */
    public static String normalizeDebugPromptDetailLevel(String value) {
        String normalized = safeString(value, DEBUG_PROMPT_LEVEL_VERBOSE).toLowerCase(Locale.ROOT);
        if (DEBUG_PROMPT_LEVEL_SIMPLIFIED.equals(normalized)) {
            return DEBUG_PROMPT_LEVEL_SIMPLIFIED;
        }
        return DEBUG_PROMPT_LEVEL_VERBOSE;
    }

    /** v1.5.5+：CS-3 — 副模型图像 detail 字段三态。auto=启发式，on=强制添加，off=强制移除。 */
    static String normalizeCaptionImageDetailMode(String value) {
        String normalized = safeString(value, "auto").toLowerCase(Locale.ROOT);
        if ("on".equals(normalized) || "yes".equals(normalized) || "true".equals(normalized)) {
            return "on";
        }
        if ("off".equals(normalized) || "no".equals(normalized) || "false".equals(normalized)) {
            return "off";
        }
        return "auto";
    }

    /** v1.5.5+：CS-3 — 副模型图文顺序三态。auto=启发式，image_first=图在前，text_first=文在前。 */
    static String normalizeCaptionImagePlacementMode(String value) {
        String normalized = safeString(value, "auto").toLowerCase(Locale.ROOT);
        if ("image_first".equals(normalized) || "image".equals(normalized)) {
            return "image_first";
        }
        if ("text_first".equals(normalized) || "text".equals(normalized)) {
            return "text_first";
        }
        return "auto";
    }

    /** v1.5.5+：CS-3 — 副模型 image_url 格式三态。auto=启发式，data_url=data:开头，raw_base64=纯 base64。 */
    static String normalizeCaptionImageUrlFormat(String value) {
        String normalized = safeString(value, "auto").toLowerCase(Locale.ROOT);
        if ("data_url".equals(normalized) || "dataurl".equals(normalized) || "data".equals(normalized)) {
            return "data_url";
        }
        if ("raw_base64".equals(normalized) || "raw".equals(normalized) || "base64".equals(normalized)) {
            return "raw_base64";
        }
        return "auto";
    }

    static String normalizeWebSearchToolEnabled(String value) {
        String normalized = safeString(value, "fallback").toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "always".equals(normalized) || "fallback".equals(normalized)) {
            return normalized;
        }
        return "fallback";
    }

    static String normalizeWebSearchProvider(String value) {
        String normalized = safeString(value, "bochaai").toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "tavily":
            case "serper":
            case "bochaai":
            case "bing_cn":
            case "duckduckgo_html":
                return normalized;
            case "qianfan":
            case "baidu_qianfan":
            case "qianfan_ai_search":
                return "qianfan_ai_search";
            case "volcengine":
            case "volcengine_native":
            case "volcengine_web_search":
            case "ark_web_search":
                return "volcengine_web_search";
            // v1.5.4+："auto" 已弃用，统一兜底到 bochaai（国内默认）。
            // 旧 SP 中残留的 "auto"（v1.5.0/v1.5.1 用户）会经此兜底升级。
            case "auto":
            default:
                return "bochaai";
        }
    }

    static String normalizeWebSearchApiKeysJson(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        try {
            JSONObject source = new JSONObject(value);
            JSONObject normalized = new JSONObject();
            JSONArray names = source.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String engine = normalizeWebSearchProvider(names.optString(i, ""));
                String key = source.optString(names.optString(i, ""), "");
                if (!TextUtils.isEmpty(key)) {
                    normalized.put(engine, key);
                }
            }
            return normalized.length() == 0 ? "" : normalized.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String getWebSearchApiKey(String keysJson, String provider) {
        String engine = normalizeWebSearchProvider(provider);
        if (TextUtils.isEmpty(keysJson)) {
            return "";
        }
        try {
            return safeString(new JSONObject(keysJson).optString(engine, ""), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    static String withWebSearchApiKey(String keysJson, String provider, String apiKey) {
        String engine = normalizeWebSearchProvider(provider);
        JSONObject object = new JSONObject();
        try {
            if (!TextUtils.isEmpty(keysJson)) {
                object = new JSONObject(keysJson);
            }
        } catch (Exception ignored) {
            object = new JSONObject();
        }
        try {
            String value = safeString(apiKey, "");
            if (TextUtils.isEmpty(value)) {
                object.remove(engine);
            } else {
                object.put(engine, value);
            }
            return object.length() == 0 ? "" : object.toString();
        } catch (Exception ignored) {
            return keysJson == null ? "" : keysJson;
        }
    }

    // v1.5.1+：per-engine 端点独立保存
    static String normalizeWebSearchEndpointsJson(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            JSONObject source = new JSONObject(value);
            JSONObject normalized = new JSONObject();
            JSONArray names = source.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String engine = normalizeWebSearchProvider(names.optString(i, ""));
                String endpoint = source.optString(names.optString(i, ""), "");
                if (!TextUtils.isEmpty(endpoint)) {
                    normalized.put(engine, endpoint);
                }
            }
            return normalized.length() == 0 ? "" : normalized.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String getWebSearchEndpoint(String endpointsJson, String provider) {
        String engine = normalizeWebSearchProvider(provider);
        if (TextUtils.isEmpty(endpointsJson)) return "";
        try {
            return safeString(new JSONObject(endpointsJson).optString(engine, ""), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    static String withWebSearchEndpoint(String endpointsJson, String provider, String endpoint) {
        String engine = normalizeWebSearchProvider(provider);
        JSONObject object = new JSONObject();
        try {
            if (!TextUtils.isEmpty(endpointsJson)) object = new JSONObject(endpointsJson);
        } catch (Exception ignored) {
            object = new JSONObject();
        }
        try {
            String value = safeString(endpoint, "");
            if (TextUtils.isEmpty(value)) {
                object.remove(engine);
            } else {
                object.put(engine, value);
            }
            return object.length() == 0 ? "" : object.toString();
        } catch (Exception ignored) {
            return endpointsJson == null ? "" : endpointsJson;
        }
    }

    // v1.5.1+：per-engine 出站代理独立保存（type/host/port 一组）
    static final class WebSearchProxyEntry {
        public String type = DEFAULT_UPSTREAM_PROXY_TYPE;
        public String host = DEFAULT_UPSTREAM_PROXY_HOST;
        public int port = DEFAULT_UPSTREAM_PROXY_PORT;
    }

    static String normalizeWebSearchProxiesJson(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            JSONObject source = new JSONObject(value);
            JSONObject normalized = new JSONObject();
            JSONArray names = source.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String engine = normalizeWebSearchProvider(names.optString(i, ""));
                JSONObject proxyObj = source.optJSONObject(names.optString(i, ""));
                if (proxyObj == null) continue;
                String type = safeString(proxyObj.optString("type", DEFAULT_UPSTREAM_PROXY_TYPE), DEFAULT_UPSTREAM_PROXY_TYPE);
                String host = safeString(proxyObj.optString("host", DEFAULT_UPSTREAM_PROXY_HOST), DEFAULT_UPSTREAM_PROXY_HOST);
                int port = proxyObj.optInt("port", DEFAULT_UPSTREAM_PROXY_PORT);
                String resolvedType = normalizeUpstreamProxyType(type, host, port);
                if (UPSTREAM_PROXY_DIRECT.equals(resolvedType) && TextUtils.isEmpty(host) && port == DEFAULT_UPSTREAM_PROXY_PORT) {
                    continue; // 默认值不存
                }
                JSONObject entry = new JSONObject();
                entry.put("type", resolvedType);
                entry.put("host", host);
                entry.put("port", Math.max(0, port));
                normalized.put(engine, entry);
            }
            return normalized.length() == 0 ? "" : normalized.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static WebSearchProxyEntry getWebSearchProxy(String proxiesJson, String provider) {
        WebSearchProxyEntry entry = new WebSearchProxyEntry();
        String engine = normalizeWebSearchProvider(provider);
        if (TextUtils.isEmpty(proxiesJson)) return entry;
        try {
            JSONObject proxyObj = new JSONObject(proxiesJson).optJSONObject(engine);
            if (proxyObj != null) {
                entry.type = safeString(proxyObj.optString("type", DEFAULT_UPSTREAM_PROXY_TYPE), DEFAULT_UPSTREAM_PROXY_TYPE);
                entry.host = safeString(proxyObj.optString("host", DEFAULT_UPSTREAM_PROXY_HOST), DEFAULT_UPSTREAM_PROXY_HOST);
                entry.port = proxyObj.optInt("port", DEFAULT_UPSTREAM_PROXY_PORT);
            }
        } catch (Exception ignored) {
        }
        return entry;
    }

    static String withWebSearchProxy(String proxiesJson, String provider, String type, String host, int port) {
        String engine = normalizeWebSearchProvider(provider);
        JSONObject object = new JSONObject();
        try {
            if (!TextUtils.isEmpty(proxiesJson)) object = new JSONObject(proxiesJson);
        } catch (Exception ignored) {
            object = new JSONObject();
        }
        try {
            String resolvedType = normalizeUpstreamProxyType(type, host, port);
            String safeHost = safeString(host, "");
            int safePort = Math.max(0, port);
            if (UPSTREAM_PROXY_DIRECT.equals(resolvedType) && TextUtils.isEmpty(safeHost) && safePort == DEFAULT_UPSTREAM_PROXY_PORT) {
                object.remove(engine);
            } else {
                JSONObject entry = new JSONObject();
                entry.put("type", resolvedType);
                entry.put("host", safeHost);
                entry.put("port", safePort);
                object.put(engine, entry);
            }
            return object.length() == 0 ? "" : object.toString();
        } catch (Exception ignored) {
            return proxiesJson == null ? "" : proxiesJson;
        }
    }

    private static SharedPreferences openSecurePreferences(Context context) {
        if (context == null) {
            return null;
        }
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    SECURE_PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception error) {
            Log.w(TAG, "secure prefs unavailable, falling back to plain: " + error.getMessage());
            return null;
        }
    }

    private static void loadProviderApiKeys(Context context, SharedPreferences legacy, List<ProviderProfile> profiles, String legacyApiKey) {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        for (ProviderProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            String value = readSecureProviderApiKey(context, legacy, profile.id);
            if (TextUtils.isEmpty(value)
                    && (TextUtils.equals(profile.id, DEFAULT_PROVIDER_ID) || profiles.size() == 1)) {
                value = legacyApiKey;
            }
            profile.apiKey = safeString(value, "");
        }
    }

    private static String readSecureProviderApiKey(Context context, SharedPreferences legacy, String providerId) {
        String key = providerApiKeyStorageKey(providerId);
        SharedPreferences secure = openSecurePreferences(context);
        if (secure != null) {
            return safeString(secure.getString(key, ""), "");
        }
        return legacy == null ? "" : safeString(legacy.getString(key, ""), "");
    }

    private static void writeProviderApiKeys(Context context, List<ProviderProfile> profiles) {
        if (context == null || profiles == null) {
            return;
        }
        SharedPreferences secure = openSecurePreferences(context);
        SharedPreferences store = secure == null
                ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                : secure;
        SharedPreferences.Editor editor = secure == null
                ? store.edit()
                : store.edit();
        List<String> liveKeys = new ArrayList<>();
        for (ProviderProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            String key = providerApiKeyStorageKey(profile.id);
            liveKeys.add(key);
            String value = safeString(profile.apiKey, "");
            if (TextUtils.isEmpty(value)) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
        }
        for (String key : store.getAll().keySet()) {
            if (key != null && key.startsWith("providerApiKey_") && !liveKeys.contains(key)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static String providerApiKeyStorageKey(String providerId) {
        String normalized = safeString(providerId, DEFAULT_PROVIDER_ID).replaceAll("[^A-Za-z0-9_.-]", "_");
        return "providerApiKey_" + normalized;
    }

    private static String readSecureApiKey(Context context, SharedPreferences legacy) {
        SharedPreferences secure = openSecurePreferences(context);
        if (secure != null) {
            String value = safeString(secure.getString(SECURE_KEY_API_KEY, ""), "");
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        String legacyValue = legacy == null ? "" : safeString(legacy.getString(LEGACY_KEY_API_KEY, ""), "");
        if (!TextUtils.isEmpty(legacyValue)) {
            if (secure != null) {
                try {
                    secure.edit().putString(SECURE_KEY_API_KEY, legacyValue).apply();
                    legacy.edit().remove(LEGACY_KEY_API_KEY).apply();
                    Log.i(TAG, "migrated apiKey from legacy prefs to secure prefs");
                } catch (Exception error) {
                    Log.w(TAG, "apiKey migration failed: " + error.getMessage());
                }
            }
            return legacyValue;
        }
        return "";
    }

    private static void writeSecureApiKey(Context context, String value) {
        SharedPreferences secure = openSecurePreferences(context);
        String safeValue = safeString(value, "");
        if (secure != null) {
            try {
                if (TextUtils.isEmpty(safeValue)) {
                    secure.edit().remove(SECURE_KEY_API_KEY).apply();
                } else {
                    secure.edit().putString(SECURE_KEY_API_KEY, safeValue).apply();
                }
                return;
            } catch (Exception error) {
                Log.w(TAG, "secure prefs write failed, falling back to plain: " + error.getMessage());
            }
        }
        if (context != null) {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            if (TextUtils.isEmpty(safeValue)) {
                editor.remove(LEGACY_KEY_API_KEY);
            } else {
                editor.putString(LEGACY_KEY_API_KEY, safeValue);
            }
            editor.apply();
        }
    }
}
