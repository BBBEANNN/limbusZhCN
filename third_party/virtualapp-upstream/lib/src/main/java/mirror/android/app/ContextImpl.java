package mirror.android.app;


import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import java.io.File;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefMethod;
import mirror.RefObject;

public class ContextImpl {
    public static Class<?> TYPE = RefClass.load(ContextImpl.class, "android.app.ContextImpl");
    @MethodParams({Context.class})
    public static RefObject<String> mBasePackageName;
    public static RefObject<Object> mPackageInfo;
    public static RefObject<ClassLoader> mClassLoader;
    public static RefObject<PackageManager> mPackageManager;
    public static RefObject<Resources> mResources;
    public static RefObject<File> mDataDir;
    public static RefObject<File> mFilesDir;
    public static RefObject<File> mNoBackupFilesDir;
    public static RefObject<File> mCacheDir;
    public static RefObject<File> mCodeCacheDir;
    public static RefObject<File> mPreferencesDir;

    public static RefMethod<Context> getReceiverRestrictedContext;

    @MethodParams({Context.class})
    public static RefMethod<Void> setOuterContext;
}
