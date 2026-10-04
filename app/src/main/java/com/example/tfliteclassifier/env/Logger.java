package com.example.tfliteclassifier.env;

import android.util.Log;

/**
 * 统一的日志输出，便于用 adb logcat -s TFLiteClassifier 过滤。
 */
public final class Logger {

    private static final String TAG = "TFLiteClassifier";

    private Logger() {
    }

    public static void d(String message) {
        Log.d(TAG, message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}
