package com.example.limbuszhcn.container

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.firebase.app.internal.cpp.CppThreadDispatcher
import com.google.firebase.app.internal.cpp.CppThreadDispatcherContext
import com.google.firebase.app.internal.cpp.JniResultCallback
import com.google.firebase.auth.internal.cpp.JniAuthPhoneListener
import com.google.firebase.auth.internal.cpp.JniAuthStateListener
import com.google.firebase.auth.internal.cpp.JniIdTokenListener
import com.lody.virtual.helper.compat.LimbusActivityCompat
import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate
import com.lody.virtual.client.stub.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class FirebaseCppShimCompatibilityTest {
    @Test
    fun `firebase cpp result callback exposes current JNI ABI`() {
        val callbackClass = JniResultCallback::class.java

        callbackClass.getConstructor(Task::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType)
        callbackClass.getMethod("cancel")
        callbackClass.getMethod("onCompletion", Any::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java)
    }

    @Test
    fun `firebase cpp dispatcher exposes current JNI ABI`() {
        val contextClass = CppThreadDispatcherContext::class.java
        contextClass.getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType)
        contextClass.getMethod("cancel")
        contextClass.getMethod("execute")
        contextClass.getMethod("releaseExecuteCancelLock")
        assertEquals(Boolean::class.javaPrimitiveType,
            contextClass.getMethod("acquireExecuteCancelLock").returnType)

        CppThreadDispatcher::class.java.getMethod(
            "runOnMainThread", Activity::class.java, contextClass)
        CppThreadDispatcher::class.java.getMethod("runOnBackgroundThread", contextClass)
    }

    @Test
    fun `host activity exception is limited to system permission requests`() {
        assertTrue(LimbusActivityCompat.isRuntimePermissionRequestAction(
            "android.content.pm.action.REQUEST_PERMISSIONS"))
        assertFalse(LimbusActivityCompat.isRuntimePermissionRequestAction(
            "android.intent.action.VIEW"))
        assertFalse(LimbusActivityCompat.isRuntimePermissionRequestAction(null))
    }

    @Test
    fun `google identity UI restores only the Limbus OAuth business identity`() {
        assertTrue(LimbusActivityCompat.shouldRestoreGoogleOAuthClientPackage(
            "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN",
            "com.example.limbuszhcn",
            "com.example.limbuszhcn"))
        assertTrue(LimbusActivityCompat.shouldRestoreGoogleOAuthClientPackage(
            "com.google.android.gms.auth.api.credentials.GOOGLE_SIGN_IN",
            "com.example.limbuszhcn",
            "com.example.limbuszhcn"))
        assertFalse(LimbusActivityCompat.shouldRestoreGoogleOAuthClientPackage(
            "android.intent.action.VIEW",
            "com.example.limbuszhcn",
            "com.example.limbuszhcn"))
        assertFalse(LimbusActivityCompat.shouldRestoreGoogleOAuthClientPackage(
            "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN",
            "com.ProjectMoon.LimbusCompany",
            "com.example.limbuszhcn"))
    }

    @Test
    fun `only virtual microG authorization preserves the game broker package`() {
        assertTrue(ServiceConnectionDelegate.shouldPreserveLimbusGmsBusinessPackage(
            "com.google.android.gms",
            "org.microg.gms.auth.credentials.identity.AuthorizationService"))
        assertFalse(ServiceConnectionDelegate.shouldPreserveLimbusGmsBusinessPackage(
            "com.google.android.gms",
            "org.microg.gms.measurement.MeasurementService"))
        assertFalse(ServiceConnectionDelegate.shouldPreserveLimbusGmsBusinessPackage(
            "com.google.android.gms",
            "com.google.android.gms.auth.api.identity.service.AuthorizationService"))
    }

    @Test
    fun `modern Android promotes the virtual server service directly`() {
        assertFalse(KeepAliveService.useDirectForegroundPromotion(25))
        assertTrue(KeepAliveService.useDirectForegroundPromotion(26))
        assertTrue(KeepAliveService.useDirectForegroundPromotion(33))
    }

    @Test
    fun `firebase auth listeners retain native callback ABI`() {
        assertTrue(Modifier.isNative(JniAuthStateListener::class.java
            .getDeclaredMethod("nativeOnAuthStateChanged", Long::class.javaPrimitiveType).modifiers))
        assertTrue(Modifier.isNative(JniIdTokenListener::class.java
            .getDeclaredMethod("nativeOnIdTokenChanged", Long::class.javaPrimitiveType).modifiers))
        val phone = JniAuthPhoneListener::class.java
        assertTrue(Modifier.isNative(phone.getDeclaredMethod(
            "nativeOnVerificationCompleted", Long::class.javaPrimitiveType,
            com.google.firebase.auth.PhoneAuthCredential::class.java).modifiers))
        assertTrue(Modifier.isNative(phone.getDeclaredMethod(
            "nativeOnVerificationFailed", Long::class.javaPrimitiveType,
            String::class.java).modifiers))
        assertTrue(Modifier.isNative(phone.getDeclaredMethod(
            "nativeOnCodeSent", Long::class.javaPrimitiveType, String::class.java,
            com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken::class.java).modifiers))
        assertTrue(Modifier.isNative(phone.getDeclaredMethod(
            "nativeOnCodeAutoRetrievalTimeOut", Long::class.javaPrimitiveType,
            String::class.java).modifiers))
    }
}
