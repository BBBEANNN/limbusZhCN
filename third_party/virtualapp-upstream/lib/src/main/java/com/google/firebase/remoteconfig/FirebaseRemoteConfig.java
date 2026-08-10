package com.google.firebase.remoteconfig;

import com.google.firebase.FirebaseApp;

public final class FirebaseRemoteConfig {
    private static final FirebaseRemoteConfig INSTANCE = new FirebaseRemoteConfig();
    public static final long LAST_FETCH_STATUS_NO_FETCH_YET = 0L;
    public static final long LAST_FETCH_STATUS_SUCCESS = -1L;
    public static final long LAST_FETCH_STATUS_FAILURE = 1L;
    public static final long LAST_FETCH_STATUS_THROTTLED = 2L;

    public static FirebaseRemoteConfig getInstance() {
        return INSTANCE;
    }

    public static FirebaseRemoteConfig getInstance(FirebaseApp app) {
        return INSTANCE;
    }

    public FirebaseRemoteConfigInfo getInfo() {
        return new FirebaseRemoteConfigInfo();
    }

    public String getString(String key) {
        return "";
    }

    public boolean getBoolean(String key) {
        return false;
    }

    public long getLong(String key) {
        return 0L;
    }

    public double getDouble(String key) {
        return 0.0;
    }

    public byte[] getByteArray(String key) {
        return new byte[0];
    }
}
