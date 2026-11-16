package com.example.limbuszhcn.container

import android.app.IBinderSession
import android.content.ComponentName
import android.os.BadParcelableException
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.lody.virtual.helper.compat.ServiceConnectionCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ServiceConnectionCompatTest {
    interface LegacyConnection {
        fun connected(name: ComponentName?, service: IBinder?)
    }

    interface OreoConnection {
        fun connected(name: ComponentName?, service: IBinder?, dead: Boolean)
    }

    interface SessionConnection {
        fun connected(name: ComponentName?, service: IBinder?, session: IBinderSession?, dead: Boolean)
    }

    interface UnrelatedFourArgumentConnection {
        fun connected(name: ComponentName?, service: IBinder?, session: Any?, dead: Boolean)
    }

    @Test
    fun detectsTheRuntimeSignatureInsteadOfAssumingAnAndroidVersion() {
        assertFalse(ServiceConnectionCompat.usesBinderSession(LegacyConnection::class.java))
        assertFalse(ServiceConnectionCompat.usesBinderSession(OreoConnection::class.java))
        assertFalse(ServiceConnectionCompat.usesBinderSession(UnrelatedFourArgumentConnection::class.java))
        assertTrue(ServiceConnectionCompat.usesBinderSession(SessionConnection::class.java))
        assertFalse(ServiceConnectionCompat.isSessionAware()) // API 35 retains the legacy path.
    }

    @Test
    fun forwardsTheOriginalServiceAndSessionBinderWithTheResolvedComponent() {
        val component = ComponentName("com.google.android.gms", "example.MeasurementService")
        val service = Binder()
        val session = Binder()
        var received: ServiceConnectionCompat.SessionConnection? = null
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(IBinder.FIRST_CALL_TRANSACTION, code)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                received = ServiceConnectionCompat.readSessionConnected(data)
                return true
            }
        }
        ServiceConnectionCompat.sendSessionConnected(target, component, service, session, true)
        val callback = requireNotNull(received)
        assertEquals(component, callback.name)
        assertSame(service, callback.service)
        assertSame(session, callback.session)
        assertTrue(callback.dead)
    }

    @Test
    fun preservesNullBindersAndNullComponentForDisconnectCallbacks() {
        var received: ServiceConnectionCompat.SessionConnection? = null
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                received = ServiceConnectionCompat.readSessionConnected(data)
                return true
            }
        }
        ServiceConnectionCompat.sendSessionConnected(target, null, null, null, false)
        val callback = requireNotNull(received)
        assertNull(callback.name)
        assertNull(callback.service)
        assertNull(callback.session)
        assertFalse(callback.dead)
    }

    @Test(expected = SecurityException::class)
    fun rejectsATransactionForAnotherInterface() {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.example.UnrelatedInterface")
            data.setDataPosition(0)
            ServiceConnectionCompat.readSessionConnected(data)
        } finally {
            data.recycle()
        }
    }

    @Test(expected = BadParcelableException::class)
    fun rejectsUnexpectedTrailingArguments() {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ServiceConnectionCompat.DESCRIPTOR)
            data.writeInt(0)
            data.writeStrongBinder(null)
            data.writeStrongBinder(null)
            data.writeInt(0)
            data.writeInt(123)
            data.setDataPosition(0)
            ServiceConnectionCompat.readSessionConnected(data)
        } finally {
            data.recycle()
        }
    }

    @Test(expected = RemoteException::class)
    fun reportsAnUnimplementedCallbackInsteadOfSilentlyDroppingTheConnection() {
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }
        ServiceConnectionCompat.sendSessionConnected(target, null, null, null, false)
    }
}
