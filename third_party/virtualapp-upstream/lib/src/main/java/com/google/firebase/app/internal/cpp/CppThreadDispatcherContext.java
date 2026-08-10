/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.firebase.app.internal.cpp;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Context for dispatching a native Firebase C++ function on another thread. */
public class CppThreadDispatcherContext {
    private long functionPtr;
    private long cancelFunctionPtr;
    private long functionData;
    private final Lock lock = new ReentrantLock();

    public CppThreadDispatcherContext(long functionPtr, long functionData, long cancelFunctionPtr) {
        this.functionPtr = functionPtr;
        this.functionData = functionData;
        this.cancelFunctionPtr = cancelFunctionPtr;
    }

    private void clear() {
        try {
            lock.lock();
            functionPtr = 0;
            functionData = 0;
            cancelFunctionPtr = 0;
        } finally {
            lock.unlock();
        }
    }

    public void cancel() {
        try {
            lock.lock();
            if (cancelFunctionPtr != 0) {
                nativeFunction(cancelFunctionPtr, functionData);
            }
        } finally {
            clear();
            lock.unlock();
        }
    }

    public void execute() {
        try {
            lock.lock();
            if (functionPtr != 0) {
                nativeFunction(functionPtr, functionData);
            }
        } finally {
            clear();
            lock.unlock();
        }
    }

    public void releaseExecuteCancelLock() {
        lock.unlock();
    }

    public boolean acquireExecuteCancelLock() {
        lock.lock();
        return functionPtr != 0;
    }

    private static native void nativeFunction(long functionPointer, long functionData);
}
