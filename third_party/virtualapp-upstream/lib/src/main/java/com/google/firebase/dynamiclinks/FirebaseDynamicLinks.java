package com.google.firebase.dynamiclinks;

import com.google.firebase.FirebaseApp;

public final class FirebaseDynamicLinks {
    private static final FirebaseDynamicLinks INSTANCE = new FirebaseDynamicLinks();

    public static FirebaseDynamicLinks getInstance() {
        return INSTANCE;
    }

    public static FirebaseDynamicLinks getInstance(FirebaseApp app) {
        return INSTANCE;
    }
}
