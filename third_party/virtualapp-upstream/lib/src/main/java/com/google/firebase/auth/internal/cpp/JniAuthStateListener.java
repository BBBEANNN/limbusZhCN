/* Copyright 2016 Google LLC. Licensed under the Apache License, Version 2.0. */
package com.google.firebase.auth.internal.cpp;

import com.google.firebase.auth.FirebaseAuth;

/** Redirects Firebase Auth state changes to the C++ listener. */
public class JniAuthStateListener implements FirebaseAuth.AuthStateListener {
    private final Object lock = new Object();
    private long cppAuthData;

    public JniAuthStateListener(long cppAuthData) {
        this.cppAuthData = cppAuthData;
    }

    public void disconnect() {
        synchronized (lock) {
            cppAuthData = 0;
        }
    }

    @Override
    public void onAuthStateChanged(FirebaseAuth auth) {
        AuthCommon.safeRunNativeMethod(() -> {
            synchronized (lock) {
                if (cppAuthData != 0) {
                    nativeOnAuthStateChanged(cppAuthData);
                }
            }
        });
    }

    private native void nativeOnAuthStateChanged(long cppAuthData);
}
