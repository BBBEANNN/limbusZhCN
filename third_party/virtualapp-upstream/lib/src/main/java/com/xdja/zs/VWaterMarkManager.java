package com.xdja.zs;

import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.ipc.LocalProxyUtils;
import com.lody.virtual.client.ipc.ServiceManagerNative;
import com.lody.virtual.helper.utils.IInterfaceUtils;

public class VWaterMarkManager {
    private static final String TAG = "VWaterMarkManager";
    private static final VWaterMarkManager sInstance = new VWaterMarkManager();
    IWaterMark mService;

    public static VWaterMarkManager get() {
        return sInstance;
    }

    private Object getRemoteInterface() {
        return IWaterMark.Stub
                .asInterface(ServiceManagerNative.getService(ServiceManagerNative.WATERMARK));
    }

    public IWaterMark getService() {

        if (mService == null || !IInterfaceUtils.isAlive(mService)) {
            synchronized (this) {
                Object binder = getRemoteInterface();
                mService = LocalProxyUtils.genProxy(IWaterMark.class, binder);
            }
        }
        return mService;
    }

    /**
     * 设置水印信息
     *
     * @param waterMark 水印信息
     */
    public void setWaterMark(WaterMarkInfo waterMark) {
        try {
            getService().setWaterMark(waterMark);
        } catch (RemoteException e) {
            Log.w(TAG, "setWaterMark ignored after remote failure", e);
        }
    }

    /**
     * 获取水印信息
     *
     * @return 水印信息
     */
    public WaterMarkInfo getWaterMark() {
        try {
            return getService().getWaterMark();
        } catch (RemoteException e) {
            Log.w(TAG, "getWaterMark fallback after remote failure", e);
            return null;
        }
    }
}
