package com.google.firebase.app.internal.cpp;

import android.app.Activity;

public class GoogleApiAvailabilityHelper {
    private static final int SUCCESS = 0;
    private static boolean stopAllCallbacks;

    public static boolean makeGooglePlayServicesAvailable(Activity activity) {
        if (!stopAllCallbacks) {
            onCompleteNative(SUCCESS, "Google Play services are available in Limbus container");
        }
        return true;
    }

    public static void stopCallbacks() {
        stopAllCallbacks = true;
    }

    private static native void onCompleteNative(int resultCode, String resultMessage);
}
