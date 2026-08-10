package mirror.android.app;


import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import mirror.MethodParams;
import mirror.RefBoolean;
import mirror.RefClass;
import mirror.RefMethod;
import mirror.RefObject;
import mirror.RefInt;

public class Activity {
    public static Class<?> TYPE = RefClass.load(Activity.class, "android.app.Activity");
    public static RefObject<ActivityInfo> mActivityInfo;
    public static RefObject<String> mCallingPackage;
    public static RefObject<ComponentName> mCallingActivity;
    public static RefBoolean mFinished;
    public static RefObject<android.app.Activity> mParent;
    public static RefInt mResultCode;
    public static RefObject<Intent> mResultData;
    public static RefObject<IBinder> mToken;
    public static RefObject<String> mEmbeddedID;
    @MethodParams({int.class, int.class, Intent.class})
    public static RefMethod<Void> onActivityResult;
}
