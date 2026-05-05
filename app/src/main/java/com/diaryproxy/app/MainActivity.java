package com.diaryproxy.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_LOG_CHARS = 120000;
    private static final int KEEP_LOG_CHARS = 90000;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 41031;
    private static final String PREF_UI_LOG_AUTO_SCROLL = "uiLogAutoScrollEnabled";
    private static final String PREF_UI_DARK_MODE = "uiDarkModeEnabled";
    private static final String PREF_UI_THEME_DRAFT_CONFIG = "uiThemeDraftConfig";
    private static final String HOME_MODEL_SEPARATOR = "\u001F";
    private static ProxyConfig pendingThemeUiDraftConfig;

    private EditText etBaseUrl;
    private EditText etApiKey;
    private Spinner spHomeActiveModel;
    // v1.6.0+：主页"当前模型"实际入口改成 BottomSheet picker；spHomeActiveModel 退化为不可见数据载体
    private View btnHomeActiveModel;
    private TextView tvHomeActiveModelLabel;
    private Spinner spProviderProfile;
    private EditText etPort;
    private EditText etTimeout;
    private Spinner spUpstreamProxyType;
    private EditText etUpstreamProxyHost;
    private EditText etUpstreamProxyPort;
    private Spinner spStrictness;
    private Spinner spAdapterPreset;
    private Spinner spPersonaProfile;
    private Spinner spGlobalPersonaProfile;
    private Spinner spPersonaTier;
    private EditText etMinLength;
    private EditText etMinLines;
    private EditText etPrefixes;
    private EditText etKeywords;
    private EditText etPersonaMatchKeywords;
    private EditText etInteractiveStoryKeywords;
    private EditText etNormalTemplate;
    private EditText etHolidayTemplate;
    private EditText etOverrideMaxTokens;
    private EditText etChatOverrideMaxTokens;
    private EditText etChatOverrideTemperature;
    private EditText etChatOverrideEnableThinking;
    private EditText etDiaryOverrideTemperature;
    private EditText etDiaryOverrideEnableThinking;
    // v1.5.x：日记 assistant 前缀续写（per-provider）
    private CheckBox cbDiaryAssistantPrefixEnabled;
    private EditText etDiaryAssistantPrefix;
    private CheckBox cbDiaryAssistantPrefixDeepseekMode;
    private CheckBox cbDiaryAssistantPrefixSiliconflowMode;
    private EditText etChatCustomRequestFields;
    private EditText etDiaryCustomRequestFields;
    private EditText etMemoryExtractTemplate;
    private EditText etMemoryExtractOverrideMaxTokens;
    private EditText etMemoryExtractOverrideTemperature;
    private EditText etMemoryExtractOverrideEnableThinking;
    private EditText etMemoryExtractCustomRequestFields;
    // v1.5.1+：主页放视觉模型 + 副模型策略；详情页只保留联网搜索
    private Spinner spHomeCaptionProvider;
    // v1.6.0+：主页"视觉模型"入口改成 BottomSheet picker；spHomeCaptionProvider 退化为不可见数据载体
    private View btnHomeCaptionProvider;
    private TextView tvHomeCaptionProviderLabel;
    private Spinner spHomeCaptionStrategy;
    // v1.5.5+：CS-4 / CS-3 副模型 max_tokens + 三态图像 quirk
    private EditText etCaptionMaxTokens;
    private Spinner spCaptionImageDetailMode;
    private Spinner spCaptionImagePlacementMode;
    private Spinner spCaptionImageUrlFormat;
    // v1.5.5+：DPS-8 调试页"文档处理"分类
    private CheckBox cbDocumentTruncationEnabled;
    private EditText etDocumentTruncationMaxChars;
    // v1.5.1+：主页镜像联网搜索开关；引擎/Key/端点等在抽屉「联网搜索」独立页（全局，不再 per-provider）
    private Spinner spHomeWebSearchToolEnabled;
    private Spinner spHomeWebSearchProvider;
    private Button btnHomeTestWebSearch;
    private boolean webSearchApiKeyVisible = false;
    private Spinner spWebSearchToolEnabled;
    private Spinner spWebSearchProvider;
    private EditText etWebSearchApiKey;
    private EditText etWebSearchEndpoint;
    private Spinner spWebSearchProxyType;
    private EditText etWebSearchProxyHost;
    private EditText etWebSearchProxyPort;
    private EditText etWebSearchMaxResults;
    private String currentWebSearchProviderDraft = "bochaai";
    /** v1.5.1+：联网搜索全局 ApiKeys / Endpoints / Proxies JSON（per-engine 独立）。在 UI 编辑时 staging 在内存，readConfigFromUi 时写到 cfg 顶层。 */
    private String pendingWebSearchApiKeysJson = "";
    private String pendingWebSearchEndpointsJson = "";
    private String pendingWebSearchProxiesJson = "";
    private boolean v15SpinnerReady = false;
    private static final String[] CAPTION_STRATEGY_VALUES = {"inject", "tool", "off"};
    private static final String[] CAPTION_STRATEGY_LABELS = {"A：注入描述", "B：工具调用", "关闭（直传图片）"};
    // v1.5.5+：CS-3 副模型图像 quirk 三态
    private static final String[] CAPTION_IMAGE_DETAIL_VALUES = {"auto", "on", "off"};
    private static final String[] CAPTION_IMAGE_DETAIL_LABELS = {"auto（启发式）", "强制启用", "强制禁用"};
    private static final String[] CAPTION_IMAGE_PLACEMENT_VALUES = {"auto", "image_first", "text_first"};
    private static final String[] CAPTION_IMAGE_PLACEMENT_LABELS = {"auto（启发式）", "图在前", "文在前"};
    private static final String[] CAPTION_IMAGE_URL_FORMAT_VALUES = {"auto", "data_url", "raw_base64"};
    private static final String[] CAPTION_IMAGE_URL_FORMAT_LABELS = {"auto（启发式）", "data:URL", "纯 base64"};
    private static final String[] WEB_SEARCH_TOOL_VALUES = {"off", "fallback", "always"};
    private static final String[] WEB_SEARCH_TOOL_LABELS = {"关闭", "fallback（游戏未发 tools 时）", "总是注入"};
    private static final String[] WEB_SEARCH_PROVIDER_VALUES = {"bochaai", "qianfan_ai_search", "volcengine_web_search", "bing_cn", "tavily", "serper", "duckduckgo_html"};
    private static final String[] WEB_SEARCH_PROVIDER_LABELS = {
            "博查 AI（国内）",
            "百度千帆 AI 搜索（国内）",
            "火山联网搜索 API（国内）",
            "必应中国",
            "Tavily（国外）",
            "Serper（国外）",
            "DuckDuckGo HTML（免 key，国外）"
    };
    private static final String CAPTION_PROVIDER_NONE = "";
    /** 视觉模型 Spinner 当前 tag 值列表（与 spinner adapter 对应；空串 = 不启用）。 */
    private java.util.List<String> homeCaptionProviderIds = new ArrayList<>();
    private EditText etMaxChars;
    private EditText etListenChatPaths;
    private EditText etListenModelsPaths;
    private EditText etUpstreamChatPath;
    private EditText etUpstreamModelsPath;
    private EditText etRequestMessagesPath;
    private EditText etRequestUserTextPath;
    private EditText etRequestModelPath;
    private EditText etRequestMaxTokensPath;
    private EditText etRequestTemperaturePath;
    private EditText etRequestEnableThinkingPath;
    private EditText etResponseTextPath;
    private EditText etPersonaJson;
    private EditText etFeedbackWebhookUrl;
    private EditText etUpdateManifestUrls;
    private EditText etUpdateManifestPublicKey;
    private CheckBox cbDetectionEnabled;
    private CheckBox cbDryRun;
    private CheckBox cbTruncate;
    private CheckBox cbSaveNormalHistory;
    private CheckBox cbSaveDiaryHistory;
    private CheckBox cbDebugPromptDump;
    // v1.5.6+：debug 提示词导出文件的详细程度（详细 / 精简）
    private RadioGroup rgDebugPromptDetailLevel;
    private RadioButton rbDebugPromptLevelVerbose;
    private RadioButton rbDebugPromptLevelSimplified;
    private CheckBox cbLogAutoScroll;
    private CheckBox cbStripEnableThinking;
    private CheckBox cbStripRestrictionLine;
    private CheckBox cbStripSystemTime;
    private CheckBox cbPersonaEnabled;
    private SwitchCompat swPersonaIgnoreAffinity;
    private TextView tvStatus;
    private TextView tvRequestStatus;
    private TextView tvActionStatus;
    private TextView tvEndpoint;
    private TextView tvHistoryPath;
    private TextView tvKeepAliveStatus;
    private TextView tvLogs;
    private TextView tvRulesStatus;
    private TextView tvPersonaJsonLabel;
    private TextView tvPersonaTierLabel;
    private TextView tvSettingsPageTitle;
    private TextView tvUpstreamChatPreview;
    private TextView tvUpstreamModelsPreview;
    private View settingsDrawer;
    private View layoutFullscreenEditor;
    private View layoutFastScroll;
    private View viewFastScrollThumb;
    private ScrollView scrollRoot;
    private ScrollView settingsScroll;
    private Button btnStartStop;
    private Button btnTest;
    private TextView btnOpenSettings;
    private TextView btnCloseSettings;
    private Button btnApiKeyCheck;
    private Button btnToggleAdvancedNetwork;
    private Button btnToggleProviderProtocolFields;
    private Button btnToggleRules;
    private Button btnReplayLastRequest;
    private Button btnKeepAliveHelp;

    // v1.5.0：附件区
    private android.widget.LinearLayout layoutAttachments;
    private androidx.recyclerview.widget.RecyclerView rvAttachmentList;
    private Button btnAddAttachmentImage;
    private Button btnAddAttachmentDoc;
    private Button btnClearAttachments;
    private TextView tvAttachmentHint;
    private final java.util.List<ProxyStorageHelper.AttachmentRef> attachmentDrafts = new ArrayList<>();
    private AttachmentDraftAdapter attachmentAdapter;
    private androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickImageLauncher;
    private androidx.activity.result.ActivityResultLauncher<String[]> pickDocumentLauncher;
    private Button btnPersonaAdd;
    private Button btnPersonaCopy;
    private Button btnPersonaRename;
    private Button btnPersonaDelete;
    private Button btnGlobalPersonaAdd;
    private Button btnGlobalPersonaCopy;
    private Button btnGlobalPersonaRename;
    private Button btnGlobalPersonaDelete;
    private Button btnProviderAdd;
    private Button btnProviderCopy;
    private Button btnProviderRename;
    private Button btnProviderDelete;
    private Button btnProviderListAdd;
    private Button btnModelDelete;
    private TextView btnSendFeedback;
    private TextView btnCheckUpdate;
    private TextView btnThemeToggle;
    private TextView btnClearLog;
    private TextView btnCopyLog;
    private TextView btnOpenLogsFullscreen;
    private TextView tvFullscreenTitle;
    private TextView tvFullscreenUndo;
    private TextView tvFullscreenCancel;
    private TextView tvFullscreenSave;
    private EditText etFullscreenEditor;
    private LinearLayout layoutMatchingRules;
    private LinearLayout layoutGenericFields;
    private LinearLayout layoutAdvancedNetwork;
    private LinearLayout layoutProviderProtocolFields;
    private LinearLayout pageConnection;
    private LinearLayout layoutProviderList;
    private LinearLayout layoutProviderRows;
    private LinearLayout layoutProviderModelRows;
    private LinearLayout settingsMenu;
    private LinearLayout layoutTierPersonaEditor;
    private LinearLayout layoutGlobalPersonaEditor;
    private View[] settingsPages = new View[0];
    private TextView[] settingsMenuItems = new TextView[0];
    private final String[] settingsPageTitles = new String[]{
            "连接 / API",
            "识别与日记",
            "人设与聊天",
            "长期记忆",
            "记录与调试"
    };

    private boolean receiverRegistered = false;
    private boolean personaSpinnerReady = false;
    private boolean personaProfileSpinnerReady = false;
    private boolean globalPersonaProfileSpinnerReady = false;
    private boolean providerSpinnerReady = false;
    private boolean providerModelSpinnerReady = false;
    private boolean homeModelSpinnerReady = false;
    private boolean apiKeyVisible = false;
    private boolean suppressAdapterAutoPaths = false;
    private boolean pendingStartAfterNotificationPermission = false;
    private boolean autoUpdateCheckStarted = false;
    private boolean crashPromptShown = false;
    private boolean settingsDetailVisible = false;
    private boolean providerDetailVisible = false;
    private boolean fullscreenEditorReadOnly = false;
    private boolean suppressFullscreenUndo = false;
    /** v1.6.0+：apply* 期间临时关闭自动保存监听，避免 setText / setSelection 触发回写。 */
    private boolean suppressUnsavedTracking = false;
    /** v1.6.0+：自动保存（WYSIWYG）调度器。停笔 ~350ms 后跑一次 persistConfigSilently。 */
    private final Handler unsavedHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshUnsavedSettingsRunnable = this::persistConfigSilently;
    private EditText fullscreenSourceEditText;
    private String fullscreenSourceOriginalText = "";
    private android.text.method.KeyListener fullscreenEditorKeyListener;
    private final ArrayList<String> fullscreenUndoStack = new ArrayList<>();
    private String currentPersonaProfileId = ProxyConfig.DEFAULT_PERSONA_PROFILE_ID;
    private String currentGlobalPersonaProfileId = ProxyConfig.DEFAULT_GLOBAL_PERSONA_PROFILE_ID;
    private String currentPersonaTier = ProxyConfig.TIER_SISTER_HIGH;
    private String currentProviderId = ProxyConfig.DEFAULT_PROVIDER_ID;
    private String activeProviderIdDraft = ProxyConfig.DEFAULT_PROVIDER_ID;
    private String lastAdapterPreset = "";
    private boolean guardianRunning = false;
    private final Map<String, String> personaProfileNames = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, String>> personaProfileDrafts = new LinkedHashMap<>();
    private final Map<String, String> globalPersonaProfileNames = new LinkedHashMap<>();
    private final Map<String, String> globalPersonaProfileDrafts = new LinkedHashMap<>();
    private final Map<String, ProxyConfig.ProviderProfile> providerProfileDrafts = new LinkedHashMap<>();
    private final Map<String, ArrayList<String>> providerFetchedModelDrafts = new LinkedHashMap<>();

    /** v1.6.0+：自动保存（WYSIWYG）后台 executor。把 ProxyConfig.save 等 SP / 加密写入推到工作线程，
     *  让连续输入时主线程只负责 readConfigFromUi（纯 View 读），完全不受 SharedPreferences 阻塞。
     *
     *  v1.5.6+：MA-9 — 直接构造 ThreadPoolExecutor（而非 Executors.newSingleThreadExecutor 的包装类），
     *  这样 onPause 可以用 instanceof + getActiveCount() 等待在飞的 SP 写入完成（最多 200ms）。 */
    private final java.util.concurrent.ExecutorService autoSaveExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "AutoSaveWriter");
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        thread.setDaemon(true);
                        return thread;
                    });

    /**
     * v1.5.4+：通用后台任务 executor（MA-1）。
     * 取代之前 8 处 `new Thread(...).start()`，统一线程命名 "MainActivity-Bg"，
     * 便于 crash 栈定位 + 复用线程池减少创建开销。
     *
     * v1.5.6+：MA-7 — 由 newCachedThreadPool（无界 + 无 max）改为有界 ThreadPoolExecutor：
     *  - 核心 2 / 最大 8，空闲 60s 回收；
     *  - ArrayBlockingQueue(32) 排队；
     *  - DiscardOldestPolicy：满载时丢最早的待执行任务（UI 上轻量任务被新任务取代不会有副作用）。
     * 用户狂点"获取模型 / 测试上游 / 检查更新"时不会无限新建线程导致 OOM 或崩溃。
     */
    private final java.util.concurrent.ExecutorService bgExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(32),
                    runnable -> {
                        Thread thread = new Thread(runnable, "MainActivity-Bg");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    private final BroadcastReceiver serviceEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (ProxyForegroundService.ACTION_LOG_EVENT.equals(action)) {
                String line = intent.getStringExtra(ProxyForegroundService.EXTRA_LOG_LINE);
                if (!TextUtils.isEmpty(line)) {
                    appendLogRaw(line);
                }
                return;
            }
            if (ProxyForegroundService.ACTION_STATE_EVENT.equals(action)) {
                boolean guardianRunning = intent.getBooleanExtra(ProxyForegroundService.EXTRA_GUARDIAN_RUNNING, false);
                boolean serverRunning = intent.getBooleanExtra(ProxyForegroundService.EXTRA_SERVER_RUNNING, false);
                boolean recentChatRequest = intent.getBooleanExtra(ProxyForegroundService.EXTRA_RECENT_CHAT_REQUEST, false);
                long lastChatRequestAt = intent.getLongExtra(ProxyForegroundService.EXTRA_LAST_CHAT_REQUEST_AT, 0L);
                String requestStatusText = intent.getStringExtra(ProxyForegroundService.EXTRA_REQUEST_STATUS_TEXT);
                updateRunningUi(new ProxyForegroundService.ServiceStateSnapshot(
                        guardianRunning,
                        serverRunning,
                        recentChatRequest,
                        lastChatRequestAt,
                        requestStatusText
                ));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedNightMode();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        applyCompactHintsToAllEditTexts(findViewById(android.R.id.content));
        hideLegacyFeedbackUpdateCard();
        moveLogAutoScrollBelowReplay();
        buildFooterFeedbackCard();
        initSpinners();
        setupLogScrolling();
        setupScrollableFields();
        setupLongTextEditors();
        setupListeners();
        setupUnsavedTracking();
        loadUiPreferences();
        applyApiKeyVisibility();
        hideObsoleteProtocolHints();

        ProxyConfig config = ProxyConfig.load(this);
        applyConfigToUi(config);
        restorePendingThemeDraftIfAny();
        applyRuntimeThemeColors();
        hideObsoleteProtocolHints();
        updateKeepAliveStatus();
        syncLogsFromServiceCache();
        updateRunningUi(ProxyForegroundService.getCachedState(this));
        setActionStatus("就绪");
        maybePromptCrashReport();
        if (scrollRoot != null) {
            scrollRoot.postDelayed(this::maybeAutoCheckUpdates, 1800L);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerServiceReceiver();
        updateKeepAliveStatus();
        syncLogsFromServiceCache();
        updateRunningUi(ProxyForegroundService.getCachedState(this));
        // v1.5.0：刷新附件草稿（外部 service 转发时可能已清掉部分草稿）
        if (rvAttachmentList != null) {
            refreshAttachmentDrafts();
        }
    }

    @Override
    protected void onStop() {
        unregisterServiceReceiver();
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // v1.6.0+：切到后台前立即 flush 挂起的自动保存，避免用户在 350ms debounce 内
        // 退出/切换 app 时丢失最后那笔改动。removeCallbacks 防止双写。
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        try {
            persistConfigSilently();
        } catch (Exception ignored) {
        }
        // v1.5.6+：MA-9 — persistConfigSilently 把任务投到 autoSaveExecutor 异步执行，
        // 但 onResume 立即从 SP 读，可能读到上一轮的旧值。这里短暂等待 ≤200ms，
        // 让在飞的 SP 写入有机会落地。超时则放弃（极端情况优先保证 onPause 不阻塞）。
        if (autoSaveExecutor instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor tpe =
                    (java.util.concurrent.ThreadPoolExecutor) autoSaveExecutor;
            long deadline = System.currentTimeMillis() + 200;
            while (tpe.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        // v1.5.4+：MA-6 — 防止 destroy 后 350ms 内的 debounce 任务在 destroyed Activity 上跑 readConfigFromUi 导致 NPE。
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        if (!autoSaveExecutor.isShutdown()) {
            autoSaveExecutor.shutdown();
            // v1.5.4+：MA-4 — 等待最多 2s，让最后一笔 SP 写入完成（短时窗口的配置丢失修复）。
            try {
                autoSaveExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        super.onDestroy();
    }

    /**
     * v1.5.4+：Activity 是否仍活着（未被 finish 也未被 system 销毁）。
     * 所有 runOnUiThread 异步回调里弹 AlertDialog 之前必须先检查，
     * 否则 destroyed Activity 上 AlertDialog.show() 会抛 BadTokenException 崩溃。
     * 解决 MA-2 / MA-5。
     */
    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    /**
     * v1.5.6+：MA-8 — 异步回调里弹 AlertDialog 的统一入口。
     *
     * <p>之前所有"异步任务完成 → 弹结果对话框"都重复写：
     * <pre>runOnUiThread(() -&gt; { if (!isAlive()) return; new AlertDialog.Builder(this)...show(); });</pre>
     * 容易漏写 isAlive 检查或者 try/catch BadTokenException。本 helper 把这三件事统一：
     * <ul>
     *   <li>切到 UI 线程；</li>
     *   <li>发生 Activity 已 finish/destroy 时静默丢弃；</li>
     *   <li>show() 抛 WindowManager.BadTokenException 时吞掉（极端竞态：刚通过 isAlive 检查，
     *       立刻被 system 销毁 → AlertDialog 仍可能抛 BadTokenException）。</li>
     * </ul></p>
     */
    private void safeShowDialog(Runnable showAction) {
        if (showAction == null) return;
        runOnUiThread(() -> {
            if (!isAlive()) return;
            try {
                showAction.run();
            } catch (android.view.WindowManager.BadTokenException ignored) {
                // Activity 已被销毁，忽略
            } catch (IllegalStateException ignored) {
                // 极端竞态：Activity state 已改变
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (layoutFullscreenEditor != null && layoutFullscreenEditor.getVisibility() == View.VISIBLE) {
            closeFullscreenEditor(false);
            return;
        }
        if (settingsDrawer != null && settingsDrawer.getVisibility() == View.VISIBLE) {
            handleSettingsBack();
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        settingsDrawer = findViewById(R.id.settingsDrawer);
        layoutFullscreenEditor = findViewById(R.id.layoutFullscreenEditor);
        layoutFastScroll = findViewById(R.id.layoutFastScroll);
        viewFastScrollThumb = findViewById(R.id.viewFastScrollThumb);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        spHomeActiveModel = findViewById(R.id.spHomeActiveModel);
        btnHomeActiveModel = findViewById(R.id.btnHomeActiveModel);
        tvHomeActiveModelLabel = findViewById(R.id.tvHomeActiveModelLabel);
        spProviderProfile = findViewById(R.id.spProviderProfile);
        etPort = findViewById(R.id.etPort);
        etTimeout = findViewById(R.id.etTimeout);
        spUpstreamProxyType = findViewById(R.id.spUpstreamProxyType);
        etUpstreamProxyHost = findViewById(R.id.etUpstreamProxyHost);
        etUpstreamProxyPort = findViewById(R.id.etUpstreamProxyPort);
        spStrictness = findViewById(R.id.spStrictness);
        spAdapterPreset = findViewById(R.id.spAdapterPreset);
        spPersonaProfile = findViewById(R.id.spPersonaProfile);
        spGlobalPersonaProfile = findViewById(R.id.spGlobalPersonaProfile);
        spPersonaTier = findViewById(R.id.spPersonaTier);
        etMinLength = findViewById(R.id.etMinLength);
        etMinLines = findViewById(R.id.etMinLines);
        etPrefixes = findViewById(R.id.etPrefixes);
        etKeywords = findViewById(R.id.etKeywords);
        etPersonaMatchKeywords = findViewById(R.id.etPersonaMatchKeywords);
        etInteractiveStoryKeywords = findViewById(R.id.etInteractiveStoryKeywords);
        etNormalTemplate = findViewById(R.id.etNormalTemplate);
        etHolidayTemplate = findViewById(R.id.etHolidayTemplate);
        etOverrideMaxTokens = findViewById(R.id.etOverrideMaxTokens);
        etChatOverrideMaxTokens = findViewById(R.id.etChatOverrideMaxTokens);
        etChatOverrideTemperature = findViewById(R.id.etChatOverrideTemperature);
        etChatOverrideEnableThinking = findViewById(R.id.etChatOverrideEnableThinking);
        etDiaryOverrideTemperature = findViewById(R.id.etDiaryOverrideTemperature);
        etDiaryOverrideEnableThinking = findViewById(R.id.etDiaryOverrideEnableThinking);
        // v1.5.x：日记 assistant 前缀续写
        cbDiaryAssistantPrefixEnabled = findViewById(R.id.cbDiaryAssistantPrefixEnabled);
        etDiaryAssistantPrefix = findViewById(R.id.etDiaryAssistantPrefix);
        cbDiaryAssistantPrefixDeepseekMode = findViewById(R.id.cbDiaryAssistantPrefixDeepseekMode);
        cbDiaryAssistantPrefixSiliconflowMode = findViewById(R.id.cbDiaryAssistantPrefixSiliconflowMode);
        etChatCustomRequestFields = findViewById(R.id.etChatCustomRequestFields);
        etDiaryCustomRequestFields = findViewById(R.id.etDiaryCustomRequestFields);
        etMemoryExtractTemplate = findViewById(R.id.etMemoryExtractTemplate);
        etMemoryExtractOverrideMaxTokens = findViewById(R.id.etMemoryExtractOverrideMaxTokens);
        etMemoryExtractOverrideTemperature = findViewById(R.id.etMemoryExtractOverrideTemperature);
        etMemoryExtractOverrideEnableThinking = findViewById(R.id.etMemoryExtractOverrideEnableThinking);
        etMemoryExtractCustomRequestFields = findViewById(R.id.etMemoryExtractCustomRequestFields);
        // v1.5.1+：主页视觉模型 + 副模型策略；详情页只剩联网搜索
        spHomeCaptionProvider = findViewById(R.id.spHomeCaptionProvider);
        btnHomeCaptionProvider = findViewById(R.id.btnHomeCaptionProvider);
        tvHomeCaptionProviderLabel = findViewById(R.id.tvHomeCaptionProviderLabel);
        spHomeCaptionStrategy = findViewById(R.id.spHomeCaptionStrategy);
        // v1.5.5+：CS-4 / CS-3 副模型 max_tokens + 三态 quirk
        etCaptionMaxTokens = findViewById(R.id.etCaptionMaxTokens);
        spCaptionImageDetailMode = findViewById(R.id.spCaptionImageDetailMode);
        spCaptionImagePlacementMode = findViewById(R.id.spCaptionImagePlacementMode);
        spCaptionImageUrlFormat = findViewById(R.id.spCaptionImageUrlFormat);
        // v1.5.5+：DPS-8 调试页"文档处理"
        cbDocumentTruncationEnabled = findViewById(R.id.cbDocumentTruncationEnabled);
        etDocumentTruncationMaxChars = findViewById(R.id.etDocumentTruncationMaxChars);
        spHomeWebSearchToolEnabled = findViewById(R.id.spHomeWebSearchToolEnabled);
        spHomeWebSearchProvider = findViewById(R.id.spHomeWebSearchProvider);
        btnHomeTestWebSearch = findViewById(R.id.btnHomeTestWebSearch);
        spWebSearchToolEnabled = findViewById(R.id.spWebSearchToolEnabled);
        spWebSearchProvider = findViewById(R.id.spWebSearchProvider);
        etWebSearchApiKey = findViewById(R.id.etWebSearchApiKey);
        etWebSearchEndpoint = findViewById(R.id.etWebSearchEndpoint);
        spWebSearchProxyType = findViewById(R.id.spWebSearchProxyType);
        etWebSearchProxyHost = findViewById(R.id.etWebSearchProxyHost);
        etWebSearchProxyPort = findViewById(R.id.etWebSearchProxyPort);
        etWebSearchMaxResults = findViewById(R.id.etWebSearchMaxResults);
        setupV15Spinners();
        etMaxChars = findViewById(R.id.etMaxChars);
        etListenChatPaths = findViewById(R.id.etListenChatPaths);
        etListenModelsPaths = findViewById(R.id.etListenModelsPaths);
        etUpstreamChatPath = findViewById(R.id.etUpstreamChatPath);
        etUpstreamModelsPath = findViewById(R.id.etUpstreamModelsPath);
        etRequestMessagesPath = findViewById(R.id.etRequestMessagesPath);
        etRequestUserTextPath = findViewById(R.id.etRequestUserTextPath);
        etRequestModelPath = findViewById(R.id.etRequestModelPath);
        etRequestMaxTokensPath = findViewById(R.id.etRequestMaxTokensPath);
        etRequestTemperaturePath = findViewById(R.id.etRequestTemperaturePath);
        etRequestEnableThinkingPath = findViewById(R.id.etRequestEnableThinkingPath);
        etResponseTextPath = findViewById(R.id.etResponseTextPath);
        etPersonaJson = findViewById(R.id.etPersonaJson);
        etFeedbackWebhookUrl = findViewById(R.id.etFeedbackWebhookUrl);
        etUpdateManifestUrls = findViewById(R.id.etUpdateManifestUrls);
        etUpdateManifestPublicKey = findViewById(R.id.etUpdateManifestPublicKey);
        cbDetectionEnabled = findViewById(R.id.cbDetectionEnabled);
        cbDryRun = findViewById(R.id.cbDryRun);
        cbTruncate = findViewById(R.id.cbTruncate);
        cbSaveNormalHistory = findViewById(R.id.cbSaveNormalHistory);
        cbSaveDiaryHistory = findViewById(R.id.cbSaveDiaryHistory);
        cbDebugPromptDump = findViewById(R.id.cbDebugPromptDump);
        rgDebugPromptDetailLevel = findViewById(R.id.rgDebugPromptDetailLevel);
        rbDebugPromptLevelVerbose = findViewById(R.id.rbDebugPromptLevelVerbose);
        rbDebugPromptLevelSimplified = findViewById(R.id.rbDebugPromptLevelSimplified);
        cbLogAutoScroll = findViewById(R.id.cbLogAutoScroll);
        cbStripEnableThinking = findViewById(R.id.cbStripEnableThinking);
        cbStripRestrictionLine = findViewById(R.id.cbStripRestrictionLine);
        cbStripSystemTime = findViewById(R.id.cbStripSystemTime);
        cbPersonaEnabled = findViewById(R.id.cbPersonaEnabled);
        swPersonaIgnoreAffinity = findViewById(R.id.swPersonaIgnoreAffinity);
        tvStatus = findViewById(R.id.tvStatus);
        tvRequestStatus = findViewById(R.id.tvRequestStatus);
        tvActionStatus = findViewById(R.id.tvActionStatus);
        tvEndpoint = findViewById(R.id.tvEndpoint);
        tvHistoryPath = findViewById(R.id.tvHistoryPath);
        tvKeepAliveStatus = findViewById(R.id.tvKeepAliveStatus);
        tvLogs = findViewById(R.id.tvLogs);
        tvRulesStatus = findViewById(R.id.tvRulesStatus);
        tvPersonaJsonLabel = findViewById(R.id.tvPersonaJsonLabel);
        tvPersonaTierLabel = findViewById(R.id.tvPersonaTierLabel);
        tvSettingsPageTitle = findViewById(R.id.tvSettingsPageTitle);
        tvUpstreamChatPreview = findViewById(R.id.tvUpstreamChatPreview);
        tvUpstreamModelsPreview = findViewById(R.id.tvUpstreamModelsPreview);
        scrollRoot = findViewById(R.id.scrollRoot);
        settingsScroll = findViewById(R.id.settingsScroll);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnTest = findViewById(R.id.btnTest);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        btnCloseSettings = findViewById(R.id.btnCloseSettings);
        btnApiKeyCheck = findViewById(R.id.btnApiKeyCheck);
        btnToggleAdvancedNetwork = findViewById(R.id.btnToggleAdvancedNetwork);
        btnToggleProviderProtocolFields = findViewById(R.id.btnToggleProviderProtocolFields);
        btnToggleRules = findViewById(R.id.btnToggleRules);
        btnReplayLastRequest = findViewById(R.id.btnReplayLastRequest);
        btnKeepAliveHelp = findViewById(R.id.btnKeepAliveHelp);

        // v1.5.0：附件区视图绑定
        layoutAttachments = findViewById(R.id.layoutAttachments);
        rvAttachmentList = findViewById(R.id.rvAttachmentList);
        btnAddAttachmentImage = findViewById(R.id.btnAddAttachmentImage);
        btnAddAttachmentDoc = findViewById(R.id.btnAddAttachmentDoc);
        btnClearAttachments = findViewById(R.id.btnClearAttachments);
        tvAttachmentHint = findViewById(R.id.tvAttachmentHint);
        btnPersonaAdd = findViewById(R.id.btnPersonaAdd);
        btnPersonaCopy = findViewById(R.id.btnPersonaCopy);
        btnPersonaRename = findViewById(R.id.btnPersonaRename);
        btnPersonaDelete = findViewById(R.id.btnPersonaDelete);
        btnGlobalPersonaAdd = findViewById(R.id.btnGlobalPersonaAdd);
        btnGlobalPersonaCopy = findViewById(R.id.btnGlobalPersonaCopy);
        btnGlobalPersonaRename = findViewById(R.id.btnGlobalPersonaRename);
        btnGlobalPersonaDelete = findViewById(R.id.btnGlobalPersonaDelete);
        btnProviderAdd = findViewById(R.id.btnProviderAdd);
        btnProviderCopy = findViewById(R.id.btnProviderCopy);
        btnProviderRename = findViewById(R.id.btnProviderRename);
        btnProviderDelete = findViewById(R.id.btnProviderDelete);
        btnProviderListAdd = findViewById(R.id.btnProviderListAdd);
        btnModelDelete = findViewById(R.id.btnModelDelete);
        btnSendFeedback = findViewById(R.id.btnSendFeedback);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnThemeToggle = findViewById(R.id.btnThemeToggle);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnOpenLogsFullscreen = findViewById(R.id.btnOpenLogsFullscreen);
        tvFullscreenTitle = findViewById(R.id.tvFullscreenTitle);
        tvFullscreenUndo = findViewById(R.id.tvFullscreenUndo);
        tvFullscreenCancel = findViewById(R.id.tvFullscreenCancel);
        tvFullscreenSave = findViewById(R.id.tvFullscreenSave);
        etFullscreenEditor = findViewById(R.id.etFullscreenEditor);
        if (etFullscreenEditor != null) {
            fullscreenEditorKeyListener = etFullscreenEditor.getKeyListener();
        }
        layoutMatchingRules = findViewById(R.id.layoutMatchingRules);
        layoutGenericFields = findViewById(R.id.layoutGenericFields);
        layoutAdvancedNetwork = findViewById(R.id.layoutAdvancedNetwork);
        if (layoutAdvancedNetwork != null) {
            layoutAdvancedNetwork.setVisibility(View.GONE);
        }
        layoutProviderProtocolFields = findViewById(R.id.layoutProviderProtocolFields);
        if (layoutProviderProtocolFields != null) {
            layoutProviderProtocolFields.setVisibility(View.GONE);
        }
        pageConnection = findViewById(R.id.pageConnection);
        layoutProviderList = findViewById(R.id.layoutProviderList);
        layoutProviderRows = findViewById(R.id.layoutProviderRows);
        layoutProviderModelRows = findViewById(R.id.layoutProviderModelRows);
        settingsMenu = findViewById(R.id.settingsMenu);
        layoutTierPersonaEditor = findViewById(R.id.layoutTierPersonaEditor);
        layoutGlobalPersonaEditor = findViewById(R.id.layoutGlobalPersonaEditor);
    }

    private void hideLegacyFeedbackUpdateCard() {
        // The drawer layout owns the feedback/update page now; keep this no-op for old call sites.
    }

    private void moveLogAutoScrollBelowReplay() {
        // The checkbox is placed directly in XML on the compact home page.
    }

    private void buildFooterFeedbackCard() {
        // Feedback/update controls are declared in the drawer instead of being built at runtime.
    }

    private void initSpinners() {
        setupSpinner(spUpstreamProxyType, R.array.upstream_proxy_type_items, R.array.upstream_proxy_type_labels);
        setupSpinner(spWebSearchProxyType, R.array.upstream_proxy_type_items, R.array.upstream_proxy_type_labels);
        setupSpinner(spStrictness, R.array.strictness_items, R.array.strictness_labels);
        setupSpinner(spAdapterPreset, R.array.adapter_preset_items, R.array.adapter_preset_labels);
        setupSpinner(spPersonaTier, R.array.persona_tier_items, R.array.persona_tier_labels);
    }

    private void setupSpinner(Spinner spinner, int valuesRes, int labelsRes) {
        String[] values = getResources().getStringArray(valuesRes);
        String[] labels = getResources().getStringArray(labelsRes);
        ArrayList<SpinnerOption> options = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            String label = i < labels.length ? labels[i] : value;
            options.add(new SpinnerOption(value, label));
        }
        setSpinnerOptions(spinner, options);
    }

    private void setSpinnerOptions(Spinner spinner, List<SpinnerOption> options) {
        if (spinner == null) {
            return;
        }
        ArrayAdapter<SpinnerOption> adapter = new ArrayAdapter<SpinnerOption>(this, android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerTextView(view, currentThemePalette());
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                UiThemePalette palette = currentThemePalette();
                styleSpinnerTextView(view, palette);
                view.setBackgroundColor(palette.surface);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private ArrayAdapter<String> buildThemedStringAdapter(List<String> labels) {
        ArrayList<String> safeLabels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, safeLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerTextView(view, currentThemePalette());
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                UiThemePalette palette = currentThemePalette();
                styleSpinnerTextView(view, palette);
                view.setBackgroundColor(palette.surface);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void styleSpinnerTextView(View view, UiThemePalette palette) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(palette.textPrimary);
            textView.setTextSize(15f);
        }
    }

    private UiThemePalette currentThemePalette() {
        return UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
    }

    private void setupLogScrolling() {
        tvLogs.setMovementMethod(new ScrollingMovementMethod());
        tvLogs.setOnTouchListener(this::allowNestedScroll);
    }

    private void setupScrollableFields() {
        // etPersonaJson 和 etMemoryExtractTemplate 现在用 wrap_content + minHeight，
        // 它们的高度 == 内容高度，没有内部滚动需求；让父 ScrollView 接管手势。
        EditText[] fields = new EditText[]{
                etPrefixes, etKeywords, etPersonaMatchKeywords, etInteractiveStoryKeywords, etNormalTemplate, etHolidayTemplate,
                etListenChatPaths, etListenModelsPaths, etRequestMessagesPath,
                etRequestUserTextPath, etRequestModelPath, etRequestMaxTokensPath,
                etRequestTemperaturePath, etRequestEnableThinkingPath,
                etResponseTextPath, etUpdateManifestUrls, etUpdateManifestPublicKey,
                etChatCustomRequestFields, etDiaryCustomRequestFields, etMemoryExtractCustomRequestFields
        };
        for (EditText field : fields) {
            setupScrollableEditText(field);
        }
    }

    private void setupScrollableEditText(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.setOnTouchListener(this::allowNestedScroll);
    }

    private void applyCompactHintsToAllEditTexts(View root) {
        if (root instanceof EditText) {
            EditText editText = (EditText) root;
            CharSequence hint = editText.getHint();
            if (!TextUtils.isEmpty(hint)) {
                setCompactHint(editText, hint.toString());
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyCompactHintsToAllEditTexts(group.getChildAt(i));
            }
        }
    }

    private void setCompactHint(EditText editText, String hint) {
        if (editText == null) {
            return;
        }
        SpannableString compact = new SpannableString(safeString(hint, ""));
        compact.setSpan(new RelativeSizeSpan(0.82f), 0, compact.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        editText.setHint(compact);
    }

    private void setupLongTextEditors() {
        installLongTextEditor(etNormalTemplate, "普通日记模板");
        installLongTextEditor(etHolidayTemplate, "节日日记模板");
        installLongTextEditor(etPersonaJson, "人设 JSON 编辑器");
        installLongTextEditor(etMemoryExtractTemplate, "长期记忆提取提示词模板");
        installLongTextEditor(etChatCustomRequestFields, "聊天自定义追加请求字段 JSON");
        installLongTextEditor(etDiaryCustomRequestFields, "日记自定义追加请求字段 JSON");
        installLongTextEditor(etMemoryExtractCustomRequestFields, "长期记忆自定义追加请求字段 JSON");
        setupFullscreenEditorUi();
    }

    private void installLongTextEditor(EditText editText, String title) {
        if (editText == null || !(editText.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) editText.getParent();
        int editIndex = parent.indexOfChild(editText);
        if (editIndex < 0) {
            return;
        }
        hideNearbyLabel(parent, editIndex, title);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(8);
        titleRow.setLayoutParams(rowParams);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ContextCompat.getColor(this, R.color.proxy_text_primary));
        label.setTextSize(14f);
        titleRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView editAction = new TextView(this);
        editAction.setText("编辑");
        editAction.setTextColor(ContextCompat.getColor(this, R.color.proxy_primary_dark));
        editAction.setTextSize(13f);
        editAction.setPadding(dp(12), dp(6), 0, dp(6));
        titleRow.addView(editAction);

        parent.addView(titleRow, editIndex);
        editText.setTextIsSelectable(false);
        editText.setKeyListener(null);
        editText.setFocusable(false);
        editText.setFocusableInTouchMode(false);
        editText.setCursorVisible(false);
        editText.setBackgroundResource(R.drawable.bg_text_preview_border);
        editText.setPadding(dp(8), dp(8), dp(8), dp(8));
        editAction.setOnClickListener(v -> openFullscreenEditor(title, editText));
    }

    private void hideNearbyLabel(LinearLayout parent, int editIndex, String title) {
        int first = Math.max(0, editIndex - 3);
        for (int i = editIndex - 1; i >= first; i--) {
            View candidate = parent.getChildAt(i);
            if (candidate instanceof TextView) {
                CharSequence text = ((TextView) candidate).getText();
                if (text != null && TextUtils.equals(text.toString(), title)) {
                    candidate.setVisibility(View.GONE);
                    return;
                }
            }
        }
    }

    private boolean allowNestedScroll(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
        }
        return false;
    }

    private void setupListeners() {
        findViewById(R.id.btnFetchModels).setOnClickListener(v -> fetchModelsFromUpstream());
        if (settingsDrawer != null) {
            settingsDrawer.setOnTouchListener((view, event) -> true);
        }
        if (layoutFullscreenEditor != null) {
            layoutFullscreenEditor.setOnTouchListener((view, event) -> true);
        }
        if (btnOpenSettings != null && settingsDrawer != null) {
            btnOpenSettings.setOnClickListener(v -> {
                showSettingsMenu();
                settingsDrawer.setVisibility(View.VISIBLE);
            });
        }
        if (btnCloseSettings != null) {
            btnCloseSettings.setOnClickListener(v -> handleSettingsBack());
        }
        if (btnApiKeyCheck != null) {
            btnApiKeyCheck.setOnClickListener(v -> testUpstream());
        }
        if (btnToggleAdvancedNetwork != null) {
            btnToggleAdvancedNetwork.setOnClickListener(v -> toggleAdvancedNetwork());
        }
        if (btnToggleProviderProtocolFields != null) {
            btnToggleProviderProtocolFields.setOnClickListener(v -> toggleProviderProtocolFields());
        }
        btnStartStop.setOnClickListener(v -> {
            if (!guardianRunning) {
                startServer();
            } else {
                stopServer();
            }
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> testActiveModelOnHome());
        btnReplayLastRequest.setOnClickListener(v -> confirmReplayLastRequest());
        btnKeepAliveHelp.setOnClickListener(v -> onKeepAliveHelpClicked());
        if (btnHomeTestWebSearch != null) {
            btnHomeTestWebSearch.setOnClickListener(v -> testWebSearchOnHome());
        }
        if (etWebSearchApiKey != null) {
            etWebSearchApiKey.setOnTouchListener(this::handleWebSearchApiKeyVisibilityTouch);
        }
        // v1.5.0：附件区
        setupAttachmentsArea();
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> confirmClearLogs());
        }
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> copyLogsToClipboard());
        }
        if (btnOpenLogsFullscreen != null) {
            btnOpenLogsFullscreen.setOnClickListener(v -> openFullscreenLogViewer());
        }
        btnToggleRules.setOnClickListener(v -> toggleMatchingRules());
        cbDetectionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateRuleToggleUi();
            markConfigDirty();
        });
        cbStripEnableThinking.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateEnableThinkingInputsEnabled();
            markConfigDirty();
        });
        cbDryRun.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbTruncate.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbSaveNormalHistory.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbSaveDiaryHistory.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbDebugPromptDump.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        if (rgDebugPromptDetailLevel != null) {
            rgDebugPromptDetailLevel.setOnCheckedChangeListener((group, checkedId) -> markConfigDirty());
        }
        // v1.5.5+：DPS-8 — 文档处理开关 + 字符上限
        if (cbDocumentTruncationEnabled != null) {
            cbDocumentTruncationEnabled.setOnCheckedChangeListener((bv, c) -> {
                markConfigDirty();
                // 切换截断开关后立即刷新附件列表的"将截断"提示
                if (attachmentAdapter != null) attachmentAdapter.notifyDataSetChanged();
            });
        }
        if (etDocumentTruncationMaxChars != null) {
            etDocumentTruncationMaxChars.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    markConfigDirty();
                    if (attachmentAdapter != null) attachmentAdapter.notifyDataSetChanged();
                }
            });
        }
        cbStripRestrictionLine.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbStripSystemTime.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        cbPersonaEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> markConfigDirty());
        // v1.5.x：日记 assistant 前缀续写——主开关切换时联动启用/禁用前缀文本框和 DeepSeek 兼容开关
        if (cbDiaryAssistantPrefixEnabled != null) {
            cbDiaryAssistantPrefixEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateDiaryAssistantPrefixInputsEnabled();
                markConfigDirty();
            });
        }
        if (cbDiaryAssistantPrefixDeepseekMode != null) {
            cbDiaryAssistantPrefixDeepseekMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // 互斥：DeepSeek 与硅基流动两种续写协议不能同时启用（顶层 prefix 与 messages 末尾 assistant+prefix:true 冲突）
                if (isChecked && cbDiaryAssistantPrefixSiliconflowMode != null
                        && cbDiaryAssistantPrefixSiliconflowMode.isChecked()) {
                    cbDiaryAssistantPrefixSiliconflowMode.setChecked(false);
                }
                markConfigDirty();
            });
        }
        if (cbDiaryAssistantPrefixSiliconflowMode != null) {
            cbDiaryAssistantPrefixSiliconflowMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && cbDiaryAssistantPrefixDeepseekMode != null
                        && cbDiaryAssistantPrefixDeepseekMode.isChecked()) {
                    cbDiaryAssistantPrefixDeepseekMode.setChecked(false);
                }
                markConfigDirty();
            });
        }
        if (cbLogAutoScroll != null) {
            cbLogAutoScroll.setOnCheckedChangeListener((buttonView, isChecked) -> persistUiBoolean(PREF_UI_LOG_AUTO_SCROLL, isChecked));
        }
        etApiKey.setOnTouchListener(this::handleApiKeyVisibilityTouch);
        if (spHomeActiveModel != null) {
            spHomeActiveModel.setOnItemSelectedListener(new SimpleItemSelectedListener(this::onHomeActiveModelSelected));
        }
        if (btnHomeActiveModel != null) {
            btnHomeActiveModel.setOnClickListener(v -> showHomeActiveModelPicker());
        }
        if (spProviderProfile != null) {
            spProviderProfile.setOnItemSelectedListener(new SimpleItemSelectedListener(providerId -> {
                if (!providerSpinnerReady) {
                    return;
                }
                onProviderProfileSelected(providerId);
                markConfigDirtyConfirmed();
            }));
        }
        spUpstreamProxyType.setOnItemSelectedListener(new SimpleItemSelectedListener(value -> {
            if (suppressUnsavedTracking) {
                return;
            }
            markConfigDirtyConfirmed();
        }));
        if (spWebSearchProxyType != null) {
            spWebSearchProxyType.setOnItemSelectedListener(new SimpleItemSelectedListener(value -> {
                if (suppressUnsavedTracking) {
                    return;
                }
                markConfigDirtyConfirmed();
            }));
        }
        spStrictness.setOnItemSelectedListener(new SimpleItemSelectedListener(value -> {
            if (suppressUnsavedTracking) {
                return;
            }
            markConfigDirtyConfirmed();
        }));
        spAdapterPreset.setOnItemSelectedListener(new SimpleItemSelectedListener(adapter -> {
            onAdapterPresetSelected(adapter);
            if (suppressUnsavedTracking) {
                return;
            }
            markConfigDirtyConfirmed();
        }));
        spPersonaProfile.setOnItemSelectedListener(new SimpleItemSelectedListener(profileId -> {
            if (!personaProfileSpinnerReady) {
                return;
            }
            onPersonaProfileSelected(profileId);
            markConfigDirtyConfirmed();
        }));
        spGlobalPersonaProfile.setOnItemSelectedListener(new SimpleItemSelectedListener(profileId -> {
            if (!globalPersonaProfileSpinnerReady) {
                return;
            }
            onGlobalPersonaProfileSelected(profileId);
            markConfigDirtyConfirmed();
        }));
        spPersonaTier.setOnItemSelectedListener(new SimpleItemSelectedListener(this::onPersonaTierSelected));
        swPersonaIgnoreAffinity.setOnCheckedChangeListener((buttonView, isChecked) -> {
            onPersonaModeSwitchChanged(isChecked);
            markConfigDirty();
        });
        btnPersonaAdd.setOnClickListener(v -> promptCreatePersonaProfile(false));
        btnPersonaCopy.setOnClickListener(v -> promptCreatePersonaProfile(true));
        btnPersonaRename.setOnClickListener(v -> promptRenamePersonaProfile());
        btnPersonaDelete.setOnClickListener(v -> promptDeletePersonaProfile());
        btnGlobalPersonaAdd.setOnClickListener(v -> promptCreateGlobalPersonaProfile(false));
        btnGlobalPersonaCopy.setOnClickListener(v -> promptCreateGlobalPersonaProfile(true));
        btnGlobalPersonaRename.setOnClickListener(v -> promptRenameGlobalPersonaProfile());
        btnGlobalPersonaDelete.setOnClickListener(v -> promptDeleteGlobalPersonaProfile());
        if (btnProviderAdd != null) {
            btnProviderAdd.setOnClickListener(v -> promptCreateProviderProfile(false));
        }
        if (btnProviderListAdd != null) {
            btnProviderListAdd.setOnClickListener(v -> promptCreateProviderProfile(false));
        }
        if (btnProviderCopy != null) {
            btnProviderCopy.setOnClickListener(v -> promptCreateProviderProfile(true));
        }
        if (btnProviderRename != null) {
            btnProviderRename.setOnClickListener(v -> promptRenameProviderProfile());
        }
        if (btnProviderDelete != null) {
            btnProviderDelete.setOnClickListener(v -> promptDeleteProviderProfile());
        }
        if (btnModelDelete != null) {
            btnModelDelete.setOnClickListener(v -> resetFetchedProviderModels());
        }
        if (btnSendFeedback != null) {
            btnSendFeedback.setOnClickListener(v -> openFeedbackDialog());
        }
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(v -> checkForUpdates(true));
        }
        if (btnThemeToggle != null) {
            btnThemeToggle.setOnClickListener(v -> toggleThemeMode());
        }
        if (tvFullscreenUndo != null) {
            tvFullscreenUndo.setOnClickListener(v -> undoFullscreenEdit());
        }
        if (tvFullscreenCancel != null) {
            tvFullscreenCancel.setOnClickListener(v -> closeFullscreenEditor(false));
        }
        if (tvFullscreenSave != null) {
            tvFullscreenSave.setOnClickListener(v -> saveFullscreenEditor());
        }
        setupFastScrollControl();
        setupSettingsNavigation();
        setupUpstreamPreviewWatchers();
    }

    private void setupUnsavedTracking() {
        TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                markConfigDirty();
            }
        };
        EditText[] fields = new EditText[]{
                etBaseUrl, etApiKey, etPort, etTimeout,
                etUpstreamProxyHost, etUpstreamProxyPort,
                etMinLength, etMinLines, etPrefixes, etKeywords,
                etPersonaMatchKeywords, etInteractiveStoryKeywords,
                etNormalTemplate, etHolidayTemplate, etOverrideMaxTokens,
                etChatOverrideMaxTokens, etChatOverrideTemperature, etChatOverrideEnableThinking,
                etDiaryOverrideTemperature, etDiaryOverrideEnableThinking,
                etDiaryAssistantPrefix,
                etChatCustomRequestFields, etDiaryCustomRequestFields,
                etMemoryExtractTemplate, etMemoryExtractOverrideMaxTokens,
                etMemoryExtractOverrideTemperature, etMemoryExtractOverrideEnableThinking,
                etMemoryExtractCustomRequestFields, etMaxChars,
                etListenChatPaths, etListenModelsPaths,
                etUpstreamChatPath, etUpstreamModelsPath,
                etRequestMessagesPath, etRequestUserTextPath, etRequestModelPath,
                etRequestMaxTokensPath, etRequestTemperaturePath, etRequestEnableThinkingPath,
                etResponseTextPath, etFeedbackWebhookUrl, etUpdateManifestUrls, etUpdateManifestPublicKey
                , etWebSearchApiKey, etWebSearchEndpoint, etWebSearchProxyHost, etWebSearchProxyPort, etWebSearchMaxResults
        };
        for (EditText field : fields) {
            addTextWatcher(field, watcher);
        }
    }

    /**
     * v1.6.0+：去掉"保存"按钮 / 未保存横幅后，所有改动都是 WYSIWYG。
     * <p>EditText 的 afterTextChanged 走这里：350ms 防抖后跑一次 {@link #persistConfigSilently()}。
     * 连续输入时 debounce 反复 reset，保证不在打字过程中写盘；停笔 ~350ms 才会触发。
     */
    private void markConfigDirty() {
        if (suppressUnsavedTracking) {
            return;
        }
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        unsavedHandler.postDelayed(refreshUnsavedSettingsRunnable, 350L);
    }

    /**
     * Spinner / CheckBox 等低频回调用，复用同一调度通道。
     */
    private void markConfigDirtyConfirmed() {
        if (suppressUnsavedTracking) {
            return;
        }
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        unsavedHandler.postDelayed(refreshUnsavedSettingsRunnable, 350L);
    }

    /**
     * v1.6.0+：debounce 触发后的静默自动保存。
     * <p>主线程内只做必须的 View 读取（{@link #readConfigFromUi()} 触摸 EditText / Spinner），
     * 然后把真正的 {@link ProxyConfig#save} 与服务热加载请求都丢到 {@link #autoSaveExecutor} 后台单线程，
     * 保证连续输入时主线程不被 SharedPreferences / EncryptedSharedPreferences 加密阻塞。
     * <p>校验失败（人设 JSON / 自定义请求字段 JSON 解析失败）时静默跳过本轮，不弹窗。
     * 用户继续编辑修正后下次 debounce 仍会再尝试。
     */
    private void persistConfigSilently() {
        if (suppressUnsavedTracking) {
            return;
        }
        final ProxyConfig cfg;
        try {
            saveCurrentProviderEditorDraft();
            saveHomeCaptionFieldsToActiveProviderDraft();
            saveCurrentPersonaEditorDraft();
            if (!TextUtils.isEmpty(validatePersonaDrafts())) {
                return;
            }
            if (!TextUtils.isEmpty(validateCustomRequestFields())) {
                return;
            }
            cfg = readConfigFromUi();
        } catch (Exception ignored) {
            return;
        }
        final boolean notifyService = guardianRunning;
        autoSaveExecutor.execute(() -> {
            try {
                ProxyConfig.save(MainActivity.this, cfg);
                if (notifyService) {
                    requestServiceAction(ProxyForegroundService.ACTION_UPDATE_CONFIG);
                }
            } catch (Exception ignored) {
                // 自动保存失败：保持上次磁盘状态，下次 debounce 仍会再尝试。
            }
        });
    }

    // v1.6.0+：configFingerprint / appendFingerprint 与"未保存横幅"配套已移除。
    // 自动保存改用 persistConfigSilently 后台写盘，不再需要指纹比对。

    private void setupFullscreenEditorUi() {
        if (etFullscreenEditor == null) {
            return;
        }
        etFullscreenEditor.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!fullscreenEditorReadOnly && !suppressFullscreenUndo) {
                    String previous = s == null ? "" : s.toString();
                    if (fullscreenUndoStack.isEmpty() || !TextUtils.equals(fullscreenUndoStack.get(fullscreenUndoStack.size() - 1), previous)) {
                        fullscreenUndoStack.add(previous);
                        if (fullscreenUndoStack.size() > 80) {
                            fullscreenUndoStack.remove(0);
                        }
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateFastScrollThumb();
            }
        });
        etFullscreenEditor.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateFastScrollThumb());
    }

    private void setupFastScrollControl() {
        View.OnTouchListener listener = (view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                scrollFullscreenEditorTo(event.getRawY());
                return true;
            }
            return true;
        };
        if (layoutFastScroll != null) {
            layoutFastScroll.setOnTouchListener(listener);
        }
        if (viewFastScrollThumb != null) {
            viewFastScrollThumb.setOnTouchListener(listener);
        }
    }

    private void openFullscreenEditor(String title, EditText source) {
        if (source == null || layoutFullscreenEditor == null || etFullscreenEditor == null) {
            return;
        }
        fullscreenSourceEditText = source;
        fullscreenSourceOriginalText = editTextValue(source);
        fullscreenEditorReadOnly = false;
        fullscreenUndoStack.clear();
        if (tvFullscreenTitle != null) {
            tvFullscreenTitle.setText(title);
        }
        if (tvFullscreenUndo != null) {
            tvFullscreenUndo.setVisibility(View.VISIBLE);
            tvFullscreenUndo.setText("上一步");
        }
        if (tvFullscreenSave != null) {
            tvFullscreenSave.setVisibility(View.VISIBLE);
        }
        if (tvFullscreenCancel != null) {
            tvFullscreenCancel.setText("取消");
        }
        etFullscreenEditor.setFocusable(true);
        etFullscreenEditor.setFocusableInTouchMode(true);
        etFullscreenEditor.setCursorVisible(true);
        etFullscreenEditor.setKeyListener(fullscreenEditorKeyListener);
        etFullscreenEditor.setTextIsSelectable(true);
        UiThemePalette editorPalette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        etFullscreenEditor.setBackground(buildRuntimeTextBoxBackground(editorPalette));
        etFullscreenEditor.setTextColor(editorPalette.textPrimary);
        applyRuntimeEditTextPadding(etFullscreenEditor);
        suppressFullscreenUndo = true;
        etFullscreenEditor.setText(fullscreenSourceOriginalText);
        etFullscreenEditor.setSelection(etFullscreenEditor.getText() == null ? 0 : etFullscreenEditor.getText().length());
        suppressFullscreenUndo = false;
        layoutFullscreenEditor.setVisibility(View.VISIBLE);
        etFullscreenEditor.requestFocus();
        updateFastScrollThumb();
    }

    private void openFullscreenLogViewer() {
        if (layoutFullscreenEditor == null || etFullscreenEditor == null) {
            return;
        }
        fullscreenSourceEditText = null;
        fullscreenSourceOriginalText = "";
        fullscreenEditorReadOnly = true;
        fullscreenUndoStack.clear();
        if (tvFullscreenTitle != null) {
            tvFullscreenTitle.setText("运行日志");
        }
        if (tvFullscreenUndo != null) {
            tvFullscreenUndo.setVisibility(View.GONE);
        }
        if (tvFullscreenSave != null) {
            tvFullscreenSave.setVisibility(View.GONE);
        }
        if (tvFullscreenCancel != null) {
            tvFullscreenCancel.setText("返回");
        }
        etFullscreenEditor.setFocusable(false);
        etFullscreenEditor.setFocusableInTouchMode(false);
        etFullscreenEditor.setCursorVisible(false);
        etFullscreenEditor.setKeyListener(null);
        etFullscreenEditor.setTextIsSelectable(true);
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        etFullscreenEditor.setBackgroundColor(palette.bg);
        etFullscreenEditor.setTextColor(palette.textPrimary);
        suppressFullscreenUndo = true;
        etFullscreenEditor.setText(tvLogs == null || tvLogs.getText() == null ? "" : tvLogs.getText().toString());
        suppressFullscreenUndo = false;
        layoutFullscreenEditor.setVisibility(View.VISIBLE);
        updateFastScrollThumb();
    }

    private void undoFullscreenEdit() {
        if (fullscreenEditorReadOnly || etFullscreenEditor == null || fullscreenUndoStack.isEmpty()) {
            return;
        }
        String previous = fullscreenUndoStack.remove(fullscreenUndoStack.size() - 1);
        suppressFullscreenUndo = true;
        etFullscreenEditor.setText(previous);
        etFullscreenEditor.setSelection(etFullscreenEditor.getText() == null ? 0 : etFullscreenEditor.getText().length());
        suppressFullscreenUndo = false;
        updateFastScrollThumb();
    }

    private void saveFullscreenEditor() {
        if (fullscreenEditorReadOnly) {
            closeFullscreenEditor(false);
            return;
        }
        if (fullscreenSourceEditText == null || etFullscreenEditor == null) {
            closeFullscreenEditor(false);
            return;
        }
        String nextText = etFullscreenEditor.getText() == null ? "" : etFullscreenEditor.getText().toString();
        fullscreenSourceEditText.setText(nextText);
        if (saveConfigFromUi()) {
            closeFullscreenEditor(true);
        } else {
            fullscreenSourceEditText.setText(fullscreenSourceOriginalText);
            saveCurrentPersonaEditorDraft();
        }
    }

    private void closeFullscreenEditor(boolean saved) {
        if (layoutFullscreenEditor != null) {
            layoutFullscreenEditor.setVisibility(View.GONE);
        }
        fullscreenSourceEditText = null;
        fullscreenSourceOriginalText = "";
        fullscreenEditorReadOnly = false;
        fullscreenUndoStack.clear();
        suppressFullscreenUndo = false;
    }

    private void scrollFullscreenEditorTo(float rawY) {
        if (layoutFastScroll == null || etFullscreenEditor == null || etFullscreenEditor.getLayout() == null) {
            return;
        }
        int[] location = new int[2];
        layoutFastScroll.getLocationOnScreen(location);
        float localY = Math.max(0f, Math.min(rawY - location[1], layoutFastScroll.getHeight()));
        int maxScroll = fullscreenEditorMaxScroll();
        if (maxScroll <= 0 || layoutFastScroll.getHeight() <= 0) {
            return;
        }
        float ratio = localY / Math.max(1f, layoutFastScroll.getHeight());
        etFullscreenEditor.setScrollY(Math.round(maxScroll * ratio));
        updateFastScrollThumb();
    }

    private int fullscreenEditorMaxScroll() {
        if (etFullscreenEditor == null || etFullscreenEditor.getLayout() == null) {
            return 0;
        }
        int contentHeight = etFullscreenEditor.getLayout().getHeight()
                + etFullscreenEditor.getTotalPaddingTop()
                + etFullscreenEditor.getTotalPaddingBottom();
        int viewportHeight = etFullscreenEditor.getHeight();
        return Math.max(0, contentHeight - viewportHeight);
    }

    private void updateFastScrollThumb() {
        if (layoutFastScroll == null || viewFastScrollThumb == null || etFullscreenEditor == null || etFullscreenEditor.getLayout() == null) {
            return;
        }
        layoutFastScroll.post(() -> {
            int trackHeight = layoutFastScroll.getHeight();
            int maxScroll = fullscreenEditorMaxScroll();
            if (trackHeight <= 0) {
                return;
            }
            int thumbHeight;
            int topMargin;
            if (maxScroll <= 0) {
                thumbHeight = trackHeight;
                topMargin = 0;
            } else {
                int contentHeight = etFullscreenEditor.getLayout().getHeight()
                        + etFullscreenEditor.getTotalPaddingTop()
                        + etFullscreenEditor.getTotalPaddingBottom();
                thumbHeight = Math.max(dp(48), Math.round(trackHeight * (etFullscreenEditor.getHeight() / Math.max(1f, contentHeight))));
                thumbHeight = Math.min(trackHeight, thumbHeight);
                topMargin = Math.round((trackHeight - thumbHeight) * (etFullscreenEditor.getScrollY() / Math.max(1f, maxScroll)));
            }
            ViewGroup.LayoutParams rawParams = viewFastScrollThumb.getLayoutParams();
            if (rawParams instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) rawParams;
                params.height = thumbHeight;
                params.topMargin = topMargin;
                viewFastScrollThumb.setLayoutParams(params);
            }
        });
    }

    private void setupSettingsNavigation() {
        settingsPages = new View[]{
                findViewById(R.id.pageConnection),
                findViewById(R.id.pageRulesDiary),
                findViewById(R.id.pagePersona),
                findViewById(R.id.pageMemory),
                findViewById(R.id.pageRecords),
                findViewById(R.id.pageWebSearch)
        };
        settingsMenuItems = new TextView[]{
                findViewById(R.id.menuConnection),
                findViewById(R.id.menuRulesDiary),
                findViewById(R.id.menuPersona),
                findViewById(R.id.menuMemory),
                findViewById(R.id.menuRecords),
                findViewById(R.id.menuWebSearch)
        };
        for (int i = 0; i < settingsMenuItems.length; i++) {
            final int index = i;
            TextView item = settingsMenuItems[i];
            if (item != null) {
                item.setOnClickListener(v -> showSettingsPage(index));
            }
        }
        showSettingsMenu();
    }

    private void handleSettingsBack() {
        if (settingsDetailVisible && providerDetailVisible) {
            showProviderListPage();
            return;
        }
        if (settingsDetailVisible) {
            showSettingsMenu();
            return;
        }
        if (settingsDrawer != null) {
            settingsDrawer.setVisibility(View.GONE);
        }
    }

    private void showSettingsMenu() {
        clearTransientFetchedModelsForCurrentProvider();
        settingsDetailVisible = false;
        if (settingsMenu != null) {
            settingsMenu.setVisibility(View.VISIBLE);
        }
        if (settingsScroll != null) {
            settingsScroll.setVisibility(View.GONE);
        }
        if (tvSettingsPageTitle != null) {
            tvSettingsPageTitle.setText("设置");
        }
        for (TextView item : settingsMenuItems) {
            selectSettingsMenuItem(item, false);
        }
    }

    private void showSettingsPage(int selectedIndex) {
        if (selectedIndex != 0) {
            clearTransientFetchedModelsForCurrentProvider();
        }
        settingsDetailVisible = true;
        if (settingsMenu != null) {
            settingsMenu.setVisibility(View.GONE);
        }
        if (settingsScroll != null) {
            settingsScroll.setVisibility(View.VISIBLE);
        }
        for (int i = 0; i < settingsPages.length; i++) {
            if (settingsPages[i] != null) {
                settingsPages[i].setVisibility(i == selectedIndex ? View.VISIBLE : View.GONE);
            }
        }
        for (int i = 0; i < settingsMenuItems.length; i++) {
            selectSettingsMenuItem(settingsMenuItems[i], i == selectedIndex);
        }
        if (tvSettingsPageTitle != null && selectedIndex >= 0 && selectedIndex < settingsPageTitles.length) {
            tvSettingsPageTitle.setText(settingsPageTitles[selectedIndex]);
        }
        if (settingsScroll != null) {
            settingsScroll.scrollTo(0, 0);
        }
        if (selectedIndex == 0) {
            showProviderListPage();
        } else {
            providerDetailVisible = false;
        }
    }

    private void selectSettingsMenuItem(TextView item, boolean selected) {
        if (item == null) {
            return;
        }
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        item.setBackground(buildRuntimeMenuItemBackground(palette, selected));
        item.setTextColor(selected ? palette.primaryDark : palette.textPrimary);
        item.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void showProviderListPage() {
        clearTransientFetchedModelsForCurrentProvider();
        providerDetailVisible = false;
        updateProviderRows();
        setConnectionChildrenVisible(false);
        if (layoutProviderList != null) {
            layoutProviderList.setVisibility(View.VISIBLE);
        }
        if (btnProviderAdd != null) {
            btnProviderAdd.setVisibility(View.VISIBLE);
        }
        if (tvSettingsPageTitle != null) {
            tvSettingsPageTitle.setText("连接 / API");
        }
        if (settingsScroll != null) {
            settingsScroll.scrollTo(0, 0);
        }
    }

    private void showProviderDetailPage(String providerId) {
        if (TextUtils.isEmpty(providerId)) {
            return;
        }
        if (providerDetailVisible) {
            saveCurrentProviderEditorDraft();
        }
        currentProviderId = providerId;
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        providerDetailVisible = true;
        setConnectionChildrenVisible(true);
        if (layoutProviderList != null) {
            layoutProviderList.setVisibility(View.GONE);
        }
        if (btnProviderAdd != null) {
            btnProviderAdd.setVisibility(View.GONE);
        }
        providerSpinnerReady = false;
        providerModelSpinnerReady = false;
        selectSpinnerValue(spProviderProfile, currentProviderId);
        applyProviderDraftToConnectionUi(provider);
        updateProviderModelSpinner();
        providerSpinnerReady = true;
        providerModelSpinnerReady = true;
        updateProviderRows();
        if (tvSettingsPageTitle != null) {
            tvSettingsPageTitle.setText(provider == null ? "提供商设置" : safeString(provider.name, "提供商设置"));
        }
        if (settingsScroll != null) {
            settingsScroll.scrollTo(0, 0);
        }
    }

    private void clearTransientFetchedModelsForCurrentProvider() {
        if (providerDetailVisible && !TextUtils.isEmpty(currentProviderId)) {
            providerFetchedModelDrafts.remove(currentProviderId);
        }
    }

    private void setConnectionChildrenVisible(boolean detailVisible) {
        if (pageConnection == null) {
            return;
        }
        for (int i = 0; i < pageConnection.getChildCount(); i++) {
            View child = pageConnection.getChildAt(i);
            if (child == layoutAdvancedNetwork || child == layoutProviderProtocolFields) {
                // 折叠区(高级路由 / 自定义请求字段)由用户手动控制展开收起，
                // 进入/离开详情页都不应该擅自展开。
                if (!detailVisible) {
                    child.setVisibility(View.GONE);
                }
                continue;
            }
            child.setVisibility(child == layoutProviderList
                    ? (detailVisible ? View.GONE : View.VISIBLE)
                    : (detailVisible ? View.VISIBLE : View.GONE));
        }
        if (spProviderProfile != null) {
            spProviderProfile.setVisibility(View.GONE);
        }
        if (detailVisible && layoutAdvancedNetwork != null && btnToggleAdvancedNetwork != null) {
            // 进入详情页时把高级路由强制收起，按钮文案对齐折叠状态。
            layoutAdvancedNetwork.setVisibility(View.GONE);
            btnToggleAdvancedNetwork.setText("展开高级路由与网络");
        }
        if (detailVisible && layoutProviderProtocolFields != null && btnToggleProviderProtocolFields != null) {
            // 进入详情页时把"自定义追加请求字段"折叠区也强制收起。
            layoutProviderProtocolFields.setVisibility(View.GONE);
            btnToggleProviderProtocolFields.setText("展开自定义追加请求字段（本提供商）");
        }
    }

    private void toggleAdvancedNetwork() {
        if (layoutAdvancedNetwork == null || btnToggleAdvancedNetwork == null) {
            return;
        }
        boolean visible = layoutAdvancedNetwork.getVisibility() == View.VISIBLE;
        layoutAdvancedNetwork.setVisibility(visible ? View.GONE : View.VISIBLE);
        btnToggleAdvancedNetwork.setText(visible ? "展开高级路由与网络" : "收起高级路由与网络");
    }

    private void toggleProviderProtocolFields() {
        if (layoutProviderProtocolFields == null || btnToggleProviderProtocolFields == null) {
            return;
        }
        boolean visible = layoutProviderProtocolFields.getVisibility() == View.VISIBLE;
        layoutProviderProtocolFields.setVisibility(visible ? View.GONE : View.VISIBLE);
        btnToggleProviderProtocolFields.setText(
                visible ? "展开自定义追加请求字段（本提供商）" : "收起自定义追加请求字段（本提供商）");
    }

    private void setupUpstreamPreviewWatchers() {
        TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updateUpstreamPreviewFromUi();
            }
        };
        addTextWatcher(etBaseUrl, watcher);
        addTextWatcher(etUpstreamChatPath, watcher);
        addTextWatcher(etUpstreamModelsPath, watcher);
    }

    private void addTextWatcher(EditText editText, TextWatcher watcher) {
        if (editText != null && watcher != null) {
            editText.addTextChangedListener(watcher);
        }
    }

    private boolean handleApiKeyVisibilityTouch(View view, MotionEvent event) {
        if (!(view instanceof EditText) || event == null) {
            return false;
        }
        EditText editText = (EditText) view;
        android.graphics.drawable.Drawable drawable = editText.getCompoundDrawablesRelative()[2];
        if (drawable == null) {
            drawable = editText.getCompoundDrawables()[2];
        }
        if (drawable == null) {
            return false;
        }
        int drawableWidth = drawable.getBounds().width() > 0 ? drawable.getBounds().width() : drawable.getIntrinsicWidth();
        float touchStart = editText.getWidth() - editText.getPaddingRight() - drawableWidth - editText.getCompoundDrawablePadding();
        if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getX() >= touchStart) {
            toggleApiKeyVisibility();
            return true;
        }
        return false;
    }

    private void toggleApiKeyVisibility() {
        apiKeyVisible = !apiKeyVisible;
        applyApiKeyVisibility();
    }

    private void applyApiKeyVisibility() {
        if (etApiKey == null) {
            return;
        }
        int start = Math.max(0, etApiKey.getSelectionStart());
        int end = Math.max(0, etApiKey.getSelectionEnd());
        if (apiKeyVisible) {
            etApiKey.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
        } else {
            etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        etApiKey.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                apiKeyVisible ? R.drawable.ic_visibility_24 : R.drawable.ic_visibility_off_24,
                0
        );
        int length = etApiKey.getText() == null ? 0 : etApiKey.getText().length();
        etApiKey.setSelection(Math.min(start, length), Math.min(end, length));
    }

    private void hideObsoleteProtocolHints() {
        View root = findViewById(android.R.id.content);
        hideTextViewsContaining(root, "DeepSeek 和 OpenAI Chat");
        hideTextViewsContaining(root, "OpenAI Chat 兼容");
    }

    private void hideTextViewsContaining(View view, String phrase) {
        if (view == null || TextUtils.isEmpty(phrase)) {
            return;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().contains(phrase)) {
                view.setVisibility(View.GONE);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideTextViewsContaining(group.getChildAt(i), phrase);
            }
        }
    }

    private void applyConfigToUi(ProxyConfig cfg) {
        suppressAdapterAutoPaths = true;
        suppressUnsavedTracking = true;
        providerSpinnerReady = false;
        providerModelSpinnerReady = false;
        homeModelSpinnerReady = false;
        providerProfileDrafts.clear();
        providerFetchedModelDrafts.clear();
        for (ProxyConfig.ProviderProfile profile : cfg.getProviderProfiles()) {
            providerProfileDrafts.put(profile.id, profile.copy());
        }
        currentProviderId = safeString(cfg.activeProviderId, ProxyConfig.DEFAULT_PROVIDER_ID);
        if (!providerProfileDrafts.containsKey(currentProviderId) && !providerProfileDrafts.isEmpty()) {
            currentProviderId = providerProfileDrafts.keySet().iterator().next();
        }
        activeProviderIdDraft = currentProviderId;
        etBaseUrl.setText(cfg.upstreamBaseUrl);
        etApiKey.setText(cfg.apiKey);
        apiKeyVisible = false;
        applyApiKeyVisibility();
        etPort.setText(String.valueOf(cfg.port));
        etTimeout.setText(String.valueOf(cfg.timeoutMs));
        selectSpinnerValue(spUpstreamProxyType, cfg.resolvedUpstreamProxyType());
        etUpstreamProxyHost.setText(cfg.upstreamProxyHost);
        etUpstreamProxyPort.setText(cfg.upstreamProxyPort > 0 ? String.valueOf(cfg.upstreamProxyPort) : "");
        etMinLength.setText(String.valueOf(cfg.minContentLength));
        etMinLines.setText(String.valueOf(cfg.minDialogueLines));
        etPrefixes.setText(cfg.prefixesText);
        etKeywords.setText(cfg.keywordsText);
        etPersonaMatchKeywords.setText(cfg.personaMatchKeywordsText);
        etInteractiveStoryKeywords.setText(cfg.interactiveStoryKeywordsText);
        etNormalTemplate.setText(cfg.normalTemplate);
        etHolidayTemplate.setText(cfg.holidayTemplate);
        // 13 个 per-provider 协议字段：cfg 顶层在 ProxyConfig.ensureDefaults 末尾已经从 active provider
        // 镜像过来，这里直接 setText 即可，避免「用户从未打开过详情页就保存 → 读到空 EditText 回写空值」的回归。
        etOverrideMaxTokens.setText(String.valueOf(cfg.overrideMaxTokens));
        etChatOverrideMaxTokens.setText(String.valueOf(cfg.chatOverrideMaxTokens));
        etChatOverrideTemperature.setText(cfg.chatOverrideTemperature);
        etChatOverrideEnableThinking.setText(cfg.chatOverrideEnableThinking);
        etDiaryOverrideTemperature.setText(cfg.diaryOverrideTemperature);
        etDiaryOverrideEnableThinking.setText(cfg.diaryOverrideEnableThinking);
        // v1.5.x：日记 assistant 前缀续写（cfg 顶层是 active provider 的镜像）
        if (cbDiaryAssistantPrefixEnabled != null) {
            cbDiaryAssistantPrefixEnabled.setChecked(cfg.diaryAssistantPrefixEnabled);
        }
        if (etDiaryAssistantPrefix != null) {
            etDiaryAssistantPrefix.setText(safeString(cfg.diaryAssistantPrefix, "【日记】"));
        }
        if (cbDiaryAssistantPrefixDeepseekMode != null) {
            cbDiaryAssistantPrefixDeepseekMode.setChecked(cfg.diaryAssistantPrefixDeepseekMode);
        }
        if (cbDiaryAssistantPrefixSiliconflowMode != null) {
            cbDiaryAssistantPrefixSiliconflowMode.setChecked(cfg.diaryAssistantPrefixSiliconflowMode);
        }
        updateDiaryAssistantPrefixInputsEnabled();
        etChatCustomRequestFields.setText(cfg.chatCustomRequestFieldsJson);
        etDiaryCustomRequestFields.setText(cfg.diaryCustomRequestFieldsJson);
        etMemoryExtractTemplate.setText(cfg.memoryExtractTemplate);
        etMemoryExtractOverrideMaxTokens.setText(String.valueOf(cfg.memoryExtractOverrideMaxTokens));
        etMemoryExtractOverrideTemperature.setText(cfg.memoryExtractOverrideTemperature);
        etMemoryExtractOverrideEnableThinking.setText(cfg.memoryExtractOverrideEnableThinking);
        etMemoryExtractCustomRequestFields.setText(cfg.memoryExtractCustomRequestFieldsJson);
        etMaxChars.setText(String.valueOf(cfg.maxChars));
        etListenChatPaths.setText(cfg.listenChatPaths);
        etListenModelsPaths.setText(cfg.listenModelsPaths);
        etUpstreamChatPath.setText(cfg.upstreamChatPath);
        etUpstreamModelsPath.setText(cfg.upstreamModelsPath);
        etRequestMessagesPath.setText(cfg.requestMessagesPath);
        etRequestUserTextPath.setText(cfg.requestUserTextPath);
        etRequestModelPath.setText(cfg.requestModelPath);
        etRequestMaxTokensPath.setText(cfg.requestMaxTokensPath);
        etRequestTemperaturePath.setText(cfg.requestTemperaturePath);
        etRequestEnableThinkingPath.setText(cfg.requestEnableThinkingPath);
        etResponseTextPath.setText(cfg.responseTextPath);
        if (etFeedbackWebhookUrl != null) {
            etFeedbackWebhookUrl.setText(cfg.feedbackWebhookUrl);
        }
        if (etUpdateManifestUrls != null) {
            etUpdateManifestUrls.setText(cfg.updateManifestUrls);
        }
        if (etUpdateManifestPublicKey != null) {
            etUpdateManifestPublicKey.setText(cfg.updateManifestPublicKey);
        }
        cbDetectionEnabled.setChecked(cfg.detectionEnabled);
        cbDryRun.setChecked(cfg.dryRun);
        cbTruncate.setChecked(cfg.truncateEnabled);
        cbSaveNormalHistory.setChecked(cfg.saveNormalChatHistory);
        cbSaveDiaryHistory.setChecked(cfg.saveDiaryHistory);
        cbDebugPromptDump.setChecked(cfg.debugPromptDumpEnabled);
        // v1.5.6+：渲染 debug 详细程度 RadioGroup
        if (rbDebugPromptLevelSimplified != null && rbDebugPromptLevelVerbose != null) {
            String level = ProxyConfig.normalizeDebugPromptDetailLevel(cfg.debugPromptDetailLevel);
            if (ProxyConfig.DEBUG_PROMPT_LEVEL_SIMPLIFIED.equals(level)) {
                rbDebugPromptLevelSimplified.setChecked(true);
            } else {
                rbDebugPromptLevelVerbose.setChecked(true);
            }
        }
        // v1.5.5+：DPS-8 — 文档处理
        if (cbDocumentTruncationEnabled != null) {
            cbDocumentTruncationEnabled.setChecked(cfg.documentTruncationEnabled);
        }
        if (etDocumentTruncationMaxChars != null) {
            int max = cfg.documentTruncationMaxChars > 0 ? cfg.documentTruncationMaxChars : 4096;
            etDocumentTruncationMaxChars.setText(String.valueOf(max));
        }
        cbStripEnableThinking.setChecked(cfg.stripEnableThinkingEnabled);
        updateEnableThinkingInputsEnabled();
        cbStripRestrictionLine.setChecked(cfg.stripRestrictionLineEnabled);
        cbStripSystemTime.setChecked(cfg.stripSystemTimeEnabled);
        cbPersonaEnabled.setChecked(cfg.personaEnabled);
        swPersonaIgnoreAffinity.setChecked(cfg.personaIgnoreAffinityEnabled);

        selectSpinnerValue(spStrictness, cfg.strictness);
        selectSpinnerValue(spAdapterPreset, cfg.adapterPreset);
        updateProviderProfileSpinner();
        updateProviderModelSpinner();
        updateHomeActiveModelSpinner();
        selectSpinnerValue(spProviderProfile, currentProviderId);
        selectSpinnerValue(spHomeActiveModel, currentHomeModelSelectionValue());
        providerSpinnerReady = true;
        providerModelSpinnerReady = true;
        homeModelSpinnerReady = true;
        refreshHomeActiveModelButtonLabel();

        personaProfileSpinnerReady = false;
        personaSpinnerReady = false;
        globalPersonaProfileSpinnerReady = false;
        personaProfileNames.clear();
        personaProfileDrafts.clear();
        globalPersonaProfileNames.clear();
        globalPersonaProfileDrafts.clear();
        for (ProxyConfig.PersonaProfile profile : cfg.getPersonaProfiles()) {
            personaProfileNames.put(profile.id, profile.name);
            LinkedHashMap<String, String> tierDrafts = new LinkedHashMap<>();
            for (String tier : ProxyConfig.PERSONA_TIERS) {
                tierDrafts.put(tier, profile.getTierJson(tier));
            }
            personaProfileDrafts.put(profile.id, tierDrafts);
        }
        for (ProxyConfig.GlobalPersonaProfile profile : cfg.getGlobalPersonaProfiles()) {
            globalPersonaProfileNames.put(profile.id, profile.name);
            globalPersonaProfileDrafts.put(profile.id, profile.rawJson);
        }
        updatePersonaProfileSpinner();
        updateGlobalPersonaProfileSpinner();
        currentPersonaProfileId = safeString(cfg.activePersonaProfileId, ProxyConfig.DEFAULT_PERSONA_PROFILE_ID);
        if (!personaProfileDrafts.containsKey(currentPersonaProfileId) && !personaProfileDrafts.isEmpty()) {
            currentPersonaProfileId = personaProfileDrafts.keySet().iterator().next();
        }
        currentGlobalPersonaProfileId = safeString(cfg.activeGlobalPersonaProfileId, ProxyConfig.DEFAULT_GLOBAL_PERSONA_PROFILE_ID);
        if (!globalPersonaProfileDrafts.containsKey(currentGlobalPersonaProfileId) && !globalPersonaProfileDrafts.isEmpty()) {
            currentGlobalPersonaProfileId = globalPersonaProfileDrafts.keySet().iterator().next();
        }
        selectSpinnerValue(spPersonaProfile, currentPersonaProfileId);
        selectSpinnerValue(spGlobalPersonaProfile, currentGlobalPersonaProfileId);
        currentPersonaTier = ProxyConfig.TIER_SISTER_HIGH;
        selectSpinnerValue(spPersonaTier, currentPersonaTier);
        personaProfileSpinnerReady = true;
        personaSpinnerReady = true;
        globalPersonaProfileSpinnerReady = true;
        updatePersonaEditorModeUi(false);

        updateRuleToggleUi();
        updateAdapterUi(false);
        lastAdapterPreset = cfg.adapterPreset;
        suppressAdapterAutoPaths = false;
        updateEndpoint(cfg);
        updateHistoryPathSummary();
        refreshHomeTestButtonState();
        applyHomeCaptionFieldsToUi();
        applyWebSearchSettingsToUi(cfg);
        suppressUnsavedTracking = false;
        // v1.6.0+：apply 完成后取消 suppress 期间累计的 debounce 回调，避免立刻误触发自动保存。
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        if (scrollRoot != null) {
            scrollRoot.post(() -> {
                scrollRoot.scrollTo(0, 0);
                scrollRoot.requestFocus();
            });
        }
    }

    private boolean saveConfigFromUi() {
        saveCurrentPersonaEditorDraft();
        String validationError = validatePersonaDrafts();
        if (!TextUtils.isEmpty(validationError)) {
            setActionStatus("保存失败");
            appendLog("人设 JSON 不合法：" + validationError);
            new AlertDialog.Builder(this)
                    .setTitle("保存失败：人设 JSON 不合法")
                    .setMessage(validationError)
                    .setPositiveButton("确定", null)
                    .show();
            return false;
        }
        validationError = validateCustomRequestFields();
        if (!TextUtils.isEmpty(validationError)) {
            setActionStatus("保存失败");
            appendLog("自定义请求字段 JSON 不合法：" + validationError);
            new AlertDialog.Builder(this)
                    .setTitle("保存失败：自定义追加请求字段 JSON 不合法")
                    .setMessage(validationError + "\n\n请展开「自定义追加请求字段」检查并修正。")
                    .setPositiveButton("确定", null)
                    .show();
            return false;
        }

        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (guardianRunning) {
            requestServiceAction(ProxyForegroundService.ACTION_UPDATE_CONFIG);
            appendLog("配置已更新，已通知前台服务热加载");
        } else {
            appendLog("配置已保存");
        }
        setActionStatus("已保存");
        updateEndpoint(cfg);
        updateHistoryPathSummary();
        refreshProviderSpinnersPreservingSelection();
        clearThemeDraftConfig();
        // v1.6.0+：显式保存路径已写盘，取消挂起的自动保存 debounce 防止再写一次。
        unsavedHandler.removeCallbacks(refreshUnsavedSettingsRunnable);
        return true;
    }

    private ProxyConfig readConfigFromUi() {
        saveCurrentProviderEditorDraft();
        saveHomeCaptionFieldsToActiveProviderDraft();
        saveCurrentPersonaEditorDraft();
        ProxyConfig cfg = ProxyConfig.load(this);
        cfg.upstreamBaseUrl = safeString(etBaseUrl.getText().toString(), ProxyConfig.DEFAULT_BASE_URL);
        cfg.apiKey = etApiKey.getText().toString().trim();
        ProxyConfig.ProviderProfile activeProviderForCfg = getActiveProviderDraft();
        cfg.model = activeProviderForCfg == null ? "" : safeString(activeProviderForCfg.getActiveModelName(), "");
        cfg.port = safeInt(etPort.getText().toString(), ProxyConfig.DEFAULT_PORT);
        cfg.timeoutMs = safeInt(etTimeout.getText().toString(), ProxyConfig.DEFAULT_TIMEOUT_MS);
        cfg.upstreamProxyType = getSpinnerValue(spUpstreamProxyType, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE);
        cfg.upstreamProxyHost = etUpstreamProxyHost.getText().toString().trim();
        cfg.upstreamProxyPort = safeInt(etUpstreamProxyPort.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_PROXY_PORT);
        cfg.strictness = getSpinnerValue(spStrictness, ProxyConfig.DEFAULT_STRICTNESS);
        cfg.adapterPreset = getSpinnerValue(spAdapterPreset, ProxyConfig.ADAPTER_OPENAI_COMPATIBLE);
        cfg.minContentLength = safeInt(etMinLength.getText().toString(), ProxyConfig.DEFAULT_MIN_CONTENT_LENGTH);
        cfg.minDialogueLines = safeInt(etMinLines.getText().toString(), ProxyConfig.DEFAULT_MIN_DIALOGUE_LINES);
        cfg.prefixesText = etPrefixes.getText().toString();
        cfg.keywordsText = etKeywords.getText().toString();
        cfg.personaMatchKeywordsText = etPersonaMatchKeywords.getText().toString();
        cfg.interactiveStoryKeywordsText = etInteractiveStoryKeywords.getText().toString();
        cfg.normalTemplate = etNormalTemplate.getText().toString();
        cfg.holidayTemplate = etHolidayTemplate.getText().toString();
        // 下面这些字段已经迁移到 ProviderProfile，由 saveCurrentProviderEditorDraft 写入 active provider，
        // ensureDefaults 末尾的 syncLegacyProviderFields 会再镜像到 cfg 顶层供 DiaryProxyServer 直接读：
        //   diary / chat / memoryExtract OverrideMaxTokens
        //   diary / chat / memoryExtract OverrideTemperature
        //   diary / chat / memoryExtract OverrideEnableThinking
        //   diary / chat / memoryExtract CustomRequestFieldsJson
        //   stripEnableThinkingEnabled
        cfg.memoryExtractTemplate = etMemoryExtractTemplate.getText().toString();
        cfg.maxChars = safeInt(etMaxChars.getText().toString(), ProxyConfig.DEFAULT_MAX_CHARS);
        cfg.listenChatPaths = etListenChatPaths.getText().toString();
        cfg.listenModelsPaths = etListenModelsPaths.getText().toString();
        cfg.upstreamChatPath = safeString(etUpstreamChatPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH);
        cfg.upstreamModelsPath = safeString(etUpstreamModelsPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH);
        cfg.requestMessagesPath = safeString(etRequestMessagesPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH);
        cfg.requestUserTextPath = safeString(etRequestUserTextPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH);
        cfg.requestModelPath = safeString(etRequestModelPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MODEL_PATH);
        cfg.requestMaxTokensPath = safeString(etRequestMaxTokensPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH);
        cfg.requestTemperaturePath = safeString(etRequestTemperaturePath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_TEMPERATURE_PATH);
        cfg.requestEnableThinkingPath = safeString(etRequestEnableThinkingPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_ENABLE_THINKING_PATH);
        cfg.responseTextPath = safeString(etResponseTextPath.getText().toString(), ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
        cfg.feedbackWebhookUrl = etFeedbackWebhookUrl == null ? cfg.feedbackWebhookUrl : trimToEmpty(etFeedbackWebhookUrl.getText().toString());
        cfg.updateManifestUrls = etUpdateManifestUrls == null ? cfg.updateManifestUrls : trimToEmpty(etUpdateManifestUrls.getText().toString());
        cfg.updateManifestPublicKey = etUpdateManifestPublicKey == null ? cfg.updateManifestPublicKey : trimToEmpty(etUpdateManifestPublicKey.getText().toString());
        cfg.detectionEnabled = cbDetectionEnabled.isChecked();
        cfg.dryRun = cbDryRun.isChecked();
        cfg.truncateEnabled = cbTruncate.isChecked();
        cfg.saveNormalChatHistory = cbSaveNormalHistory.isChecked();
        cfg.saveDiaryHistory = cbSaveDiaryHistory.isChecked();
        cfg.debugPromptDumpEnabled = cbDebugPromptDump.isChecked();
        // v1.5.6+：从 RadioGroup 读 debug 详细程度
        if (rbDebugPromptLevelSimplified != null && rbDebugPromptLevelSimplified.isChecked()) {
            cfg.debugPromptDetailLevel = ProxyConfig.DEBUG_PROMPT_LEVEL_SIMPLIFIED;
        } else {
            cfg.debugPromptDetailLevel = ProxyConfig.DEBUG_PROMPT_LEVEL_VERBOSE;
        }
        // v1.5.5+：DPS-8 — 文档处理
        if (cbDocumentTruncationEnabled != null) {
            cfg.documentTruncationEnabled = cbDocumentTruncationEnabled.isChecked();
        }
        if (etDocumentTruncationMaxChars != null) {
            int parsed = safeInt(etDocumentTruncationMaxChars.getText().toString(), 4096);
            cfg.documentTruncationMaxChars = parsed > 0 ? parsed : 4096;
        }
        // cfg.stripEnableThinkingEnabled 已经按 provider 隔离，由 syncLegacyProviderFields 同步
        cfg.stripRestrictionLineEnabled = cbStripRestrictionLine.isChecked();
        cfg.stripSystemTimeEnabled = cbStripSystemTime.isChecked();
        cfg.personaEnabled = cbPersonaEnabled.isChecked();
        cfg.personaIgnoreAffinityEnabled = swPersonaIgnoreAffinity.isChecked();
        ArrayList<ProxyConfig.ProviderProfile> providers = new ArrayList<>();
        for (ProxyConfig.ProviderProfile profile : providerProfileDrafts.values()) {
            if (profile != null) {
                providers.add(profile.copy());
            }
        }
        if (!providerProfileDrafts.containsKey(activeProviderIdDraft) && !providerProfileDrafts.isEmpty()) {
            activeProviderIdDraft = providerProfileDrafts.keySet().iterator().next();
        }
        cfg.replaceProviderProfiles(providers, activeProviderIdDraft);
        ArrayList<ProxyConfig.PersonaProfile> profiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : personaProfileNames.entrySet()) {
            String profileId = entry.getKey();
            LinkedHashMap<String, String> tierDrafts = personaProfileDrafts.get(profileId);
            ProxyConfig.PersonaProfile profile = new ProxyConfig.PersonaProfile(profileId, entry.getValue());
            for (String tier : ProxyConfig.PERSONA_TIERS) {
                String rawJson = tierDrafts == null ? "" : tierDrafts.get(tier);
                profile.setTierJson(tier, safeString(rawJson, ""));
            }
            profiles.add(profile);
        }
        cfg.replacePersonaProfiles(profiles, currentPersonaProfileId);
        ArrayList<ProxyConfig.GlobalPersonaProfile> globalProfiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : globalPersonaProfileNames.entrySet()) {
            String profileId = entry.getKey();
            String rawJson = safeString(globalPersonaProfileDrafts.get(profileId), "");
            globalProfiles.add(new ProxyConfig.GlobalPersonaProfile(profileId, entry.getValue(), rawJson));
        }
        cfg.replaceGlobalPersonaProfiles(globalProfiles, currentGlobalPersonaProfileId);
        // v1.5.1+：联网搜索全局字段写到 cfg 顶层（独立持久化，不再 per-provider）
        cfg.webSearchToolEnabled = ProxyConfig.normalizeWebSearchToolEnabled(
                getSpinnerStringValueFromArray(
                        spHomeWebSearchToolEnabled != null ? spHomeWebSearchToolEnabled : spWebSearchToolEnabled,
                        WEB_SEARCH_TOOL_VALUES,
                        cfg.webSearchToolEnabled));
        // 切换前先把当前 etWebSearchApiKey/Endpoint/Proxy 文本存到 pendingJson 当前引擎槽
        saveWebSearchEngineDraftGlobal(currentWebSearchProviderDraft);
        cfg.webSearchProvider = ProxyConfig.normalizeWebSearchProvider(
                getSpinnerStringValueFromArray(
                        spHomeWebSearchProvider != null ? spHomeWebSearchProvider : spWebSearchProvider,
                        WEB_SEARCH_PROVIDER_VALUES, cfg.webSearchProvider));
        cfg.webSearchApiKeysJson = ProxyConfig.normalizeWebSearchApiKeysJson(pendingWebSearchApiKeysJson);
        cfg.webSearchApiKey = ProxyConfig.getWebSearchApiKey(cfg.webSearchApiKeysJson, cfg.webSearchProvider);
        cfg.webSearchEndpointsJson = ProxyConfig.normalizeWebSearchEndpointsJson(pendingWebSearchEndpointsJson);
        cfg.webSearchEndpoint = ProxyConfig.getWebSearchEndpoint(cfg.webSearchEndpointsJson, cfg.webSearchProvider);
        cfg.webSearchProxiesJson = ProxyConfig.normalizeWebSearchProxiesJson(pendingWebSearchProxiesJson);
        ProxyConfig.WebSearchProxyEntry currentProxy = ProxyConfig.getWebSearchProxy(cfg.webSearchProxiesJson, cfg.webSearchProvider);
        cfg.webSearchProxyType = currentProxy.type;
        cfg.webSearchProxyHost = currentProxy.host;
        cfg.webSearchProxyPort = currentProxy.port;
        if (etWebSearchMaxResults != null) {
            int parsed = safeInt(etWebSearchMaxResults.getText().toString(), cfg.webSearchMaxResults);
            cfg.webSearchMaxResults = Math.max(1, Math.min(20, parsed));
        }
        return cfg.ensureDefaults();
    }

    private void toggleMatchingRules() {
        layoutMatchingRules.setVisibility(layoutMatchingRules.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        updateRuleToggleUi();
    }

    private void updateRuleToggleUi() {
        boolean visible = layoutMatchingRules.getVisibility() == View.VISIBLE;
        btnToggleRules.setText(visible ? "收起识别规则（日记 / 主人设 / 小剧场）" : "展开识别规则（日记 / 主人设 / 小剧场）");
        tvRulesStatus.setText(cbDetectionEnabled.isChecked() ? "识别规则：已开启" : "识别规则：已关闭");
    }

    private void updateEnableThinkingInputsEnabled() {
        boolean enabled = cbStripEnableThinking == null || !cbStripEnableThinking.isChecked();
        applyEnableThinkingInputState(etChatOverrideEnableThinking, enabled);
        applyEnableThinkingInputState(etDiaryOverrideEnableThinking, enabled);
        applyEnableThinkingInputState(etMemoryExtractOverrideEnableThinking, enabled);
    }

    private void applyEnableThinkingInputState(EditText editText, boolean enabled) {
        if (editText == null) {
            return;
        }
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        editText.setEnabled(enabled);
        setCompactHint(editText, enabled ? "true / false / 留空（v1.5.2+ 自动翻译为 thinking:{type:...}）" : "已由移除思考开关禁用");
        editText.setTextColor(enabled ? palette.textPrimary : palette.textSecondary);
        editText.setHintTextColor(palette.textSecondary);
        editText.setAlpha(enabled ? 1f : 0.65f);
    }

    /**
     * v1.5.x：日记 assistant 前缀续写——主开关切换时联动 EditText 和 DeepSeek 兼容 CB 的可用状态。
     * 主开关关闭时，前缀文本框和 DeepSeek 复选框置灰（视觉提示但不清空，用户重新打开主开关后保留先前输入）。
     */
    private void updateDiaryAssistantPrefixInputsEnabled() {
        boolean enabled = cbDiaryAssistantPrefixEnabled != null && cbDiaryAssistantPrefixEnabled.isChecked();
        if (etDiaryAssistantPrefix != null) {
            UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
            etDiaryAssistantPrefix.setEnabled(enabled);
            etDiaryAssistantPrefix.setTextColor(enabled ? palette.textPrimary : palette.textSecondary);
            etDiaryAssistantPrefix.setHintTextColor(palette.textSecondary);
            etDiaryAssistantPrefix.setAlpha(enabled ? 1f : 0.65f);
        }
        if (cbDiaryAssistantPrefixDeepseekMode != null) {
            cbDiaryAssistantPrefixDeepseekMode.setEnabled(enabled);
            cbDiaryAssistantPrefixDeepseekMode.setAlpha(enabled ? 1f : 0.65f);
        }
        if (cbDiaryAssistantPrefixSiliconflowMode != null) {
            cbDiaryAssistantPrefixSiliconflowMode.setEnabled(enabled);
            cbDiaryAssistantPrefixSiliconflowMode.setAlpha(enabled ? 1f : 0.65f);
        }
    }

    private void onAdapterPresetSelected(String adapter) {
        updateAdapterUi(true);
    }

    private void updateAdapterUi() {
        updateAdapterUi(false);
    }

    private void updateAdapterUi(boolean applyRecommendedPaths) {
        String adapter = getSpinnerValue(spAdapterPreset, ProxyConfig.ADAPTER_OPENAI_COMPATIBLE);
        layoutGenericFields.setVisibility(ProxyConfig.ADAPTER_GENERIC_CUSTOM.equals(adapter) ? View.VISIBLE : View.GONE);
        if (applyRecommendedPaths && !suppressAdapterAutoPaths && !TextUtils.equals(adapter, lastAdapterPreset)) {
            applyRecommendedUpstreamPaths(adapter);
        }
        lastAdapterPreset = adapter;
        updateUpstreamPreviewFromUi();
    }

    private void applyRecommendedUpstreamPaths(String adapter) {
        String recommendedChatPath = recommendedUpstreamChatPath(adapter);
        String recommendedModelsPath = recommendedUpstreamModelsPath(adapter);
        if (!TextUtils.isEmpty(recommendedChatPath)) {
            etUpstreamChatPath.setText(recommendedChatPath);
        }
        if (!TextUtils.isEmpty(recommendedModelsPath)) {
            etUpstreamModelsPath.setText(recommendedModelsPath);
        }
    }

    private String recommendedUpstreamChatPath(String adapter) {
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapter)) {
            return "/v1/messages";
        }
        if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapter)) {
            return "/v1/responses";
        }
        if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapter)) {
            return "/v1beta/models/${model}:generateContent";
        }
        if (ProxyConfig.ADAPTER_OPENAI_COMPATIBLE.equals(adapter)) {
            return ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH;
        }
        return "";
    }

    private String recommendedUpstreamModelsPath(String adapter) {
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapter)) {
            return "/v1/models";
        }
        if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapter)) {
            return "/v1/models";
        }
        if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapter)) {
            return "/v1beta/models";
        }
        if (ProxyConfig.ADAPTER_OPENAI_COMPATIBLE.equals(adapter)) {
            return ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH;
        }
        return "";
    }

    private void updateUpstreamPreviewFromUi() {
        String baseUrl = editTextValue(etBaseUrl);
        String chatPath = editTextValue(etUpstreamChatPath);
        String modelsPath = editTextValue(etUpstreamModelsPath);
        ProxyConfig.ProviderProfile activeProvider = getActiveProviderDraft();
        String model = activeProvider == null ? "" : safeString(activeProvider.getActiveModelName(), "");
        updateUpstreamPathPreviews(baseUrl, chatPath, modelsPath, model);
    }

    private void updateUpstreamPathPreviews(String baseUrl, String chatPath, String modelsPath, String model) {
        if (tvUpstreamChatPreview != null) {
            tvUpstreamChatPreview.setText("聊天完整路径：" + buildFullUpstreamPreviewUrl(baseUrl, chatPath, model));
        }
        if (tvUpstreamModelsPreview != null) {
            tvUpstreamModelsPreview.setText("模型完整路径：" + buildFullUpstreamPreviewUrl(baseUrl, modelsPath, model));
        }
    }

    private String buildFullUpstreamPreviewUrl(String baseUrl, String path, String model) {
        String trimmedBase = safeString(baseUrl, "").trim();
        if (TextUtils.isEmpty(trimmedBase)) {
            return "请先填写上游 URL";
        }
        String trimmedPath = safeString(path, "").trim();
        if (TextUtils.isEmpty(trimmedPath)) {
            trimmedPath = "/";
        }
        String resolvedModel = safeString(model, "").trim();
        if (!TextUtils.isEmpty(resolvedModel)) {
            trimmedPath = trimmedPath.replace("${model}", resolvedModel);
        }
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        if (!trimmedPath.startsWith("/")) {
            trimmedPath = "/" + trimmedPath;
        }
        return trimmedBase + trimmedPath;
    }

    private ProxyConfig.ProviderProfile getCurrentProviderDraft() {
        ProxyConfig.ProviderProfile profile = providerProfileDrafts.get(currentProviderId);
        if (profile != null) {
            return profile;
        }
        if (!providerProfileDrafts.isEmpty()) {
            currentProviderId = providerProfileDrafts.keySet().iterator().next();
            return providerProfileDrafts.get(currentProviderId);
        }
        ProxyConfig.ProviderProfile created = buildProviderTemplate("custom", ProxyConfig.DEFAULT_PROVIDER_NAME);
        providerProfileDrafts.put(created.id, created);
        currentProviderId = created.id;
        return created;
    }

    private ProxyConfig.ProviderProfile getActiveProviderDraft() {
        ProxyConfig.ProviderProfile profile = providerProfileDrafts.get(activeProviderIdDraft);
        if (profile != null) {
            return profile;
        }
        if (!providerProfileDrafts.isEmpty()) {
            activeProviderIdDraft = providerProfileDrafts.keySet().iterator().next();
            return providerProfileDrafts.get(activeProviderIdDraft);
        }
        return null;
    }

    private void saveCurrentProviderEditorDraft() {
        ProxyConfig.ProviderProfile profile = getCurrentProviderDraft();
        if (profile == null || etBaseUrl == null) {
            return;
        }
        profile.upstreamBaseUrl = trimToEmpty(etBaseUrl.getText().toString());
        profile.apiKey = etApiKey == null ? "" : trimToEmpty(etApiKey.getText().toString());
        profile.adapterPreset = getSpinnerValue(spAdapterPreset, ProxyConfig.ADAPTER_OPENAI_COMPATIBLE);
        profile.listenChatPaths = etListenChatPaths == null ? ProxyConfig.DEFAULT_LISTEN_CHAT_PATHS : etListenChatPaths.getText().toString();
        profile.listenModelsPaths = etListenModelsPaths == null ? ProxyConfig.DEFAULT_LISTEN_MODELS_PATHS : etListenModelsPaths.getText().toString();
        profile.upstreamChatPath = etUpstreamChatPath == null ? ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH : safeString(etUpstreamChatPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH);
        profile.upstreamModelsPath = etUpstreamModelsPath == null ? ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH : safeString(etUpstreamModelsPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH);
        profile.upstreamProxyType = getSpinnerValue(spUpstreamProxyType, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE);
        profile.upstreamProxyHost = etUpstreamProxyHost == null ? "" : trimToEmpty(etUpstreamProxyHost.getText().toString());
        profile.upstreamProxyPort = etUpstreamProxyPort == null ? 0 : safeInt(etUpstreamProxyPort.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_PROXY_PORT);
        profile.requestMessagesPath = etRequestMessagesPath == null ? ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH : safeString(etRequestMessagesPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH);
        profile.requestUserTextPath = etRequestUserTextPath == null ? ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH : safeString(etRequestUserTextPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH);
        profile.requestModelPath = etRequestModelPath == null ? ProxyConfig.DEFAULT_REQUEST_MODEL_PATH : safeString(etRequestModelPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MODEL_PATH);
        profile.requestMaxTokensPath = etRequestMaxTokensPath == null ? ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH : safeString(etRequestMaxTokensPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH);
        profile.requestTemperaturePath = etRequestTemperaturePath == null ? ProxyConfig.DEFAULT_REQUEST_TEMPERATURE_PATH : safeString(etRequestTemperaturePath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_TEMPERATURE_PATH);
        profile.requestEnableThinkingPath = etRequestEnableThinkingPath == null ? ProxyConfig.DEFAULT_REQUEST_ENABLE_THINKING_PATH : safeString(etRequestEnableThinkingPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_ENABLE_THINKING_PATH);
        profile.responseTextPath = etResponseTextPath == null ? ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH : safeString(etResponseTextPath.getText().toString(), ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
        profile.chatCustomRequestFieldsJson = etChatCustomRequestFields == null ? "" : trimToEmpty(etChatCustomRequestFields.getText().toString());
        profile.diaryCustomRequestFieldsJson = etDiaryCustomRequestFields == null ? "" : trimToEmpty(etDiaryCustomRequestFields.getText().toString());
        profile.memoryExtractCustomRequestFieldsJson = etMemoryExtractCustomRequestFields == null ? "" : trimToEmpty(etMemoryExtractCustomRequestFields.getText().toString());
        profile.chatOverrideEnableThinking = etChatOverrideEnableThinking == null ? "false" : trimToEmpty(etChatOverrideEnableThinking.getText().toString());
        profile.diaryOverrideEnableThinking = etDiaryOverrideEnableThinking == null ? "false" : trimToEmpty(etDiaryOverrideEnableThinking.getText().toString());
        profile.memoryExtractOverrideEnableThinking = etMemoryExtractOverrideEnableThinking == null ? "false" : trimToEmpty(etMemoryExtractOverrideEnableThinking.getText().toString());
        profile.chatOverrideMaxTokens = etChatOverrideMaxTokens == null ? 1500 : safeInt(etChatOverrideMaxTokens.getText().toString(), 1500);
        profile.chatOverrideTemperature = etChatOverrideTemperature == null ? "0.9" : trimToEmpty(etChatOverrideTemperature.getText().toString());
        profile.diaryOverrideMaxTokens = etOverrideMaxTokens == null ? 2500 : safeInt(etOverrideMaxTokens.getText().toString(), 2500);
        profile.diaryOverrideTemperature = etDiaryOverrideTemperature == null ? "0.9" : trimToEmpty(etDiaryOverrideTemperature.getText().toString());
        // v1.5.x：日记 assistant 前缀续写（per-provider）
        profile.diaryAssistantPrefixEnabled = cbDiaryAssistantPrefixEnabled != null && cbDiaryAssistantPrefixEnabled.isChecked();
        profile.diaryAssistantPrefix = etDiaryAssistantPrefix == null
                ? "【日记】"
                : safeString(etDiaryAssistantPrefix.getText().toString(), "【日记】");
        profile.diaryAssistantPrefixDeepseekMode = cbDiaryAssistantPrefixDeepseekMode != null && cbDiaryAssistantPrefixDeepseekMode.isChecked();
        profile.diaryAssistantPrefixSiliconflowMode = cbDiaryAssistantPrefixSiliconflowMode != null && cbDiaryAssistantPrefixSiliconflowMode.isChecked();
        profile.memoryExtractOverrideMaxTokens = etMemoryExtractOverrideMaxTokens == null ? ProxyConfig.DEFAULT_MEMORY_EXTRACT_MAX_TOKENS : safeInt(etMemoryExtractOverrideMaxTokens.getText().toString(), ProxyConfig.DEFAULT_MEMORY_EXTRACT_MAX_TOKENS);
        profile.memoryExtractOverrideTemperature = etMemoryExtractOverrideTemperature == null ? ProxyConfig.DEFAULT_MEMORY_EXTRACT_TEMPERATURE : trimToEmpty(etMemoryExtractOverrideTemperature.getText().toString());
        profile.stripEnableThinkingEnabled = cbStripEnableThinking != null && cbStripEnableThinking.isChecked();
        // v1.5.1+：附件 / 副模型 / 联网搜索
        // multimodalCapability：保留字段做兼容兜底，但 UI 不再暴露开关，统一写 "auto"
        profile.multimodalCapability = "auto";
        // 视觉模型 + 副模型策略来自主页 Spinner。这里保存的是 provider 详情页字段；
        // 主页字段统一在 readConfigFromUi() 里写入 active provider，避免误写到当前正在编辑但未生效的 provider。
        profile.captionMaxImagesPerRequest = profile.captionMaxImagesPerRequest > 0 ? profile.captionMaxImagesPerRequest : 4;
        // v1.5.1+：联网搜索字段已全局化（cfg 顶层），不再写入 ProviderProfile
    }

    private ProxyConfig buildConfigForProviderAction(ProxyConfig cfg, ProxyConfig.ProviderProfile provider, String modelName) {
        if (cfg == null || provider == null) {
            return cfg;
        }
        cfg.activeProviderId = safeString(provider.id, cfg.activeProviderId);
        cfg.upstreamBaseUrl = safeString(provider.upstreamBaseUrl, ProxyConfig.DEFAULT_BASE_URL);
        cfg.apiKey = safeString(provider.apiKey, "");
        cfg.adapterPreset = safeString(provider.adapterPreset, ProxyConfig.ADAPTER_OPENAI_COMPATIBLE);
        cfg.listenChatPaths = safeString(provider.listenChatPaths, ProxyConfig.DEFAULT_LISTEN_CHAT_PATHS);
        cfg.listenModelsPaths = safeString(provider.listenModelsPaths, ProxyConfig.DEFAULT_LISTEN_MODELS_PATHS);
        cfg.upstreamChatPath = safeString(provider.upstreamChatPath, ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH);
        cfg.upstreamModelsPath = safeString(provider.upstreamModelsPath, ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH);
        cfg.upstreamProxyType = safeString(provider.upstreamProxyType, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE);
        cfg.upstreamProxyHost = safeString(provider.upstreamProxyHost, ProxyConfig.DEFAULT_UPSTREAM_PROXY_HOST);
        cfg.upstreamProxyPort = provider.upstreamProxyPort > 0 ? provider.upstreamProxyPort : ProxyConfig.DEFAULT_UPSTREAM_PROXY_PORT;
        cfg.requestMessagesPath = safeString(provider.requestMessagesPath, ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH);
        cfg.requestUserTextPath = safeString(provider.requestUserTextPath, ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH);
        cfg.requestModelPath = safeString(provider.requestModelPath, ProxyConfig.DEFAULT_REQUEST_MODEL_PATH);
        cfg.requestMaxTokensPath = safeString(provider.requestMaxTokensPath, ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH);
        cfg.requestTemperaturePath = safeString(provider.requestTemperaturePath, ProxyConfig.DEFAULT_REQUEST_TEMPERATURE_PATH);
        cfg.requestEnableThinkingPath = safeString(provider.requestEnableThinkingPath, ProxyConfig.DEFAULT_REQUEST_ENABLE_THINKING_PATH);
        cfg.responseTextPath = safeString(provider.responseTextPath, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
        cfg.chatCustomRequestFieldsJson = safeString(provider.chatCustomRequestFieldsJson, "");
        cfg.diaryCustomRequestFieldsJson = safeString(provider.diaryCustomRequestFieldsJson, "");
        cfg.memoryExtractCustomRequestFieldsJson = safeString(provider.memoryExtractCustomRequestFieldsJson, "");
        cfg.chatOverrideEnableThinking = safeString(provider.chatOverrideEnableThinking, "false");
        cfg.diaryOverrideEnableThinking = safeString(provider.diaryOverrideEnableThinking, "false");
        cfg.memoryExtractOverrideEnableThinking = safeString(provider.memoryExtractOverrideEnableThinking, "false");
        cfg.chatOverrideMaxTokens = Math.max(0, provider.chatOverrideMaxTokens);
        cfg.chatOverrideTemperature = safeString(provider.chatOverrideTemperature, "0.9");
        cfg.overrideMaxTokens = Math.max(0, provider.diaryOverrideMaxTokens);
        cfg.diaryOverrideTemperature = safeString(provider.diaryOverrideTemperature, "0.9");
        // v1.5.x：日记 assistant 前缀续写（per-provider → cfg 顶层镜像，供 DiaryProxyServer 直接读）
        cfg.diaryAssistantPrefixEnabled = provider.diaryAssistantPrefixEnabled;
        cfg.diaryAssistantPrefix = safeString(provider.diaryAssistantPrefix, "【日记】");
        cfg.diaryAssistantPrefixDeepseekMode = provider.diaryAssistantPrefixDeepseekMode;
        cfg.diaryAssistantPrefixSiliconflowMode = provider.diaryAssistantPrefixSiliconflowMode;
        cfg.memoryExtractOverrideMaxTokens = Math.max(0, provider.memoryExtractOverrideMaxTokens);
        cfg.memoryExtractOverrideTemperature = safeString(provider.memoryExtractOverrideTemperature, ProxyConfig.DEFAULT_MEMORY_EXTRACT_TEMPERATURE);
        cfg.stripEnableThinkingEnabled = provider.stripEnableThinkingEnabled;
        // v1.5.1+：附件 / 副模型字段（联网搜索字段已全局化，不再从 provider 覆盖到 cfg 顶层）
        cfg.multimodalCapability = "auto";
        cfg.captionProviderId = safeString(provider.captionProviderId, "");
        cfg.captionStrategy = ProxyConfig.normalizeCaptionStrategy(provider.captionStrategy);
        cfg.captionMaxImagesPerRequest = provider.captionMaxImagesPerRequest > 0 ? provider.captionMaxImagesPerRequest : 4;
        cfg.model = trimToEmpty(modelName);
        return cfg;
    }

    private void applyProviderDraftToConnectionUi(ProxyConfig.ProviderProfile profile) {
        if (profile == null) {
            return;
        }
        boolean oldSuppress = suppressUnsavedTracking;
        suppressUnsavedTracking = true;
        suppressAdapterAutoPaths = true;
        etBaseUrl.setText(safeString(profile.upstreamBaseUrl, ""));
        etApiKey.setText(safeString(profile.apiKey, ""));
        apiKeyVisible = false;
        applyApiKeyVisibility();
        selectSpinnerValue(spAdapterPreset, safeString(profile.adapterPreset, ProxyConfig.ADAPTER_OPENAI_COMPATIBLE));
        etListenChatPaths.setText(safeString(profile.listenChatPaths, ProxyConfig.DEFAULT_LISTEN_CHAT_PATHS));
        etListenModelsPaths.setText(safeString(profile.listenModelsPaths, ProxyConfig.DEFAULT_LISTEN_MODELS_PATHS));
        etUpstreamChatPath.setText(safeString(profile.upstreamChatPath, ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH));
        etUpstreamModelsPath.setText(safeString(profile.upstreamModelsPath, ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH));
        selectSpinnerValue(spUpstreamProxyType, safeString(profile.upstreamProxyType, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE));
        etUpstreamProxyHost.setText(safeString(profile.upstreamProxyHost, ""));
        etUpstreamProxyPort.setText(profile.upstreamProxyPort > 0 ? String.valueOf(profile.upstreamProxyPort) : "");
        etRequestMessagesPath.setText(safeString(profile.requestMessagesPath, ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH));
        etRequestUserTextPath.setText(safeString(profile.requestUserTextPath, ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH));
        etRequestModelPath.setText(safeString(profile.requestModelPath, ProxyConfig.DEFAULT_REQUEST_MODEL_PATH));
        etRequestMaxTokensPath.setText(safeString(profile.requestMaxTokensPath, ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH));
        etRequestTemperaturePath.setText(safeString(profile.requestTemperaturePath, ProxyConfig.DEFAULT_REQUEST_TEMPERATURE_PATH));
        etRequestEnableThinkingPath.setText(safeString(profile.requestEnableThinkingPath, ProxyConfig.DEFAULT_REQUEST_ENABLE_THINKING_PATH));
        etResponseTextPath.setText(safeString(profile.responseTextPath, ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH));
        if (etChatCustomRequestFields != null) {
            etChatCustomRequestFields.setText(safeString(profile.chatCustomRequestFieldsJson, ""));
        }
        if (etDiaryCustomRequestFields != null) {
            etDiaryCustomRequestFields.setText(safeString(profile.diaryCustomRequestFieldsJson, ""));
        }
        if (etMemoryExtractCustomRequestFields != null) {
            etMemoryExtractCustomRequestFields.setText(safeString(profile.memoryExtractCustomRequestFieldsJson, ""));
        }
        if (etChatOverrideEnableThinking != null) {
            etChatOverrideEnableThinking.setText(safeString(profile.chatOverrideEnableThinking, "false"));
        }
        if (etDiaryOverrideEnableThinking != null) {
            etDiaryOverrideEnableThinking.setText(safeString(profile.diaryOverrideEnableThinking, "false"));
        }
        if (etMemoryExtractOverrideEnableThinking != null) {
            etMemoryExtractOverrideEnableThinking.setText(safeString(profile.memoryExtractOverrideEnableThinking, "false"));
        }
        if (etChatOverrideMaxTokens != null) {
            etChatOverrideMaxTokens.setText(String.valueOf(profile.chatOverrideMaxTokens));
        }
        if (etChatOverrideTemperature != null) {
            etChatOverrideTemperature.setText(safeString(profile.chatOverrideTemperature, "0.9"));
        }
        if (etOverrideMaxTokens != null) {
            etOverrideMaxTokens.setText(String.valueOf(profile.diaryOverrideMaxTokens));
        }
        if (etDiaryOverrideTemperature != null) {
            etDiaryOverrideTemperature.setText(safeString(profile.diaryOverrideTemperature, "0.9"));
        }
        // v1.5.x：日记 assistant 前缀续写（profile → UI，切换 provider 时同步）
        if (cbDiaryAssistantPrefixEnabled != null) {
            cbDiaryAssistantPrefixEnabled.setChecked(profile.diaryAssistantPrefixEnabled);
        }
        if (etDiaryAssistantPrefix != null) {
            etDiaryAssistantPrefix.setText(safeString(profile.diaryAssistantPrefix, "【日记】"));
        }
        if (cbDiaryAssistantPrefixDeepseekMode != null) {
            cbDiaryAssistantPrefixDeepseekMode.setChecked(profile.diaryAssistantPrefixDeepseekMode);
        }
        if (cbDiaryAssistantPrefixSiliconflowMode != null) {
            cbDiaryAssistantPrefixSiliconflowMode.setChecked(profile.diaryAssistantPrefixSiliconflowMode);
        }
        updateDiaryAssistantPrefixInputsEnabled();
        if (etMemoryExtractOverrideMaxTokens != null) {
            etMemoryExtractOverrideMaxTokens.setText(String.valueOf(profile.memoryExtractOverrideMaxTokens));
        }
        if (etMemoryExtractOverrideTemperature != null) {
            etMemoryExtractOverrideTemperature.setText(safeString(profile.memoryExtractOverrideTemperature, ProxyConfig.DEFAULT_MEMORY_EXTRACT_TEMPERATURE));
        }
        if (cbStripEnableThinking != null) {
            cbStripEnableThinking.setChecked(profile.stripEnableThinkingEnabled);
        }
        // v1.5.1+：附件 / 副模型 / 联网搜索字段已全局化（applyWebSearchSettingsToUi + applyHomeCaptionFieldsToUi 处理）
        updateAdapterUi(false);
        suppressAdapterAutoPaths = false;
        updateUpstreamPreviewFromUi();
        suppressUnsavedTracking = oldSuppress;
    }

    private void updateProviderProfileSpinner() {
        ArrayList<SpinnerOption> options = new ArrayList<>();
        for (ProxyConfig.ProviderProfile profile : providerProfileDrafts.values()) {
            if (profile != null) {
                options.add(new SpinnerOption(profile.id, safeString(profile.name, profile.id)));
            }
        }
        setSpinnerOptions(spProviderProfile, options);
        updateProviderRows();
        updateProviderButtonState();
    }

    private void updateProviderRows() {
        if (layoutProviderRows == null) {
            return;
        }
        layoutProviderRows.removeAllViews();
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        for (ProxyConfig.ProviderProfile profile : providerProfileDrafts.values()) {
            if (profile == null) {
                continue;
            }
            profile.ensureModelDefaults();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4), dp(14), dp(4), dp(14));
            row.setBackground(buildProviderRowBackground(palette));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = dp(1);
            row.setLayoutParams(rowParams);

            TextView icon = new TextView(this);
            icon.setGravity(android.view.Gravity.CENTER);
            icon.setText(providerInitial(profile.name));
            icon.setTextColor(Color.WHITE);
            icon.setTextSize(15f);
            icon.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setShape(GradientDrawable.OVAL);
            iconBg.setColor(providerIconColor(profile.adapterPreset));
            icon.setBackground(iconBg);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
            iconParams.setMarginEnd(dp(14));
            row.addView(icon, iconParams);

            LinearLayout textColumn = new LinearLayout(this);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(safeString(profile.name, profile.id));
            name.setTextColor(palette.textPrimary);
            name.setTextSize(17f);
            TextView summary = new TextView(this);
            int modelCount = profile.models == null ? 0 : profile.models.size();
            summary.setText("已加入 " + modelCount + " 个模型");
            summary.setTextColor(palette.textSecondary);
            summary.setTextSize(12f);
            textColumn.addView(name);
            textColumn.addView(summary);
            row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextColor(palette.textSecondary);
            arrow.setTextSize(30f);
            row.addView(arrow);
            row.setOnClickListener(v -> showProviderDetailPage(profile.id));
            layoutProviderRows.addView(row);
        }
    }

    private GradientDrawable buildProviderRowBackground(UiThemePalette palette) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bg);
        bg.setStroke(dp(1), palette.outline);
        return bg;
    }

    private String providerInitial(String name) {
        String value = safeString(name, "P");
        return value.length() <= 1 ? value : value.substring(0, 1);
    }

    private int providerIconColor(String adapterPreset) {
        if (ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT.equals(adapterPreset)) {
            return Color.rgb(63, 100, 244);
        }
        if (ProxyConfig.ADAPTER_CLAUDE_MESSAGES.equals(adapterPreset)) {
            return Color.rgb(207, 119, 86);
        }
        if (ProxyConfig.ADAPTER_OPENAI_RESPONSES.equals(adapterPreset)) {
            return Color.rgb(46, 161, 116);
        }
        if (ProxyConfig.ADAPTER_GENERIC_CUSTOM.equals(adapterPreset)) {
            return Color.rgb(115, 84, 206);
        }
        return Color.rgb(40, 40, 40);
    }

    private void updateProviderModelSpinner() {
        updateProviderModelRows();
        updateProviderButtonState();
    }

    private void updateProviderModelRows() {
        if (layoutProviderModelRows == null) {
            return;
        }
        layoutProviderModelRows.removeAllViews();
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null) {
            return;
        }
        provider.ensureModelDefaults();
        for (ProxyConfig.ModelProfile model : provider.models) {
            if (model == null || TextUtils.isEmpty(model.name)) {
                continue;
            }
            layoutProviderModelRows.addView(buildProviderModelRow(model.name, true));
        }
    }

    private View buildProviderModelRow(String modelName, boolean saved) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(12), dp(8), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bg);
        bg.setStroke(dp(1), palette.outline);
        row.setBackground(bg);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(modelName);
        name.setTextColor(palette.textPrimary);
        name.setTextSize(16f);
        textColumn.addView(name);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setGravity(android.view.Gravity.CENTER);
        action.setText(saved ? "⊖" : "⊕");
        action.setTextColor(saved ? Color.rgb(239, 68, 68) : palette.secondary);
        action.setTextSize(26f);
        action.setOnClickListener(v -> {
            if (saved) {
                deleteProviderModelByName(modelName);
            } else {
                addFetchedModelToCurrentProvider(modelName);
            }
        });
        row.addView(action, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(1);
        row.setLayoutParams(rowParams);
        return row;
    }

    private void updateHomeActiveModelSpinner() {
        ArrayList<SpinnerOption> options = new ArrayList<>();
        options.add(new SpinnerOption("", "未选择模型"));
        for (ProxyConfig.ProviderProfile provider : providerProfileDrafts.values()) {
            if (provider == null) {
                continue;
            }
            provider.ensureModelDefaults();
            for (ProxyConfig.ModelProfile model : provider.models) {
                if (model != null) {
                    options.add(new SpinnerOption(
                            homeModelValue(provider.id, model.id),
                            safeString(provider.name, provider.id) + " / " + safeString(model.name, model.id)
                    ));
                }
            }
        }
        setSpinnerOptions(spHomeActiveModel, options);
        refreshHomeActiveModelButtonLabel();
        refreshHomeTestButtonState();
    }

    private void updateProviderButtonState() {
        boolean hasProvider = !providerProfileDrafts.isEmpty();
        if (spProviderProfile != null) {
            spProviderProfile.setEnabled(hasProvider);
        }
        if (btnProviderCopy != null) {
            btnProviderCopy.setEnabled(hasProvider);
        }
        if (btnProviderRename != null) {
            btnProviderRename.setEnabled(hasProvider);
        }
        if (btnProviderDelete != null) {
            btnProviderDelete.setEnabled(providerProfileDrafts.size() > 1);
        }
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (btnModelDelete != null) {
            boolean hasSavedModels = provider != null && provider.models != null && !provider.models.isEmpty();
            btnModelDelete.setEnabled(hasSavedModels);
        }
    }

    private void onProviderProfileSelected(String providerId) {
        if (!providerSpinnerReady || TextUtils.isEmpty(providerId) || TextUtils.equals(providerId, currentProviderId)) {
            return;
        }
        saveCurrentProviderEditorDraft();
        currentProviderId = providerId;
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        providerModelSpinnerReady = false;
        applyProviderDraftToConnectionUi(provider);
        updateProviderModelSpinner();
        providerModelSpinnerReady = true;
        homeModelSpinnerReady = false;
        updateHomeActiveModelSpinner();
        selectSpinnerValue(spHomeActiveModel, currentHomeModelSelectionValue());
        homeModelSpinnerReady = true;
    }

    private void onProviderModelSelected(String modelId) {
        // Provider detail no longer owns "current model" selection.
        // Runtime provider/model is selected only from the home page spinner.
    }

    private void onHomeActiveModelSelected(String rawValue) {
        if (!homeModelSpinnerReady) {
            return;
        }
        if (TextUtils.isEmpty(rawValue)) {
            clearActiveModelFromHome();
            return;
        }
        String[] parts = splitHomeModelValue(rawValue);
        if (parts.length != 2) {
            return;
        }
        ProxyConfig.ProviderProfile provider = providerProfileDrafts.get(parts[0]);
        if (provider == null || provider.findModel(parts[1]) == null) {
            return;
        }
        if (TextUtils.equals(parts[0], activeProviderIdDraft) && TextUtils.equals(parts[1], provider.activeModelId)) {
            return;
        }
        String oldSelection = currentHomeModelSelectionValue();
        String oldActiveProviderId = activeProviderIdDraft;
        ProxyConfig.ProviderProfile oldActiveProvider = providerProfileDrafts.get(oldActiveProviderId);
        String oldActiveModelId = oldActiveProvider == null ? "" : safeString(oldActiveProvider.activeModelId, "");
        String newProviderPreviousModelId = safeString(provider.activeModelId, "");
        saveCurrentProviderEditorDraft();
        saveHomeCaptionFieldsToActiveProviderDraft();
        activeProviderIdDraft = parts[0];
        provider.activeModelId = parts[1];
        providerSpinnerReady = false;
        providerModelSpinnerReady = false;
        updateProviderModelSpinner();
        selectSpinnerValue(spProviderProfile, currentProviderId);
        providerSpinnerReady = true;
        providerModelSpinnerReady = true;
        ProxyConfig cfg = readConfigFromUi();
        String validationError = DiaryProxyServer.validateUpstreamConfig(cfg);
        if (!TextUtils.isEmpty(validationError)) {
            appendLog("切换模型失败：" + validationError);
            setActionStatus("切换模型失败");
            provider.activeModelId = newProviderPreviousModelId;
            activeProviderIdDraft = oldActiveProviderId;
            if (oldActiveProvider != null) {
                oldActiveProvider.activeModelId = oldActiveModelId;
            }
            homeModelSpinnerReady = false;
            selectSpinnerValue(spHomeActiveModel, oldSelection);
            homeModelSpinnerReady = true;
            refreshHomeActiveModelButtonLabel();
            new AlertDialog.Builder(this)
                    .setTitle("切换模型失败")
                    .setMessage(validationError)
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        ProxyConfig.save(this, cfg);
        if (guardianRunning) {
            requestServiceAction(ProxyForegroundService.ACTION_UPDATE_CONFIG);
        }
        appendLog("已切换当前模型：" + activeProviderModelLabel());
        setActionStatus("当前模型已生效");
        updateEndpoint(cfg);
        applyHomeCaptionFieldsToUi();
        refreshHomeActiveModelButtonLabel();
        refreshHomeTestButtonState();
    }

    private void clearActiveModelFromHome() {
        saveCurrentProviderEditorDraft();
        ProxyConfig.ProviderProfile provider = getActiveProviderDraft();
        if (provider != null && TextUtils.isEmpty(provider.activeModelId)) {
            return;
        }
        if (provider != null) {
            provider.activeModelId = "";
        }
        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (guardianRunning) {
            requestServiceAction(ProxyForegroundService.ACTION_UPDATE_CONFIG);
        }
        appendLog("当前模型已清空，请先在主页选择模型后再启动代理");
        setActionStatus("当前未选择模型");
        updateEndpoint(cfg);
        refreshHomeActiveModelButtonLabel();
        refreshHomeTestButtonState();
    }

    private String activeProviderModelLabel() {
        ProxyConfig.ProviderProfile provider = getActiveProviderDraft();
        if (provider == null) {
            return "未选择模型";
        }
        String modelName = provider.getActiveModelName();
        return safeString(provider.name, provider.id) + " / " + (TextUtils.isEmpty(modelName) ? "未选择模型" : modelName);
    }

    private String currentHomeModelSelectionValue() {
        ProxyConfig.ProviderProfile provider = getActiveProviderDraft();
        if (provider == null) {
            return "";
        }
        provider.ensureModelDefaults();
        if (TextUtils.isEmpty(provider.activeModelId) || provider.findModel(provider.activeModelId) == null) {
            return "";
        }
        return homeModelValue(provider.id, provider.activeModelId);
    }

    private String homeModelValue(String providerId, String modelId) {
        return safeString(providerId, "") + HOME_MODEL_SEPARATOR + safeString(modelId, "");
    }

    private String[] splitHomeModelValue(String rawValue) {
        return safeString(rawValue, "").split(HOME_MODEL_SEPARATOR, 2);
    }

    private void promptCreateProviderProfile(boolean copyCurrent) {
        saveCurrentProviderEditorDraft();
        if (!copyCurrent) {
            showProviderTemplateDialog();
            return;
        }
        ProxyConfig.ProviderProfile current = getCurrentProviderDraft();
        if (current == null) {
            return;
        }
        showProfileNameDialog("复制提供商", buildUniqueProviderName(safeString(current.name, "当前提供商") + " 副本"), inputName -> {
            ProxyConfig.ProviderProfile copy = current.copy();
            copy.id = buildUniqueProviderId();
            copy.name = inputName;
            providerProfileDrafts.put(copy.id, copy);
            currentProviderId = copy.id;
            refreshProviderUiAfterDraftChange(copy.activeModelId);
            appendLog("已复制提供商：" + inputName + "，记得保存配置");
            setActionStatus("已复制提供商");
            markConfigDirty();
        });
    }

    private void showProviderTemplateDialog() {
        String[] labels = new String[]{
                "OpenAI / DeepSeek 兼容",
                "DeepSeek",
                "硅基流动",
                "Qwen",
                "Gemini",
                "Claude",
                "OpenAI Responses",
                "自定义"
        };
        String[] keys = new String[]{"openai", "deepseek", "siliconflow", "qwen", "gemini", "claude", "responses", "custom"};
        new AlertDialog.Builder(this)
                .setTitle("选择提供商模板")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= keys.length) {
                        return;
                    }
                    String suggestedName = buildUniqueProviderName(labels[which]);
                    showProfileNameDialog("新增提供商", suggestedName, inputName -> {
                        ProxyConfig.ProviderProfile profile = buildProviderTemplate(keys[which], inputName);
                        providerProfileDrafts.put(profile.id, profile);
                        currentProviderId = profile.id;
                        refreshProviderUiAfterDraftChange(profile.activeModelId);
                        appendLog("已新增提供商：" + inputName + "，记得保存配置");
                        setActionStatus("已新增提供商");
                        markConfigDirty();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private ProxyConfig.ProviderProfile buildProviderTemplate(String templateKey, String name) {
        ProxyConfig.ProviderProfile profile = new ProxyConfig.ProviderProfile(buildUniqueProviderId(), safeString(name, ProxyConfig.DEFAULT_PROVIDER_NAME));
        profile.adapterPreset = ProxyConfig.ADAPTER_OPENAI_COMPATIBLE;
        profile.upstreamBaseUrl = "";
        profile.upstreamChatPath = ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH;
        profile.upstreamModelsPath = ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH;
        profile.listenChatPaths = ProxyConfig.DEFAULT_LISTEN_CHAT_PATHS;
        profile.listenModelsPaths = ProxyConfig.DEFAULT_LISTEN_MODELS_PATHS;
        if ("deepseek".equals(templateKey)) {
            profile.upstreamBaseUrl = "https://api.deepseek.com/v1";
        } else if ("siliconflow".equals(templateKey)) {
            // 硅基流动 OpenAI 兼容 endpoint。前缀续写默认勾上 SiliconFlow 兼容（顶层 prefix 字段，
            // 见 https://docs.siliconflow.cn/cn/userguide/guides/prefix），用户首次新增即可开箱即用。
            profile.upstreamBaseUrl = "https://api.siliconflow.cn/v1";
            profile.diaryAssistantPrefixSiliconflowMode = true;
        } else if ("qwen".equals(templateKey)) {
            profile.upstreamBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        } else if ("gemini".equals(templateKey)) {
            profile.adapterPreset = ProxyConfig.ADAPTER_GEMINI_GENERATE_CONTENT;
            profile.upstreamBaseUrl = "https://generativelanguage.googleapis.com";
            profile.upstreamChatPath = "/v1beta/models/${model}:generateContent";
            profile.upstreamModelsPath = "/v1beta/models";
        } else if ("claude".equals(templateKey)) {
            profile.adapterPreset = ProxyConfig.ADAPTER_CLAUDE_MESSAGES;
            profile.upstreamBaseUrl = "https://api.anthropic.com";
            profile.upstreamChatPath = "/v1/messages";
            profile.upstreamModelsPath = "/v1/models";
        } else if ("responses".equals(templateKey)) {
            profile.adapterPreset = ProxyConfig.ADAPTER_OPENAI_RESPONSES;
            profile.upstreamBaseUrl = "https://api.openai.com/v1";
            profile.upstreamChatPath = "/v1/responses";
            profile.upstreamModelsPath = "/v1/models";
        } else if ("openai".equals(templateKey)) {
            profile.upstreamBaseUrl = "https://api.openai.com/v1";
        }
        return profile;
    }

    private void promptRenameProviderProfile() {
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null) {
            return;
        }
        showProfileNameDialog("重命名提供商", safeString(provider.name, ""), inputName -> {
            provider.name = inputName;
            refreshProviderUiAfterDraftChange(provider.activeModelId);
            appendLog("已重命名提供商：" + inputName + "，记得保存配置");
            setActionStatus("已重命名提供商");
            markConfigDirty();
        });
    }

    private void promptDeleteProviderProfile() {
        if (providerProfileDrafts.size() <= 1) {
            appendLog("至少需要保留一个提供商");
            setActionStatus("删除提供商失败");
            return;
        }
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null) {
            return;
        }
        final String deletingId = provider.id;
        final String deletingName = safeString(provider.name, deletingId);
        new AlertDialog.Builder(this)
                .setTitle("删除提供商")
                .setMessage("确定删除“" + deletingName + "”吗？保存配置后生效。")
                .setPositiveButton("删除", (dialog, which) -> {
                    providerProfileDrafts.remove(deletingId);
                    currentProviderId = providerProfileDrafts.keySet().iterator().next();
                    if (TextUtils.equals(activeProviderIdDraft, deletingId) || !providerProfileDrafts.containsKey(activeProviderIdDraft)) {
                        activeProviderIdDraft = currentProviderId;
                    }
                    ProxyConfig.ProviderProfile next = getCurrentProviderDraft();
                    refreshProviderUiAfterDraftChange(next == null ? "" : next.activeModelId);
                    appendLog("已删除提供商：" + deletingName + "，记得保存配置");
                    setActionStatus("已删除提供商");
                    markConfigDirty();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetFetchedProviderModels() {
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null) {
            return;
        }
        if (provider.models != null) {
            provider.models.clear();
        }
        provider.activeModelId = "";
        ArrayList<String> fetched = providerFetchedModelDrafts.get(provider.id);
        if (fetched != null) {
            fetched.clear();
        }
        refreshProviderUiAfterDraftChange("");
        saveConfigFromUi();
        appendLog("已清空当前提供商所有已加入模型");
        setActionStatus("已清空已加入模型");
    }

    private void deleteProviderModelByName(String modelName) {
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null || provider.models == null || provider.models.isEmpty()) {
            return;
        }
        ProxyConfig.ModelProfile model = provider.findModelByName(modelName);
        if (model == null) {
            return;
        }
        final String deletingId = model.id;
        final String deletingName = safeString(model.name, deletingId);
        for (int i = provider.models.size() - 1; i >= 0; i--) {
            ProxyConfig.ModelProfile item = provider.models.get(i);
            if (item != null && TextUtils.equals(item.id, deletingId)) {
                provider.models.remove(i);
                break;
            }
        }
        provider.ensureModelDefaults();
        if (TextUtils.equals(provider.activeModelId, deletingId) || provider.findModel(provider.activeModelId) == null) {
            provider.activeModelId = "";
        }
        refreshProviderUiAfterDraftChange(provider.activeModelId);
        saveConfigFromUi();
        appendLog("已删除模型：" + deletingName);
        setActionStatus("已删除模型并保存");
    }

    private void refreshProviderUiAfterDraftChange(String selectedModelId) {
        providerSpinnerReady = false;
        providerModelSpinnerReady = false;
        homeModelSpinnerReady = false;
        updateProviderProfileSpinner();
        selectSpinnerValue(spProviderProfile, currentProviderId);
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider != null && !TextUtils.isEmpty(selectedModelId) && provider.findModel(selectedModelId) != null) {
            provider.activeModelId = selectedModelId;
        }
        applyProviderDraftToConnectionUi(provider);
        updateProviderModelSpinner();
        updateHomeActiveModelSpinner();
        selectSpinnerValue(spHomeActiveModel, currentHomeModelSelectionValue());
        refreshHomeCaptionProviderSpinnerPreservingSelection();
        providerSpinnerReady = true;
        providerModelSpinnerReady = true;
        homeModelSpinnerReady = true;
    }

    private void refreshProviderSpinnersPreservingSelection() {
        boolean oldProviderReady = providerSpinnerReady;
        boolean oldProviderModelReady = providerModelSpinnerReady;
        boolean oldHomeReady = homeModelSpinnerReady;
        providerSpinnerReady = false;
        providerModelSpinnerReady = false;
        homeModelSpinnerReady = false;
        updateProviderProfileSpinner();
        selectSpinnerValue(spProviderProfile, currentProviderId);
        updateProviderModelSpinner();
        updateHomeActiveModelSpinner();
        selectSpinnerValue(spHomeActiveModel, currentHomeModelSelectionValue());
        refreshHomeCaptionProviderSpinnerPreservingSelection();
        providerSpinnerReady = oldProviderReady;
        providerModelSpinnerReady = oldProviderModelReady;
        homeModelSpinnerReady = oldHomeReady;
    }

    private String buildUniqueProviderName(String baseName) {
        String base = safeString(baseName, "新提供商");
        String candidate = base;
        int suffix = 2;
        while (providerNameExists(candidate)) {
            candidate = base + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean providerNameExists(String name) {
        for (ProxyConfig.ProviderProfile provider : providerProfileDrafts.values()) {
            if (provider != null && TextUtils.equals(provider.name, name)) {
                return true;
            }
        }
        return false;
    }

    private String buildUniqueProviderId() {
        String base = "provider_" + System.currentTimeMillis();
        String candidate = base;
        int suffix = 2;
        while (providerProfileDrafts.containsKey(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String buildUniqueModelId(ProxyConfig.ProviderProfile provider) {
        String base = "model_" + System.currentTimeMillis();
        String candidate = base;
        int suffix = 2;
        while (provider != null && provider.findModel(candidate) != null) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void onPersonaTierSelected(String tier) {
        if (TextUtils.isEmpty(tier)) {
            return;
        }
        if (personaSpinnerReady) {
            saveCurrentTierPersonaDraft();
        }
        currentPersonaTier = tier;
        updatePersonaEditorModeUi(false);
    }

    private void saveCurrentTierPersonaDraft() {
        if (etPersonaJson != null && !TextUtils.isEmpty(currentPersonaTier)) {
            getCurrentPersonaDraftMap().put(currentPersonaTier, etPersonaJson.getText().toString());
        }
    }

    private void saveCurrentGlobalPersonaDraft() {
        if (etPersonaJson != null && !TextUtils.isEmpty(currentGlobalPersonaProfileId)) {
            globalPersonaProfileDrafts.put(currentGlobalPersonaProfileId, etPersonaJson.getText().toString());
        }
    }

    private void saveCurrentPersonaEditorDraft() {
        saveCurrentPersonaEditorDraft(isGlobalPersonaModeEnabled());
    }

    private void saveCurrentPersonaEditorDraft(boolean globalMode) {
        if (globalMode) {
            saveCurrentGlobalPersonaDraft();
        } else {
            saveCurrentTierPersonaDraft();
        }
    }

    private void onPersonaModeSwitchChanged(boolean switchedToGlobal) {
        if (!personaSpinnerReady && !globalPersonaProfileSpinnerReady) {
            return;
        }
        saveCurrentPersonaEditorDraft(!switchedToGlobal);
        updatePersonaEditorModeUi(true);
    }

    private String validatePersonaDrafts() {
        for (Map.Entry<String, String> profileEntry : personaProfileNames.entrySet()) {
            String profileId = profileEntry.getKey();
            String profileName = safeString(profileEntry.getValue(), profileId);
            LinkedHashMap<String, String> tierDrafts = personaProfileDrafts.get(profileId);
            for (String tier : ProxyConfig.PERSONA_TIERS) {
                String raw = tierDrafts == null ? "" : tierDrafts.get(tier);
                if (TextUtils.isEmpty(raw)) {
                    continue;
                }
                try {
                    new JSONObject(raw);
                } catch (Exception error) {
                    return profileName + " / " + tier + "：" + error.getMessage();
                }
            }
        }
        for (Map.Entry<String, String> profileEntry : globalPersonaProfileNames.entrySet()) {
            String profileId = profileEntry.getKey();
            String profileName = safeString(profileEntry.getValue(), profileId);
            String raw = safeString(globalPersonaProfileDrafts.get(profileId), "");
            if (TextUtils.isEmpty(raw)) {
                return profileName + "：内容为空";
            }
            try {
                new JSONObject(raw);
            } catch (Exception error) {
                return profileName + "：" + error.getMessage();
            }
        }
        return "";
    }

    private String validateCustomRequestFields() {
        // 1. 当前 active provider 的 UI 内容（最常见入口）
        String error = validateCustomRequestField("聊天自定义请求字段", etChatCustomRequestFields);
        if (!TextUtils.isEmpty(error)) {
            return error;
        }
        error = validateCustomRequestField("日记自定义请求字段", etDiaryCustomRequestFields);
        if (!TextUtils.isEmpty(error)) {
            return error;
        }
        error = validateCustomRequestField("长期记忆自定义请求字段", etMemoryExtractCustomRequestFields);
        if (!TextUtils.isEmpty(error)) {
            return error;
        }
        // 2. 其它 provider 的 draft（用户切到 B 编辑、A 上一次留下了非法 JSON 的情况）
        for (ProxyConfig.ProviderProfile draft : providerProfileDrafts.values()) {
            if (draft == null || TextUtils.equals(draft.id, currentProviderId)) {
                continue;
            }
            String prefix = "提供商「" + safeString(draft.name, draft.id) + "」";
            error = validateProviderDraftJson(prefix + " 聊天自定义请求字段", draft.chatCustomRequestFieldsJson);
            if (!TextUtils.isEmpty(error)) {
                return error;
            }
            error = validateProviderDraftJson(prefix + " 日记自定义请求字段", draft.diaryCustomRequestFieldsJson);
            if (!TextUtils.isEmpty(error)) {
                return error;
            }
            error = validateProviderDraftJson(prefix + " 长期记忆自定义请求字段", draft.memoryExtractCustomRequestFieldsJson);
            if (!TextUtils.isEmpty(error)) {
                return error;
            }
        }
        return "";
    }

    private String validateProviderDraftJson(String label, String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        try {
            new JSONObject(raw);
            return "";
        } catch (Exception error) {
            return label + "：" + error.getMessage();
        }
    }

    private String validateCustomRequestField(String label, EditText editText) {
        String raw = editText == null ? "" : trimToEmpty(editText.getText().toString());
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        try {
            new JSONObject(raw);
            return "";
        } catch (Exception error) {
            return label + "：" + error.getMessage();
        }
    }

    private boolean ensureCustomRequestFieldsValid(String actionLabel) {
        return ensureCustomRequestFieldsValid(actionLabel, true);
    }

    private boolean ensureCustomRequestFieldsValid(String actionLabel, boolean showDialog) {
        String validationError = validateCustomRequestFields();
        if (TextUtils.isEmpty(validationError)) {
            return true;
        }
        setActionStatus(actionLabel + "失败");
        appendLog("自定义请求字段 JSON 不合法：" + validationError);
        if (showDialog) {
            new AlertDialog.Builder(this)
                    .setTitle(actionLabel + "失败：自定义追加请求字段 JSON 不合法")
                    .setMessage(validationError + "\n\n请展开「自定义追加请求字段」检查并修正。")
                    .setPositiveButton("确定", null)
                    .show();
        }
        return false;
    }

    private boolean ensureSelectedModelAvailable(ProxyConfig cfg, String actionLabel) {
        if (cfg != null && !TextUtils.isEmpty(trimToEmpty(cfg.model))) {
            return true;
        }
        String message = "请先在连接/API 获取并加入模型，再到主页选择模型。";
        setActionStatus(actionLabel + "失败");
        appendLog(actionLabel + "失败：当前未选择模型。" + message);
        new AlertDialog.Builder(this)
                .setTitle("当前未选择模型")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
        return false;
    }

    private void updatePersonaProfileSpinner() {
        ArrayList<SpinnerOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : personaProfileNames.entrySet()) {
            options.add(new SpinnerOption(entry.getKey(), safeString(entry.getValue(), entry.getKey())));
        }
        setSpinnerOptions(spPersonaProfile, options);
        updatePersonaProfileButtonState();
    }

    private void updateGlobalPersonaProfileSpinner() {
        ArrayList<SpinnerOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : globalPersonaProfileNames.entrySet()) {
            options.add(new SpinnerOption(entry.getKey(), safeString(entry.getValue(), entry.getKey())));
        }
        setSpinnerOptions(spGlobalPersonaProfile, options);
        updateGlobalPersonaProfileButtonState();
    }

    private void updatePersonaProfileButtonState() {
        boolean hasProfile = !personaProfileNames.isEmpty();
        if (spPersonaProfile != null) {
            spPersonaProfile.setEnabled(hasProfile);
        }
        if (btnPersonaCopy != null) {
            btnPersonaCopy.setEnabled(hasProfile);
        }
        if (btnPersonaRename != null) {
            btnPersonaRename.setEnabled(hasProfile);
        }
        if (btnPersonaDelete != null) {
            btnPersonaDelete.setEnabled(personaProfileNames.size() > 1);
        }
    }

    private void updateGlobalPersonaProfileButtonState() {
        boolean hasProfile = !globalPersonaProfileNames.isEmpty();
        if (spGlobalPersonaProfile != null) {
            spGlobalPersonaProfile.setEnabled(hasProfile);
        }
        if (btnGlobalPersonaCopy != null) {
            btnGlobalPersonaCopy.setEnabled(hasProfile);
        }
        if (btnGlobalPersonaRename != null) {
            btnGlobalPersonaRename.setEnabled(hasProfile);
        }
        if (btnGlobalPersonaDelete != null) {
            btnGlobalPersonaDelete.setEnabled(globalPersonaProfileNames.size() > 1);
        }
    }

    private LinkedHashMap<String, String> getCurrentPersonaDraftMap() {
        LinkedHashMap<String, String> tierDrafts = personaProfileDrafts.get(currentPersonaProfileId);
        if (tierDrafts != null) {
            return tierDrafts;
        }
        if (!personaProfileDrafts.isEmpty()) {
            currentPersonaProfileId = personaProfileDrafts.keySet().iterator().next();
            return personaProfileDrafts.get(currentPersonaProfileId);
        }
        currentPersonaProfileId = ProxyConfig.DEFAULT_PERSONA_PROFILE_ID;
        personaProfileNames.put(currentPersonaProfileId, ProxyConfig.DEFAULT_PERSONA_PROFILE_NAME);
        LinkedHashMap<String, String> created = buildBuiltinPersonaDraftMap();
        personaProfileDrafts.put(currentPersonaProfileId, created);
        updatePersonaProfileSpinner();
        return created;
    }

    private String getCurrentGlobalPersonaDraft() {
        String raw = globalPersonaProfileDrafts.get(currentGlobalPersonaProfileId);
        if (!TextUtils.isEmpty(raw)) {
            return raw;
        }
        if (!globalPersonaProfileDrafts.isEmpty()) {
            currentGlobalPersonaProfileId = globalPersonaProfileDrafts.keySet().iterator().next();
            return safeString(globalPersonaProfileDrafts.get(currentGlobalPersonaProfileId), "");
        }
        currentGlobalPersonaProfileId = ProxyConfig.DEFAULT_GLOBAL_PERSONA_PROFILE_ID;
        globalPersonaProfileNames.put(currentGlobalPersonaProfileId, ProxyConfig.DEFAULT_GLOBAL_PERSONA_PROFILE_NAME);
        String created = buildBuiltinGlobalPersonaDraft();
        globalPersonaProfileDrafts.put(currentGlobalPersonaProfileId, created);
        updateGlobalPersonaProfileSpinner();
        return created;
    }

    private String getCurrentPersonaDraft(String tier) {
        LinkedHashMap<String, String> tierDrafts = getCurrentPersonaDraftMap();
        String raw = tierDrafts.get(tier);
        if (!TextUtils.isEmpty(raw)) {
            return raw;
        }
        LinkedHashMap<String, String> builtinDrafts = buildBuiltinPersonaDraftMap();
        String fallback = safeString(builtinDrafts.get(tier), "");
        tierDrafts.put(tier, fallback);
        return fallback;
    }

    private void onPersonaProfileSelected(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (personaProfileSpinnerReady) {
            saveCurrentTierPersonaDraft();
        }
        currentPersonaProfileId = profileId;
        updatePersonaProfileButtonState();
        updatePersonaEditorModeUi(false);
    }

    private void onGlobalPersonaProfileSelected(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (globalPersonaProfileSpinnerReady) {
            saveCurrentGlobalPersonaDraft();
        }
        currentGlobalPersonaProfileId = profileId;
        updateGlobalPersonaProfileButtonState();
        updatePersonaEditorModeUi(false);
    }

    private void promptCreatePersonaProfile(boolean copyCurrent) {
        saveCurrentTierPersonaDraft();
        String currentName = safeString(personaProfileNames.get(currentPersonaProfileId), "当前方案");
        String suggestedName = copyCurrent
                ? buildUniquePersonaProfileName(currentName + " 副本")
                : buildUniquePersonaProfileName("新方案");
        showProfileNameDialog(
                copyCurrent ? "复制人设方案" : "新增人设方案",
                suggestedName,
                inputName -> {
                    String profileId = buildUniquePersonaProfileId();
                    LinkedHashMap<String, String> tierDrafts = copyCurrent
                            ? copyPersonaDraftMap(getCurrentPersonaDraftMap())
                            : buildBuiltinPersonaDraftMap();
                    personaProfileNames.put(profileId, inputName);
                    personaProfileDrafts.put(profileId, tierDrafts);
                    currentPersonaProfileId = profileId;
                    personaProfileSpinnerReady = false;
                    updatePersonaProfileSpinner();
                    selectSpinnerValue(spPersonaProfile, profileId);
                    personaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog((copyCurrent ? "已复制人设方案：" : "已新增人设方案：") + inputName + "，记得保存配置");
                    setActionStatus(copyCurrent ? "已复制人设方案" : "已新增人设方案");
                    markConfigDirty();
                }
        );
    }

    private void promptRenamePersonaProfile() {
        if (TextUtils.isEmpty(currentPersonaProfileId) || !personaProfileNames.containsKey(currentPersonaProfileId)) {
            appendLog("当前没有可重命名的人设方案");
            setActionStatus("重命名失败");
            return;
        }
        showProfileNameDialog(
                "重命名人设方案",
                safeString(personaProfileNames.get(currentPersonaProfileId), ""),
                inputName -> {
                    personaProfileNames.put(currentPersonaProfileId, inputName);
                    personaProfileSpinnerReady = false;
                    updatePersonaProfileSpinner();
                    selectSpinnerValue(spPersonaProfile, currentPersonaProfileId);
                    personaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog("已重命名当前人设方案为：" + inputName + "，记得保存配置");
                    setActionStatus("已重命名人设方案");
                    markConfigDirty();
                }
        );
    }

    private void promptDeletePersonaProfile() {
        if (personaProfileNames.size() <= 1) {
            appendLog("至少需要保留一个人设方案");
            setActionStatus("删除失败");
            return;
        }
        final String deletingId = currentPersonaProfileId;
        final String deletingName = safeString(personaProfileNames.get(deletingId), deletingId);
        new AlertDialog.Builder(this)
                .setTitle("删除人设方案")
                .setMessage("确定删除“" + deletingName + "”吗？该方案下的 5 档 JSON 都会一起删除，保存配置后生效。")
                .setPositiveButton("删除", (dialog, which) -> {
                    saveCurrentTierPersonaDraft();
                    personaProfileNames.remove(deletingId);
                    personaProfileDrafts.remove(deletingId);
                    currentPersonaProfileId = personaProfileNames.keySet().iterator().next();
                    personaProfileSpinnerReady = false;
                    updatePersonaProfileSpinner();
                    selectSpinnerValue(spPersonaProfile, currentPersonaProfileId);
                    personaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog("已删除人设方案：" + deletingName + "，记得保存配置");
                    setActionStatus("已删除人设方案");
                    markConfigDirty();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptCreateGlobalPersonaProfile(boolean copyCurrent) {
        saveCurrentGlobalPersonaDraft();
        String currentName = safeString(globalPersonaProfileNames.get(currentGlobalPersonaProfileId), "当前全局方案");
        String suggestedName = copyCurrent
                ? buildUniqueGlobalPersonaProfileName(currentName + " 副本")
                : buildUniqueGlobalPersonaProfileName("新全局方案");
        showProfileNameDialog(
                copyCurrent ? "复制全局人设方案" : "新增全局人设方案",
                suggestedName,
                inputName -> {
                    String profileId = buildUniqueGlobalPersonaProfileId();
                    String rawJson = copyCurrent ? getCurrentGlobalPersonaDraft() : buildBuiltinGlobalPersonaDraft();
                    globalPersonaProfileNames.put(profileId, inputName);
                    globalPersonaProfileDrafts.put(profileId, safeString(rawJson, ""));
                    currentGlobalPersonaProfileId = profileId;
                    globalPersonaProfileSpinnerReady = false;
                    updateGlobalPersonaProfileSpinner();
                    selectSpinnerValue(spGlobalPersonaProfile, profileId);
                    globalPersonaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog((copyCurrent ? "已复制全局人设方案：" : "已新增全局人设方案：") + inputName + "，记得保存配置");
                    setActionStatus(copyCurrent ? "已复制全局方案" : "已新增全局方案");
                    markConfigDirty();
                }
        );
    }

    private void promptRenameGlobalPersonaProfile() {
        if (TextUtils.isEmpty(currentGlobalPersonaProfileId) || !globalPersonaProfileNames.containsKey(currentGlobalPersonaProfileId)) {
            appendLog("当前没有可重命名的全局人设方案");
            setActionStatus("重命名失败");
            return;
        }
        showProfileNameDialog(
                "重命名全局人设方案",
                safeString(globalPersonaProfileNames.get(currentGlobalPersonaProfileId), ""),
                inputName -> {
                    globalPersonaProfileNames.put(currentGlobalPersonaProfileId, inputName);
                    globalPersonaProfileSpinnerReady = false;
                    updateGlobalPersonaProfileSpinner();
                    selectSpinnerValue(spGlobalPersonaProfile, currentGlobalPersonaProfileId);
                    globalPersonaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog("已重命名当前全局人设方案为：" + inputName + "，记得保存配置");
                    setActionStatus("已重命名全局方案");
                    markConfigDirty();
                }
        );
    }

    private void promptDeleteGlobalPersonaProfile() {
        if (globalPersonaProfileNames.size() <= 1) {
            appendLog("至少需要保留一个全局人设方案");
            setActionStatus("删除失败");
            return;
        }
        final String deletingId = currentGlobalPersonaProfileId;
        final String deletingName = safeString(globalPersonaProfileNames.get(deletingId), deletingId);
        new AlertDialog.Builder(this)
                .setTitle("删除全局人设方案")
                .setMessage("确定删除“" + deletingName + "”吗？保存配置后生效。")
                .setPositiveButton("删除", (dialog, which) -> {
                    saveCurrentGlobalPersonaDraft();
                    globalPersonaProfileNames.remove(deletingId);
                    globalPersonaProfileDrafts.remove(deletingId);
                    currentGlobalPersonaProfileId = globalPersonaProfileNames.keySet().iterator().next();
                    globalPersonaProfileSpinnerReady = false;
                    updateGlobalPersonaProfileSpinner();
                    selectSpinnerValue(spGlobalPersonaProfile, currentGlobalPersonaProfileId);
                    globalPersonaProfileSpinnerReady = true;
                    updatePersonaEditorModeUi(false);
                    appendLog("已删除全局人设方案：" + deletingName + "，记得保存配置");
                    setActionStatus("已删除全局方案");
                    markConfigDirty();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showProfileNameDialog(String title, String presetValue, ProfileNameHandler handler) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        setCompactHint(input, "请输入方案名称");
        input.setText(safeString(presetValue, ""));
        input.setSelection(input.getText().length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String inputName = safeString(input.getText() == null ? "" : input.getText().toString(), "");
            if (TextUtils.isEmpty(inputName)) {
                input.setError("请输入方案名称");
                return;
            }
            if (handler != null) {
                handler.accept(inputName);
            }
            dialog.dismiss();
        }));
        dialog.show();
    }

    private LinkedHashMap<String, String> buildBuiltinPersonaDraftMap() {
        ProxyConfig cfg = ProxyConfig.load(this);
        LinkedHashMap<String, String> tierDrafts = new LinkedHashMap<>();
        for (String tier : ProxyConfig.PERSONA_TIERS) {
            String raw = cfg.getBuiltinPersonaJson(tier);
            if (TextUtils.isEmpty(raw)) {
                raw = cfg.getPersonaJson(tier);
            }
            tierDrafts.put(tier, safeString(raw, ""));
        }
        return tierDrafts;
    }

    private String buildBuiltinGlobalPersonaDraft() {
        ProxyConfig cfg = ProxyConfig.load(this);
        String raw = cfg.getBuiltinPersonaJson(ProxyConfig.TIER_SISTER_HIGH);
        if (TextUtils.isEmpty(raw)) {
            raw = cfg.getGlobalPersonaJson();
        }
        return safeString(raw, "");
    }

    private LinkedHashMap<String, String> copyPersonaDraftMap(Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (String tier : ProxyConfig.PERSONA_TIERS) {
            String raw = source == null ? "" : source.get(tier);
            copy.put(tier, safeString(raw, ""));
        }
        return copy;
    }

    private String buildUniquePersonaProfileName(String baseName) {
        String base = safeString(baseName, "新方案");
        String candidate = base;
        int suffix = 2;
        while (personaProfileNames.containsValue(candidate)) {
            candidate = base + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    private String buildUniqueGlobalPersonaProfileName(String baseName) {
        String base = safeString(baseName, "新全局方案");
        String candidate = base;
        int suffix = 2;
        while (globalPersonaProfileNames.containsValue(candidate)) {
            candidate = base + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    private String buildUniquePersonaProfileId() {
        String base = "persona_" + System.currentTimeMillis();
        String candidate = base;
        int suffix = 2;
        while (personaProfileNames.containsKey(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String buildUniqueGlobalPersonaProfileId() {
        String base = "global_persona_" + System.currentTimeMillis();
        String candidate = base;
        int suffix = 2;
        while (globalPersonaProfileNames.containsKey(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean isGlobalPersonaModeEnabled() {
        return swPersonaIgnoreAffinity != null && swPersonaIgnoreAffinity.isChecked();
    }

    private void updatePersonaEditorModeUi(boolean fromUserToggle) {
        boolean globalMode = isGlobalPersonaModeEnabled();
        if (layoutTierPersonaEditor != null) {
            layoutTierPersonaEditor.setVisibility(globalMode ? View.GONE : View.VISIBLE);
        }
        if (layoutGlobalPersonaEditor != null) {
            layoutGlobalPersonaEditor.setVisibility(globalMode ? View.VISIBLE : View.GONE);
        }
        if (tvPersonaTierLabel != null) {
            tvPersonaTierLabel.setVisibility(globalMode ? View.GONE : View.VISIBLE);
        }
        if (spPersonaTier != null) {
            spPersonaTier.setVisibility(globalMode ? View.GONE : View.VISIBLE);
        }
        if (tvPersonaJsonLabel != null) {
            tvPersonaJsonLabel.setText(globalMode ? "全局人设 JSON 编辑器" : "人设 JSON 编辑器");
        }
        if (etPersonaJson != null) {
            etPersonaJson.setText(globalMode ? getCurrentGlobalPersonaDraft() : getCurrentPersonaDraft(currentPersonaTier));
        }
        if (fromUserToggle) {
            appendLog(globalMode ? "已切换为全局人设模式，当前会忽略好感度档位" : "已切换为五档人设模式");
            setActionStatus(globalMode ? "已启用全局人设模式" : "已恢复五档人设模式");
        }
        updatePersonaProfileButtonState();
        updateGlobalPersonaProfileButtonState();
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        if (spinner == null || spinner.getAdapter() == null) {
            return;
        }
        for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
            Object item = spinner.getAdapter().getItem(i);
            if (TextUtils.equals(spinnerValueOf(item), value)) {
                spinner.setSelection(i, false);
                return;
            }
        }
    }

    private String getSpinnerValue(Spinner spinner, String fallback) {
        if (spinner == null) {
            return fallback;
        }
        return safeString(spinnerValueOf(spinner.getSelectedItem()), fallback);
    }

    private String spinnerValueOf(Object item) {
        if (item instanceof SpinnerOption) {
            return ((SpinnerOption) item).value;
        }
        return item == null ? "" : String.valueOf(item);
    }

    private void startServer() {
        if (!ensureNotificationPermissionForStart()) {
            return;
        }
        if (!ensureCustomRequestFieldsValid("启动代理")) {
            return;
        }
        pendingStartAfterNotificationPermission = false;
        ProxyConfig cfg = readConfigFromUi();
        if (!ensureSelectedModelAvailable(cfg, "启动代理")) {
            return;
        }
        ProxyConfig.save(this, cfg);
        if (!ensureUpstreamActionAllowed(cfg, "启动代理")) {
            return;
        }
        requestServiceAction(ProxyForegroundService.ACTION_START);
        appendLog("已请求启动代理服务");
        setActionStatus("正在启动代理");
        updateEndpoint(cfg);
    }

    private boolean ensureNotificationPermissionForStart() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        pendingStartAfterNotificationPermission = true;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_CODE_POST_NOTIFICATIONS
        );
        appendLog("已请求通知权限，用于前台服务常驻通知");
        setActionStatus("请先允许通知权限");
        return false;
    }

    private void stopServer() {
        requestServiceAction(ProxyForegroundService.ACTION_STOP);
        appendLog("已请求停止代理服务");
        setActionStatus("正在停止代理");
    }

    private void testUpstream() {
        if (!ensureCustomRequestFieldsValid("测试上游")) {
            return;
        }
        saveCurrentProviderEditorDraft();
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        ArrayList<ProxyConfig.ModelProfile> savedModels = getSavedModels(provider);
        if (savedModels.isEmpty()) {
            setActionStatus("测试上游失败");
            appendLog("测试上游失败：当前提供商还没有已加入模型，请先获取模型并加入。");
            new AlertDialog.Builder(this)
                    .setTitle("没有可测试的已加入模型")
                    .setMessage("请先在当前提供商里获取模型并加入，然后再检查 API。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        showSavedModelTestPicker(provider, savedModels);
    }

    private void testActiveModelOnHome() {
        if (!ensureCustomRequestFieldsValid("测试上游")) {
            return;
        }
        saveCurrentProviderEditorDraft();
        ProxyConfig.ProviderProfile activeProvider = getActiveProviderDraft();
        String activeModelName = activeProvider == null ? "" : safeString(activeProvider.getActiveModelName(), "");
        if (activeProvider == null || TextUtils.isEmpty(activeModelName)) {
            setActionStatus("当前未选择模型");
            appendLog("测试上游失败：主页未选择模型");
            return;
        }
        // v1.5.0：Issue A 修复——纯内存 actionCfg，不写 SP / 不重启服务
        ProxyConfig actionCfg = buildConfigForProviderAction(readConfigFromUi(), activeProvider, activeModelName);
        if (!ensureUpstreamActionAllowed(actionCfg, "测试上游")) {
            return;
        }
        setActionStatus("正在测试模型：" + actionCfg.model);
        appendLog("开始测试上游模型：" + actionCfg.model);
        showUpstreamFeatureTestSheet(actionCfg);
    }

    /** v1.5.1+：单独测试主页当前选中的联网搜索引擎，不调主模型 / 视觉模型。 */
    private void testWebSearchOnHome() {
        saveCurrentProviderEditorDraft();
        ProxyConfig.ProviderProfile activeProvider = getActiveProviderDraft();
        if (activeProvider == null) {
            setActionStatus("当前没有 active provider");
            appendLog("测试联网搜索失败：未选择 provider");
            return;
        }
        ProxyConfig cfg = buildConfigForProviderAction(readConfigFromUi(), activeProvider,
                safeString(activeProvider.getActiveModelName(), ""));
        String engine = ProxyConfig.normalizeWebSearchProvider(cfg.webSearchProvider);
        setActionStatus("正在测试联网搜索：" + engine);
        appendLog("开始测试联网搜索：engine=" + engine);
        showWebSearchTestSheet(cfg, "今天北京天气");
    }

    /** v1.5.1+：联网搜索单项测试 BottomSheet。 */
    private void showWebSearchTestSheet(ProxyConfig cfg, String query) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(20));
        container.setBackgroundColor(palette.surface);

        TextView title = new TextView(this);
        title.setText("联网搜索测试");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(palette.textPrimary);
        container.addView(title, defaultDialogLayoutParams());

        TextView engineTv = new TextView(this);
        engineTv.setText("引擎：" + ProxyConfig.normalizeWebSearchProvider(cfg.webSearchProvider)
                + "    查询：" + query);
        engineTv.setTextSize(13f);
        engineTv.setTextColor(palette.textSecondary);
        LinearLayout.LayoutParams engineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        engineParams.bottomMargin = dp(10);
        container.addView(engineTv, engineParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(8));
        cardBg.setColor(Color.argb(28, 128, 128, 128));
        card.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(14);
        container.addView(card, cardParams);

        LinearLayout searchRow = buildFeatureTestRow("联网搜索:", palette);
        card.addView(searchRow);

        TextView resultPreview = new TextView(this);
        resultPreview.setTextSize(12f);
        resultPreview.setTextColor(palette.textSecondary);
        resultPreview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.bottomMargin = dp(10);
        container.addView(resultPreview, previewParams);

        Button okButton = new Button(this);
        okButton.setText("确认");
        okButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.primary));
        okButton.setTextColor(Color.WHITE);
        okButton.setOnClickListener(v -> dialog.dismiss());
        container.addView(okButton, defaultDialogLayoutParams());

        dialog.setContentView(container);
        dialog.show();

        bgExecutor.execute(() -> {
            DiaryProxyServer.UpstreamFeatureTestResult result;
            String preview = "";
            try {
                long start = System.currentTimeMillis();
                java.util.List<WebSearchSupport.SearchResult> hits = WebSearchSupport.performSearch(query, cfg);
                long elapsed = System.currentTimeMillis() - start;
                if (hits == null || hits.isEmpty()) {
                    result = DiaryProxyServer.UpstreamFeatureTestResult.fail(0,
                            "返回 0 条结果（耗时 " + elapsed + "ms）");
                } else {
                    result = DiaryProxyServer.UpstreamFeatureTestResult.success(200,
                            "返回 " + hits.size() + " 条（" + elapsed + "ms）");
                    StringBuilder builder = new StringBuilder();
                    int show = Math.min(hits.size(), 3);
                    for (int i = 0; i < show; i++) {
                        WebSearchSupport.SearchResult sr = hits.get(i);
                        if (sr == null) continue;
                        if (builder.length() > 0) builder.append("\n");
                        builder.append(i + 1).append(". ").append(safeString(sr.title, "(无标题)"));
                        if (!TextUtils.isEmpty(sr.url)) {
                            builder.append("\n   ").append(sr.url);
                        }
                    }
                    preview = builder.toString();
                }
            } catch (Exception error) {
                result = DiaryProxyServer.UpstreamFeatureTestResult.fail(0,
                        firstNonEmptyText(error.getMessage(), error.getClass().getSimpleName()));
            }
            DiaryProxyServer.UpstreamFeatureTestResult finalResult = result;
            String finalPreview = preview;
            runOnUiThread(() -> {
                applyFeatureTestRowResult(searchRow, finalResult);
                if (!TextUtils.isEmpty(finalPreview)) {
                    resultPreview.setText(finalPreview);
                    resultPreview.setVisibility(View.VISIBLE);
                }
                appendLog("联网搜索测试：" + describeFeatureTestResult(finalResult));
                if (finalResult.isSuccess()) {
                    setActionStatus("联网搜索测试成功");
                } else {
                    setActionStatus("联网搜索测试失败");
                }
            });
        });
    }

    private void refreshHomeTestButtonState() {
        if (btnTest == null) {
            return;
        }
        ProxyConfig.ProviderProfile activeProvider = getActiveProviderDraft();
        boolean hasActiveModel = activeProvider != null
                && !TextUtils.isEmpty(safeString(activeProvider.getActiveModelName(), ""));
        btnTest.setEnabled(hasActiveModel);
        btnTest.setAlpha(hasActiveModel ? 1f : 0.45f);
    }

    private ArrayList<ProxyConfig.ModelProfile> getSavedModels(ProxyConfig.ProviderProfile provider) {
        ArrayList<ProxyConfig.ModelProfile> savedModels = new ArrayList<>();
        if (provider == null || provider.models == null) {
            return savedModels;
        }
        for (ProxyConfig.ModelProfile model : provider.models) {
            if (model != null && !TextUtils.isEmpty(safeString(model.name, ""))) {
                savedModels.add(model);
            }
        }
        return savedModels;
    }

    private void showSavedModelTestPicker(ProxyConfig.ProviderProfile provider, List<ProxyConfig.ModelProfile> savedModels) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(18));
        container.setBackgroundColor(palette.surface);

        TextView title = new TextView(this);
        title.setText("选择测试模型");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(palette.textPrimary);
        container.addView(title, defaultDialogLayoutParams());

        String providerName = provider == null ? "当前提供商" : safeString(provider.name, "当前提供商");
        TextView hint = new TextView(this);
        hint.setText("检查 " + providerName + " 中的哪个模型？");
        hint.setTextColor(palette.textSecondary);
        hint.setTextSize(12f);
        container.addView(hint, defaultDialogLayoutParams());

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420)
        );
        scrollParams.topMargin = dp(8);
        container.addView(scrollView, scrollParams);

        for (ProxyConfig.ModelProfile m : savedModels) {
            if (m == null || TextUtils.isEmpty(m.name)) {
                continue;
            }
            list.addView(buildSavedModelTestRow(m.name, dialog));
        }

        dialog.setContentView(container);
        dialog.show();
    }

    private View buildSavedModelTestRow(String modelName, BottomSheetDialog dialog) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        TextView name = new TextView(this);
        name.setText(modelName);
        name.setTextColor(palette.textPrimary);
        name.setTextSize(15f);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22f);
        arrow.setTextColor(palette.textSecondary);
        arrow.setPadding(dp(8), 0, dp(4), 0);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> {
            dialog.dismiss();
            runUpstreamTestWithModel(modelName);
        });
        return row;
    }

    private void runUpstreamTestWithModel(String modelName) {
        ProxyConfig savedCfg = readConfigFromUi();
        ProxyConfig.save(this, savedCfg);
        ProxyConfig actionCfg = buildConfigForProviderAction(savedCfg, getCurrentProviderDraft(), modelName);
        if (!ensureUpstreamActionAllowed(actionCfg, "测试上游")) {
            return;
        }
        setActionStatus("正在测试模型：" + actionCfg.model);
        appendLog("开始测试上游模型：" + actionCfg.model);
        showUpstreamFeatureTestSheet(actionCfg, false);
    }

    private void showUpstreamFeatureTestSheet(ProxyConfig cfg) {
        showUpstreamFeatureTestSheet(cfg, true);
    }

    private void showUpstreamFeatureTestSheet(ProxyConfig cfg, boolean includeCaption) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(20));
        container.setBackgroundColor(palette.surface);

        TextView title = new TextView(this);
        title.setText("模型测试结果");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(palette.textPrimary);
        container.addView(title, defaultDialogLayoutParams());

        // 主模型分区
        TextView mainHeader = new TextView(this);
        mainHeader.setText("主模型");
        mainHeader.setTextSize(13f);
        mainHeader.setTextColor(palette.textSecondary);
        LinearLayout.LayoutParams mainHeaderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mainHeaderParams.topMargin = dp(8);
        container.addView(mainHeader, mainHeaderParams);

        TextView modelTv = new TextView(this);
        modelTv.setText(safeString(cfg.model, "未填写模型"));
        modelTv.setTextSize(20f);
        modelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        modelTv.setTextColor(palette.textPrimary);
        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        modelParams.topMargin = dp(2);
        modelParams.bottomMargin = dp(6);
        container.addView(modelTv, modelParams);

        TextView overall = new TextView(this);
        overall.setText("测试中...");
        overall.setTextSize(14f);
        overall.setTextColor(palette.textSecondary);
        LinearLayout.LayoutParams overallParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        overallParams.bottomMargin = dp(10);
        container.addView(overall, overallParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(8));
        cardBg.setColor(Color.argb(28, 128, 128, 128));
        card.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(14);
        container.addView(card, cardParams);

        LinearLayout textRow = buildFeatureTestRow("文本请求:", palette);
        LinearLayout imageRow = buildFeatureTestRow("图片请求:", palette);
        LinearLayout toolRow = buildFeatureTestRow("工具调用请求:", palette);
        card.addView(textRow);
        card.addView(imageRow);
        card.addView(toolRow);

        // v1.5.1+：副模型分区（仅当配置了视觉模型时显示）
        ProxyConfig captionCfg = includeCaption ? buildCaptionTestCfg(cfg) : null;
        boolean hasCaption = captionCfg != null;
        LinearLayout captionRow1 = null;
        LinearLayout captionRow2 = null;
        if (hasCaption) {
            TextView captionHeader = new TextView(this);
            captionHeader.setText("视觉模型");
            captionHeader.setTextSize(13f);
            captionHeader.setTextColor(palette.textSecondary);
            container.addView(captionHeader, mainHeaderParams);

            TextView captionModelTv = new TextView(this);
            captionModelTv.setText(safeString(captionCfg.model, "未填写模型"));
            captionModelTv.setTextSize(20f);
            captionModelTv.setTypeface(null, android.graphics.Typeface.BOLD);
            captionModelTv.setTextColor(palette.textPrimary);
            container.addView(captionModelTv, modelParams);

            LinearLayout captionCard = new LinearLayout(this);
            captionCard.setOrientation(LinearLayout.VERTICAL);
            captionCard.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable captionBg = new GradientDrawable();
            captionBg.setCornerRadius(dp(8));
            captionBg.setColor(Color.argb(28, 128, 128, 128));
            captionCard.setBackground(captionBg);
            container.addView(captionCard, cardParams);

            captionRow1 = buildFeatureTestRow("文本请求:", palette);
            captionRow2 = buildFeatureTestRow("图片请求:", palette);
            captionCard.addView(captionRow1);
            captionCard.addView(captionRow2);
        }

        Button okButton = new Button(this);
        okButton.setText("确认");
        okButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.primary));
        okButton.setTextColor(Color.WHITE);
        okButton.setOnClickListener(v -> dialog.dismiss());
        container.addView(okButton, defaultDialogLayoutParams());

        dialog.setContentView(container);
        dialog.show();

        final int totalTests = hasCaption ? 5 : 3;
        final boolean[] anySuccess = {false};
        final int[] doneCount = {0};
        Runnable updateOverall = () -> {
            doneCount[0]++;
            if (doneCount[0] >= totalTests) {
                if (anySuccess[0]) {
                    overall.setText("连接成功！");
                    overall.setTextColor(Color.rgb(16, 185, 129));
                    setActionStatus("上游测试完成");
                } else {
                    overall.setText("连接失败");
                    overall.setTextColor(Color.rgb(239, 68, 68));
                    setActionStatus("上游测试失败");
                }
            }
        };

        runFeatureTest(textRow, "文本", cfg,
                () -> DiaryProxyServer.performUpstreamTextTest(cfg), result -> {
                    if (result.isSuccess()) {
                        anySuccess[0] = true;
                    }
                    updateOverall.run();
                });
        runFeatureTest(imageRow, "图片", cfg,
                () -> DiaryProxyServer.performUpstreamImageTest(cfg), result -> {
                    if (result.isSuccess()) {
                        anySuccess[0] = true;
                    }
                    updateOverall.run();
                });
        runFeatureTest(toolRow, "工具调用", cfg,
                () -> DiaryProxyServer.performUpstreamToolTest(cfg), result -> {
                    if (result.isSuccess()) {
                        anySuccess[0] = true;
                    }
                    updateOverall.run();
                });
        if (hasCaption) {
            runFeatureTest(captionRow1, "副模型文本", captionCfg,
                    () -> DiaryProxyServer.performUpstreamTextTest(captionCfg), result -> {
                        if (result.isSuccess()) {
                            anySuccess[0] = true;
                        }
                        updateOverall.run();
                    });
            runFeatureTest(captionRow2, "副模型图片", captionCfg,
                    () -> DiaryProxyServer.performUpstreamImageTest(captionCfg), result -> {
                        if (result.isSuccess()) {
                            anySuccess[0] = true;
                        }
                        updateOverall.run();
                    });
        }
    }

    /**
     * v1.5.1+：从主 cfg 派生出副模型测试用的 cfg。
     * 解析 captionProviderId 形式 "providerId\u001FmodelName"，找到副模型 provider 并改写 cfg。
     * 找不到副模型返回 null（不显示副模型分区）。
     */
    private ProxyConfig buildCaptionTestCfg(ProxyConfig cfg) {
        if (cfg == null || TextUtils.isEmpty(cfg.captionProviderId)) {
            return null;
        }
        String raw = cfg.captionProviderId;
        String providerId = raw;
        String modelName = "";
        int sep = raw.indexOf(HOME_MODEL_SEPARATOR);
        if (sep >= 0) {
            providerId = raw.substring(0, sep);
            modelName = raw.substring(sep + HOME_MODEL_SEPARATOR.length());
        }
        ProxyConfig.ProviderProfile captionProvider = providerProfileDrafts == null ? null : providerProfileDrafts.get(providerId);
        if (captionProvider == null) {
            return null;
        }
        if (TextUtils.isEmpty(modelName)) {
            modelName = safeString(captionProvider.getActiveModelName(), "");
        }
        if (TextUtils.isEmpty(modelName)) {
            return null;
        }
        return buildConfigForProviderAction(readConfigFromUi(), captionProvider, modelName);
    }

    private LinearLayout buildFeatureTestRow(String label, UiThemePalette palette) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextColor(palette.textPrimary);
        labelTv.setTextSize(15f);
        row.addView(labelTv, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout indicator = new FrameLayout(this);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        indicator.setLayoutParams(indicatorParams);
        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(20), dp(20));
        spinnerParams.gravity = android.view.Gravity.CENTER;
        spinner.setLayoutParams(spinnerParams);
        indicator.addView(spinner);
        indicator.setTag("indicator");
        row.addView(indicator);

        TextView reasonTv = new TextView(this);
        reasonTv.setText("测试中...");
        reasonTv.setTextColor(palette.textSecondary);
        reasonTv.setTextSize(14f);
        reasonTv.setPadding(dp(8), 0, 0, 0);
        reasonTv.setTag("reason");
        row.addView(reasonTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void runFeatureTest(LinearLayout row, String label, ProxyConfig cfg,
                                java.util.function.Supplier<DiaryProxyServer.UpstreamFeatureTestResult> task,
                                java.util.function.Consumer<DiaryProxyServer.UpstreamFeatureTestResult> onDone) {
        bgExecutor.execute(() -> {
            DiaryProxyServer.UpstreamFeatureTestResult result;
            try {
                result = task.get();
            } catch (Exception error) {
                result = DiaryProxyServer.UpstreamFeatureTestResult.fail(0,
                        firstNonEmptyText(error.getMessage(), error.getClass().getSimpleName()));
            }
            DiaryProxyServer.UpstreamFeatureTestResult finalResult = result;
            runOnUiThread(() -> {
                applyFeatureTestRowResult(row, finalResult);
                appendLog("测试" + label + "（" + cfg.model + "）："
                        + describeFeatureTestResult(finalResult));
                onDone.accept(finalResult);
            });
        });
    }

    private void applyFeatureTestRowResult(LinearLayout row, DiaryProxyServer.UpstreamFeatureTestResult result) {
        FrameLayout indicator = (FrameLayout) row.findViewWithTag("indicator");
        TextView reasonTv = (TextView) row.findViewWithTag("reason");
        if (indicator == null || reasonTv == null) {
            return;
        }
        indicator.removeAllViews();
        TextView icon = new TextView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        iconParams.gravity = android.view.Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setTextSize(20f);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        if (result.isSuccess()) {
            icon.setText("✓");
            icon.setTextColor(Color.rgb(16, 185, 129));
            reasonTv.setText("");
        } else {
            icon.setText("✗");
            icon.setTextColor(Color.rgb(239, 68, 68));
            reasonTv.setText(result.detail == null ? "失败" : result.detail);
        }
        indicator.addView(icon);
    }

    private String describeFeatureTestResult(DiaryProxyServer.UpstreamFeatureTestResult result) {
        if (result == null) {
            return "无返回";
        }
        if (result.isSuccess()) {
            return "成功 status=" + result.statusCode;
        }
        if (result.state == DiaryProxyServer.UpstreamFeatureTestResult.STATE_UNSUPPORTED) {
            return "不支持 - " + result.detail;
        }
        return "失败 status=" + result.statusCode + " " + result.detail;
    }

    private String firstNonEmptyText(String a, String b) {
        if (!TextUtils.isEmpty(a)) {
            return a;
        }
        if (!TextUtils.isEmpty(b)) {
            return b;
        }
        return "";
    }

    private ViewGroup.LayoutParams defaultDialogLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        return params;
    }

    private void fetchModelsFromUpstream() {
        if (!ensureCustomRequestFieldsValid("获取模型列表")) {
            return;
        }
        String fetchingProviderId = currentProviderId;
        // v1.5.0：Issue A 修复——纯内存 actionCfg，不写 SP / 不重启服务
        ProxyConfig actionCfg = buildConfigForProviderAction(readConfigFromUi(), providerProfileDrafts.get(fetchingProviderId), "");
        if (!ensureUpstreamActionAllowed(actionCfg, "获取模型列表")) {
            return;
        }
        setActionStatus("正在获取模型列表");
        appendLog("开始请求上游模型列表");
        bgExecutor.execute(() -> {
            DiaryProxyServer.ModelFetchResult result = DiaryProxyServer.fetchModels(actionCfg);
            runOnUiThread(() -> showModelFetchResult(fetchingProviderId, result));
        });
    }

    private void showModelFetchResult(String providerId, DiaryProxyServer.ModelFetchResult result) {
        if (result == null) {
            setActionStatus("获取模型列表失败");
            appendLog("获取模型列表失败：返回为空");
            FeedbackSupport.recordOperationalError(this, "fetch_models", "获取模型列表失败", "返回为空");
            return;
        }
        if (!result.success) {
            setActionStatus("获取模型列表失败");
            appendLog("获取模型列表失败：" + result.message);
            FeedbackSupport.recordOperationalError(this, "fetch_models", "获取模型列表失败", result.message);
            new AlertDialog.Builder(this)
                    .setTitle("获取模型列表失败")
                    .setMessage(result.message)
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        if (result.models.isEmpty()) {
            setActionStatus("未解析到模型");
            appendLog("上游请求成功，但未解析到模型列表");
            new AlertDialog.Builder(this)
                    .setTitle("模型列表为空")
                    .setMessage("请求成功，但没有从返回体中解析到模型名称。\n\n" + result.message)
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        setActionStatus("模型列表获取成功");
        appendLog("获取到 " + result.models.size() + " 个模型");
        saveCurrentProviderEditorDraft();
        ProxyConfig.ProviderProfile provider = providerProfileDrafts.get(providerId);
        if (provider != null) {
            providerFetchedModelDrafts.put(provider.id, new ArrayList<>(result.models));
            if (TextUtils.equals(provider.id, currentProviderId)) {
                updateProviderModelRows();
                updateProviderButtonState();
            }
        }
        setActionStatus("点击 + 把模型加入当前提供商");
        if (TextUtils.equals(providerId, currentProviderId)) {
            showFetchedModelPicker(result.models);
        }
    }

    private void showFetchedModelPicker(List<String> fetchedModels) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(18));
        container.setBackgroundColor(palette.surface);

        TextView title = new TextView(this);
        title.setText("模型");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(palette.textPrimary);
        container.addView(title, defaultDialogLayoutParams());

        TextView hint = new TextView(this);
        hint.setTextColor(palette.textSecondary);
        hint.setTextSize(12f);
        container.addView(hint, defaultDialogLayoutParams());

        // v1.6.0+：搜索框
        EditText search = new EditText(this);
        search.setHint("搜索模型");
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(palette.textPrimary);
        search.setHintTextColor(palette.textSecondary);
        search.setBackground(buildRuntimeTextBoxBackground(palette));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.bottomMargin = dp(8);
        container.addView(search, searchParams);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420)
        );
        scrollParams.topMargin = dp(8);
        container.addView(scrollView, scrollParams);

        Runnable rebuild = () -> {
            list.removeAllViews();
            String query = search.getText() == null
                    ? "" : search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int shown = 0;
            for (String modelName : fetchedModels) {
                if (TextUtils.isEmpty(modelName)) {
                    continue;
                }
                if (!query.isEmpty()
                        && !modelName.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                    continue;
                }
                list.addView(buildFetchedModelRow(modelName));
                shown++;
            }
            if (shown == 0) {
                TextView empty = new TextView(this);
                empty.setText(query.isEmpty() ? "（暂无可加入模型）" : "（未匹配到模型）");
                empty.setTextColor(palette.textSecondary);
                empty.setGravity(android.view.Gravity.CENTER);
                empty.setPadding(dp(8), dp(24), dp(8), dp(24));
                list.addView(empty);
            }
        };
        rebuild.run();
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        dialog.setContentView(container);
        dialog.show();
    }

    private View buildFetchedModelRow(String modelName) {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));

        TextView name = new TextView(this);
        name.setText(modelName);
        name.setTextColor(palette.textPrimary);
        name.setTextSize(15f);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggle = new TextView(this);
        toggle.setGravity(android.view.Gravity.CENTER);
        toggle.setTextSize(20f);
        toggle.setTypeface(null, android.graphics.Typeface.BOLD);
        toggle.setIncludeFontPadding(false);
        final boolean[] saved = { isCurrentProviderModelSaved(modelName) };
        applyFetchedToggleStyle(toggle, saved[0], palette);
        toggle.setOnClickListener(v -> {
            if (saved[0]) {
                deleteProviderModelByName(modelName);
                saved[0] = false;
            } else {
                addFetchedModelToCurrentProvider(modelName);
                saved[0] = true;
            }
            applyFetchedToggleStyle(toggle, saved[0], palette);
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        toggleParams.leftMargin = dp(8);
        row.addView(toggle, toggleParams);
        return row;
    }

    private void applyFetchedToggleStyle(TextView tv, boolean saved, UiThemePalette palette) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (saved) {
            bg.setColor(Color.rgb(239, 68, 68));
            tv.setText("−");
        } else {
            bg.setColor(palette.secondary);
            tv.setText("+");
        }
        tv.setTextColor(Color.WHITE);
        tv.setBackground(bg);
    }

    private boolean isCurrentProviderModelSaved(String modelName) {
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        return provider != null && provider.findModelByName(modelName) != null;
    }

    private void addFetchedModelToCurrentProvider(String modelName) {
        ProxyConfig.ProviderProfile provider = getCurrentProviderDraft();
        if (provider == null || TextUtils.isEmpty(modelName)) {
            return;
        }
        ProxyConfig.ModelProfile existing = provider.findModelByName(modelName);
        if (existing == null) {
            existing = new ProxyConfig.ModelProfile(buildUniqueModelId(provider), modelName);
            provider.models.add(existing);
        }
        ArrayList<String> fetchedModels = providerFetchedModelDrafts.get(provider.id);
        if (fetchedModels != null) {
            fetchedModels.remove(modelName);
        }
        refreshProviderUiAfterDraftChange(provider.activeModelId);
        saveConfigFromUi();
        appendLog("已加入模型：" + modelName);
        setActionStatus("模型已加入并保存");
    }

    private void replayLastRequest() {
        if (!ensureCustomRequestFieldsValid("重发日记")) {
            return;
        }
        ProxyConfig cfg = readConfigFromUi();
        if (!ensureSelectedModelAvailable(cfg, "重发上一次日记请求")) {
            return;
        }
        // v1.5.0：Issue A 修复——纯内存 cfg，不写 SP / 不重启服务
        if (!ensureUpstreamActionAllowed(cfg, "重发上一次日记请求")) {
            return;
        }
        setActionStatus("正在重发上一次日记请求");
        appendLog("开始重发上一次日记请求");
        bgExecutor.execute(() -> {
            DiaryProxyServer.ReplayResult result = DiaryProxyServer.replayLastDiaryRequest(this, cfg, this::appendLog);
            runOnUiThread(() -> {
                if (result.success) {
                    appendLog(result.message);
                    setActionStatus("重发完成");
                } else {
                    appendLog("重发失败：" + result.message);
                    setActionStatus("重发失败");
                    FeedbackSupport.recordOperationalError(this, "replay_diary", "重发日记失败", result.message);
                }
            });
        });
    }

    private void confirmReplayLastRequest() {
        new AlertDialog.Builder(this)
                .setTitle("确认重发日记")
                .setMessage("这会使用当前配置重发最近一次缓存的日记请求。\n\n注意：代理无法在没有游戏新请求的情况下主动把结果推回游戏。")
                .setPositiveButton("继续重发", (dialog, which) -> replayLastRequest())
                .setNegativeButton("取消", null)
                .show();
    }

    private void maybePromptCrashReport() {
        if (crashPromptShown || !FeedbackSupport.hasPendingCrashReport(this)) {
            return;
        }
        crashPromptShown = true;
        String summary = FeedbackSupport.getCrashSummary(this);
        String message = TextUtils.isEmpty(summary)
                ? "检测到应用上次运行时发生了异常退出。你可以现在发送一份错误报告，也可以稍后手动发送。"
                : "检测到应用上次运行时发生了异常退出。\n\n摘要：" + summary + "\n\n你可以现在发送一份错误报告，也可以稍后手动发送。";
        new AlertDialog.Builder(this)
                .setTitle("发现上次异常退出")
                .setMessage(message)
                .setPositiveButton("发送报告", (dialog, which) -> openErrorReportDialog(true))
                .setNegativeButton("稍后再说", null)
                .setNeutralButton("忽略这次", (dialog, which) -> FeedbackSupport.clearCrashReport(this))
                .show();
    }

    private void maybeAutoCheckUpdates() {
        if (autoUpdateCheckStarted) {
            return;
        }
        autoUpdateCheckStarted = true;
        if (!ensureCustomRequestFieldsValid("自动检查更新", false)) {
            return;
        }
        ProxyConfig cfg = readConfigFromUi();
        // v1.5.4+：异步 save，避免主线程加密 SP 写入阻塞按钮响应（MA-3）。
        autoSaveExecutor.execute(() -> ProxyConfig.save(this, cfg));
        if (!UpdateManager.shouldAutoCheck(cfg)) {
            return;
        }
        bgExecutor.execute(() -> {
            UpdateManager.CheckResult result = UpdateManager.checkForUpdates(this, cfg, false);
            runOnUiThread(() -> handleUpdateCheckResult(result, false));
        });
    }

    private void checkForUpdates(boolean manual) {
        if (!ensureCustomRequestFieldsValid("检查更新")) {
            return;
        }
        ProxyConfig cfg = readConfigFromUi();
        autoSaveExecutor.execute(() -> ProxyConfig.save(this, cfg)); // MA-3
        setActionStatus("正在检查更新");
        appendLog("开始检查更新");
        bgExecutor.execute(() -> {
            UpdateManager.CheckResult result = UpdateManager.checkForUpdates(this, cfg, manual);
            runOnUiThread(() -> handleUpdateCheckResult(result, manual));
        });
    }

    private void handleUpdateCheckResult(UpdateManager.CheckResult result, boolean manual) {
        // v1.5.4+：从 runOnUiThread 异步回调进入；Activity 已 destroyed 时直接 return，避免后续 AlertDialog.show() 抛 BadTokenException（MA-2/MA-5）。
        if (!isAlive()) return;
        if (result == null) {
            setActionStatus("检查更新失败");
            appendLog("检查更新失败：返回为空");
            FeedbackSupport.recordOperationalError(this, "update_check", "检查更新失败", "返回结果为空");
            return;
        }
        if ((result.updateAvailable || result.dismissed) && result.manifest != null) {
            setActionStatus("发现新版本");
            appendLog("检测到新版本：" + result.manifest.versionName);
            showUpdateAvailableDialog(result);
            return;
        }
        if (result.dismissed) {
            appendLog("更新检查已跳过：当前版本已被用户暂时忽略");
            return;
        }
        if (result.success) {
            setActionStatus("已是最新版本");
            if (manual) {
                appendLog("检查更新完成：" + result.message);
                new AlertDialog.Builder(this)
                        .setTitle("检查更新")
                        .setMessage(result.message)
                        .setPositiveButton("确定", null)
                        .show();
            }
            return;
        }
        if (result.skipped) {
            if (manual) {
                setActionStatus("无法检查更新");
                appendLog("检查更新已跳过：" + result.message);
                new AlertDialog.Builder(this)
                        .setTitle("无法检查更新")
                        .setMessage(result.message)
                        .setPositiveButton("确定", null)
                        .show();
            }
            return;
        }
        setActionStatus("检查更新失败");
        appendLog("检查更新失败：" + result.message);
        FeedbackSupport.recordOperationalError(this, "update_check", "检查更新失败", result.message);
        if (manual) {
            new AlertDialog.Builder(this)
                    .setTitle("检查更新失败")
                    .setMessage(result.message)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private void showUpdateAvailableDialog(UpdateManager.CheckResult result) {
        if (result == null || result.manifest == null) {
            return;
        }
        UpdateManager.UpdateManifest manifest = result.manifest;
        StringBuilder message = new StringBuilder();
        message.append("当前检测到新版本：").append(manifest.versionName).append('\n');
        if (!TextUtils.isEmpty(manifest.publishedAt)) {
            message.append("发布时间：").append(manifest.publishedAt).append('\n');
        }
        message.append('\n');
        message.append(TextUtils.isEmpty(manifest.changelog) ? "暂无更新说明" : manifest.changelog);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(message.toString())
                .setPositiveButton("立即更新", null)
                .setNegativeButton("稍后再说", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) {
                Linkify.addLinks(messageView, Linkify.WEB_URLS);
                messageView.setMovementMethod(LinkMovementMethod.getInstance());
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        dialog.dismiss();
                        startUpdateDownload(manifest);
                    });
        });
        dialog.show();
    }

    private void startUpdateDownload(UpdateManager.UpdateManifest manifest) {
        UpdateManager.StartDownloadResult result = UpdateManager.startDownload(this, manifest);
        if (result.success) {
            setActionStatus("正在下载更新包");
            appendLog(result.message);
            return;
        }
        setActionStatus("启动更新下载失败");
        appendLog("启动更新下载失败：" + result.message);
        FeedbackSupport.recordOperationalError(this, "update_download", "启动更新下载失败", result.message);
    }

    private void openFeedbackDialog() {
        LinearLayout container = buildDialogContainer();
        Spinner categorySpinner = new Spinner(this);
        ArrayList<String> categories = new ArrayList<>();
        categories.add("功能建议");
        categories.add("Bug");
        categories.add("网络问题");
        categories.add("人设问题");
        categories.add("更新问题");
        categories.add("其他");
        categorySpinner.setAdapter(buildThemedStringAdapter(categories));
        container.addView(categorySpinner, defaultDialogLayoutParams(false));

        EditText messageInput = buildDialogEditText("请尽量写清楚现象、预期和复现条件");
        messageInput.setMinLines(4);
        container.addView(messageInput, defaultDialogLayoutParams(true));

        EditText contactInput = buildDialogEditText("可选：QQ / 邮箱 / 其他联系方式");
        container.addView(contactInput, defaultDialogLayoutParams(true));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发送用户反馈")
                .setView(container)
                .setPositiveButton("发送", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String message = trimToEmpty(messageInput.getText().toString());
            if (TextUtils.isEmpty(message)) {
                messageInput.setError("请先填写反馈内容");
                return;
            }
            dialog.dismiss();
            sendUserFeedback(
                    safeString(String.valueOf(categorySpinner.getSelectedItem()), "其他"),
                    message,
                    trimToEmpty(contactInput.getText().toString())
            );
        }));
        dialog.show();
    }

    private void openErrorReportDialog(boolean clearCrashAfterSuccess) {
        LinearLayout container = buildDialogContainer();
        TextView hint = new TextView(this);
        hint.setText("会附带最近错误摘要、最近日志摘要，以及你下面补充的说明。不会上传 API Key、完整请求体或完整提示词。");
        hint.setTextSize(12f);
        container.addView(hint, defaultDialogLayoutParams(false));

        EditText noteInput = buildDialogEditText("可选：补充当时的现象、机型、网络环境");
        noteInput.setMinLines(3);
        container.addView(noteInput, defaultDialogLayoutParams(true));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(clearCrashAfterSuccess ? "发送崩溃报告" : "发送错误报告")
                .setView(container)
                .setPositiveButton("发送", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.dismiss();
            sendErrorReport(trimToEmpty(noteInput.getText().toString()), clearCrashAfterSuccess);
        }));
        dialog.show();
    }

    private void sendUserFeedback(String category, String message, String contact) {
        if (!ensureCustomRequestFieldsValid("发送反馈")) {
            return;
        }
        ProxyConfig cfg = readConfigFromUi();
        autoSaveExecutor.execute(() -> ProxyConfig.save(this, cfg)); // MA-3
        setActionStatus("正在发送用户反馈");
        bgExecutor.execute(() -> {
            FeedbackSupport.SendResult result = FeedbackSupport.sendUserFeedback(this, cfg, category, message, contact);
            runOnUiThread(() -> {
                if (!isAlive()) return; // MA-2/MA-5
                if (result.success) {
                    appendLog("用户反馈已发送");
                    setActionStatus("用户反馈已发送");
                } else {
                    appendLog("用户反馈发送失败：" + result.message);
                    setActionStatus("用户反馈发送失败");
                    FeedbackSupport.recordOperationalError(this, "user_feedback", "用户反馈发送失败", result.message);
                }
            });
        });
    }

    private void sendErrorReport(String extraMessage, boolean clearCrashAfterSuccess) {
        if (!ensureCustomRequestFieldsValid("发送错误报告")) {
            return;
        }
        ProxyConfig cfg = readConfigFromUi();
        autoSaveExecutor.execute(() -> ProxyConfig.save(this, cfg)); // MA-3
        setActionStatus("正在发送错误报告");
        bgExecutor.execute(() -> {
            FeedbackSupport.SendResult result = FeedbackSupport.sendErrorReport(this, cfg, extraMessage);
            runOnUiThread(() -> {
                if (!isAlive()) return; // MA-2/MA-5
                if (result.success) {
                    appendLog("错误报告已发送");
                    setActionStatus("错误报告已发送");
                    if (clearCrashAfterSuccess) {
                        FeedbackSupport.clearCrashReport(this);
                    }
                } else {
                    appendLog("错误报告发送失败：" + result.message);
                    setActionStatus("错误报告发送失败");
                    FeedbackSupport.recordOperationalError(this, "error_report", "错误报告发送失败", result.message);
                }
            });
        });
    }

    private LinearLayout buildDialogContainer() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        container.setPadding(padding, padding / 2, padding, 0);
        return container;
    }

    private LinearLayout.LayoutParams defaultDialogLayoutParams(boolean topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (topMargin) {
            params.topMargin = dp(8);
        }
        return params;
    }

    private EditText buildDialogEditText(String hint) {
        EditText editText = new EditText(this);
        setCompactHint(editText, hint);
        return editText;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ============================================================
    // v1.6.0+：主页"当前模型 / 视觉模型"BottomSheet picker
    // ------------------------------------------------------------
    // 隐藏 Spinner（spHomeActiveModel / spHomeCaptionProvider）继续作为数据载体，所有
    // setSpinnerOptions / selectSpinnerValue / onItemSelected 既有链路不动；可见入口是
    // btnHomeActiveModel / btnHomeCaptionProvider，点击弹出 picker。
    // 选中后：
    //   - active model 走 selectSpinnerValue（SpinnerOption.value 形如 "<providerId>\u001F<modelId>"）
    //     → onHomeActiveModelSelected → save cfg + 切换运行时模型
    //   - caption 走 spinner.setSelection(idx) → dirtyListener → markConfigDirty
    // ============================================================

    /** 主页"当前模型"按钮文字同步。每次 spHomeActiveModel 选项 / 选中变化后调用。 */
    private void refreshHomeActiveModelButtonLabel() {
        if (tvHomeActiveModelLabel == null) return;
        String label = activeProviderModelLabel();
        tvHomeActiveModelLabel.setText(TextUtils.isEmpty(label) ? "未选择模型" : label);
    }

    /** 主页"视觉模型"按钮文字同步。 */
    private void refreshHomeCaptionProviderButtonLabel() {
        if (tvHomeCaptionProviderLabel == null) return;
        if (spHomeCaptionProvider == null
                || spHomeCaptionProvider.getAdapter() == null
                || spHomeCaptionProvider.getAdapter().getCount() == 0) {
            tvHomeCaptionProviderLabel.setText("（不启用视觉模型）");
            return;
        }
        int pos = spHomeCaptionProvider.getSelectedItemPosition();
        if (pos < 0 || pos >= spHomeCaptionProvider.getAdapter().getCount()) {
            tvHomeCaptionProviderLabel.setText("（不启用视觉模型）");
            return;
        }
        Object item = spHomeCaptionProvider.getAdapter().getItem(pos);
        tvHomeCaptionProviderLabel.setText(item == null ? "（不启用视觉模型）" : String.valueOf(item));
    }

    private void showHomeActiveModelPicker() {
        showHomeModelPickerSheet(
                "选择当前模型",
                new SpinnerOption("", "未选择模型"),
                /*activeModelMode=*/true,
                currentHomeModelSelectionValue());
    }

    private void showHomeCaptionProviderPicker() {
        if (spHomeCaptionStrategy != null
                && !spHomeCaptionProvider.isEnabled()) {
            // 策略=关闭 时按钮看起来灰，仍可点；保持现有 enabled 控制，无需在此提示
        }
        showHomeModelPickerSheet(
                "选择视觉模型",
                new SpinnerOption(CAPTION_PROVIDER_NONE, "（不启用视觉模型）"),
                /*activeModelMode=*/false,
                getHomeCaptionProviderId());
    }

    /**
     * 共用的 BottomSheet picker：搜索框 + 按 provider 分组 + 顶部特殊行。
     *
     * @param activeModelMode true = active model（value=providerId\u001FmodelId，写回隐藏 spinner 走
     *                        selectSpinnerValue）；false = caption（value=providerId\u001FmodelName，
     *                        写回 spHomeCaptionProvider 走 setSelection(idx)）
     */
    private void showHomeModelPickerSheet(
            String title,
            SpinnerOption topSpecial,
            boolean activeModelMode,
            String currentValue) {
        UiThemePalette palette = currentThemePalette();
        BottomSheetDialog dialog = new BottomSheetDialog(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(16));
        container.setBackgroundColor(palette.surface);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setTextSize(18f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(palette.textPrimary);
        container.addView(titleView, defaultDialogLayoutParams());

        EditText search = new EditText(this);
        search.setHint("搜索模型 / 提供商");
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(palette.textPrimary);
        search.setHintTextColor(palette.textSecondary);
        search.setBackground(buildRuntimeTextBoxBackground(palette));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.bottomMargin = dp(8);
        container.addView(search, searchParams);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(440));
        container.addView(scroll, scrollParams);

        // 收集每个 provider 的 entries
        LinkedHashMap<String, List<SpinnerOption>> grouped = collectHomeModelGroups(activeModelMode);
        // 默认折叠所有 section；当前选中所属 section 默认展开；搜索时强制展开有命中的 section
        HashMap<String, Boolean> expandedMap = new HashMap<>();
        String currentProviderId = extractProviderIdFromHomeValue(currentValue);
        if (!TextUtils.isEmpty(currentProviderId)) {
            String label = providerLabelFor(currentProviderId);
            if (!TextUtils.isEmpty(label)) {
                expandedMap.put(label, true);
            }
        }

        Runnable[] rebuildRef = new Runnable[1];
        rebuildRef[0] = () -> rebuildHomeModelPickerList(
                listContainer,
                palette,
                topSpecial,
                grouped,
                expandedMap,
                search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT),
                currentValue,
                activeModelMode,
                dialog,
                rebuildRef);

        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { rebuildRef[0].run(); }
        });

        rebuildRef[0].run();
        dialog.setContentView(container);
        dialog.show();
    }

    /**
     * 把 spHomeActiveModel / spHomeCaptionProvider 的可选项按 provider 分组（顶部特殊行另外处理）。
     * key 是 providerLabel（"<providerName>"），value 是该 provider 下的 entries（label 仅 modelName）。
     */
    private LinkedHashMap<String, List<SpinnerOption>> collectHomeModelGroups(boolean activeModelMode) {
        LinkedHashMap<String, List<SpinnerOption>> grouped = new LinkedHashMap<>();
        if (providerProfileDrafts == null) return grouped;
        for (ProxyConfig.ProviderProfile provider : providerProfileDrafts.values()) {
            if (provider == null) continue;
            provider.ensureModelDefaults();
            if (provider.models == null || provider.models.isEmpty()) continue;
            String providerLabel = safeString(provider.name, provider.id);
            List<SpinnerOption> bucket = new ArrayList<>();
            for (ProxyConfig.ModelProfile model : provider.models) {
                if (model == null) continue;
                String value;
                String label;
                if (activeModelMode) {
                    value = homeModelValue(provider.id, model.id);
                    label = safeString(model.name, model.id);
                } else {
                    if (TextUtils.isEmpty(model.name)) continue;
                    value = provider.id + HOME_MODEL_SEPARATOR + model.name;
                    label = model.name;
                }
                bucket.add(new SpinnerOption(value, label));
            }
            if (!bucket.isEmpty()) {
                grouped.put(providerLabel, bucket);
            }
        }
        return grouped;
    }

    private void rebuildHomeModelPickerList(
            LinearLayout listContainer,
            UiThemePalette palette,
            SpinnerOption topSpecial,
            LinkedHashMap<String, List<SpinnerOption>> grouped,
            HashMap<String, Boolean> expandedMap,
            String query,
            String currentValue,
            boolean activeModelMode,
            BottomSheetDialog dialog,
            Runnable[] rebuildRef) {
        listContainer.removeAllViews();
        boolean searching = !TextUtils.isEmpty(query);

        // 顶部特殊行（"未选择模型" / "（不启用视觉模型）"）
        if (topSpecial != null) {
            if (!searching || topSpecial.label.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                listContainer.addView(buildHomePickerRow(
                        topSpecial, currentValue, palette, activeModelMode, dialog));
            }
        }

        for (Map.Entry<String, List<SpinnerOption>> g : grouped.entrySet()) {
            String label = g.getKey();
            List<SpinnerOption> entries = g.getValue();
            String labelLower = label.toLowerCase(java.util.Locale.ROOT);
            boolean groupNameMatches = searching && labelLower.contains(query);

            List<SpinnerOption> filtered = new ArrayList<>();
            if (searching) {
                for (SpinnerOption e : entries) {
                    String entryLower = (e.label == null ? "" : e.label).toLowerCase(java.util.Locale.ROOT);
                    if (groupNameMatches || entryLower.contains(query)) {
                        filtered.add(e);
                    }
                }
                if (filtered.isEmpty()) continue;
            } else {
                filtered = entries;
            }

            boolean expanded = searching || Boolean.TRUE.equals(expandedMap.get(label));

            listContainer.addView(buildHomePickerSectionHeader(
                    label, filtered.size(), expanded, palette,
                    () -> {
                        expandedMap.put(label, !Boolean.TRUE.equals(expandedMap.get(label)));
                        rebuildRef[0].run();
                    }));

            if (expanded) {
                for (SpinnerOption e : filtered) {
                    listContainer.addView(buildHomePickerRow(
                            e, currentValue, palette, activeModelMode, dialog));
                }
            }
        }
    }

    private View buildHomePickerSectionHeader(
            String label,
            int count,
            boolean expanded,
            UiThemePalette palette,
            Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(12), dp(6), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) {
            row.setBackgroundResource(tv.resourceId);
        }

        TextView arrow = new TextView(this);
        arrow.setText(expanded ? "▾" : "▸");
        arrow.setTextColor(palette.textSecondary);
        arrow.setTextSize(13f);
        arrow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(arrow, arrowParams);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(palette.textPrimary);
        name.setTextSize(15f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(6);
        row.addView(name, nameParams);

        TextView countView = new TextView(this);
        countView.setText(String.valueOf(count));
        countView.setTextColor(palette.textSecondary);
        countView.setTextSize(13f);
        row.addView(countView);

        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private View buildHomePickerRow(
            SpinnerOption opt,
            String currentValue,
            UiThemePalette palette,
            boolean activeModelMode,
            BottomSheetDialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(28), dp(12), dp(12), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        boolean current = TextUtils.equals(opt.value, currentValue);
        if (current) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(8));
            bg.setColor(palette.primarySoft);
            row.setBackground(bg);
        } else {
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) {
                row.setBackgroundResource(tv.resourceId);
            }
        }

        TextView label = new TextView(this);
        label.setText(opt.label);
        label.setTextSize(15f);
        label.setTextColor(current ? palette.primary : palette.textPrimary);
        if (current) {
            label.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (current) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextSize(15f);
            check.setTextColor(palette.primary);
            check.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(check);
        }

        row.setOnClickListener(v -> {
            if (activeModelMode) {
                // 把值写回隐藏 spinner，触发 onHomeActiveModelSelected 链路
                selectSpinnerValue(spHomeActiveModel, opt.value);
                refreshHomeActiveModelButtonLabel();
            } else {
                selectHomeCaptionProviderValue(opt.value);
                refreshHomeCaptionProviderButtonLabel();
            }
            dialog.dismiss();
        });
        return row;
    }

    /** 把 value 应用到 spHomeCaptionProvider，触发 dirtyListener.onItemSelected。 */
    private void selectHomeCaptionProviderValue(String value) {
        if (spHomeCaptionProvider == null || homeCaptionProviderIds == null) return;
        String want = TextUtils.isEmpty(value) ? CAPTION_PROVIDER_NONE : value;
        int target = -1;
        for (int i = 0; i < homeCaptionProviderIds.size(); i++) {
            if (TextUtils.equals(homeCaptionProviderIds.get(i), want)) {
                target = i;
                break;
            }
        }
        // 兼容旧版只存 providerId 不带 \u001F + modelName 的情况
        if (target < 0 && !TextUtils.isEmpty(want) && !want.contains(HOME_MODEL_SEPARATOR)) {
            for (int i = 0; i < homeCaptionProviderIds.size(); i++) {
                if (homeCaptionProviderIds.get(i).startsWith(want + HOME_MODEL_SEPARATOR)) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) target = 0;
        spHomeCaptionProvider.setSelection(target, false);
    }

    private String extractProviderIdFromHomeValue(String value) {
        if (TextUtils.isEmpty(value)) return "";
        int sep = value.indexOf(HOME_MODEL_SEPARATOR);
        if (sep < 0) return value;
        return value.substring(0, sep);
    }

    private String providerLabelFor(String providerId) {
        if (TextUtils.isEmpty(providerId) || providerProfileDrafts == null) return "";
        ProxyConfig.ProviderProfile p = providerProfileDrafts.get(providerId);
        return p == null ? providerId : safeString(p.name, p.id);
    }
    // ============================================================
    // /v1.6.0+ 主页 picker 区域结束
    // ============================================================

    private void registerServiceReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ProxyForegroundService.ACTION_LOG_EVENT);
        filter.addAction(ProxyForegroundService.ACTION_STATE_EVENT);
        ContextCompat.registerReceiver(this, serviceEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterServiceReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(serviceEventReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void requestServiceAction(String action) {
        Intent intent = new Intent(this, ProxyForegroundService.class);
        intent.setAction(action);
        if (ProxyForegroundService.ACTION_START.equals(action) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean ensureUpstreamActionAllowed(ProxyConfig cfg, String actionLabel) {
        String validationError = DiaryProxyServer.validateUpstreamConfig(cfg);
        if (TextUtils.isEmpty(validationError)) {
            return true;
        }
        appendLog(actionLabel + "失败：" + validationError);
        setActionStatus(actionLabel + "失败");
        FeedbackSupport.recordOperationalError(this, actionLabel, actionLabel + "前校验失败", validationError);
        return false;
    }

    private void updateRunningUi(ProxyForegroundService.ServiceStateSnapshot snapshot) {
        ProxyForegroundService.ServiceStateSnapshot safe = snapshot == null
                ? new ProxyForegroundService.ServiceStateSnapshot(false, false, 0L)
                : snapshot;
        guardianRunning = safe.guardianRunning;
        btnStartStop.setText(guardianRunning ? "停止代理" : "启动代理");

        if (!safe.guardianRunning) {
            setServiceStatus("未启动");
        } else if (safe.serverRunning) {
            setServiceStatus("运行中");
        } else {
            setServiceStatus("守护已启动，但本地代理未启动");
        }

        if (safe.hasRecentChatRequest() && !TextUtils.isEmpty(safe.requestStatusText)) {
            tvRequestStatus.setText("最近请求：" + safe.requestStatusText);
        } else if (safe.hasRecentChatRequest()) {
            tvRequestStatus.setText("最近请求：最近 2 分钟内已检测到入站聊天请求");
        } else {
            tvRequestStatus.setText("最近请求：最近 2 分钟内暂无入站聊天请求");
        }
    }

    private void setServiceStatus(String status) {
        tvStatus.setText("代理服务状态：" + status);
    }

    private void setActionStatus(String status) {
        tvActionStatus.setText("操作提示：" + status);
    }

    private void updateEndpoint(ProxyConfig cfg) {
        tvEndpoint.setText("本地入口：http://localhost:" + cfg.port + cfg.getEndpointPreviewPath());
        String baseUrl = !TextUtils.isEmpty(editTextValue(etBaseUrl)) ? editTextValue(etBaseUrl) : cfg.upstreamBaseUrl;
        String chatPath = !TextUtils.isEmpty(editTextValue(etUpstreamChatPath)) ? editTextValue(etUpstreamChatPath) : cfg.upstreamChatPath;
        String modelsPath = !TextUtils.isEmpty(editTextValue(etUpstreamModelsPath)) ? editTextValue(etUpstreamModelsPath) : cfg.upstreamModelsPath;
        ProxyConfig.ProviderProfile activeProvider = getActiveProviderDraft();
        String activeModelName = activeProvider == null ? "" : safeString(activeProvider.getActiveModelName(), "");
        String model = !TextUtils.isEmpty(activeModelName) ? activeModelName : cfg.model;
        updateUpstreamPathPreviews(baseUrl, chatPath, modelsPath, model);
    }

    private void updateHistoryPathSummary() {
        if (tvHistoryPath == null) {
            return;
        }
        String historyPath = ProxyStorageHelper.getHistoryRootDisplayPath(this);
        String debugPath = ProxyStorageHelper.getDebugPromptRootDisplayPath(this);
        String requestPath = ProxyStorageHelper.getLastDiaryRequestDisplayPath(this);
        tvHistoryPath.setText("记录保存位置：" + historyPath
                + "\n调试提示词导出：" + debugPath
                + "\n最近一次日记请求缓存：" + requestPath);
    }

    private void updateKeepAliveStatus() {
        if (tvKeepAliveStatus == null) {
            return;
        }
        boolean ignoringBatteryOptimization = isIgnoringBatteryOptimizations();
        if (ignoringBatteryOptimization) {
            tvKeepAliveStatus.setText("当前保活状态：电池优化限制已解除；后台保活会更稳定，但后台常驻/手动管理后台仍需在系统设置中手动开启");
            tvKeepAliveStatus.setTextColor(0xFF2E7D32);
        } else {
            tvKeepAliveStatus.setText("当前保活状态：仍受系统电池优化限制；后台可能被省电策略干扰，建议点下方按钮关闭电池优化，并手动开启后台活动");
            tvKeepAliveStatus.setTextColor(0xFFC25B00);
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void setupAttachmentsArea() {
        if (rvAttachmentList == null) return;
        attachmentAdapter = new AttachmentDraftAdapter(attachmentDrafts, (ref, position) -> {
            ProxyStorageHelper.removeAttachmentDraft(this, ref.id);
            refreshAttachmentDrafts();
        });
        // v1.5.5+：DPS-8 — 让 adapter 实时读取调试页"文档处理"两个开关，渲染"将截断到 N 字"提示
        attachmentAdapter.setTruncationConfig(new AttachmentDraftAdapter.TruncationConfigSupplier() {
            @Override
            public boolean isTruncationEnabled() {
                return cbDocumentTruncationEnabled != null && cbDocumentTruncationEnabled.isChecked();
            }
            @Override
            public int getTruncationMaxChars() {
                if (etDocumentTruncationMaxChars == null) return 4096;
                return safeInt(etDocumentTruncationMaxChars.getText().toString(), 4096);
            }
        });
        rvAttachmentList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        rvAttachmentList.setAdapter(attachmentAdapter);

        pickImageLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
                this::handlePickedAttachment);
        pickDocumentLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                this::handlePickedAttachment);

        if (btnAddAttachmentImage != null) {
            btnAddAttachmentImage.setOnClickListener(v -> {
                if (pickImageLauncher == null) return;
                pickImageLauncher.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                        .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            });
        }
        if (btnAddAttachmentDoc != null) {
            btnAddAttachmentDoc.setOnClickListener(v -> {
                if (pickDocumentLauncher == null) return;
                pickDocumentLauncher.launch(new String[]{
                        "text/*",
                        "application/pdf",
                        "application/json",
                        "application/xml"
                });
            });
        }
        if (btnClearAttachments != null) {
            btnClearAttachments.setOnClickListener(v -> {
                if (attachmentDrafts.isEmpty()) {
                    appendLog("附件草稿已是空");
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("清空附件")
                        .setMessage("确定要移除所有 " + attachmentDrafts.size() + " 项附件草稿？")
                        .setPositiveButton("清空", (d, w) -> {
                            ProxyStorageHelper.clearAttachmentDrafts(this);
                            refreshAttachmentDrafts();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }
        refreshAttachmentDrafts();
    }

    private void handlePickedAttachment(Uri uri) {
        if (uri == null) {
            return;
        }
        // v1.5.6+：PSH-4 — 把附件读取 / PDF 解码 / 落盘整段移到 bgExecutor，
        // 避免主线程被 PDF 解码阻塞 1-3s（低端机选 8MB PDF 可能 ANR）。
        // 这里先在 UI 线程拍下当前已用总大小，避免后台线程跨线程读 attachmentDrafts 列表。
        long currentTotal = 0;
        for (ProxyStorageHelper.AttachmentRef existing : attachmentDrafts) {
            if (existing != null) currentTotal += existing.byteSize;
        }
        final long currentTotalBytes = currentTotal;
        bgExecutor.execute(() -> {
            try {
                android.content.ContentResolver resolver = getContentResolver();
                String mimeRaw = resolver.getType(uri);
                String displayName = queryDisplayName(uri);
                byte[] bytes = readBytesFromUri(uri);
                if (bytes == null || bytes.length == 0) {
                    runOnUiThread(() -> appendLog("附件读取失败：内容为空"));
                    return;
                }
                final String mime = TextUtils.isEmpty(mimeRaw) ? "application/octet-stream" : mimeRaw;
                // 硬上限：单文件 32MB（合计上限在 AttachmentSupport.SIZE_HARD_LIMIT_TOTAL_BYTES 处再卡）
                long total = bytes.length + currentTotalBytes;
                if (total > AttachmentSupport.SIZE_HARD_LIMIT_TOTAL_BYTES) {
                    final String formattedTotal = AttachmentSupport.formatSize(total);
                    runOnUiThread(() -> {
                        if (!isAlive()) return;
                        new AlertDialog.Builder(this)
                                .setTitle("附件过大")
                                .setMessage("加上本次后总大小 " + formattedTotal
                                        + " 超过 " + AttachmentSupport.formatSize(AttachmentSupport.SIZE_HARD_LIMIT_TOTAL_BYTES)
                                        + " 的硬上限。已拒绝。")
                                .setPositiveButton("确定", null)
                                .show();
                    });
                    return;
                }
                // PSH-4：在后台线程预先算好 charCount（PDF 解码可能耗 1-3s），再传给 helper 落盘。
                int charCount = -1;
                if (!AttachmentSupport.isImageMime(mime)) {
                    try {
                        String decoded = PdfTextExtractor.decodeAttachmentText(mime, bytes);
                        charCount = decoded == null ? 0 : decoded.length();
                    } catch (Throwable ignored) {
                        charCount = -1;
                    }
                }
                ProxyStorageHelper.AttachmentRef ref = ProxyStorageHelper.appendAttachmentDraft(
                        this, mime, displayName, bytes, charCount);
                final String dispName = ref.displayName;
                final long byteSize = ref.byteSize;
                runOnUiThread(() -> {
                    if (!isAlive()) return;
                    appendLog("已加入附件 " + dispName + " (" + AttachmentSupport.formatSize(byteSize) + ")");
                    refreshAttachmentDrafts();
                    // 单图软警告（>8MB）
                    if (AttachmentSupport.isImageMime(mime) && byteSize > AttachmentSupport.SIZE_SOFT_LIMIT_IMAGE_BYTES) {
                        new AlertDialog.Builder(this)
                                .setTitle("超大图片提示")
                                .setMessage("「" + dispName + "」"
                                        + AttachmentSupport.formatSize(byteSize)
                                        + "，部分上游可能拒绝。仍可发送，必要时主动压缩后再传。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            } catch (Exception error) {
                final String msg = error.getMessage();
                runOnUiThread(() -> appendLog("附件加入失败：" + msg));
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? "附件" : fallback;
    }

    private byte[] readBytesFromUri(Uri uri) throws java.io.IOException {
        try (java.io.InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return null;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void refreshAttachmentDrafts() {
        attachmentDrafts.clear();
        attachmentDrafts.addAll(ProxyStorageHelper.listAttachmentDrafts(this));
        if (attachmentAdapter != null) {
            attachmentAdapter.notifyDataSetChanged();
        }
        updateAttachmentHint();
    }

    private void updateAttachmentHint() {
        if (tvAttachmentHint == null) return;
        if (attachmentDrafts.isEmpty()) {
            tvAttachmentHint.setText("附件会随主对话请求一同发送（日记 / 长期记忆 / 健康检查 不附带）。");
            return;
        }
        long total = 0L;
        int images = 0;
        int docs = 0;
        for (ProxyStorageHelper.AttachmentRef ref : attachmentDrafts) {
            if (ref == null) continue;
            total += ref.byteSize;
            if (AttachmentSupport.isImageMime(ref.mime)) images++;
            else docs++;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("已选 ").append(attachmentDrafts.size()).append(" 项");
        if (images > 0) builder.append("：").append(images).append(" 张图");
        if (docs > 0) builder.append((images > 0 ? "、" : "：")).append(docs).append(" 个文档");
        builder.append("，合计 ").append(AttachmentSupport.formatSize(total));
        builder.append("。发送成功后会自动清空，失败保留。");
        tvAttachmentHint.setText(builder.toString());
    }

    /** v1.5.1+：Spinner 适配器初始化（fixed labels）。 */
    private void setupV15Spinners() {
        if (spWebSearchToolEnabled == null || spWebSearchProvider == null) {
            return;
        }
        if (spHomeCaptionStrategy != null) {
            bindSpinnerLabels(spHomeCaptionStrategy, CAPTION_STRATEGY_LABELS);
        }
        // v1.5.5+：CS-3 三态 quirk
        if (spCaptionImageDetailMode != null) {
            bindSpinnerLabels(spCaptionImageDetailMode, CAPTION_IMAGE_DETAIL_LABELS);
        }
        if (spCaptionImagePlacementMode != null) {
            bindSpinnerLabels(spCaptionImagePlacementMode, CAPTION_IMAGE_PLACEMENT_LABELS);
        }
        if (spCaptionImageUrlFormat != null) {
            bindSpinnerLabels(spCaptionImageUrlFormat, CAPTION_IMAGE_URL_FORMAT_LABELS);
        }
        bindSpinnerLabels(spWebSearchToolEnabled, WEB_SEARCH_TOOL_LABELS);
        bindSpinnerLabels(spWebSearchProvider, WEB_SEARCH_PROVIDER_LABELS);
        if (spHomeWebSearchToolEnabled != null) {
            bindSpinnerLabels(spHomeWebSearchToolEnabled, WEB_SEARCH_TOOL_LABELS);
        }
        if (spHomeWebSearchProvider != null) {
            bindSpinnerLabels(spHomeWebSearchProvider, WEB_SEARCH_PROVIDER_LABELS);
        }
        AdapterView.OnItemSelectedListener dirtyListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!v15SpinnerReady) return;
                markConfigDirty();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        if (spHomeCaptionStrategy != null) {
            spHomeCaptionStrategy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!v15SpinnerReady) return;
                    markConfigDirty();
                    refreshCaptionProviderEnabledByStrategy();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (spHomeCaptionProvider != null) {
            spHomeCaptionProvider.setOnItemSelectedListener(dirtyListener);
        }
        // v1.5.5+：CS-3 三态 quirk Spinner 标记 dirty
        if (spCaptionImageDetailMode != null) {
            spCaptionImageDetailMode.setOnItemSelectedListener(dirtyListener);
        }
        if (spCaptionImagePlacementMode != null) {
            spCaptionImagePlacementMode.setOnItemSelectedListener(dirtyListener);
        }
        if (spCaptionImageUrlFormat != null) {
            spCaptionImageUrlFormat.setOnItemSelectedListener(dirtyListener);
        }
        if (btnHomeCaptionProvider != null) {
            btnHomeCaptionProvider.setOnClickListener(v -> showHomeCaptionProviderPicker());
        }
        spWebSearchToolEnabled.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!v15SpinnerReady) return;
                markConfigDirty();
                mirrorWebSearchToolEnabled(spWebSearchToolEnabled, spHomeWebSearchToolEnabled);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        spWebSearchProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onWebSearchProviderSelected();
                // 抽屉新页改引擎 → 镜像到主页 spHomeWebSearchProvider
                if (spHomeWebSearchProvider != null && v15SpinnerReady) {
                    boolean wasReady = v15SpinnerReady;
                    v15SpinnerReady = false;
                    try {
                        int pos = spWebSearchProvider.getSelectedItemPosition();
                        if (pos >= 0 && pos < spHomeWebSearchProvider.getCount()) {
                            spHomeWebSearchProvider.setSelection(pos, false);
                        }
                    } finally {
                        v15SpinnerReady = wasReady;
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        if (spHomeWebSearchToolEnabled != null) {
            spHomeWebSearchToolEnabled.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!v15SpinnerReady) return;
                    markConfigDirty();
                    mirrorWebSearchToolEnabled(spHomeWebSearchToolEnabled, spWebSearchToolEnabled);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (spHomeWebSearchProvider != null) {
            spHomeWebSearchProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!v15SpinnerReady) return;
                    // 主页改引擎 → 镜像到抽屉新页 spWebSearchProvider，让 onWebSearchProviderSelected 处理 staging
                    boolean wasReady = v15SpinnerReady;
                    v15SpinnerReady = false;
                    try {
                        int pos = spHomeWebSearchProvider.getSelectedItemPosition();
                        if (pos >= 0 && spWebSearchProvider != null && pos < spWebSearchProvider.getCount()) {
                            spWebSearchProvider.setSelection(pos, false);
                        }
                    } finally {
                        v15SpinnerReady = wasReady;
                    }
                    onWebSearchProviderSelected();
                    markConfigDirty();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        v15SpinnerReady = true;
    }

    /** v1.5.1+：联网搜索全局开关 spinner 双向镜像（主页 ↔ 抽屉新页）。全局状态，不再分 provider。 */
    private void mirrorWebSearchToolEnabled(Spinner from, Spinner to) {
        if (from == null || to == null) return;
        boolean wasReady = v15SpinnerReady;
        v15SpinnerReady = false;
        try {
            int pos = from.getSelectedItemPosition();
            if (pos >= 0 && pos < to.getCount()) {
                to.setSelection(pos, false);
            }
        } finally {
            v15SpinnerReady = wasReady;
        }
    }

    private void bindSpinnerLabels(Spinner spinner, String[] labels) {
        if (spinner == null || labels == null) return;
        spinner.setAdapter(buildThemedStringAdapter(new ArrayList<>(java.util.Arrays.asList(labels))));
    }

    private String getSpinnerStringValueFromArray(Spinner spinner, String[] values, String fallback) {
        if (spinner == null || values == null) return fallback;
        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= values.length) return fallback;
        return values[pos];
    }

    private void selectSpinnerByValue(Spinner spinner, String[] values, String value) {
        if (spinner == null || values == null) return;
        int target = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(value)) {
                target = i;
                break;
            }
        }
        if (target < 0) target = 0;
        boolean wasReady = v15SpinnerReady;
        v15SpinnerReady = false;
        spinner.setSelection(target, false);
        v15SpinnerReady = wasReady;
    }

    /**
     * v1.5.1+：主页视觉模型 Spinner 全量刷新。
     * 列出"所有 provider × 各自已保存的 model"组合，让用户精确选到 provider+model 粒度。
     * 第一项是"（不启用视觉模型）"，对应空字符串。
     */
    private void refreshHomeCaptionProviderSpinner(String currentSelection) {
        if (spHomeCaptionProvider == null) return;
        ArrayList<String> labels = new ArrayList<>();
        homeCaptionProviderIds = new ArrayList<>();
        labels.add("（不启用视觉模型）");
        homeCaptionProviderIds.add(CAPTION_PROVIDER_NONE);
        if (providerProfileDrafts != null) {
            for (ProxyConfig.ProviderProfile draft : providerProfileDrafts.values()) {
                if (draft == null) continue;
                draft.ensureModelDefaults();
                if (draft.models == null || draft.models.isEmpty()) continue;
                for (ProxyConfig.ModelProfile model : draft.models) {
                    if (model == null || TextUtils.isEmpty(model.name)) continue;
                    labels.add(safeString(draft.name, draft.id) + " / " + model.name);
                    homeCaptionProviderIds.add(draft.id + HOME_MODEL_SEPARATOR + model.name);
                }
            }
        }
        spHomeCaptionProvider.setAdapter(buildThemedStringAdapter(labels));
        // 找当前选中 index
        int target = 0;
        String want = safeString(currentSelection, CAPTION_PROVIDER_NONE);
        for (int i = 0; i < homeCaptionProviderIds.size(); i++) {
            if (homeCaptionProviderIds.get(i).equals(want)) {
                target = i;
                break;
            }
        }
        // 兼容旧版只存 providerId 不带 \u001F + modelName 的情况
        if (target == 0 && !TextUtils.isEmpty(want) && !want.contains(HOME_MODEL_SEPARATOR)) {
            for (int i = 0; i < homeCaptionProviderIds.size(); i++) {
                if (homeCaptionProviderIds.get(i).startsWith(want + HOME_MODEL_SEPARATOR)) {
                    target = i;
                    break;
                }
            }
        }
        boolean wasReady = v15SpinnerReady;
        v15SpinnerReady = false;
        spHomeCaptionProvider.setSelection(target, false);
        v15SpinnerReady = wasReady;
        refreshHomeCaptionProviderButtonLabel();
    }

    /** v1.5.1+：provider/model 列表变化后刷新视觉模型候选项，同时尽量保留当前主页选择。 */
    private void refreshHomeCaptionProviderSpinnerPreservingSelection() {
        String currentSelection = getHomeCaptionProviderId();
        if (TextUtils.isEmpty(currentSelection)) {
            ProxyConfig.ProviderProfile active = getActiveProviderDraft();
            currentSelection = active == null
                    ? CAPTION_PROVIDER_NONE
                    : safeString(active.captionProviderId, CAPTION_PROVIDER_NONE);
        }
        refreshHomeCaptionProviderSpinner(currentSelection);
        refreshCaptionProviderEnabledByStrategy();
    }

    /** v1.5.1+：主页视觉模型设置属于 active provider，而不是当前正在编辑的 provider。 */
    private void saveHomeCaptionFieldsToActiveProviderDraft() {
        ProxyConfig.ProviderProfile active = getActiveProviderDraft();
        if (active == null || spHomeCaptionProvider == null || spHomeCaptionStrategy == null) {
            return;
        }
        active.captionProviderId = getHomeCaptionProviderId();
        active.captionStrategy = ProxyConfig.normalizeCaptionStrategy(
                getSpinnerStringValueFromArray(spHomeCaptionStrategy, CAPTION_STRATEGY_VALUES, "inject"));
        active.captionMaxImagesPerRequest = active.captionMaxImagesPerRequest > 0
                ? active.captionMaxImagesPerRequest
                : 4;
        // v1.5.5+：CS-4 / CS-3 — 副模型 max_tokens + 三态 quirk
        if (etCaptionMaxTokens != null) {
            int parsed = safeInt(etCaptionMaxTokens.getText().toString(), 1024);
            active.captionMaxTokens = parsed > 0 ? parsed : 1024;
        }
        if (spCaptionImageDetailMode != null) {
            active.captionImageDetailMode = ProxyConfig.normalizeCaptionImageDetailMode(
                    getSpinnerStringValueFromArray(spCaptionImageDetailMode, CAPTION_IMAGE_DETAIL_VALUES, "auto"));
        }
        if (spCaptionImagePlacementMode != null) {
            active.captionImagePlacementMode = ProxyConfig.normalizeCaptionImagePlacementMode(
                    getSpinnerStringValueFromArray(spCaptionImagePlacementMode, CAPTION_IMAGE_PLACEMENT_VALUES, "auto"));
        }
        if (spCaptionImageUrlFormat != null) {
            active.captionImageUrlFormat = ProxyConfig.normalizeCaptionImageUrlFormat(
                    getSpinnerStringValueFromArray(spCaptionImageUrlFormat, CAPTION_IMAGE_URL_FORMAT_VALUES, "auto"));
        }
    }

    /** 读主页视觉模型 Spinner 当前选中的 providerId+modelName 字符串。 */
    private String getHomeCaptionProviderId() {
        if (spHomeCaptionProvider == null || homeCaptionProviderIds == null) {
            return CAPTION_PROVIDER_NONE;
        }
        int pos = spHomeCaptionProvider.getSelectedItemPosition();
        if (pos < 0 || pos >= homeCaptionProviderIds.size()) {
            return CAPTION_PROVIDER_NONE;
        }
        return homeCaptionProviderIds.get(pos);
    }

    private void onWebSearchProviderSelected() {
        if (spWebSearchProvider == null) {
            return;
        }
        String selected = ProxyConfig.normalizeWebSearchProvider(
                getSpinnerStringValueFromArray(spWebSearchProvider, WEB_SEARCH_PROVIDER_VALUES, "bochaai")
        );
        if (TextUtils.equals(selected, currentWebSearchProviderDraft)) {
            return;
        }
        // 切换前把当前 etWebSearchApiKey/Endpoint/Proxy 文本存到 pendingJson 当前引擎槽
        saveWebSearchEngineDraftGlobal(currentWebSearchProviderDraft);
        currentWebSearchProviderDraft = selected;
        applyWebSearchEngineToUiGlobal(selected);
        if (v15SpinnerReady && !suppressUnsavedTracking) {
            markConfigDirtyConfirmed();
        }
    }

    /** v1.5.1+：把当前 etWebSearchApiKey/Endpoint/Proxy 文本保存到全局 pendingJson 的指定 engine 槽。 */
    private void saveWebSearchEngineDraftGlobal(String engineRaw) {
        String engine = ProxyConfig.normalizeWebSearchProvider(engineRaw);
        if (etWebSearchApiKey != null) {
            pendingWebSearchApiKeysJson = ProxyConfig.withWebSearchApiKey(
                    pendingWebSearchApiKeysJson,
                    engine,
                    trimToEmpty(etWebSearchApiKey.getText().toString())
            );
        }
        if (etWebSearchEndpoint != null) {
            pendingWebSearchEndpointsJson = ProxyConfig.withWebSearchEndpoint(
                    pendingWebSearchEndpointsJson,
                    engine,
                    trimToEmpty(etWebSearchEndpoint.getText().toString())
            );
        }
        if (spWebSearchProxyType != null) {
            String type = getSpinnerValue(spWebSearchProxyType, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE);
            String host = etWebSearchProxyHost == null ? "" : trimToEmpty(etWebSearchProxyHost.getText().toString());
            int port = etWebSearchProxyPort == null ? 0 : safeInt(etWebSearchProxyPort.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_PROXY_PORT);
            pendingWebSearchProxiesJson = ProxyConfig.withWebSearchProxy(
                    pendingWebSearchProxiesJson, engine, type, host, port);
        }
    }

    /** v1.5.1+：把全局 pendingJson 中指定 engine 的 ApiKey/Endpoint/Proxy 加载到 UI。 */
    private void applyWebSearchEngineToUiGlobal(String engineRaw) {
        String engine = ProxyConfig.normalizeWebSearchProvider(engineRaw);
        boolean oldSuppress = suppressUnsavedTracking;
        suppressUnsavedTracking = true;
        try {
            if (etWebSearchApiKey != null) {
                String key = ProxyConfig.getWebSearchApiKey(pendingWebSearchApiKeysJson, engine);
                etWebSearchApiKey.setText(safeString(key, ""));
                etWebSearchApiKey.setEnabled(!"duckduckgo_html".equals(engine));
                setCompactHint(etWebSearchApiKey, webSearchApiKeyHint(engine));
            }
            if (etWebSearchEndpoint != null) {
                String endpoint = ProxyConfig.getWebSearchEndpoint(pendingWebSearchEndpointsJson, engine);
                etWebSearchEndpoint.setText(safeString(endpoint, ""));
                setCompactHint(etWebSearchEndpoint, webSearchEndpointHint(engine));
            }
            if (spWebSearchProxyType != null) {
                ProxyConfig.WebSearchProxyEntry entry = ProxyConfig.getWebSearchProxy(pendingWebSearchProxiesJson, engine);
                selectSpinnerValue(spWebSearchProxyType, safeString(entry.type, ProxyConfig.DEFAULT_UPSTREAM_PROXY_TYPE));
                if (etWebSearchProxyHost != null) {
                    etWebSearchProxyHost.setText(safeString(entry.host, ""));
                }
                if (etWebSearchProxyPort != null) {
                    etWebSearchProxyPort.setText(entry.port > 0 ? String.valueOf(entry.port) : "");
                }
            }
        } finally {
            suppressUnsavedTracking = oldSuppress;
        }
    }

    /** v1.5.1+：API Key 显隐切换（小眼睛 drawableEnd 风格，对齐主页 etApiKey）。 */
    private boolean handleWebSearchApiKeyVisibilityTouch(View view, MotionEvent event) {
        if (!(view instanceof EditText) || event == null) {
            return false;
        }
        EditText editText = (EditText) view;
        android.graphics.drawable.Drawable drawable = editText.getCompoundDrawablesRelative()[2];
        if (drawable == null) {
            drawable = editText.getCompoundDrawables()[2];
        }
        if (drawable == null) {
            return false;
        }
        int drawableWidth = drawable.getBounds().width() > 0 ? drawable.getBounds().width() : drawable.getIntrinsicWidth();
        float touchStart = editText.getWidth() - editText.getPaddingRight() - drawableWidth - editText.getCompoundDrawablePadding();
        if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getX() >= touchStart) {
            toggleWebSearchApiKeyVisible();
            return true;
        }
        return false;
    }

    private void toggleWebSearchApiKeyVisible() {
        if (etWebSearchApiKey == null) return;
        webSearchApiKeyVisible = !webSearchApiKeyVisible;
        int start = Math.max(0, etWebSearchApiKey.getSelectionStart());
        int end = Math.max(0, etWebSearchApiKey.getSelectionEnd());
        if (webSearchApiKeyVisible) {
            etWebSearchApiKey.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
        } else {
            etWebSearchApiKey.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }
        etWebSearchApiKey.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                webSearchApiKeyVisible ? R.drawable.ic_visibility_24 : R.drawable.ic_visibility_off_24,
                0
        );
        int length = etWebSearchApiKey.getText() == null ? 0 : etWebSearchApiKey.getText().length();
        etWebSearchApiKey.setSelection(Math.min(start, length), Math.min(end, length));
    }

    private String webSearchApiKeyHint(String engine) {
        switch (ProxyConfig.normalizeWebSearchProvider(engine)) {
            case "qianfan_ai_search":
                return "填写百度千帆 API Key / AppBuilder Key（Bearer 可省略）";
            case "volcengine_web_search":
                return "填写火山联网搜索 API Key（Bearer 可省略）";
            case "bing_cn":
                return "填写 Bing Search API Key";
            case "tavily":
                return "填写 Tavily API Key";
            case "serper":
                return "填写 Serper API Key";
            case "duckduckgo_html":
                return "DuckDuckGo HTML 不需要 API Key";
            case "bochaai":
            default:
                return "填写博查 AI API Key";
        }
    }

    private String webSearchEndpointHint(String engine) {
        switch (ProxyConfig.normalizeWebSearchProvider(engine)) {
            case "qianfan_ai_search":
                return "留空使用 https://qianfan.baidubce.com/v2/ai_search/web_search";
            case "volcengine_web_search":
                return "留空使用 https://open.feedcoopapi.com/search_api/web_search";
            case "tavily":
                return "留空使用 https://api.tavily.com/search";
            case "serper":
                return "留空使用 https://google.serper.dev/search";
            case "bing_cn":
                return "留空使用 https://api.bing.microsoft.com/v7.0/search";
            case "duckduckgo_html":
                return "DuckDuckGo HTML 使用内置地址";
            case "bochaai":
            default:
                return "留空使用 https://api.bochaai.com/v1/web-search";
        }
    }

    /** v1.5.1+：把 cfg 顶层联网搜索字段加载到主页 + 抽屉新页所有 web search UI 元素。 */
    private void applyWebSearchSettingsToUi(ProxyConfig cfg) {
        if (cfg == null) return;
        boolean wasReady = v15SpinnerReady;
        v15SpinnerReady = false;
        try {
            String tool = ProxyConfig.normalizeWebSearchToolEnabled(cfg.webSearchToolEnabled);
            selectSpinnerByValue(spHomeWebSearchToolEnabled, WEB_SEARCH_TOOL_VALUES, tool);
            selectSpinnerByValue(spWebSearchToolEnabled, WEB_SEARCH_TOOL_VALUES, tool);
            String engine = ProxyConfig.normalizeWebSearchProvider(cfg.webSearchProvider);
            selectSpinnerByValue(spWebSearchProvider, WEB_SEARCH_PROVIDER_VALUES, engine);
            selectSpinnerByValue(spHomeWebSearchProvider, WEB_SEARCH_PROVIDER_VALUES, engine);
            currentWebSearchProviderDraft = engine;
            // 加载所有 per-engine staging
            pendingWebSearchApiKeysJson = ProxyConfig.normalizeWebSearchApiKeysJson(cfg.webSearchApiKeysJson);
            pendingWebSearchEndpointsJson = ProxyConfig.normalizeWebSearchEndpointsJson(cfg.webSearchEndpointsJson);
            pendingWebSearchProxiesJson = ProxyConfig.normalizeWebSearchProxiesJson(cfg.webSearchProxiesJson);
            // 把当前选中引擎的 ApiKey/Endpoint/Proxy 显示到 UI
            applyWebSearchEngineToUiGlobal(engine);
            if (etWebSearchMaxResults != null) {
                int n = cfg.webSearchMaxResults > 0 ? cfg.webSearchMaxResults : 5;
                etWebSearchMaxResults.setText(String.valueOf(n));
            }
        } finally {
            v15SpinnerReady = wasReady;
        }
    }

    /**
     * v1.5.1+：主页视觉模型 + 副模型策略来自 active provider draft 的字段。
     * 由 applyConfigToUi 在切换 active provider 时和初始化时各调一次；
     * 切 provider 时会重新刷新可选模型列表（新 provider 可能 model 数变了）。
     */
    private void applyHomeCaptionFieldsToUi() {
        boolean wasReady = v15SpinnerReady;
        v15SpinnerReady = false;
        try {
            ProxyConfig.ProviderProfile active = getActiveProviderDraft();
            String currentSelection = active == null ? CAPTION_PROVIDER_NONE : safeString(active.captionProviderId, CAPTION_PROVIDER_NONE);
            refreshHomeCaptionProviderSpinner(currentSelection);
            String strategy = active == null
                    ? "inject"
                    : ProxyConfig.normalizeCaptionStrategy(active.captionStrategy);
            selectSpinnerByValue(spHomeCaptionStrategy, CAPTION_STRATEGY_VALUES, strategy);
            // v1.5.5+：CS-4 / CS-3 — max_tokens + 三态 quirk
            if (etCaptionMaxTokens != null) {
                int max = active == null || active.captionMaxTokens <= 0 ? 1024 : active.captionMaxTokens;
                etCaptionMaxTokens.setText(String.valueOf(max));
            }
            selectSpinnerByValue(spCaptionImageDetailMode, CAPTION_IMAGE_DETAIL_VALUES,
                    active == null ? "auto" : ProxyConfig.normalizeCaptionImageDetailMode(active.captionImageDetailMode));
            selectSpinnerByValue(spCaptionImagePlacementMode, CAPTION_IMAGE_PLACEMENT_VALUES,
                    active == null ? "auto" : ProxyConfig.normalizeCaptionImagePlacementMode(active.captionImagePlacementMode));
            selectSpinnerByValue(spCaptionImageUrlFormat, CAPTION_IMAGE_URL_FORMAT_VALUES,
                    active == null ? "auto" : ProxyConfig.normalizeCaptionImageUrlFormat(active.captionImageUrlFormat));
        } finally {
            v15SpinnerReady = wasReady;
        }
        refreshCaptionProviderEnabledByStrategy();
    }

    /** v1.5.1+：策略=关闭 时把视觉模型 Spinner 灰化（保留选中值），让用户视觉上知道当前策略下副模型不生效。 */
    private void refreshCaptionProviderEnabledByStrategy() {
        if (spHomeCaptionStrategy == null || spHomeCaptionProvider == null) return;
        String strategy = getSpinnerStringValueFromArray(spHomeCaptionStrategy, CAPTION_STRATEGY_VALUES, "inject");
        boolean enabled = !"off".equals(strategy);
        spHomeCaptionProvider.setEnabled(enabled);
        spHomeCaptionProvider.setAlpha(enabled ? 1f : 0.4f);
        if (btnHomeCaptionProvider != null) {
            btnHomeCaptionProvider.setEnabled(enabled);
            btnHomeCaptionProvider.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private void onKeepAliveHelpClicked() {
        if (!isIgnoringBatteryOptimizations()) {
            requestIgnoreBatteryOptimizations();
            return;
        }
        appendLog("当前已经忽略电池优化，已打开应用设置页，可继续手动开启后台活动/自启动");
        setActionStatus("电池优化已关闭，可继续设置后台活动");
        openAppDetailsSettings();
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            appendLog("当前系统版本无需申请忽略电池优化");
            setActionStatus("当前系统无需此设置");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            appendLog("已打开系统弹窗，申请忽略电池优化");
            setActionStatus("请在系统弹窗中允许忽略电池优化");
        } catch (Exception error) {
            appendLog("打开忽略电池优化弹窗失败，已改为打开设置页：" + error.getMessage());
            setActionStatus("已打开电池优化设置页");
            openBatteryOptimizationSettingsPage();
        }
    }

    private void openBatteryOptimizationSettingsPage() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            appendLog("已打开电池优化设置页");
            setActionStatus("请将本代理设为不受限制/忽略优化");
        } catch (Exception error) {
            appendLog("打开电池优化设置页失败：" + error.getMessage());
            setActionStatus("打开设置失败");
        }
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
            appendLog("已打开应用设置页，请手动开启后台运行/手动管理后台/自启动");
            setActionStatus("请在系统设置中手动开启后台保活");
        } catch (Exception error) {
            appendLog("打开应用设置页失败：" + error.getMessage());
            setActionStatus("打开设置失败");
        }
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String line = ts + "  " + msg;
            tvLogs.append(line + "\n");
            ProxyForegroundService.appendRecentLog(this, line);
            trimLogBuffer();
            scrollLogsToBottomIfNeeded();
        });
    }

    private void appendLogRaw(String line) {
        runOnUiThread(() -> {
            tvLogs.append(line + "\n");
            trimLogBuffer();
            scrollLogsToBottomIfNeeded();
        });
    }

    private void trimLogBuffer() {
        if (tvLogs.getText().length() > MAX_LOG_CHARS) {
            CharSequence text = tvLogs.getText();
            tvLogs.setText(text.subSequence(Math.max(0, text.length() - KEEP_LOG_CHARS), text.length()));
        }
    }

    private void syncLogsFromServiceCache() {
        String logs = ProxyForegroundService.getRecentLogs(this);
        if (!TextUtils.equals(tvLogs.getText(), logs)) {
            tvLogs.setText(logs);
            trimLogBuffer();
            scrollLogsToBottomIfNeeded();
        }
    }

    private void loadUiPreferences() {
        if (cbLogAutoScroll != null) {
            cbLogAutoScroll.setChecked(readUiBoolean(PREF_UI_LOG_AUTO_SCROLL, true));
        }
        updateThemeToggleUi();
    }

    private void applySavedNightMode() {
        AppCompatDelegate.setDefaultNightMode(readUiBoolean(PREF_UI_DARK_MODE, false)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    private void toggleThemeMode() {
        boolean darkMode = !readUiBoolean(PREF_UI_DARK_MODE, false);
        persistUiBoolean(PREF_UI_DARK_MODE, darkMode);
        updateThemeToggleUi();
        clearThemeDraftConfig();
        applyRuntimeThemeColors();
    }

    private void rememberUiDraftForThemeChange() {
        try {
            saveCurrentPersonaEditorDraft();
            pendingThemeUiDraftConfig = readConfigFromUi();
            persistThemeDraftConfig(pendingThemeUiDraftConfig);
        } catch (Exception ignored) {
            pendingThemeUiDraftConfig = null;
            clearThemeDraftConfig();
        }
    }

    private void restorePendingThemeDraftIfAny() {
        ProxyConfig draft = pendingThemeUiDraftConfig;
        pendingThemeUiDraftConfig = null;
        if (draft == null) {
            draft = loadThemeDraftConfig();
        }
        clearThemeDraftConfig();
        if (draft == null) {
            return;
        }
        applyConfigToUi(draft);
        // v1.6.0+：草稿与磁盘配置不一致时，applyConfigToUi 已经把 UI 切到 draft；
        // 自动保存机制（markConfigDirty 系列）会在用户后续动作时自然把改动写盘，
        // 不再需要单独维护"未保存"指示。
    }

    private void persistThemeDraftConfig(ProxyConfig config) {
        if (config == null) {
            clearThemeDraftConfig();
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(config.copy().ensureDefaults());
            String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_UI_THEME_DRAFT_CONFIG, encoded)
                    .commit();
        } catch (Exception ignored) {
            clearThemeDraftConfig();
        }
    }

    private ProxyConfig loadThemeDraftConfig() {
        String encoded = getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE)
                .getString(PREF_UI_THEME_DRAFT_CONFIG, "");
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT));
             ObjectInputStream input = new ObjectInputStream(bytes)) {
            Object decoded = input.readObject();
            if (decoded instanceof ProxyConfig) {
                return ((ProxyConfig) decoded).ensureDefaults();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void clearThemeDraftConfig() {
        getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE)
                .edit()
                .remove(PREF_UI_THEME_DRAFT_CONFIG)
                .commit();
    }

    private void updateThemeToggleUi() {
        if (btnThemeToggle == null) {
            return;
        }
        boolean darkMode = readUiBoolean(PREF_UI_DARK_MODE, false);
        btnThemeToggle.setText(darkMode ? "\u263E" : "\u2600\uFE0F");
        btnThemeToggle.setContentDescription(darkMode ? "切换到亮色模式" : "切换到暗色模式");
    }

    private void applyRuntimeThemeColors() {
        UiThemePalette palette = UiThemePalette.fromDarkMode(readUiBoolean(PREF_UI_DARK_MODE, false));
        getWindow().setStatusBarColor(palette.primaryDark);
        getWindow().setNavigationBarColor(palette.surface);
        View root = findViewById(R.id.drawerRoot);
        applyRuntimeThemeToView(root, palette);
        updateRuleToggleUi();
        updateEnableThinkingInputsEnabled();
        updateThemeToggleUi();
        String currentHomeSelection = currentHomeModelSelectionValue();
        updateHomeActiveModelSpinner();
        selectSpinnerValue(spHomeActiveModel, currentHomeSelection);
    }

    private void applyRuntimeThemeToView(View view, UiThemePalette palette) {
        if (view == null) {
            return;
        }
        int id = view.getId();
        if (id == R.id.drawerRoot
                || id == R.id.homeRoot
                || id == R.id.homeTopBar
                || id == R.id.settingsDrawer
                || id == R.id.settingsMenu
                || id == R.id.pageConnection
                || id == R.id.pageRulesDiary
                || id == R.id.pagePersona
                || id == R.id.pageMemory
                || id == R.id.pageRecords
                || id == R.id.pageWebSearch
                || id == R.id.layoutFullscreenEditor) {
            view.setBackgroundColor(palette.bg);
        } else if (id == R.id.settingsTopBar) {
            view.setBackgroundColor(palette.surface);
        } else if (id == R.id.tvLogs) {
            view.setBackgroundColor(palette.logBg);
        } else if (id == R.id.etFullscreenEditor) {
            view.setBackgroundColor(palette.bg);
        } else if (id == R.id.viewFastScrollThumb) {
            view.setBackgroundColor(palette.textSecondary);
        } else if (view instanceof LinearLayout
                || view instanceof android.widget.FrameLayout
                || view instanceof ScrollView) {
            view.setBackgroundColor(palette.bg);
        }

        if (view instanceof Button) {
            applyRuntimeButtonTheme((Button) view, palette);
        } else if (view instanceof CheckBox) {
            ((CheckBox) view).setTextColor(palette.textPrimary);
        } else if (view instanceof EditText) {
            applyRuntimeEditTextTheme((EditText) view, palette);
        } else if (view instanceof Spinner) {
            applyRuntimeSpinnerTheme((Spinner) view, palette);
        } else if (view instanceof TextView) {
            applyRuntimeTextTheme((TextView) view, palette);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRuntimeThemeToView(group.getChildAt(i), palette);
            }
        }
    }

    private void applyRuntimeButtonTheme(Button button, UiThemePalette palette) {
        int id = button.getId();
        int bg = palette.primarySoft;
        int text = palette.textPrimary;
        if (id == R.id.btnApiKeyCheck || id == R.id.btnFetchModels) {
            bg = palette.primary;
            text = Color.WHITE;
        }
        button.setBackgroundTintList(ColorStateList.valueOf(bg));
        button.setTextColor(text);
    }

    private void applyRuntimeEditTextTheme(EditText editText, UiThemePalette palette) {
        if (editText == tvLogs) {
            return;
        }
        boolean enabled = editText.isEnabled();
        editText.setTextColor(enabled ? palette.textPrimary : palette.textSecondary);
        editText.setHintTextColor(palette.textSecondary);
        editText.setBackground(buildRuntimeTextBoxBackground(palette));
        applyRuntimeEditTextPadding(editText);
    }

    private void applyRuntimeEditTextPadding(EditText editText) {
        int horizontal = dp(12);
        int vertical = dp(8);
        if (editText == etFullscreenEditor) {
            editText.setPadding(horizontal, vertical, dp(28), vertical);
            return;
        }
        editText.setPadding(horizontal, vertical, horizontal, vertical);
    }

    private void applyRuntimeSpinnerTheme(Spinner spinner, UiThemePalette palette) {
        if (spinner == null) return;
        styleSpinnerTextView(spinner.getSelectedView(), palette);
        if (spinner.getAdapter() instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) spinner.getAdapter()).notifyDataSetChanged();
        }
        spinner.invalidate();
    }

    private void applyRuntimeTextTheme(TextView textView, UiThemePalette palette) {
        int id = textView.getId();
        if (id == R.id.tvLogs) {
            textView.setTextColor(Color.rgb(185, 231, 255));
            return;
        }
        if (isSettingsMenuItemId(id)) {
            boolean selected = textView.getTypeface() != null && textView.getTypeface().isBold();
            textView.setBackground(buildRuntimeMenuItemBackground(palette, selected));
            textView.setTextColor(selected ? palette.primaryDark : palette.textPrimary);
            return;
        }
        if (id == R.id.tvRequestStatus
                || id == R.id.tvEndpoint
                || id == R.id.tvActionStatus
                || id == R.id.tvKeepAliveStatus
                || id == R.id.tvHistoryPath
                || id == R.id.btnClearLog
                || id == R.id.btnCopyLog
                || id == R.id.tvFullscreenCancel) {
            textView.setTextColor(palette.textSecondary);
            return;
        }
        if (id == R.id.btnThemeToggle
                || id == R.id.btnCheckUpdate
                || id == R.id.btnSendFeedback
                || id == R.id.tvFullscreenUndo
                || id == R.id.tvFullscreenSave
                || TextUtils.equals(textView.getText(), "编辑")) {
            textView.setTextColor(palette.primaryDark);
            return;
        }
        if (id == R.id.btnOpenLogsFullscreen) {
            textView.setTextColor(palette.textPrimary);
            try {
                textView.setCompoundDrawableTintList(ColorStateList.valueOf(palette.textPrimary));
            } catch (Throwable ignored) {
            }
            return;
        }
        textView.setTextColor(palette.textPrimary);
    }

    private boolean isSettingsMenuItemId(int id) {
        return id == R.id.menuConnection
                || id == R.id.menuRulesDiary
                || id == R.id.menuPersona
                || id == R.id.menuMemory
                || id == R.id.menuRecords
                || id == R.id.menuWebSearch;
    }

    private GradientDrawable buildRuntimeTextBoxBackground(UiThemePalette palette) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(palette.bg);
        drawable.setStroke(dp(1), palette.outline);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private LayerDrawable buildRuntimeMenuItemBackground(UiThemePalette palette, boolean selected) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(selected ? palette.primarySoft : palette.bg);

        GradientDrawable divider = new GradientDrawable();
        divider.setColor(palette.outline);

        LayerDrawable background = new LayerDrawable(new android.graphics.drawable.Drawable[]{fill, divider});
        background.setLayerInset(1, 0, dp(55), 0, 0);
        return background;
    }

    private static final class UiThemePalette {
        final int bg;
        final int surface;
        final int primary;
        final int primaryDark;
        final int primarySoft;
        final int secondary;
        final int textPrimary;
        final int textSecondary;
        final int outline;
        final int logBg;

        private UiThemePalette(
                int bg,
                int surface,
                int primary,
                int primaryDark,
                int primarySoft,
                int secondary,
                int textPrimary,
                int textSecondary,
                int outline,
                int logBg
        ) {
            this.bg = bg;
            this.surface = surface;
            this.primary = primary;
            this.primaryDark = primaryDark;
            this.primarySoft = primarySoft;
            this.secondary = secondary;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.outline = outline;
            this.logBg = logBg;
        }

        static UiThemePalette fromDarkMode(boolean darkMode) {
            if (darkMode) {
                return new UiThemePalette(
                        Color.rgb(17, 24, 39),
                        Color.rgb(31, 41, 55),
                        Color.rgb(96, 165, 250),
                        Color.rgb(147, 197, 253),
                        Color.rgb(30, 58, 95),
                        Color.rgb(45, 212, 191),
                        Color.rgb(248, 250, 252),
                        Color.rgb(203, 213, 225),
                        Color.rgb(51, 65, 85),
                        Color.rgb(2, 6, 23)
                );
            }
            return new UiThemePalette(
                    Color.rgb(238, 243, 248),
                    Color.WHITE,
                    Color.rgb(37, 99, 235),
                    Color.rgb(29, 78, 216),
                    Color.rgb(220, 233, 255),
                    Color.rgb(15, 118, 110),
                    Color.rgb(23, 32, 51),
                    Color.rgb(91, 100, 114),
                    Color.rgb(214, 223, 234),
                    Color.rgb(15, 23, 42)
            );
        }
    }

    private boolean readUiBoolean(String key, boolean fallback) {
        return getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE).getBoolean(key, fallback);
    }

    private void persistUiBoolean(String key, boolean value) {
        getSharedPreferences(ProxyConfig.PREF_NAME, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private boolean isLogAutoScrollEnabled() {
        return cbLogAutoScroll == null || cbLogAutoScroll.isChecked();
    }

    private void scrollLogsToBottomIfNeeded() {
        if (tvLogs == null || !isLogAutoScrollEnabled()) {
            return;
        }
        tvLogs.post(() -> {
            if (tvLogs.getLayout() != null) {
                int scrollAmount = tvLogs.getLayout().getLineTop(tvLogs.getLineCount()) - tvLogs.getHeight();
                tvLogs.scrollTo(0, Math.max(scrollAmount, 0));
            } else {
                tvLogs.scrollTo(0, tvLogs.getBottom());
            }
        });
    }

    private void copyLogsToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            appendLog("复制日志失败：剪贴板不可用");
            return;
        }
        String logs = tvLogs.getText() == null ? "" : tvLogs.getText().toString();
        clipboard.setPrimaryClip(ClipData.newPlainText("ProxyLogs", logs));
        appendLog("日志已复制，长度=" + logs.length());
    }

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确定要清空当前运行日志吗？")
                .setPositiveButton("清空", (dialog, which) -> {
                    tvLogs.setText("");
                    ProxyForegroundService.clearRecentLogs(this);
                    appendLog("日志已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int safeInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    private String editTextValue(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString();
    }

    private String safeString(String text, String fallback) {
        return TextUtils.isEmpty(text) ? fallback : text.trim();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_POST_NOTIFICATIONS) {
            return;
        }
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            appendLog("通知权限已允许");
            setActionStatus("通知权限已允许");
            if (pendingStartAfterNotificationPermission) {
                startServer();
            }
        } else {
            appendLog("通知权限未允许，前台服务通知可能无法正常显示");
            setActionStatus("通知权限未允许");
        }
        pendingStartAfterNotificationPermission = false;
    }

    private static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }

    private static final class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
        private final StringSelectionHandler consumer;

        SimpleItemSelectedListener(StringSelectionHandler consumer) {
            this.consumer = consumer;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (consumer != null && parent != null) {
                Object item = parent.getItemAtPosition(position);
                consumer.accept(item instanceof SpinnerOption ? ((SpinnerOption) item).value : String.valueOf(item));
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private interface StringSelectionHandler {
        void accept(String value);
    }

    private interface ProfileNameHandler {
        void accept(String value);
    }

    private static final class SpinnerOption {
        final String value;
        final String label;

        SpinnerOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
