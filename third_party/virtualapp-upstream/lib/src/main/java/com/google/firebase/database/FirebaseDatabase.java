package com.google.firebase.database;

import com.google.firebase.FirebaseApp;

public final class FirebaseDatabase {
    private static final FirebaseDatabase INSTANCE = new FirebaseDatabase();

    public static FirebaseDatabase getInstance() {
        return INSTANCE;
    }

    public static FirebaseDatabase getInstance(FirebaseApp app) {
        return INSTANCE;
    }

    public static FirebaseDatabase getInstance(String url) {
        return INSTANCE;
    }

    public static FirebaseDatabase getInstance(FirebaseApp app, String url) {
        return INSTANCE;
    }
}
