package com.google.firebase.functions;

import com.google.firebase.FirebaseApp;

public final class FirebaseFunctions {
    private static final FirebaseFunctions INSTANCE = new FirebaseFunctions();

    public static FirebaseFunctions getInstance() {
        return INSTANCE;
    }

    public static FirebaseFunctions getInstance(FirebaseApp app) {
        return INSTANCE;
    }

    public static FirebaseFunctions getInstance(String region) {
        return INSTANCE;
    }

    public static FirebaseFunctions getInstance(FirebaseApp app, String region) {
        return INSTANCE;
    }
}
