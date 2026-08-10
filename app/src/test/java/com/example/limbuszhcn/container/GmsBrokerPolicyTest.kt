package com.example.limbuszhcn.container

import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsBrokerPolicyTest {
    @Test
    fun wrapsLimbusRequestsToGmsRegardlessOfWhereGmsIsInstalled() {
        assertTrue(
            ServiceConnectionDelegate.shouldWrapLimbusGmsServiceBroker(
                "com.google.android.gms",
                "com.ProjectMoon.LimbusCompany"
            )
        )
        assertTrue(
            ServiceConnectionDelegate.shouldWrapLimbusGmsServiceBroker(
                null,
                "com.ProjectMoon.LimbusCompany",
                "com.google.android.gms.common.internal.IGmsServiceBroker"
            )
        )
    }

    @Test
    fun ignoresOtherServicesAndGuestPackages() {
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusGmsServiceBroker(
                "com.example.service",
                "com.ProjectMoon.LimbusCompany"
            )
        )
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusGmsServiceBroker(
                "com.google.android.gms",
                "com.example.other"
            )
        )
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusGmsServiceBroker(
                null,
                "com.ProjectMoon.LimbusCompany",
                "com.example.service.IBroker"
            )
        )
    }
}
