package com.google.android.gms.common;

import android.content.Context;

public class GoogleApiAvailability {
    private static final GoogleApiAvailability INSTANCE = new GoogleApiAvailability();

    public static GoogleApiAvailability getInstance() {
        return INSTANCE;
    }

    public int isGooglePlayServicesAvailable(Context context) {
        return 0;
    }
}
