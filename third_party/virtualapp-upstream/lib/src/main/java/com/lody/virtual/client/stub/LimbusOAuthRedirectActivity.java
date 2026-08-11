package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.LimbusAuthenticationCompat;

/**
 * 接收系统浏览器返回的 Firebase Auth 回调，并把它安全转发到容器内 Limbus。
 *
 * <p>系统浏览器无法解析只存在于虚拟游戏 manifest 中的 Activity，因此宿主需要一个
 * 真实导出入口。该入口只接受游戏已声明的两个精确 URI，不复制外部 extras、component
 * 或 package，避免任意浏览器 Intent 借此进入容器。</p>
 */
public final class LimbusOAuthRedirectActivity extends Activity {
    private static final String TAG = "LimbusVA";
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final int LIMBUS_USER_ID = 0;

    /**
     * Activity 首次创建时立即验证并转发浏览器回调。
     *
     * @param savedInstanceState 系统保存状态；该瞬态跳板不依赖其中内容
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forwardRedirect(getIntent());
    }

    /**
     * singleTop 实例收到新的浏览器回调时复用同一套验证和转发流程。
     *
     * @param intent 系统浏览器送达的新 Intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forwardRedirect(intent);
    }

    /**
     * 校验 Firebase Auth URI，并显式启动容器内对应 Activity。
     *
     * @param incoming 系统浏览器送达的外部 Intent
     */
    private void forwardRedirect(Intent incoming) {
        Uri data = incoming == null ? null : incoming.getData();
        String targetActivity = data == null
                ? null
                : LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
                        data.getScheme(),
                        data.getHost(),
                        data.getPath());
        if (!Intent.ACTION_VIEW.equals(incoming == null ? null : incoming.getAction())
                || targetActivity == null) {
            Log.w(TAG, "Reject untrusted Limbus OAuth redirect scheme="
                    + (data == null ? null : data.getScheme())
                    + " host=" + (data == null ? null : data.getHost())
                    + " path=" + (data == null ? null : data.getPath()));
            finish();
            return;
        }

        /*
         * 只重建 Firebase Auth 真正需要的 ACTION_VIEW 与 URI。查询参数中包含会话状态，
         * 必须原样保留；外部 Intent 的 extras、selector、clipData 和显式组件均不可信。
         */
        Intent virtualIntent = new Intent(Intent.ACTION_VIEW, data);
        virtualIntent.addCategory(Intent.CATEGORY_DEFAULT);
        virtualIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        virtualIntent.setClassName(LIMBUS_PACKAGE, targetActivity);
        virtualIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int result = VActivityManager.get().startActivity(virtualIntent, LIMBUS_USER_ID);
        Log.i(TAG, "Forward Limbus OAuth redirect scheme=" + data.getScheme()
                + " target=" + targetActivity + " result=" + result);
        finish();
    }
}
