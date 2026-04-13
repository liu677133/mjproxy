package com.diaryproxy.app;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_LOG_CHARS = 120000;
    private static final int KEEP_LOG_CHARS = 90000;

    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etPort;
    private EditText etTimeout;
    private Spinner spUpstreamProxyType;
    private EditText etUpstreamProxyHost;
    private EditText etUpstreamProxyPort;
    private Spinner spStrictness;
    private Spinner spAdapterPreset;
    private Spinner spPersonaProfile;
    private Spinner spPersonaTier;
    private EditText etMinLength;
    private EditText etMinLines;
    private EditText etPrefixes;
    private EditText etKeywords;
    private EditText etNormalTemplate;
    private EditText etHolidayTemplate;
    private EditText etOverrideMaxTokens;
    private EditText etMaxChars;
    private EditText etListenChatPaths;
    private EditText etListenModelsPaths;
    private EditText etUpstreamChatPath;
    private EditText etUpstreamModelsPath;
    private EditText etRequestMessagesPath;
    private EditText etRequestUserTextPath;
    private EditText etRequestModelPath;
    private EditText etRequestMaxTokensPath;
    private EditText etResponseTextPath;
    private EditText etPersonaJson;
    private CheckBox cbDetectionEnabled;
    private CheckBox cbDryRun;
    private CheckBox cbTruncate;
    private CheckBox cbSaveNormalHistory;
    private CheckBox cbSaveDiaryHistory;
    private CheckBox cbDebugPromptDump;
    private CheckBox cbStripRestrictionLine;
    private CheckBox cbStripSystemTime;
    private CheckBox cbPersonaEnabled;
    private TextView tvStatus;
    private TextView tvRequestStatus;
    private TextView tvActionStatus;
    private TextView tvEndpoint;
    private TextView tvHistoryPath;
    private TextView tvKeepAliveStatus;
    private TextView tvLogs;
    private TextView tvRulesStatus;
    private ScrollView scrollRoot;
    private Button btnStartStop;
    private Button btnToggleRules;
    private Button btnReplayLastRequest;
    private Button btnKeepAliveHelp;
    private Button btnPersonaAdd;
    private Button btnPersonaCopy;
    private Button btnPersonaRename;
    private Button btnPersonaDelete;
    private LinearLayout layoutMatchingRules;
    private LinearLayout layoutGenericFields;

    private boolean receiverRegistered = false;
    private boolean personaSpinnerReady = false;
    private boolean personaProfileSpinnerReady = false;
    private boolean apiKeyVisible = false;
    private boolean suppressAdapterAutoPaths = false;
    private String currentPersonaProfileId = ProxyConfig.DEFAULT_PERSONA_PROFILE_ID;
    private String currentPersonaTier = ProxyConfig.TIER_SISTER_HIGH;
    private String lastAdapterPreset = "";
    private boolean guardianRunning = false;
    private final Map<String, String> personaProfileNames = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, String>> personaProfileDrafts = new LinkedHashMap<>();

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
                long lastChatRequestAt = intent.getLongExtra(ProxyForegroundService.EXTRA_LAST_CHAT_REQUEST_AT, 0L);
                updateRunningUi(new ProxyForegroundService.ServiceStateSnapshot(guardianRunning, serverRunning, lastChatRequestAt));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initSpinners();
        setupLogScrolling();
        setupScrollableFields();
        setupListeners();
        applyApiKeyVisibility();
        hideObsoleteProtocolHints();

        ProxyConfig config = ProxyConfig.load(this);
        applyConfigToUi(config);
        hideObsoleteProtocolHints();
        updateKeepAliveStatus();
        syncLogsFromServiceCache();
        updateRunningUi(ProxyForegroundService.getCachedState(this));
        setActionStatus("就绪");
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerServiceReceiver();
        updateKeepAliveStatus();
        syncLogsFromServiceCache();
        updateRunningUi(ProxyForegroundService.getCachedState(this));
    }

    @Override
    protected void onStop() {
        unregisterServiceReceiver();
        super.onStop();
    }

    private void bindViews() {
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        etPort = findViewById(R.id.etPort);
        etTimeout = findViewById(R.id.etTimeout);
        spUpstreamProxyType = findViewById(R.id.spUpstreamProxyType);
        etUpstreamProxyHost = findViewById(R.id.etUpstreamProxyHost);
        etUpstreamProxyPort = findViewById(R.id.etUpstreamProxyPort);
        spStrictness = findViewById(R.id.spStrictness);
        spAdapterPreset = findViewById(R.id.spAdapterPreset);
        spPersonaProfile = findViewById(R.id.spPersonaProfile);
        spPersonaTier = findViewById(R.id.spPersonaTier);
        etMinLength = findViewById(R.id.etMinLength);
        etMinLines = findViewById(R.id.etMinLines);
        etPrefixes = findViewById(R.id.etPrefixes);
        etKeywords = findViewById(R.id.etKeywords);
        etNormalTemplate = findViewById(R.id.etNormalTemplate);
        etHolidayTemplate = findViewById(R.id.etHolidayTemplate);
        etOverrideMaxTokens = findViewById(R.id.etOverrideMaxTokens);
        etMaxChars = findViewById(R.id.etMaxChars);
        etListenChatPaths = findViewById(R.id.etListenChatPaths);
        etListenModelsPaths = findViewById(R.id.etListenModelsPaths);
        etUpstreamChatPath = findViewById(R.id.etUpstreamChatPath);
        etUpstreamModelsPath = findViewById(R.id.etUpstreamModelsPath);
        etRequestMessagesPath = findViewById(R.id.etRequestMessagesPath);
        etRequestUserTextPath = findViewById(R.id.etRequestUserTextPath);
        etRequestModelPath = findViewById(R.id.etRequestModelPath);
        etRequestMaxTokensPath = findViewById(R.id.etRequestMaxTokensPath);
        etResponseTextPath = findViewById(R.id.etResponseTextPath);
        etPersonaJson = findViewById(R.id.etPersonaJson);
        cbDetectionEnabled = findViewById(R.id.cbDetectionEnabled);
        cbDryRun = findViewById(R.id.cbDryRun);
        cbTruncate = findViewById(R.id.cbTruncate);
        cbSaveNormalHistory = findViewById(R.id.cbSaveNormalHistory);
        cbSaveDiaryHistory = findViewById(R.id.cbSaveDiaryHistory);
        cbDebugPromptDump = findViewById(R.id.cbDebugPromptDump);
        cbStripRestrictionLine = findViewById(R.id.cbStripRestrictionLine);
        cbStripSystemTime = findViewById(R.id.cbStripSystemTime);
        cbPersonaEnabled = findViewById(R.id.cbPersonaEnabled);
        tvStatus = findViewById(R.id.tvStatus);
        tvRequestStatus = findViewById(R.id.tvRequestStatus);
        tvActionStatus = findViewById(R.id.tvActionStatus);
        tvEndpoint = findViewById(R.id.tvEndpoint);
        tvHistoryPath = findViewById(R.id.tvHistoryPath);
        tvKeepAliveStatus = findViewById(R.id.tvKeepAliveStatus);
        tvLogs = findViewById(R.id.tvLogs);
        tvRulesStatus = findViewById(R.id.tvRulesStatus);
        scrollRoot = findViewById(R.id.scrollRoot);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnToggleRules = findViewById(R.id.btnToggleRules);
        btnReplayLastRequest = findViewById(R.id.btnReplayLastRequest);
        btnKeepAliveHelp = findViewById(R.id.btnKeepAliveHelp);
        btnPersonaAdd = findViewById(R.id.btnPersonaAdd);
        btnPersonaCopy = findViewById(R.id.btnPersonaCopy);
        btnPersonaRename = findViewById(R.id.btnPersonaRename);
        btnPersonaDelete = findViewById(R.id.btnPersonaDelete);
        layoutMatchingRules = findViewById(R.id.layoutMatchingRules);
        layoutGenericFields = findViewById(R.id.layoutGenericFields);
    }

    private void initSpinners() {
        setupSpinner(spUpstreamProxyType, R.array.upstream_proxy_type_items, R.array.upstream_proxy_type_labels);
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
        ArrayAdapter<SpinnerOption> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setupLogScrolling() {
        tvLogs.setMovementMethod(new ScrollingMovementMethod());
        tvLogs.setOnTouchListener(this::allowNestedScroll);
    }

    private void setupScrollableFields() {
        EditText[] fields = new EditText[]{
                etPrefixes, etKeywords, etNormalTemplate, etHolidayTemplate,
                etListenChatPaths, etListenModelsPaths, etRequestMessagesPath,
                etRequestUserTextPath, etRequestModelPath, etRequestMaxTokensPath,
                etResponseTextPath, etPersonaJson
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
        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfigFromUi());
        findViewById(R.id.btnFetchModels).setOnClickListener(v -> fetchModelsFromUpstream());
        btnStartStop.setOnClickListener(v -> {
            if (!guardianRunning) {
                startServer();
            } else {
                stopServer();
            }
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> testUpstream());
        btnReplayLastRequest.setOnClickListener(v -> confirmReplayLastRequest());
        btnKeepAliveHelp.setOnClickListener(v -> onKeepAliveHelpClicked());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            tvLogs.setText("");
            ProxyForegroundService.clearRecentLogs();
        });
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLogsToClipboard());
        btnToggleRules.setOnClickListener(v -> toggleMatchingRules());
        cbDetectionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> updateRuleToggleUi());
        etApiKey.setOnTouchListener(this::handleApiKeyVisibilityTouch);
        spAdapterPreset.setOnItemSelectedListener(new SimpleItemSelectedListener(this::onAdapterPresetSelected));
        spPersonaProfile.setOnItemSelectedListener(new SimpleItemSelectedListener(this::onPersonaProfileSelected));
        spPersonaTier.setOnItemSelectedListener(new SimpleItemSelectedListener(this::onPersonaTierSelected));
        btnPersonaAdd.setOnClickListener(v -> promptCreatePersonaProfile(false));
        btnPersonaCopy.setOnClickListener(v -> promptCreatePersonaProfile(true));
        btnPersonaRename.setOnClickListener(v -> promptRenamePersonaProfile());
        btnPersonaDelete.setOnClickListener(v -> promptDeletePersonaProfile());
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
        etBaseUrl.setText(cfg.upstreamBaseUrl);
        etApiKey.setText(cfg.apiKey);
        apiKeyVisible = false;
        applyApiKeyVisibility();
        etModel.setText(cfg.model);
        etPort.setText(String.valueOf(cfg.port));
        etTimeout.setText(String.valueOf(cfg.timeoutMs));
        selectSpinnerValue(spUpstreamProxyType, cfg.resolvedUpstreamProxyType());
        etUpstreamProxyHost.setText(cfg.upstreamProxyHost);
        etUpstreamProxyPort.setText(cfg.upstreamProxyPort > 0 ? String.valueOf(cfg.upstreamProxyPort) : "");
        etMinLength.setText(String.valueOf(cfg.minContentLength));
        etMinLines.setText(String.valueOf(cfg.minDialogueLines));
        etPrefixes.setText(cfg.prefixesText);
        etKeywords.setText(cfg.keywordsText);
        etNormalTemplate.setText(cfg.normalTemplate);
        etHolidayTemplate.setText(cfg.holidayTemplate);
        etOverrideMaxTokens.setText(String.valueOf(cfg.overrideMaxTokens));
        etMaxChars.setText(String.valueOf(cfg.maxChars));
        etListenChatPaths.setText(cfg.listenChatPaths);
        etListenModelsPaths.setText(cfg.listenModelsPaths);
        etUpstreamChatPath.setText(cfg.upstreamChatPath);
        etUpstreamModelsPath.setText(cfg.upstreamModelsPath);
        etRequestMessagesPath.setText(cfg.requestMessagesPath);
        etRequestUserTextPath.setText(cfg.requestUserTextPath);
        etRequestModelPath.setText(cfg.requestModelPath);
        etRequestMaxTokensPath.setText(cfg.requestMaxTokensPath);
        etResponseTextPath.setText(cfg.responseTextPath);
        cbDetectionEnabled.setChecked(cfg.detectionEnabled);
        cbDryRun.setChecked(cfg.dryRun);
        cbTruncate.setChecked(cfg.truncateEnabled);
        cbSaveNormalHistory.setChecked(cfg.saveNormalChatHistory);
        cbSaveDiaryHistory.setChecked(cfg.saveDiaryHistory);
        cbDebugPromptDump.setChecked(cfg.debugPromptDumpEnabled);
        cbStripRestrictionLine.setChecked(cfg.stripRestrictionLineEnabled);
        cbStripSystemTime.setChecked(cfg.stripSystemTimeEnabled);
        cbPersonaEnabled.setChecked(cfg.personaEnabled);

        selectSpinnerValue(spStrictness, cfg.strictness);
        selectSpinnerValue(spAdapterPreset, cfg.adapterPreset);

        personaProfileSpinnerReady = false;
        personaSpinnerReady = false;
        personaProfileNames.clear();
        personaProfileDrafts.clear();
        for (ProxyConfig.PersonaProfile profile : cfg.getPersonaProfiles()) {
            personaProfileNames.put(profile.id, profile.name);
            LinkedHashMap<String, String> tierDrafts = new LinkedHashMap<>();
            for (String tier : ProxyConfig.PERSONA_TIERS) {
                tierDrafts.put(tier, profile.getTierJson(tier));
            }
            personaProfileDrafts.put(profile.id, tierDrafts);
        }
        updatePersonaProfileSpinner();
        currentPersonaProfileId = safeString(cfg.activePersonaProfileId, ProxyConfig.DEFAULT_PERSONA_PROFILE_ID);
        if (!personaProfileDrafts.containsKey(currentPersonaProfileId) && !personaProfileDrafts.isEmpty()) {
            currentPersonaProfileId = personaProfileDrafts.keySet().iterator().next();
        }
        selectSpinnerValue(spPersonaProfile, currentPersonaProfileId);
        currentPersonaTier = ProxyConfig.TIER_SISTER_HIGH;
        selectSpinnerValue(spPersonaTier, currentPersonaTier);
        etPersonaJson.setText(getCurrentPersonaDraft(currentPersonaTier));
        personaProfileSpinnerReady = true;
        personaSpinnerReady = true;

        updateRuleToggleUi();
        updateAdapterUi(false);
        lastAdapterPreset = cfg.adapterPreset;
        suppressAdapterAutoPaths = false;
        updateEndpoint(cfg);
        updateHistoryPathSummary();
        if (scrollRoot != null) {
            scrollRoot.post(() -> {
                scrollRoot.scrollTo(0, 0);
                scrollRoot.requestFocus();
            });
        }
    }

    private void saveConfigFromUi() {
        saveCurrentPersonaDraft();
        String validationError = validatePersonaDrafts();
        if (!TextUtils.isEmpty(validationError)) {
            setActionStatus("保存失败");
            appendLog("人设 JSON 不合法：" + validationError);
            return;
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
    }

    private ProxyConfig readConfigFromUi() {
        saveCurrentPersonaDraft();
        ProxyConfig cfg = ProxyConfig.load(this);
        cfg.upstreamBaseUrl = safeString(etBaseUrl.getText().toString(), ProxyConfig.DEFAULT_BASE_URL);
        cfg.apiKey = etApiKey.getText().toString().trim();
        cfg.model = safeString(etModel.getText().toString(), ProxyConfig.DEFAULT_MODEL);
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
        cfg.normalTemplate = etNormalTemplate.getText().toString();
        cfg.holidayTemplate = etHolidayTemplate.getText().toString();
        cfg.overrideMaxTokens = safeInt(etOverrideMaxTokens.getText().toString(), 0);
        cfg.maxChars = safeInt(etMaxChars.getText().toString(), ProxyConfig.DEFAULT_MAX_CHARS);
        cfg.listenChatPaths = etListenChatPaths.getText().toString();
        cfg.listenModelsPaths = etListenModelsPaths.getText().toString();
        cfg.upstreamChatPath = safeString(etUpstreamChatPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_CHAT_PATH);
        cfg.upstreamModelsPath = safeString(etUpstreamModelsPath.getText().toString(), ProxyConfig.DEFAULT_UPSTREAM_MODELS_PATH);
        cfg.requestMessagesPath = safeString(etRequestMessagesPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MESSAGES_PATH);
        cfg.requestUserTextPath = safeString(etRequestUserTextPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_USER_TEXT_PATH);
        cfg.requestModelPath = safeString(etRequestModelPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MODEL_PATH);
        cfg.requestMaxTokensPath = safeString(etRequestMaxTokensPath.getText().toString(), ProxyConfig.DEFAULT_REQUEST_MAX_TOKENS_PATH);
        cfg.responseTextPath = safeString(etResponseTextPath.getText().toString(), ProxyConfig.DEFAULT_RESPONSE_TEXT_PATH);
        cfg.detectionEnabled = cbDetectionEnabled.isChecked();
        cfg.dryRun = cbDryRun.isChecked();
        cfg.truncateEnabled = cbTruncate.isChecked();
        cfg.saveNormalChatHistory = cbSaveNormalHistory.isChecked();
        cfg.saveDiaryHistory = cbSaveDiaryHistory.isChecked();
        cfg.debugPromptDumpEnabled = cbDebugPromptDump.isChecked();
        cfg.stripRestrictionLineEnabled = cbStripRestrictionLine.isChecked();
        cfg.stripSystemTimeEnabled = cbStripSystemTime.isChecked();
        cfg.personaEnabled = cbPersonaEnabled.isChecked();
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
        return cfg.ensureDefaults();
    }

    private void toggleMatchingRules() {
        layoutMatchingRules.setVisibility(layoutMatchingRules.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        updateRuleToggleUi();
    }

    private void updateRuleToggleUi() {
        boolean visible = layoutMatchingRules.getVisibility() == View.VISIBLE;
        btnToggleRules.setText(visible ? "收起日记识别规则" : "展开日记识别规则");
        tvRulesStatus.setText(cbDetectionEnabled.isChecked() ? "日记识别：已开启" : "日记识别：已关闭");
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

    private void onPersonaTierSelected(String tier) {
        if (TextUtils.isEmpty(tier)) {
            return;
        }
        if (personaSpinnerReady) {
            saveCurrentPersonaDraft();
        }
        currentPersonaTier = tier;
        etPersonaJson.setText(getCurrentPersonaDraft(tier));
    }

    private void saveCurrentPersonaDraft() {
        if (etPersonaJson != null && !TextUtils.isEmpty(currentPersonaTier)) {
            getCurrentPersonaDraftMap().put(currentPersonaTier, etPersonaJson.getText().toString());
        }
    }

    private String validatePersonaDrafts() {
        for (Map.Entry<String, String> profileEntry : personaProfileNames.entrySet()) {
            String profileId = profileEntry.getKey();
            String profileName = safeString(profileEntry.getValue(), profileId);
            LinkedHashMap<String, String> tierDrafts = personaProfileDrafts.get(profileId);
            for (String tier : ProxyConfig.PERSONA_TIERS) {
                String raw = tierDrafts == null ? "" : tierDrafts.get(tier);
                if (TextUtils.isEmpty(raw)) {
                    return profileName + " / " + tier + "：内容为空";
                }
                try {
                    new JSONObject(raw);
                } catch (Exception error) {
                    return profileName + " / " + tier + "：" + error.getMessage();
                }
            }
        }
        return "";
    }

    private void updatePersonaProfileSpinner() {
        ArrayList<SpinnerOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : personaProfileNames.entrySet()) {
            options.add(new SpinnerOption(entry.getKey(), safeString(entry.getValue(), entry.getKey())));
        }
        setSpinnerOptions(spPersonaProfile, options);
        updatePersonaProfileButtonState();
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
            saveCurrentPersonaDraft();
        }
        currentPersonaProfileId = profileId;
        etPersonaJson.setText(getCurrentPersonaDraft(currentPersonaTier));
        updatePersonaProfileButtonState();
    }

    private void promptCreatePersonaProfile(boolean copyCurrent) {
        saveCurrentPersonaDraft();
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
                    etPersonaJson.setText(getCurrentPersonaDraft(currentPersonaTier));
                    appendLog((copyCurrent ? "已复制人设方案：" : "已新增人设方案：") + inputName + "，记得保存配置");
                    setActionStatus(copyCurrent ? "已复制人设方案" : "已新增人设方案");
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
                    appendLog("已重命名当前人设方案为：" + inputName + "，记得保存配置");
                    setActionStatus("已重命名人设方案");
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
                    saveCurrentPersonaDraft();
                    personaProfileNames.remove(deletingId);
                    personaProfileDrafts.remove(deletingId);
                    currentPersonaProfileId = personaProfileNames.keySet().iterator().next();
                    personaProfileSpinnerReady = false;
                    updatePersonaProfileSpinner();
                    selectSpinnerValue(spPersonaProfile, currentPersonaProfileId);
                    personaProfileSpinnerReady = true;
                    etPersonaJson.setText(getCurrentPersonaDraft(currentPersonaTier));
                    appendLog("已删除人设方案：" + deletingName + "，记得保存配置");
                    setActionStatus("已删除人设方案");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showProfileNameDialog(String title, String presetValue, ProfileNameHandler handler) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("请输入方案名称");
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
        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (!ensureUpstreamActionAllowed(cfg, "启动代理")) {
            return;
        }
        requestServiceAction(ProxyForegroundService.ACTION_START);
        appendLog("已请求启动代理服务");
        setActionStatus("正在启动代理");
        updateEndpoint(cfg);
    }

    private void stopServer() {
        requestServiceAction(ProxyForegroundService.ACTION_STOP);
        appendLog("已请求停止代理服务");
        setActionStatus("正在停止代理");
    }

    private void testUpstream() {
        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (!ensureUpstreamActionAllowed(cfg, "测试上游")) {
            return;
        }
        setActionStatus("正在测试上游连通性");
        new Thread(() -> {
            String result = DiaryProxyServer.performUpstreamTest(cfg);
            runOnUiThread(() -> {
                appendLog("上游测试结果：" + result);
                setActionStatus("上游测试完成");
            });
        }).start();
    }

    private void fetchModelsFromUpstream() {
        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (!ensureUpstreamActionAllowed(cfg, "获取模型列表")) {
            return;
        }
        setActionStatus("正在获取模型列表");
        appendLog("开始请求上游模型列表");
        new Thread(() -> {
            DiaryProxyServer.ModelFetchResult result = DiaryProxyServer.fetchModels(cfg);
            runOnUiThread(() -> showModelFetchResult(result));
        }).start();
    }

    private void showModelFetchResult(DiaryProxyServer.ModelFetchResult result) {
        if (result == null) {
            setActionStatus("获取模型列表失败");
            appendLog("获取模型列表失败：返回为空");
            return;
        }
        if (!result.success) {
            setActionStatus("获取模型列表失败");
            appendLog("获取模型列表失败：" + result.message);
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
        CharSequence[] items = result.models.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < result.models.size()) {
                        String selected = result.models.get(which);
                        etModel.setText(selected);
                        appendLog("已选择模型：" + selected);
                        setActionStatus("已选择模型，记得保存配置");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void replayLastRequest() {
        ProxyConfig cfg = readConfigFromUi();
        ProxyConfig.save(this, cfg);
        if (!ensureUpstreamActionAllowed(cfg, "重发上一次日记请求")) {
            return;
        }
        setActionStatus("正在重发上一次日记请求");
        appendLog("开始重发上一次日记请求");
        new Thread(() -> {
            DiaryProxyServer.ReplayResult result = DiaryProxyServer.replayLastDiaryRequest(this, cfg, this::appendLog);
            runOnUiThread(() -> {
                if (result.success) {
                    appendLog(result.message);
                    setActionStatus("重发完成");
                } else {
                    appendLog("重发失败：" + result.message);
                    setActionStatus("重发失败");
                }
            });
        }).start();
    }

    private void confirmReplayLastRequest() {
        new AlertDialog.Builder(this)
                .setTitle("确认重发日记")
                .setMessage("这会使用当前配置重发最近一次缓存的日记请求。\n\n注意：代理无法在没有游戏新请求的情况下主动把结果推回游戏。")
                .setPositiveButton("继续重发", (dialog, which) -> replayLastRequest())
                .setNegativeButton("取消", null)
                .show();
    }

    private void registerServiceReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ProxyForegroundService.ACTION_LOG_EVENT);
        filter.addAction(ProxyForegroundService.ACTION_STATE_EVENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceEventReceiver, filter);
        }
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

        if (safe.hasRecentChatRequest()) {
            tvRequestStatus.setText("最近聊天请求：最近 2 分钟内已检测到入站聊天请求");
        } else {
            tvRequestStatus.setText("最近聊天请求：最近 2 分钟内暂无入站聊天请求");
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
            tvLogs.append(ts + "  " + msg + "\n");
            trimLogBuffer();
        });
    }

    private void appendLogRaw(String line) {
        runOnUiThread(() -> {
            tvLogs.append(line + "\n");
            trimLogBuffer();
        });
    }

    private void trimLogBuffer() {
        if (tvLogs.getText().length() > MAX_LOG_CHARS) {
            CharSequence text = tvLogs.getText();
            tvLogs.setText(text.subSequence(Math.max(0, text.length() - KEEP_LOG_CHARS), text.length()));
        }
    }

    private void syncLogsFromServiceCache() {
        String logs = ProxyForegroundService.getRecentLogs();
        if (!TextUtils.equals(tvLogs.getText(), logs)) {
            tvLogs.setText(logs);
            trimLogBuffer();
        }
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

    private int safeInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String safeString(String text, String fallback) {
        return TextUtils.isEmpty(text) ? fallback : text.trim();
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
