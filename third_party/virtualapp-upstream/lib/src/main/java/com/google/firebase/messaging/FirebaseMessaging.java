package com.google.firebase.messaging;

import com.google.firebase.FirebaseApp;

public final class FirebaseMessaging {
    private static final FirebaseMessaging INSTANCE = new FirebaseMessaging();

    public static FirebaseMessaging getInstance() {
        return INSTANCE;
    }

    public static FirebaseMessaging getInstance(FirebaseApp app) {
        return INSTANCE;
    }
}
