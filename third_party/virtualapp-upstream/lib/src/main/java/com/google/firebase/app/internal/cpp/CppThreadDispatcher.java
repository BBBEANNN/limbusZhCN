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

import android.app.Activity;

/** Runs native Firebase C++ work on the requested Java thread. */
public class CppThreadDispatcher {
    public static void runOnMainThread(Activity activity,
            final CppThreadDispatcherContext context) {
        activity.runOnUiThread(context::execute);
    }

    public static void runOnBackgroundThread(final CppThreadDispatcherContext context) {
        new Thread(context::execute, "FirebaseCppWorker").start();
    }
}
