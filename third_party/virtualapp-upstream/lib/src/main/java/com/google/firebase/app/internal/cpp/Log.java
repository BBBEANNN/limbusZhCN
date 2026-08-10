package com.google.firebase.app.internal.cpp;

public class Log {
    private static native void nativeLog(int level, String tag, String message);

    public static void d(String tag, String message) {
        android.util.Log.d(tag, message);
    }

    public static void i(String tag, String message) {
        android.util.Log.i(tag, message);
    }

    public static void w(String tag, String message) {
        android.util.Log.w(tag, message);
    }

    public static void e(String tag, String message) {
        android.util.Log.e(tag, message);
    }

    public static void shutdown() {
    }
}
