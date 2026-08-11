package com.lody.virtual.oem;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import com.lody.virtual.helper.compat.BuildCompat;

import java.util.Arrays;
import java.util.List;

/**
 * 为常见 Android 定制系统查找自启动与后台运行权限页面。
 *
 * <p>所有候选组件在返回前都会经过 PackageManager 校验；系统升级导致旧组件消失时，
 * 调用方可以安全回退到 Android 标准应用详情页。</p>
 */
public class OemPermissionHelper {
    private static final List<ComponentName> EMUI_AUTO_START_COMPONENTS = Arrays.asList(
            new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"),
            new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupAwakedAppListActivity")
    );

    private static final List<ComponentName> FLYME_AUTO_START_COMPONENTS = Arrays.asList(
            new ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            new ComponentName("com.meizu.safe", "com.meizu.safe.security.HomeActivity")
    );

    private static final List<ComponentName> VIVO_AUTO_START_COMPONENTS = Arrays.asList(
            // OriginOS 新版本逐步把后台启动入口从 i 管家迁移到了权限管理器。
            new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"),
            new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewActivity")
    );
    /**
     * 查找当前 ROM 可用的自启动或后台运行权限页面。
     *
     * @param context 用于识别 ROM 并验证候选 Activity 的宿主上下文
     * @return 已安装且允许外部启动的设置页 Intent；没有匹配项时返回 {@code null}
     */
    public static Intent getPermissionActivityIntent(Context context) {
        BuildCompat.ROMType romType = BuildCompat.getROMType();
        switch (romType) {
            case EMUI: {
                for (ComponentName component : EMUI_AUTO_START_COMPONENTS) {
                    Intent intent = new Intent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(component);
                    if (verifyIntent(context, intent)) {
                        return intent;
                    }
                }
                break;
            }
            case MIUI: {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity");
                if (verifyIntent(context, intent)) {
                    return intent;
                }
                break;
            }
            case FLYME: {
                for (ComponentName component : FLYME_AUTO_START_COMPONENTS) {
                    Intent intent = new Intent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(component);
                    if (verifyIntent(context, intent)) {
                        return intent;
                    }
                }
                break;
            }
            case COLOR_OS: {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity");
                if (verifyIntent(context, intent)) {
                    return intent;
                }
                break;
            }
            case LETV: {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity");
                if (verifyIntent(context, intent)) {
                    return intent;
                }
                break;
            }
            case VIVO: {
                for (ComponentName component : VIVO_AUTO_START_COMPONENTS) {
                    Intent intent = new Intent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(component);
                    if (verifyIntent(context, intent)) {
                        return intent;
                    }
                }
                break;
            }
            case _360: {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.qihoo360.mobilesafe", "com.qihoo360.mobilesafe.ui.index.AppEnterActivity");
                if (verifyIntent(context, intent)) {
                    return intent;
                }
                break;
            }

        }
        return null;
    }

    private static boolean verifyIntent(Context context, Intent intent) {
        ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
        return info != null && info.activityInfo != null && info.activityInfo.exported;
    }
}
