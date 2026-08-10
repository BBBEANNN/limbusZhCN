package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.StorageManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageManagerCompatibilityTest {
    @Test
    fun android12AndOlderVolumeListUsesHostUid() {
        assertEquals(
            10624,
            StorageManagerCompat.getVolumeListCallerIdentity(32, 10624, 0)
        )
    }

    @Test
    fun android13AndNewerVolumeListUsesHostUserId() {
        assertEquals(
            0,
            StorageManagerCompat.getVolumeListCallerIdentity(33, 10624, 0)
        )
        assertEquals(
            10,
            StorageManagerCompat.getVolumeListCallerIdentity(35, 1010624, 10)
        )
    }
}
