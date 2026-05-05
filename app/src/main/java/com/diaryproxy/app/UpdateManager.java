package com.diaryproxy.app;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UpdateManager {

    private static final String PREF_DISMISSED_RELEASE_VERSION_CODE = "dismissedReleaseVersionCode";
    private static final String PREF_UPDATE_DOWNLOAD_ID = "updateDownloadId";
    private static final String PREF_UPDATE_DOWNLOAD_URL_INDEX = "updateDownloadUrlIndex";
    private static final String PREF_UPDATE_DOWNLOAD_MANIFEST = "updateDownloadManifest";
    private static final String PREF_UPDATE_DOWNLOAD_PATH = "updateDownloadPath";
    private static final String PREF_UPDATE_VERIFIED_APK_PATH = "verifiedUpdateApkPath";
    private static final String CHANNEL_ID = "diary_proxy_updates";
    private static final int NOTIFICATION_ID_READY = 41051;

    private UpdateManager() {
    }

    static boolean shouldAutoCheck(ProxyConfig config) {
        return config != null
                && !config.getUpdateManifestUrlList().isEmpty()
                && !TextUtils.isEmpty(config.normalizedUpdateManifestPublicKey());
    }

    static void markDismissedRelease(Context context, int versionCode) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_DISMISSED_RELEASE_VERSION_CODE, versionCode)
                .apply();
    }

    static void installVerifiedApk(Context context, String filePath) {
        if (context == null || TextUtils.isEmpty(filePath)) {
            return;
        }
        File file = new File(filePath);
        if (!file.isFile()) {
            FeedbackSupport.recordOperationalError(context, "update_install", "更新包不存在", filePath);
            return;
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    static CheckResult checkForUpdates(Context context, ProxyConfig config, boolean manual) {
        if (context == null) {
            return CheckResult.failure("当前上下文为空。");
        }
        ProxyConfig safeConfig = config == null ? ProxyConfig.load(context) : config.copy();
        List<String> manifestUrls = safeConfig.getUpdateManifestUrlList();
        if (manifestUrls.isEmpty()) {
            return manual
                    ? CheckResult.failure("请先填写更新清单地址。")
                    : CheckResult.skipped("未配置更新清单地址，已跳过自动检查。");
        }
        String publicKey = safeConfig.normalizedUpdateManifestPublicKey();
        if (TextUtils.isEmpty(publicKey)) {
            return manual
                    ? CheckResult.failure("请先填写 update.json 验签公钥。")
                    : CheckResult.skipped("未配置更新公钥，已跳过自动检查。");
        }

        String lastFailure = "未能获取更新信息。";
        for (String manifestUrl : manifestUrls) {
            String validationError = validateHttpsUrl(manifestUrl, "更新清单地址");
            if (!TextUtils.isEmpty(validationError)) {
                lastFailure = validationError;
                continue;
            }
            try {
                String body = fetchText(manifestUrl);
                UpdateManifest manifest = UpdateManifest.fromJson(new JSONObject(body));
                verifyManifest(manifest, publicKey);
                InstalledVersion installed = getInstalledVersion(context);
                int compare = compareManifestAgainstInstalled(manifest, installed);
                if (compare <= 0) {
                    return CheckResult.upToDate("当前已经是最新版本。", manifest, manifestUrl);
                }
                return CheckResult.updateAvailable("检测到新版本。", manifest, manifestUrl);
            } catch (Exception error) {
                lastFailure = "读取 " + manifestUrl + " 失败：" + safe(error.getMessage());
            }
        }
        return manual ? CheckResult.failure(lastFailure) : CheckResult.skipped(lastFailure);
    }

    static StartDownloadResult startDownload(Context context, UpdateManifest manifest) {
        if (context == null || manifest == null) {
            return StartDownloadResult.failure("更新信息为空。");
        }
        try {
            return enqueueDownload(context, manifest, 0);
        } catch (Exception error) {
            return StartDownloadResult.failure("启动下载失败：" + safe(error.getMessage()));
        }
    }

    static void handleDownloadCompleted(Context context, long downloadId) {
        if (context == null || downloadId <= 0) {
            return;
        }
        SharedPreferences sp = context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE);
        long trackedId = sp.getLong(PREF_UPDATE_DOWNLOAD_ID, -1L);
        if (trackedId != downloadId) {
            return;
        }
        UpdateManifest manifest = loadTrackedManifest(context);
        if (manifest == null) {
            clearTrackedDownload(context);
            return;
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            FeedbackSupport.recordOperationalError(context, "update_download", "系统下载服务不可用", "");
            clearTrackedDownload(context);
            return;
        }
        // v1.5.6+：UM-3 — 入口处快照"本次正在跟踪的下载产物路径"，作为 tryNextMirrorOrFail
        // 的 failedPath 显式传入，避免 SP 在 enqueueDownload 中被改写后取到下一轮的路径。
        final String trackedPath = sp.getString(PREF_UPDATE_DOWNLOAD_PATH, "");
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                tryNextMirrorOrFail(context, manifest, sp.getInt(PREF_UPDATE_DOWNLOAD_URL_INDEX, 0),
                        "下载记录不存在。", trackedPath);
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String storedPath = sp.getString(PREF_UPDATE_DOWNLOAD_PATH, "");
                File target = TextUtils.isEmpty(storedPath) ? null : new File(storedPath);
                String verificationError = verifyDownloadedApk(context, manifest, target);
                if (!TextUtils.isEmpty(verificationError)) {
                    deleteIfExists(target);
                    // 已经显式 deleteIfExists(target)，failedPath 传 null 避免重复删
                    tryNextMirrorOrFail(context, manifest, sp.getInt(PREF_UPDATE_DOWNLOAD_URL_INDEX, 0),
                            verificationError, null);
                    return;
                }
                sp.edit()
                        .putString(PREF_UPDATE_VERIFIED_APK_PATH, target == null ? "" : target.getAbsolutePath())
                        .remove(PREF_UPDATE_DOWNLOAD_ID)
                        .remove(PREF_UPDATE_DOWNLOAD_URL_INDEX)
                        .remove(PREF_UPDATE_DOWNLOAD_MANIFEST)
                        .remove(PREF_UPDATE_DOWNLOAD_PATH)
                        .apply();
                showInstallReadyNotification(context, manifest, target);
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                tryNextMirrorOrFail(context, manifest, sp.getInt(PREF_UPDATE_DOWNLOAD_URL_INDEX, 0),
                        "下载失败，reason=" + reason, trackedPath);
            }
        } catch (Exception error) {
            tryNextMirrorOrFail(context, manifest, sp.getInt(PREF_UPDATE_DOWNLOAD_URL_INDEX, 0),
                    "下载校验失败：" + safe(error.getMessage()), trackedPath);
        }
    }

    private static StartDownloadResult enqueueDownload(Context context, UpdateManifest manifest, int startIndex) throws Exception {
        List<String> urls = manifest.getValidDownloadUrls();
        if (urls.isEmpty()) {
            return StartDownloadResult.failure("更新清单里没有可用的 https 下载地址。");
        }
        if (startIndex >= urls.size()) {
            return StartDownloadResult.failure("所有下载镜像都已尝试失败。");
        }
        String targetUrl = urls.get(startIndex);
        String validationError = validateHttpsUrl(targetUrl, "更新下载地址");
        if (!TextUtils.isEmpty(validationError)) {
            return StartDownloadResult.failure(validationError);
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return StartDownloadResult.failure("系统下载服务不可用。");
        }

        File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mjproxy");
        // v1.5.4+：UM-1 — Android 11+ scope storage 下应用直接在公共 Download 目录 mkdirs 可能因厂商
        // ROM 权限策略失败；但 DownloadManager 是系统服务，调 setDestinationInExternalPublicDir 时
        // 系统会代我们建目录。所以 mkdirs 失败不再视为致命错误：仅在 dir 已存在/创建成功时跳过；
        // 否则允许继续，由 DownloadManager.enqueue 决定是否真能写入。
        if (!targetDir.isDirectory()) {
            try {
                targetDir.mkdirs();
            } catch (Throwable ignored) {
                // 忽略 SecurityException 等，让 DownloadManager 自己 handle
            }
        }
        File targetFile = new File(targetDir, "mjproxy-" + manifest.versionCode + ".apk");
        deleteIfExists(targetFile);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(targetUrl));
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle("妹居代理更新包");
        request.setDescription("正在下载 " + safe(manifest.versionName));
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "mjproxy/" + targetFile.getName()
        );

        long downloadId = downloadManager.enqueue(request);
        context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_UPDATE_DOWNLOAD_ID, downloadId)
                .putInt(PREF_UPDATE_DOWNLOAD_URL_INDEX, startIndex)
                .putString(PREF_UPDATE_DOWNLOAD_MANIFEST, manifest.toJson().toString())
                .putString(PREF_UPDATE_DOWNLOAD_PATH, targetFile.getAbsolutePath())
                .apply();
        return StartDownloadResult.success("已开始下载更新包：" + targetUrl, downloadId);
    }

    /**
     * v1.5.6+：UM-3 — 删除路径改由 caller 显式传入。
     *
     * <p>之前直接读 SP 的 PREF_UPDATE_DOWNLOAD_PATH 来删失败的下载产物，但 SP 当前快照
     * 与"本次实际失败的镜像路径"关系不确定（取决于 enqueueDownload 是否在抛异常前已经
     * 写过 SP）。在某些时序下可能删错文件（删了下一轮还没下完的产物，或漏删本轮失败的产物）。</p>
     *
     * <p>修复：caller 在调用前从 SP 读到当前路径自己传进来；或者 caller 已经 deleteIfExists
     * 过的可以传 null。tryNextMirrorOrFail 内不再读 SP 删文件。</p>
     */
    private static void tryNextMirrorOrFail(Context context, UpdateManifest manifest, int currentIndex,
                                            String error, String failedPath) {
        if (!TextUtils.isEmpty(failedPath)) {
            deleteIfExists(new File(failedPath));
        }
        int nextIndex = currentIndex + 1;
        try {
            StartDownloadResult next = enqueueDownload(context, manifest, nextIndex);
            if (next.success) {
                FeedbackSupport.recordOperationalError(context, "update_download_retry", error, "已自动切换到下一个镜像。");
                return;
            }
        } catch (Exception ignored) {
        }
        FeedbackSupport.recordOperationalError(context, "update_download", "更新下载失败", error);
        clearTrackedDownload(context);
        showFailureNotification(context, "更新下载失败", error);
    }

    private static UpdateManifest loadTrackedManifest(Context context) {
        if (context == null) {
            return null;
        }
        String raw = context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_UPDATE_DOWNLOAD_MANIFEST, "");
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return UpdateManifest.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void clearTrackedDownload(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(ProxyConfig.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_UPDATE_DOWNLOAD_ID)
                .remove(PREF_UPDATE_DOWNLOAD_URL_INDEX)
                .remove(PREF_UPDATE_DOWNLOAD_MANIFEST)
                .remove(PREF_UPDATE_DOWNLOAD_PATH)
                .apply();
    }

    private static void verifyManifest(UpdateManifest manifest, String publicKeyText) throws Exception {
        if (manifest == null) {
            throw new SignatureException("更新清单为空");
        }
        if (manifest.versionCode <= 0) {
            throw new SignatureException("versionCode 无效");
        }
        if (TextUtils.isEmpty(manifest.versionName)
                || TextUtils.isEmpty(manifest.apkSha256)
                || TextUtils.isEmpty(manifest.apkPackageName)
                || TextUtils.isEmpty(manifest.apkSigningCertSha256)
                || manifest.downloadUrls.isEmpty()
                || TextUtils.isEmpty(manifest.signature)) {
            throw new SignatureException("更新清单缺少关键字段");
        }
        PublicKey key = parsePublicKey(publicKeyText);
        java.security.Signature verifier = java.security.Signature.getInstance(resolveSignatureAlgorithm(key));
        verifier.initVerify(key);
        verifier.update(manifest.canonicalPayload().getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = decodeBase64(manifest.signature);
        if (!verifier.verify(signatureBytes)) {
            throw new SignatureException("update.json 验签失败");
        }
    }

    private static String verifyDownloadedApk(Context context, UpdateManifest manifest, File file) {
        if (manifest == null) {
            return "更新清单为空。";
        }
        if (file == null || !file.isFile()) {
            return "下载文件不存在。";
        }
        try {
            String actualSha256 = sha256OfFile(file);
            if (!manifest.apkSha256.equalsIgnoreCase(actualSha256)) {
                return "APK 的 sha256 校验失败。";
            }
            PackageManager packageManager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (info == null) {
                return "无法读取 APK 包信息。";
            }
            if (!TextUtils.equals(manifest.apkPackageName, info.packageName)) {
                return "APK 包名与更新清单不一致。";
            }
            String signingDigest = readSigningCertSha256(info);
            if (TextUtils.isEmpty(signingDigest)
                    || !manifest.apkSigningCertSha256.equalsIgnoreCase(signingDigest)) {
                return "APK 签名指纹与更新清单不一致。";
            }
            return "";
        } catch (Exception error) {
            return "APK 校验失败：" + safe(error.getMessage());
        }
    }

    private static InstalledVersion getInstalledVersion(Context context) {
        InstalledVersion version = new InstalledVersion();
        if (context == null) {
            return version;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version.versionName = safe(info.versionName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                version.versionCode = info.getLongVersionCode();
            } else {
                version.versionCode = info.versionCode;
            }
        } catch (Exception ignored) {
        }
        return version;
    }

    private static int compareManifestAgainstInstalled(UpdateManifest manifest, InstalledVersion installed) {
        if (manifest == null) {
            return -1;
        }
        if (manifest.versionCode != installed.versionCode) {
            return Long.compare(manifest.versionCode, installed.versionCode);
        }
        return compareVersionNames(manifest.versionName, installed.versionName);
    }

    /**
     * v1.5.6+：UM-4 — 字符串语义版本比较。本项目仅使用 N.N.N 三段数字版本号，
     * 不涉及 -beta / -rc 等预发标签，因此走"按 [._\-] 切片，每段数字优先 / 否则字母比较"
     * 的简单逻辑就足够。如果未来引入预发标签（例如 1.6.0-beta1），此函数会把 "1.6.0-beta1"
     * 视为 "1.6.0.beta1"，按字母序与 "1.6.0" 比较得到 "1.6.0-beta1 &gt; 1.6.0"——这与
     * SemVer 约定（pre-release 应排在正式版前面）相反。届时需要改为：先比较前 3 段数字，
     * 再特判预发标签的 5 个固定优先级（alpha &lt; beta &lt; rc &lt; release）。
     * 当前业务不需要，保持现状。
     */
    private static int compareVersionNames(String left, String right) {
        String[] leftParts = safe(left).split("[._\\-]");
        String[] rightParts = safe(right).split("[._\\-]");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            String a = i < leftParts.length ? leftParts[i] : "0";
            String b = i < rightParts.length ? rightParts[i] : "0";
            boolean aNumeric = a.matches("\\d+");
            boolean bNumeric = b.matches("\\d+");
            int cmp;
            if (aNumeric && bNumeric) {
                cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } else {
                cmp = a.compareToIgnoreCase(b);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static String fetchText(String rawUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("status=" + status + " body=" + clip(readStream(inputStream), 160));
            }
            return readStream(inputStream);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String validateHttpsUrl(String rawUrl, String label) {
        if (TextUtils.isEmpty(rawUrl)) {
            return label + "为空。";
        }
        try {
            URL url = new URL(rawUrl);
            String protocol = safe(url.getProtocol()).toLowerCase(Locale.ROOT);
            if (!"https".equals(protocol)) {
                return label + "只支持 https。";
            }
            if (TextUtils.isEmpty(url.getHost())) {
                return label + "缺少主机名。";
            }
            return "";
        } catch (Exception ignored) {
            return label + "格式不正确。";
        }
    }

    private static void showInstallReadyNotification(Context context, UpdateManifest manifest, File file) {
        if (context == null || file == null || !file.isFile()) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        ensureNotificationChannel(notificationManager);
        Intent intent = new Intent(context, UpdateInstallActivity.class);
        intent.putExtra(UpdateInstallActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        intent.putExtra(UpdateInstallActivity.EXTRA_VERSION_NAME, manifest.versionName);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("更新包已下载")
                .setContentText("点击安装 " + safe(manifest.versionName))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        notificationManager.notify(NOTIFICATION_ID_READY, builder.build());
    }

    private static void showFailureNotification(Context context, String title, String message) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        ensureNotificationChannel(notificationManager);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(clip(message, 120))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true);
        notificationManager.notify(NOTIFICATION_ID_READY, builder.build());
    }

    private static void ensureNotificationChannel(NotificationManager notificationManager) {
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        notificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "更新通知",
                NotificationManager.IMPORTANCE_DEFAULT
        ));
    }

    private static void deleteIfExists(File file) {
        try {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static PublicKey parsePublicKey(String publicKeyText) throws Exception {
        String normalized = safe(publicKeyText)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = decodeBase64(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ignored) {
        }
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    private static String resolveSignatureAlgorithm(PublicKey key) {
        if (key instanceof RSAPublicKey) {
            return "SHA256withRSA";
        }
        if (key instanceof ECPublicKey) {
            return "SHA256withECDSA";
        }
        String algorithm = safe(key == null ? "" : key.getAlgorithm()).toUpperCase(Locale.ROOT);
        if (algorithm.contains("EC")) {
            return "SHA256withECDSA";
        }
        return "SHA256withRSA";
    }

    private static byte[] decodeBase64(String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.util.Base64.getDecoder().decode(value);
        }
        return android.util.Base64.decode(value, android.util.Base64.DEFAULT);
    }

    private static String readStream(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        try (InputStream in = inputStream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String sha256OfFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return sha256Hex(digest.digest());
    }

    private static String sha256Hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.US, "%02x", b));
        }
        return builder.toString();
    }

    private static String readSigningCertSha256(PackageInfo info) throws Exception {
        if (info == null) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures != null && signatures.length > 0) {
                return sha256OfCertificate(signatures[0]);
            }
        }
        if (info.signatures != null && info.signatures.length > 0) {
            return sha256OfCertificate(info.signatures[0]);
        }
        return "";
    }

    private static String sha256OfCertificate(Signature signature) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(signature.toByteArray()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return sha256Hex(digest.digest(certificate.getEncoded()));
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private static String clip(String text, int maxLen) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen)) + "...";
    }

    static final class CheckResult {
        final boolean success;
        final boolean updateAvailable;
        final boolean skipped;
        final boolean dismissed;
        final String message;
        final UpdateManifest manifest;
        final String sourceUrl;

        private CheckResult(
                boolean success,
                boolean updateAvailable,
                boolean skipped,
                boolean dismissed,
                String message,
                UpdateManifest manifest,
                String sourceUrl
        ) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.skipped = skipped;
            this.dismissed = dismissed;
            this.message = message;
            this.manifest = manifest;
            this.sourceUrl = sourceUrl;
        }

        static CheckResult failure(String message) {
            return new CheckResult(false, false, false, false, message, null, "");
        }

        static CheckResult skipped(String message) {
            return new CheckResult(false, false, true, false, message, null, "");
        }

        static CheckResult dismissed(String message, UpdateManifest manifest, String sourceUrl) {
            return new CheckResult(true, false, false, true, message, manifest, sourceUrl);
        }

        static CheckResult upToDate(String message, UpdateManifest manifest, String sourceUrl) {
            return new CheckResult(true, false, false, false, message, manifest, sourceUrl);
        }

        static CheckResult updateAvailable(String message, UpdateManifest manifest, String sourceUrl) {
            return new CheckResult(true, true, false, false, message, manifest, sourceUrl);
        }
    }

    static final class StartDownloadResult {
        final boolean success;
        final String message;
        final long downloadId;

        private StartDownloadResult(boolean success, String message, long downloadId) {
            this.success = success;
            this.message = message;
            this.downloadId = downloadId;
        }

        static StartDownloadResult success(String message, long downloadId) {
            return new StartDownloadResult(true, message, downloadId);
        }

        static StartDownloadResult failure(String message) {
            return new StartDownloadResult(false, message, 0L);
        }
    }

    static final class UpdateManifest {
        int versionCode;
        String versionName = "";
        String changelog = "";
        String publishedAt = "";
        String apkSha256 = "";
        String apkPackageName = "";
        String apkSigningCertSha256 = "";
        List<String> downloadUrls = new ArrayList<>();
        String signature = "";

        static UpdateManifest fromJson(JSONObject json) {
            UpdateManifest manifest = new UpdateManifest();
            if (json == null) {
                return manifest;
            }
            manifest.versionCode = json.optInt("versionCode", 0);
            manifest.versionName = safe(json.optString("versionName", ""));
            manifest.changelog = safe(json.optString("changelog", ""));
            manifest.publishedAt = safe(json.optString("publishedAt", ""));
            manifest.apkSha256 = safe(json.optString("apkSha256", "")).toLowerCase(Locale.ROOT);
            manifest.apkPackageName = safe(json.optString("apkPackageName", ""));
            manifest.apkSigningCertSha256 = safe(json.optString("apkSigningCertSha256", "")).toLowerCase(Locale.ROOT);
            manifest.signature = safe(json.optString("signature", ""));
            JSONArray urls = json.optJSONArray("downloadUrls");
            if (urls != null) {
                for (int i = 0; i < urls.length(); i++) {
                    String url = safe(urls.optString(i, ""));
                    if (!TextUtils.isEmpty(url)) {
                        manifest.downloadUrls.add(url);
                    }
                }
            }
            return manifest;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("versionCode", versionCode);
            json.put("versionName", versionName);
            json.put("changelog", changelog);
            json.put("publishedAt", publishedAt);
            json.put("apkSha256", apkSha256);
            json.put("apkPackageName", apkPackageName);
            json.put("apkSigningCertSha256", apkSigningCertSha256);
            json.put("signature", signature);
            JSONArray urls = new JSONArray();
            for (String url : downloadUrls) {
                urls.put(url);
            }
            json.put("downloadUrls", urls);
            return json;
        }

        String canonicalPayload() {
            StringBuilder builder = new StringBuilder();
            builder.append("versionCode=").append(versionCode).append('\n');
            builder.append("versionName=").append(versionName).append('\n');
            builder.append("publishedAt=").append(publishedAt).append('\n');
            builder.append("apkSha256=").append(apkSha256).append('\n');
            builder.append("apkPackageName=").append(apkPackageName).append('\n');
            builder.append("apkSigningCertSha256=").append(apkSigningCertSha256).append('\n');
            builder.append("changelog=").append(changelog.replace("\r\n", "\n").replace('\r', '\n')).append('\n');
            builder.append("downloadUrls=");
            for (int i = 0; i < downloadUrls.size(); i++) {
                if (i > 0) {
                    builder.append('|');
                }
                builder.append(downloadUrls.get(i));
            }
            return builder.toString();
        }

        List<String> getValidDownloadUrls() {
            List<String> urls = new ArrayList<>();
            for (String url : downloadUrls) {
                if (!TextUtils.isEmpty(url) && !urls.contains(url)) {
                    urls.add(url);
                }
            }
            return urls;
        }
    }

    private static final class InstalledVersion {
        long versionCode = 0L;
        String versionName = "";
    }
}
