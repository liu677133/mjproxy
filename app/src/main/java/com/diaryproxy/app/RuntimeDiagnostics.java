package com.diaryproxy.app;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RuntimeDiagnostics {

    private RuntimeDiagnostics() {
    }

    static String buildDeviceSummary() {
        return "brand=" + safe(Build.BRAND)
                + ",manufacturer=" + safe(Build.MANUFACTURER)
                + ",model=" + safe(Build.MODEL)
                + ",sdk=" + Build.VERSION.SDK_INT
                + ",release=" + safe(Build.VERSION.RELEASE);
    }

    static String buildRuntimeSummary(Context context) {
        return buildPowerSummary(context) + "," + buildNetworkSummary(context);
    }

    static String buildPowerSummary(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append("batteryOptIgnored=").append(isIgnoringBatteryOptimizations(context));
        builder.append(",powerSave=").append(isPowerSaveMode(context));
        builder.append(",backgroundRestricted=").append(isBackgroundRestricted(context));
        return builder.toString();
    }

    static String buildNetworkSummary(Context context) {
        ConnectivityManager manager = context == null
                ? null
                : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return "network=unknown";
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = manager.getActiveNetwork();
                if (active == null) {
                    return "network=none";
                }
                NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                LinkProperties link = manager.getLinkProperties(active);
                List<String> transports = new ArrayList<>();
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        transports.add("wifi");
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        transports.add("cellular");
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        transports.add("vpn");
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        transports.add("ethernet");
                    }
                }
                StringBuilder builder = new StringBuilder();
                builder.append("network=").append(transports.isEmpty() ? "other" : TextUtils.join("+", transports));
                if (caps != null) {
                    builder.append(",validated=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    builder.append(",notSuspended=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));
                }
                builder.append(",metered=").append(manager.isActiveNetworkMetered());
                if (link != null && !TextUtils.isEmpty(link.getInterfaceName())) {
                    builder.append(",iface=").append(link.getInterfaceName());
                }
                return builder.toString();
            }

            NetworkInfo info = manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return "network=none";
            }
            return "network=" + safe(info.getTypeName()).toLowerCase(Locale.ROOT)
                    + ",connected=" + info.isConnected()
                    + ",roaming=" + info.isRoaming()
                    + ",metered=" + manager.isActiveNetworkMetered();
        } catch (SecurityException error) {
            return "network=unknown(permission_denied)";
        } catch (Exception error) {
            return "network=unknown(" + safe(error.getClass().getSimpleName()) + ")";
        }
    }

    static PowerManager.WakeLock acquireWakeLock(Context context, String tag, long timeoutMs) {
        if (context == null) {
            return null;
        }
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (manager == null) {
                return null;
            }
            PowerManager.WakeLock wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
            wakeLock.setReferenceCounted(false);
            if (timeoutMs > 0) {
                wakeLock.acquire(timeoutMs);
            } else {
                wakeLock.acquire();
            }
            return wakeLock;
        } catch (Exception ignored) {
            return null;
        }
    }

    static void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    static boolean isWakeLockHeld(PowerManager.WakeLock wakeLock) {
        try {
            return wakeLock != null && wakeLock.isHeld();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPowerSaveMode(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isPowerSaveMode();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBackgroundRestricted(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return manager != null && manager.isBackgroundRestricted();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
