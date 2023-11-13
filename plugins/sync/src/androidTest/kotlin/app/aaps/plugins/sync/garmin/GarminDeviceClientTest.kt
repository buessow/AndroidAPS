package app.aaps.plugins.sync.garmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.shared.tests.TestBase
import com.garmin.android.apps.connectmobile.connectiq.IConnectIQService
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class GarminDeviceClientTest: TestBase() {
    private val serviceDescriptor = "com.garmin.android.apps.connectmobile.connectiq.IConnectIQService"
    private lateinit var client: GarminDeviceClient
    private lateinit var serviceConnection: ServiceConnection
    private lateinit var device: GarminDevice
    private val packageName = "TestPackage"
    private val actions = mutableMapOf<String, BroadcastReceiver>()

    private val receiver = mock<GarminReceiver>()
    private val binder = mock<IBinder>() {
        on { isBinderAlive } doReturn true
    }
    private val ciqService = mock<IConnectIQService>() {
        on { asBinder() } doReturn binder
        on { connectedDevices } doReturn listOf(IQDevice().apply {
            deviceIdentifier = 1L
            friendlyName = "TDevice" })
    }
    private val context = mock<Context>() {
        on { packageName } doReturn this@GarminDeviceClientTest.packageName
        on { registerReceiver(any<BroadcastReceiver>(), any<IntentFilter>()) } doAnswer { i ->
            actions[i.getArgument<IntentFilter>(1).getAction(0)] = i.getArgument(0)
            Intent()
        }
        on { unregisterReceiver(any()) } doAnswer { i ->
            val keys = actions.entries.filter {(_, br) -> br == i.getArgument(0) }.map { (k, _) -> k }
            keys.forEach { k -> actions.remove(k) }
        }
        on { bindService(any(), any(), eq(Context.BIND_AUTO_CREATE)) }. doAnswer { i ->
            serviceConnection = i.arguments[1] as ServiceConnection
            true
        }
    }

    @Before
    fun setup() {
        client = GarminDeviceClient(aapsLogger, context, receiver, retryWaitFactor = 0L)
        device = GarminDevice(client, 1L, "TDevice")
        serviceConnection.onServiceConnected(
            GarminDeviceClient.CONNECTIQ_SERVICE_COMPONENT,
            Binder().apply { attachInterface(ciqService, serviceDescriptor) })
        verify(receiver).onConnect(client)
        verify(ciqService).connectedDevices
        verify(receiver).onConnectDevice(client, 1L, "TDevice")
    }

    @After
    fun shutdown() {
        if (::client.isInitialized) client.dispose()
        assertEquals(0, actions.size)  // make sure all broadcastReceivers were unregistered
        verify(context).unbindService(serviceConnection)
    }

    @Test
    fun connect() {
    }

    @Test
    fun disconnect() {
        serviceConnection.onServiceDisconnected(GarminDeviceClient.CONNECTIQ_SERVICE_COMPONENT)
        verify(receiver).onDisconnect(client)
        assertEquals(0, actions.size)
    }

    @Test
    fun reconnectDeadBinder() {
        val appId = "appid1"
        whenever(binder.isBinderAlive).thenReturn(false)
        val t = Thread { client.retrieveApplicationInfo(device, appId, "$appId-name") }.apply { start() }
        verify(ciqService, timeout(4000L).atLeastOnce()).asBinder()
        verifyNoMoreInteractions(ciqService)
        verify(context, timeout(4000L).times(2))
            .bindService(any(), any(), eq(Context.BIND_AUTO_CREATE))

        verify(ciqService).connectedDevices
        verify(receiver).onConnectDevice(client, device.id, device.name)

        whenever(binder.isBinderAlive).thenReturn(true)
        serviceConnection.onServiceConnected(
            GarminDeviceClient.CONNECTIQ_SERVICE_COMPONENT,
            Binder().apply { attachInterface(ciqService, serviceDescriptor) })
        verify(ciqService, times(2)).connectedDevices
        verify(receiver, times(2)).onConnectDevice(client, device.id, device.name)
        verify(ciqService, atLeastOnce()).asBinder()
        t.join()
        verify(ciqService).getApplicationInfo(
            eq(packageName), anyString(), argThat { deviceIdentifier == device.id }, eq(appId))
        verifyNoMoreInteractions(ciqService)
        verifyNoMoreInteractions(receiver)
    }

    @Test
    fun retrieveApplicationInfoAndSendMessage() {
        val appId = "appId1"
        var respAction: String? = null
        var msgAction: String? = null
        var iqDevice: IQDevice? = null
        whenever(ciqService.getApplicationInfo(any(), any(), any(), eq(appId)))
            .thenAnswer { i -> respAction = i.getArgument(1); iqDevice = i.getArgument(2); {} }
        whenever(ciqService.registerApp(any(IQApp::class.java), anyString(), eq(packageName)))
            .thenAnswer { i -> msgAction = i.getArgument(1); {}}
        client.retrieveApplicationInfo(device, appId, "$appId-name")
        val intent = Intent()
        intent.putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        intent.putExtra(GarminDeviceClient.EXTRA_APPLICATION_VERSION, 1)
        verify(ciqService).getApplicationInfo(eq(packageName), anyString(), any(IQDevice::class.java), eq(appId))
        assertEquals(device.id, iqDevice?.deviceIdentifier)
        actions[respAction]!!.onReceive(context, intent)
        verify(receiver).onApplicationInfo(device, appId, true)

        val data = "Hello, World!".toByteArray()
        val msgIntent = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_PAYLOAD, data)
        }
        actions[msgAction]!!.onReceive(context, msgIntent)
        verify(receiver).onReceiveMessage(eq(client), eq(device.id), eq(appId), eq(data))
    }

    @Test
    fun retrieveApplicationInfo_uninstalled() {
        val appId = "appId2"
        var respAction: String? = null
        var iqDevice: IQDevice? = null
        whenever(ciqService.getApplicationInfo(any(), any(), any(), eq(appId)))
            .thenAnswer { i -> 
                respAction = i.getArgument(1)
                iqDevice = i.getArgument(2); { }}
        client.retrieveApplicationInfo(device, appId, "$appId-name")
        val intent = Intent()
        intent.putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        intent.putExtra(GarminDeviceClient.EXTRA_APPLICATION_VERSION, 65535)
        verify(ciqService).getApplicationInfo(eq(packageName), anyString(), any(IQDevice::class.java), eq(appId))
        assertEquals(device.id, iqDevice?.deviceIdentifier)
        actions[respAction]!!.onReceive(context, intent)
        verify(receiver).onApplicationInfo(device, appId, false)
    }

    @Test
    fun sendMessage() {
        val appId = "appid1"
        val data = "Hello, World!".toByteArray()

        client.sendMessage(GarminApplication(client, device, appId, "$appId-name"), data)
        verify(ciqService).sendMessage(
            argThat { iqMsg -> data.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })

        val intent = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.SUCCESS)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent)
        actions[client.sendMessageAction]!!.onReceive(context, intent)  // extra on receive will be ignored
        verify(receiver).onSendMessage(client, device.id, appId, null)
    }

    @Test
    fun sendMessage_failNoRetry() {
        val appId = "appid1"
        val data = "Hello, World!".toByteArray()

        client.sendMessage(GarminApplication(client, device, appId, "$appId-name"), data)
        verify(ciqService).sendMessage(
            argThat { iqMsg -> data.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })

        val intent = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.FAILURE_MESSAGE_TOO_LARGE)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent)
        verify(receiver).onSendMessage(client, device.id, appId, "error 3")
    }

    @Test
    fun sendMessage_failRetry() {
        val appId = "appid1"
        val data = "Hello, World!".toByteArray()

        client.sendMessage(GarminApplication(client, device, appId, "$appId-name"), data)
        verify(ciqService).sendMessage(
            argThat { iqMsg -> data.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })

        val intent = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.FAILURE_DURING_TRANSFER)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent)
        verifyNoMoreInteractions(receiver)

        // Verify retry ...
        verify(ciqService, timeout(10000L).times( 2)).sendMessage(
            argThat { iqMsg -> data.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })

        intent.putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.SUCCESS)
        actions[client.sendMessageAction]!!.onReceive(context, intent)
        verify(receiver).onSendMessage(client, device.id, appId, null)
    }

    @Test
    fun sendMessage_2toSameApp() {
        val appId = "appid1"
        val data1 = "m1".toByteArray()
        val data2 = "m2".toByteArray()

        client.sendMessage(GarminApplication(client, device, appId, "$appId-name"), data1)
        client.sendMessage(GarminApplication(client, device, appId, "$appId-name"), data2)
        verify(ciqService).sendMessage(
            argThat { iqMsg -> data1.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })
        verify(ciqService, atLeastOnce()).asBinder()
        verifyNoMoreInteractions(ciqService)

        val intent = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.SUCCESS)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent)
        verify(receiver).onSendMessage(client, device.id, appId, null)

        verify(ciqService, timeout(5000L)).sendMessage(
            argThat { iqMsg -> data2.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId })

        actions[client.sendMessageAction]!!.onReceive(context, intent)
        verify(receiver, times(2)).onSendMessage(client, device.id, appId, null)
    }

    @Test
    fun sendMessage_2to2Apps() {
        val appId1 = "appid1"
        val appId2 = "appid2"
        val data1 = "m1".toByteArray()
        val data2 = "m2".toByteArray()

        client.sendMessage(GarminApplication(client, device, appId1, "$appId1-name"), data1)
        client.sendMessage(GarminApplication(client, device, appId2, "$appId2-name"), data2)
        verify(ciqService).sendMessage(
            argThat { iqMsg -> data1.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId1 })
        verify(ciqService, timeout(5000L)).sendMessage(
            argThat { iqMsg -> data2.contentEquals(iqMsg.messageData)
                && iqMsg.notificationPackage == packageName
                && iqMsg.notificationAction == client.sendMessageAction },
            argThat {iqDevice -> iqDevice.deviceIdentifier == device.id },
            argThat { iqApp -> iqApp?.applicationID == appId2 })

        val intent1 = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.SUCCESS)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId1)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent1)
        verify(receiver).onSendMessage(client, device.id, appId1, null)

        val intent2 = Intent().apply {
            putExtra(GarminDeviceClient.EXTRA_STATUS, IQMessage.SUCCESS)
            putExtra(GarminDeviceClient.EXTRA_REMOTE_DEVICE, device.toIQDevice())
            putExtra(GarminDeviceClient.EXTRA_APPLICATION_ID, appId2)
        }
        actions[client.sendMessageAction]!!.onReceive(context, intent2)
        verify(receiver).onSendMessage(client, device.id, appId2, null)
    }
}