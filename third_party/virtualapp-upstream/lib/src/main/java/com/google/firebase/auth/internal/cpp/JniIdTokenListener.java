/* Copyright 2017 Google LLC. Licensed under the Apache License, Version 2.0. */
package com.google.firebase.auth.internal.cpp;

import com.google.firebase.auth.FirebaseAuth;

/** Redirects Firebase ID token changes to the C++ listener. */
public class JniIdTokenListener implements FirebaseAuth.IdTokenListener {
    private final Object lock = new Object();
    private long cppAuthData;

    public JniIdTokenListener(long cppAuthData) {
        this.cppAuthData = cppAuthData;
    }

    public void disconnect() {
        synchronized (lock) {
            cppAuthData = 0;
        }
    }

    @Override
    public void onIdTokenChanged(FirebaseAuth auth) {
        AuthCommon.safeRunNativeMethod(() -> {
            synchronized (lock) {
                if (cppAuthData != 0) {
                    nativeOnIdTokenChanged(cppAuthData);
                }
            }
        });
    }

    private native void nativeOnIdTokenChanged(long cppAuthData);
}
