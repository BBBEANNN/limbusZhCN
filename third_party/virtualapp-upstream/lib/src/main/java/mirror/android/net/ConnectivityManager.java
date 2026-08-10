package mirror.android.net;

import android.os.IInterface;

import mirror.RefClass;
import mirror.RefObject;

public class ConnectivityManager {
    public static Class<?> TYPE = RefClass.load(ConnectivityManager.class, "android.net.ConnectivityManager");

    public static RefObject<IInterface> mService;
}
