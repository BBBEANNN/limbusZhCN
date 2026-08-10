package com.lody.virtual.client.hook.proxies.libcore;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.libcore.io.Os;

/**
 * @author Lody
 */

class MethodProxies {

    private static boolean shouldKeepRealUidForLimbus() {
        return "com.ProjectMoon.LimbusCompany".equals(VClient.get().getCurrentPackage());
    }

    static class Lstat extends Stat {

        @Override
        public String getMethodName() {
            return "lstat";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            if (result != null) {
                Reflect pwd = Reflect.on(result);
                int uid = pwd.get("st_uid");
                if (!shouldKeepRealUidForLimbus() && uid == VirtualCore.get().myUid()) {
                    pwd.set("st_uid", VClient.get().getVUid());
                }
            }
            return result;
        }
    }

    static class Fstat extends Stat {

        @Override
        public String getMethodName() {
            return "fstat";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            if (result != null) {
                Reflect pwd = Reflect.on(result);
                int uid = pwd.get("st_uid");
                if (!shouldKeepRealUidForLimbus() && uid == VirtualCore.get().myUid()) {
                    pwd.set("st_uid", VClient.get().getVUid());
                }
            }
            return result;
        }
    }
    static class Getpwnam extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getpwnam";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect pwd = Reflect.on(result);
                    int uid = pwd.get("pw_uid");
                    if (!shouldKeepRealUidForLimbus() && uid == VirtualCore.get().myUid()) {
                        pwd.set("pw_uid", VClient.get().getVUid());
                    }
                }
                return result;
            }
        }

    static class GetUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getuid";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            int uid = (int) result;
            return NativeEngine.onGetUid(uid);
        }
    }

    static class GetsockoptUcred extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getsockoptUcred";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect ucred = Reflect.on(result);
                    int uid = ucred.get("uid");
                    if (!shouldKeepRealUidForLimbus() && uid == VirtualCore.get().myUid()) {
                        ucred.set("uid", getBaseVUid());
                    }
                }
                return result;
            }
        }

    /**
     * 在 Java 文件系统执行原子重命名前，把游戏可见路径转换为宿主私有容器路径。
     *
     * <p>Android 16 的短 {@code renameat} 跳板无法沿用旧版 native inline hook；
     * Unity PlayerPrefs 又依赖重命名提交 SharedPreferences。这里同时改写源路径与目标路径，
     * 避免资源文件仍在、但下载完成标记因提交失败而丢失。</p>
     */
    static class Rename extends MethodProxy {

        /**
         * 返回需要代理的 Libcore 方法名。
         *
         * @return 固定返回 {@code rename}。
         */
        @Override
        public String getMethodName() {
            return "rename";
        }

        /**
         * 在调用真实文件系统前重定向重命名的源路径和目标路径。
         *
         * @param who 原始 Libcore OS 实现。
         * @param method 当前被调用的方法。
         * @param args 原始方法参数，前两个字符串分别为源路径和目标路径。
         * @return 始终返回 {@code true}，允许继续调用真实实现。
         */
        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args != null && args.length >= 2
                    && args[0] instanceof String && args[1] instanceof String) {
                // 两端必须一起改写；只改目标路径会让跨目录判断或备份文件提交继续失败。
                args[0] = NativeEngine.getRedirectedPath((String) args[0]);
                args[1] = NativeEngine.getRedirectedPath((String) args[1]);
            }
            return true;
        }
    }

    static class Stat extends MethodProxy {

        private static Field st_uid;

        static {
            try {
                Method stat = Os.TYPE.getMethod("stat", String.class);
                Class<?> StructStat = stat.getReturnType();
                st_uid = StructStat.getDeclaredField("st_uid");
                st_uid.setAccessible(true);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            int uid = (int) st_uid.get(result);
            if (!shouldKeepRealUidForLimbus() && uid == VirtualCore.get().myUid()) {
                st_uid.set(result, getBaseVUid());
            }
            return result;
        }

        @Override
        public String getMethodName() {
            return "stat";
        }
    }
}
