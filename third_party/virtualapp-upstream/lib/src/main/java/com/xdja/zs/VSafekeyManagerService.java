package com.xdja.zs;

import android.content.Context;
import android.os.RemoteException;

public class VSafekeyManagerService extends IVSafekey.Stub {
    private static final int UNSUPPORTED = -2;
    private static VSafekeyManagerService sInstance;

    public static void systemReady(Context context) {
        sInstance = new VSafekeyManagerService();
    }

    public static VSafekeyManagerService get() {
        if (sInstance == null) {
            sInstance = new VSafekeyManagerService();
        }
        return sInstance;
    }

    public static boolean initSafekeyLib() {
        return false;
    }

    @Override
    public boolean checkCardState() throws RemoteException {
        return false;
    }

    @Override
    public String getCardId() throws RemoteException {
        return null;
    }

    @Override
    public int getPinTryCount() throws RemoteException {
        return UNSUPPORTED;
    }

    @Override
    public byte[] encryptKey(byte[] key, int keylen) throws RemoteException {
        return null;
    }

    @Override
    public byte[] decryptKey(byte[] seckey, int seckeylen) throws RemoteException {
        return null;
    }

    @Override
    public byte[] getRandom(int len) throws RemoteException {
        return null;
    }

    @Override
    public void registerCallback(IVSCallback vsCallback) throws RemoteException {
    }

    @Override
    public void unregisterCallback() throws RemoteException {
    }

    @Override
    public int initSafekeyCard() throws RemoteException {
        return UNSUPPORTED;
    }
}
