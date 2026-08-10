package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;


/**
 * @author Lody
 */
public class KeepAliveService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
            // The legacy two-service notification-hiding trick does not promote this service on
            // modern Android. It leaves the VirtualApp server at a killable cached-service adj and
            // a background restart cannot legally start the second foreground service.
            HiddenForeNotification.bindForeground(this);
            VLog.i("LimbusVA", "KeepAliveService promoted directly on Android "
                    + Build.VERSION.SDK_INT);
        } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
            Intent intent = new Intent(this, HiddenForeNotification.class);
            startService(intent);
        } else {
            HiddenForeNotification.bindForeground(this);
        }
    }

    public static boolean useDirectForegroundPromotion(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.O;
    }

    @Override
    public void onDestroy() {
        if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
            HiddenForeNotification.hideForeground(this);
        } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
            stopService(new Intent(this, HiddenForeNotification.class));
        } else {
            HiddenForeNotification.hideForeground(this);
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
