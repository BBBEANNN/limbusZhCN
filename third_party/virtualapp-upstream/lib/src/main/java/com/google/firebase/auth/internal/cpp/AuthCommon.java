/* Copyright 2018 Google LLC. Licensed under the Apache License, Version 2.0. */
package com.google.firebase.auth.internal.cpp;

import com.google.firebase.app.internal.cpp.Log;

/** Safe native invocation shared by Firebase Auth listener bridges. */
public final class AuthCommon {
    private AuthCommon() {
    }

    public static void safeRunNativeMethod(Runnable runnable) {
        try {
            runnable.run();
        } catch (UnsatisfiedLinkError e) {
            Log.e("AuthCpp", "Failed to execute native method; Auth may have shut down: " + e);
        }
    }
}
