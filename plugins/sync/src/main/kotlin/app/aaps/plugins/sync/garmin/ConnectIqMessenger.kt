package app.aaps.plugins.sync.garmin


import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.garmin.android.connectiq.IQApp
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ConnectIqMessenger(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    applicationIdNames: Map<String, String>,
    private val onConnectDevice: (device: GarminDevice) -> Unit,
    private val onMessage: (app: DeviceApplication, msg: Any) -> Unit,
    enableDebug: Boolean): Disposable, ConnectIqReceiver {

    private var disposed: Boolean = false
    private val devices = ConcurrentHashMap<Long, DeviceInfo>()
    private val connectedAppIds = mutableSetOf<DeviceApplication>()
    private val clients = mutableListOf<ConnectIqClient>()
    private val appIdNames = mutableMapOf<String, String>()

    init {
        aapsLogger.info(LTag.GARMIN, "init CIQ debug=$enableDebug")
        appIdNames.putAll(applicationIdNames)
        startIqDeviceClient()
        if (enableDebug) {
            appIdNames["SimAp"] = "SimulatorApp"
            ConnectIqSimulatorClient(aapsLogger, this)
        }
    }

    private fun onIqDeviceClientStopped(client: ConnectIqClient) {
        aapsLogger.info(LTag.GARMIN, "onIqDeviceClientStopped ${client.name}")
        clients.remove(client)
        client.dispose()
        startIqDeviceClient()
    }

    private fun startIqDeviceClient() {
        ConnectIqDeviceClient(aapsLogger,  context, this, ::onIqDeviceClientStopped)
    }

    override fun onConnect(client: ConnectIqClient) {
        aapsLogger.info(LTag.GARMIN, "onConnect $client")
        clients.add(client)
        client.knownDevices.forEach { d -> onConnectDevice(client, d) }
    }

    private fun name(device: GarminDevice): String {
        return device.name.takeUnless { it.isEmpty() }
            ?: devices[device.id]?.device?.name?.takeUnless { it.isEmpty() }
            ?: device.id.toString()
    }

    override fun onDisconnect(client: ConnectIqClient) {
        aapsLogger.info(LTag.GARMIN, "onDisconnect ${client.name}")
        clients.remove(client)
    }

    override fun onConnectDevice(client: ConnectIqClient, device: GarminDevice) {
        val deviceInfo = DeviceInfo(client, device)
        devices[device.id] = deviceInfo
        deviceInfo.pendingApplicationInfo.addAndGet(appIdNames.size)
        val deviceStatus = client.getDeviceStatus(device)
        aapsLogger.info(
            LTag.GARMIN,
            "device-$client '$device' ${device.id} $deviceStatus")
        appIdNames.forEach { (id, name) -> client.retrieveApplicationInfo(device, id, name) }
    }

    override fun onDisconnectDevice(client: ConnectIqClient, device: GarminDevice) {
        devices.remove(device.id)
        aapsLogger.info(
            LTag.GARMIN,
            "device-$client '$device' ${device.id} removed")
    }

    override fun onApplicationInfo(deviceApplication: DeviceApplication) {
        if (deviceApplication.isInstalled) {
            aapsLogger.info(LTag.GARMIN, "adding $deviceApplication")
            connectedAppIds.add(deviceApplication)
        }
        val devInfo = devices[deviceApplication.device.id]
        if (devInfo != null && devInfo.pendingApplicationInfo.decrementAndGet() == 0) {
            onConnectDevice(devInfo.device)
        }
    }

    override fun onReceiveMessage(client: ConnectIqClient, device: GarminDevice, app: IQApp, data: ByteArray) {
        val deviceApplication = DeviceApplication(client, device, app)
        if (!connectedAppIds.contains(deviceApplication)) connectedAppIds.add(deviceApplication)

        val msg = ConnectIqSerializer.deserialize(data)
        if (msg == null) {
            aapsLogger.warn(LTag.GARMIN, "receive NULL msg")
        } else {
            aapsLogger.info(LTag.GARMIN, "receive ${data.size} bytes")
            onMessage(DeviceApplication(client, device, app), msg)
        }
    }

    override fun onSendMessage(device: GarminDevice, app: IQApp, errorMessage: String?) {
        aapsLogger.info(LTag.GARMIN, "" +
            "${name(device)} ${appIdNames[app.applicationId] ?: app} ${errorMessage ?: "OK"}")
    }

    fun sendMessage(device: GarminDevice, msg: Any) {
        connectedAppIds
            .filter { a -> a.device.id == device.id }
            .forEach { a -> sendMessage(a, msg) }
    }

    fun sendMessage(msg: Any) {
        val connected = mutableSetOf<GarminDevice>().apply { clients.forEach { addAll(it.connectedDevices)}}
        connectedAppIds.filter { connected.contains(it.device) }
            .also { aapsLogger.info(LTag.GARMIN, "sending $msg to ${it.joinToString()} of ${connected.joinToString()}") }
            .forEach { a -> sendMessage(a, msg) }
    }

    fun sendMessage(app: DeviceApplication, msg: Any) {
        val s = when (msg) {
            is Map<*,*> ->
                msg.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=$v" }
            is List<*> ->
                msg.joinToString(", ", "(", ")")
            else ->
                msg.toString()
        }
        val data = ConnectIqSerializer.serialize(msg)
        aapsLogger.info(LTag.GARMIN, "sendMessage $app ${appIdNames[app.app.applicationId]} ${data.size} bytes $s")
        try {
            app.client.sendMessage(app, data)
        } catch (e: IllegalStateException) {
            aapsLogger.error(LTag.GARMIN, "${app.client} not connected", e)
        }
    }

    private data class DeviceInfo(
        val client: ConnectIqClient,
        val device: GarminDevice,
        val installedApps: MutableList<IQApp> = mutableListOf(),
        val pendingApplicationInfo: AtomicInteger = AtomicInteger(0))

    override fun dispose() {
        if (!disposed) {
            clients.forEach { c -> c.dispose() }
            disposed = true
        }
        clients.clear()
    }

    override fun isDisposed() = disposed
}