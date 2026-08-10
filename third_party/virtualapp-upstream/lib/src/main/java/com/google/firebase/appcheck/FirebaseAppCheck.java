package com.google.firebase.appcheck;

import com.google.firebase.FirebaseApp;

public final class FirebaseAppCheck {
    private static final FirebaseAppCheck INSTANCE = new FirebaseAppCheck();

    public static FirebaseAppCheck getInstance() {
        return INSTANCE;
    }

    public static FirebaseAppCheck getInstance(FirebaseApp app) {
        return INSTANCE;
    }
}
