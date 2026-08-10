/* Copyright 2017 Google LLC. Licensed under the Apache License, Version 2.0. */
package com.google.firebase.auth.internal.cpp;

import com.google.firebase.FirebaseException;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken;
import com.google.firebase.auth.PhoneAuthProvider.OnVerificationStateChangedCallbacks;

/** Redirects phone authentication callbacks to C++. */
public class JniAuthPhoneListener extends OnVerificationStateChangedCallbacks {
    private final Object lock = new Object();
    private long cListener;

    public JniAuthPhoneListener(long cListener) {
        this.cListener = cListener;
    }

    public void disconnect() {
        synchronized (lock) {
            cListener = 0;
        }
    }

    @Override
    public void onVerificationCompleted(final PhoneAuthCredential credential) {
        synchronized (lock) {
            if (cListener != 0) {
                AuthCommon.safeRunNativeMethod(
                        () -> nativeOnVerificationCompleted(cListener, credential));
            }
        }
    }

    @Override
    public void onVerificationFailed(final FirebaseException exception) {
        synchronized (lock) {
            if (cListener != 0) {
                AuthCommon.safeRunNativeMethod(
                        () -> nativeOnVerificationFailed(cListener, exception.getMessage()));
            }
        }
    }

    @Override
    public void onCodeSent(final String verificationId, final ForceResendingToken token) {
        synchronized (lock) {
            if (cListener != 0) {
                AuthCommon.safeRunNativeMethod(
                        () -> nativeOnCodeSent(cListener, verificationId, token));
            }
        }
    }

    @Override
    public void onCodeAutoRetrievalTimeOut(final String verificationId) {
        synchronized (lock) {
            if (cListener != 0) {
                AuthCommon.safeRunNativeMethod(
                        () -> nativeOnCodeAutoRetrievalTimeOut(cListener, verificationId));
            }
        }
    }

    private native void nativeOnVerificationCompleted(long cListener,
            PhoneAuthCredential credential);
    private native void nativeOnVerificationFailed(long cListener, String exceptionMessage);
    private native void nativeOnCodeSent(long cListener, String verificationId,
            ForceResendingToken forceResendingToken);
    private native void nativeOnCodeAutoRetrievalTimeOut(long cListener, String verificationId);
}
