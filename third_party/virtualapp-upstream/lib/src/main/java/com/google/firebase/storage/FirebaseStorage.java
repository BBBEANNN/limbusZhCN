package com.google.firebase.storage;

import com.google.firebase.FirebaseApp;

public final class FirebaseStorage {
    private static final FirebaseStorage INSTANCE = new FirebaseStorage();

    public static FirebaseStorage getInstance() {
        return INSTANCE;
    }

    public static FirebaseStorage getInstance(FirebaseApp app) {
        return INSTANCE;
    }

    public static FirebaseStorage getInstance(String url) {
        return INSTANCE;
    }

    public static FirebaseStorage getInstance(FirebaseApp app, String url) {
        return INSTANCE;
    }
}
