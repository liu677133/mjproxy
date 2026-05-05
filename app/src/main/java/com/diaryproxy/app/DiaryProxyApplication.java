package com.diaryproxy.app;

import android.app.Application;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class DiaryProxyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // v1.5.1+：初始化 PDFBox-Android（懒加载字体资源，必须在 main thread 调一次）
        try {
            PDFBoxResourceLoader.init(getApplicationContext());
        } catch (Throwable error) {
            // v1.5.4+：DPA-1 — PDF 提取依赖 PDFBox 字体资源；初始化失败时给 FeedbackSupport
            // 留一行操作日志，方便排查"PDF 提取空白"等问题（之前是静默 ignored）。
            try {
                FeedbackSupport.recordOperationalError(
                        getApplicationContext(),
                        "pdfbox_init",
                        "PDFBox 初始化失败，PDF 文本提取可能不可用",
                        error.getClass().getSimpleName() + ": " + error.getMessage());
            } catch (Throwable ignored) {
            }
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                FeedbackSupport.recordCrash(this, thread, throwable);
            } catch (Exception ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }
}
