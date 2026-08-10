package mirror.android.view;

import android.content.res.Resources;
import android.view.LayoutInflater;

import mirror.RefClass;
import mirror.RefInt;
import mirror.RefObject;

public class ContextThemeWrapper {
    public static Class<?> TYPE = RefClass.load(ContextThemeWrapper.class, "android.view.ContextThemeWrapper");
    public static RefObject<Resources> mResources;
    public static RefObject<LayoutInflater> mInflater;
    public static RefObject<Resources.Theme> mTheme;
    public static RefInt mThemeResource;
}
