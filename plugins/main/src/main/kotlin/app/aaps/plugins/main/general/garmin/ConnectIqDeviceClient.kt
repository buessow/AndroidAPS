package app.aaps.plugins.main.general.garmin

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQMessage
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ConnectIqDeviceClient(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val receiver: ConnectIqReceiver,
    private val disconnect: (ConnectIqClient) -> Unit): Disposable, ConnectIqClient {

    override val name = "Device"
    private var bindLock = Object()
    // private var ciqService: IConnectIQService? = null
    //     get() {
    //         synchronized (bindLock) {
    //             if (state == State.BINDING) return null
    //             if (field?.asBinder()?.isBinderAlive == false) {
    //                 field = null
    //             }
    //             if (field == null) {
    //                 connectService()
    //             }
    //             return field
    //         }
    //     }

    private val registeredActions = ConcurrentHashMap<String, Boolean>()
    private val broadcastReceiver = mutableListOf<BroadcastReceiver>()
    private var state = State.DISCONNECTED
    private val registeredApplications = mutableSetOf<String>()
    private val sendMessageAction = getAction("SEND_MESSAGE")
    private val knownDevicesMap = mutableMapOf<Long, GarminDevice>()
    private val appIdNames = mutableMapOf<String, String>()

    override val connectedDevices
        get() = transactList(Operation.GET_CONNECTED_DEVICES, emptyList(), GarminDevice.CREATOR)
    override val knownDevices get() = knownDevicesMap.values.iterator().asSequence().toList()

    private enum class State {
        BINDING,
        CONNECTED,
        DISCONNECTED,
        DISPOSED,
    }

    @Suppress("unused")
    private enum class Operation(val code: Int) {
        OPEN_STORE(1),
        GET_CONNECTED_DEVICES(2),
        GET_KNOWN_DEVICES(3),
        GET_STATUS(4),
        GET_APPLICATION_INFO(5),
        OPEN_APPLICATION(6),
        SEND_MESSAGE(7),
        SEND_IMAGE(8),
        REGISTER_APP(9),
    }

    private var service: IBinder? = null

    private val ciqServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            var connecting: Boolean
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App connected")
                this@ConnectIqDeviceClient.service = service
                // ciqService = IConnectIQService.Stub.asInterface(service)
                connecting = state != State.CONNECTED
                state = State.CONNECTED
            }
            loadKnownDevices()
            if (connecting) receiver.onConnect(this@ConnectIqDeviceClient)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App disconnected")
                service = null
                if (state != State.DISPOSED) state = State.DISCONNECTED
            }
            broadcastReceiver.forEach { br -> context.unregisterReceiver(br) }
            broadcastReceiver.clear()
            registeredActions.clear()
            registeredApplications.clear()
            disconnect(this@ConnectIqDeviceClient)
        }
    }

    private fun connectService() {
        if (state == State.BINDING) return
        aapsLogger.info(LTag.GARMIN, "binding to Garmin service")
        registerReceiver(sendMessageAction, ::onSendMessage)
        state = State.BINDING
        val serviceIntent = Intent("com.garmin.android.apps.connectmobile.CONNECTIQ_SERVICE_ACTION")
        serviceIntent.component = ComponentName(
            "com.garmin.android.apps.connectmobile",
            "com.garmin.android.apps.connectmobile.connectiq.ConnectIQService")
        context.bindService(serviceIntent, ciqServiceConnection, Context.BIND_AUTO_CREATE)
    }

    init {
        connectService()
   }

    override fun isDisposed() = state == State.DISPOSED
    override fun dispose() {
        broadcastReceiver.forEach { context.unregisterReceiver(it) }
        broadcastReceiver.clear()
        registeredActions.clear()
        registeredActions.clear()
        try {
            context.unbindService(ciqServiceConnection)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.GARMIN, "unbind CIQ failed ${e.message}")
        }
        service = null
        state = State.DISPOSED
    }

    private fun getAction(action: String) = "${javaClass.`package`!!.name}.$action"
    private fun registerReceiver(action: String, receive: (intent: Intent) -> Unit) {
        if (registeredActions.put(action, true) != null) {
            aapsLogger.info(LTag.GARMIN, "registerReceiver $action already registered")
            return
        }
        val recv = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) { receive(intent) }
        }
        broadcastReceiver.add(recv)
        context.registerReceiver(recv, IntentFilter(action))
    }

    private fun <T> transact(op: Operation, args: List<Any>, flags: Int, creator: (Parcel)->T): T? {
        val inData = Parcel.obtain()
        val outData = Parcel.obtain()
        try {
            inData.writeInterfaceToken("com.garmin.android.apps.connectmobile.connectiq.IConnectIQService")
            for(arg in args) {
                when (arg) {
                    is Int -> inData.writeInt(arg)
                    is Long -> inData.writeLong(arg)
                    is String -> inData.writeString(arg)
                    is Parcelable -> {
                        inData.writeInt(1)
                        arg.writeToParcel(inData, 0)
                    }
                    else -> throw IllegalArgumentException("unsupported argument type ${arg.javaClass} '$arg'")
                }
            }

            var result: T? = null
            try {
                val localService = service
                if (localService != null &&
                    localService.isBinderAlive &&
                    localService.pingBinder()) {
                    localService.transact(op.code, inData, outData, flags)
                } else {
                    // If service != null but it's not alive or we cannot ping it
                    // disconnect to force creation of a new ConnectIqDeviceClient instance
                    // and reconnect.
                    aapsLogger.warn(LTag.GARMIN, "IQConnectService disconnected $state")
                    if (state != State.BINDING) {
                        service = null
                        state = State.DISCONNECTED
                        disconnect(this)
                    }
                    return null
                }
                outData.readException()
                return creator(outData)?.also { result = it }
            } finally {
                aapsLogger.info(LTag.GARMIN, "calling $op(${args.joinToString(", ")}) = $result")
            }
        } finally {
            inData.recycle()
            outData.recycle()
        }
    }

    @Suppress("unused")
    private fun <T> transact(op: Operation, args: List<Any>, flags: Int, creator: Parcelable.Creator<T>): T? {
       return transact(op, args, flags) { p -> creator.createFromParcel(p) }
    }

    private fun <T> transactList(op: Operation, args: List<Any>, creator: Parcelable.Creator<T>): List<T>  {
        return transact(op, args, 0) { p -> mutableListOf<T>().also { l -> p.readTypedList(l, creator) }} ?: emptyList()
    }

    private fun loadKnownDevices() {
        transactList(Operation.GET_KNOWN_DEVICES, emptyList(), GarminDevice.CREATOR).forEach {
            knownDevicesMap[it.id] = it
        }
    }

    override fun getDeviceStatus(device: GarminDevice): GarminDevice.Status {
        try {
            val status = transact(Operation.GET_STATUS, listOf(device), 0) { p -> p.readInt() }
            return GarminDevice.Status
                .values()
                .firstOrNull { it.ordinal == status } ?: GarminDevice.Status.UNKNOWN
        } catch(e: Exception) {
            aapsLogger.error(LTag.GARMIN, "getDeviceStatus failed", e)
            return GarminDevice.Status.UNKNOWN
        }
    }

    override fun retrieveApplicationInfo(device: GarminDevice, appId: String, appName: String) {
        appIdNames[appId] = appName
        aapsLogger.info(LTag.GARMIN, "app info $device $appId")
        val action = getAction("APPLICATION_INFO_${device.id}_$appId")
        registerReceiver(action) { intent -> onApplicationInfo(appId, device, intent) }
        transact(Operation.GET_APPLICATION_INFO, listOf(context.packageName, action, device, appId), IBinder.FLAG_ONEWAY) {}
    }

    private fun onApplicationInfo(appId: String, device: GarminDevice, intent: Intent) {
        val receivedAppId = intent.getStringExtra(
            "com.garmin.android.connectiq.EXTRA_APPLICATION_ID")?.lowercase(Locale.getDefault())
        val version = intent.getIntExtra(
            "com.garmin.android.connectiq.EXTRA_APPLICATION_VERSION", -1)
        val status = if (receivedAppId == null || version < 0 || version == 65535)
            IQApp.IQAppStatus.NOT_INSTALLED
        else
            IQApp.IQAppStatus.INSTALLED
        val oappid = if (appId == receivedAppId) "" else "!$appId"
        aapsLogger.info(LTag.GARMIN, "app result $name $device $receivedAppId $oappid v=$version s=$status")

        val app = IQApp(receivedAppId, status, appIdNames[receivedAppId], version)
        if (status == IQApp.IQAppStatus.INSTALLED) registerForMessages(appId)
        receiver.onApplicationInfo(DeviceApplication(this, device, app))
    }

    private fun registerForMessages(appId: String) {
        if (registeredApplications.contains(appId)) return
        aapsLogger.info(LTag.GARMIN, "registerForMessage $name ${appId}")
        val a = getAction("ON_MESSAGE_$appId")
        val app = IQApp(appId, appIdNames[appId], 1)
        registerReceiver(a) { intent: Intent -> onReceiveMessage(app, intent) }
        transact(Operation.REGISTER_APP, listOf(app, a, context.packageName), 0) {}
        registeredApplications.add(app.applicationId)
    }

    @Suppress("Deprecation")
    private fun onReceiveMessage(app: IQApp, intent: Intent) {
        val iqDevice = intent.getParcelableExtra(
            "com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE") as IQDevice?
        val data = intent.getByteArrayExtra("com.garmin.android.connectiq.EXTRA_PAYLOAD")
        if (iqDevice != null && data != null)
            receiver.onReceiveMessage(this, getDevice(iqDevice.deviceIdentifier), app, data)
    }

    private fun onSendMessage(intent: Intent) {
        val statusOrd = intent.getIntExtra("com.garmin.android.connectiq.EXTRA_STATUS", 0)
        val device = getDevice(intent)
        val appId =
            intent.getStringExtra("com.garmin.android.connectiq.EXTRA_APPLICATION_ID")?.lowercase()
        if (device == null || appId == null) {
            aapsLogger.warn(LTag.GARMIN, "onSendMessage device='$device' app='$appId'")
        } else {
            val status = ConnectIQ.IQMessageStatus.values().firstOrNull { it.ordinal == statusOrd }
                ?: ConnectIQ.IQMessageStatus.FAILURE_UNKNOWN
            val da = DeviceApplication(this, device, IQApp(appId))
            val (retry, data) = synchronized(pendingMessages) {
                pendingMessages.remove(da) ?: (null to null)
            }
            aapsLogger.info(
                LTag.GARMIN,
                "onSendMessage dev='$device' app='$appId' s=$statusOrd/$status r=$retry")
            val canRetry = when (status) {
                ConnectIQ.IQMessageStatus.FAILURE_DURING_TRANSFER -> true
                ConnectIQ.IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> true
                else -> false
            }

            if (canRetry && retry != null && retry < 9) {
                Schedulers.io().scheduleDirect(
                    { sendMessage(da, data!!, retry) }, retry * 10L, TimeUnit.SECONDS)
            }
        }
    }

    private fun getDevice(id: Long): GarminDevice {
        return knownDevicesMap.getOrElse(id) { GarminDevice(id, "?")}
    }

    @Suppress("Deprecation")
    private fun getDevice(intent: Intent): GarminDevice? {
        val rawDevice = intent.extras?.get("com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE")
        val deviceId = if (rawDevice is Long) rawDevice else (rawDevice as IQDevice?)?.deviceIdentifier
            ?: return null
        return knownDevicesMap[deviceId]
            ?: GarminDevice(deviceId, (rawDevice as? IQDevice)?.friendlyName ?: "?")
    }

    private val pendingMessages = mutableMapOf<DeviceApplication, Pair<Int, ByteArray>>()

    override fun sendMessage(da: DeviceApplication, data: ByteArray) {
      sendMessage(da, data, 0)
    }

    private fun sendMessage(da: DeviceApplication, data: ByteArray, retry: Int) {
        aapsLogger.info(LTag.GARMIN, "sendMessage $da r=$retry")
        synchronized(pendingMessages) {
          if (pendingMessages.contains(da) && retry > 0) {
              aapsLogger.warn(LTag.GARMIN,  "skip retry for $da")
              return
          }
          pendingMessages.put(da, retry + 1 to data)
        }
        val iqMsg = IQMessage(data, context.packageName, sendMessageAction)
        transact(Operation.SEND_MESSAGE, listOf(iqMsg, da.device, da.app), 0) {}
    }

    override fun toString() = "$name[$state]"
}