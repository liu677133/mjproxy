package com.diaryproxy.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;

public class UpdateInstallActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_VERSION_NAME = "versionName";
    private String filePath = "";
    private boolean requestedUnknownSourcesPage = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePath = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_FILE_PATH);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            if (!requestedUnknownSourcesPage) {
                requestedUnknownSourcesPage = true;
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                finish();
            }
            return;
        }
        if (!TextUtils.isEmpty(filePath)) {
            UpdateManager.installVerifiedApk(this, filePath);
        }
        finish();
    }
}
