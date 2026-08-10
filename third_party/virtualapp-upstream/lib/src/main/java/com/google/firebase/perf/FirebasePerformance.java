package com.google.firebase.perf;

import com.google.firebase.FirebaseApp;

public final class FirebasePerformance {
    private static final FirebasePerformance INSTANCE = new FirebasePerformance();
    private boolean performanceCollectionEnabled = true;

    public static FirebasePerformance getInstance() {
        return INSTANCE;
    }

    public static FirebasePerformance getInstance(FirebaseApp app) {
        return INSTANCE;
    }

    public void setPerformanceCollectionEnabled(boolean enabled) {
        performanceCollectionEnabled = enabled;
    }

    public boolean isPerformanceCollectionEnabled() {
        return performanceCollectionEnabled;
    }
}
