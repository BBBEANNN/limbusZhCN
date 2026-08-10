/*
 * Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.firebase.app.internal.cpp;

import com.google.android.gms.tasks.OnCanceledListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

/** Bridges a Google Task completion back to the Firebase C++ callback. */
public class JniResultCallback<TResult> {
    private interface Callback {
        void register();
        void disconnect();
    }

    private long callbackFn;
    private long callbackData;
    private Callback callbackHandler;
    private final Object lockObject = new Object();

    private class TaskCallback<T> implements OnSuccessListener<T>, OnFailureListener,
            OnCanceledListener, Callback {
        private Task<T> task;

        TaskCallback(Task<T> task) {
            this.task = task;
        }

        @Override
        public void onSuccess(T result) {
            synchronized (lockObject) {
                if (task != null) {
                    onCompletion(result, true, false, null);
                }
                disconnect();
            }
        }

        @Override
        public void onFailure(Exception exception) {
            synchronized (lockObject) {
                if (task != null) {
                    onCompletion(exception, false, false, exception.getMessage());
                }
                disconnect();
            }
        }

        @Override
        public void onCanceled() {
            synchronized (lockObject) {
                if (task != null) {
                    cancel();
                }
                disconnect();
            }
        }

        @Override
        public void register() {
            task.addOnSuccessListener(this);
            task.addOnFailureListener(this);
            task.addOnCanceledListener(this);
        }

        @Override
        public void disconnect() {
            synchronized (lockObject) {
                task = null;
            }
        }
    }

    public JniResultCallback(Task<TResult> task, long callbackFn, long callbackData) {
        initializeNativeCallbackFunctionAndData(callbackFn, callbackData);
        initializeWithTask(task);
    }

    protected JniResultCallback(long callbackFn, long callbackData) {
        initializeNativeCallbackFunctionAndData(callbackFn, callbackData);
    }

    protected void initializeNativeCallbackFunctionAndData(long callbackFn, long callbackData) {
        this.callbackFn = callbackFn;
        this.callbackData = callbackData;
    }

    protected void initializeWithTask(Task<TResult> task) {
        synchronized (lockObject) {
            callbackHandler = new TaskCallback<>(task);
            callbackHandler.register();
        }
    }

    public void cancel() {
        onCompletion(null, false, true, "cancelled");
    }

    public void onCompletion(Object result, boolean success, boolean cancelled,
            String statusMessage) {
        synchronized (lockObject) {
            if (callbackHandler != null) {
                nativeOnResult(result, success, cancelled, statusMessage, callbackFn, callbackData);
                callbackHandler.disconnect();
                callbackHandler = null;
            }
        }
    }

    private native void nativeOnResult(Object result, boolean success, boolean cancelled,
            String statusString, long callbackFn, long callbackData);
}
