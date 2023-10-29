package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.garmin.android.connectiq.IQApp
import io.reactivex.rxjava3.disposables.Disposable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ConnectIqSimulatorClient(
    private val aapsLogger: AAPSLogger,
    private val receiver: ConnectIqReceiver,
    private val port: Int = 7381
): Disposable, ConnectIqClient {

    override val name = "Sim"
    private val executor: Executor = Executors.newCachedThreadPool()
    private val socket = ServerSocket()
    private val connections: MutableList<Connection> = Collections.synchronizedList(mutableListOf())
    private var nextDeviceId = AtomicLong(1)
    private val app = IQApp("SimApp", IQApp.IQAppStatus.INSTALLED, "Simulator", 1)

    private inner class Connection(private val socket: Socket): Disposable {
        val device = GarminDevice(
            nextDeviceId.getAndAdd(1),
            "Sim@${socket.remoteSocketAddress}")

        fun start() {
            executor.execute {
                try {
                    receiver.onConnectDevice(this@ConnectIqSimulatorClient, device)
                    run()
                } catch (e: Throwable) {
                  aapsLogger.error(LTag.GARMIN, "$device failed", e)
                }
            }
        }

        fun getDeviceApplication(appId: String): DeviceApplication {
            return if (appId == app.applicationId)
                DeviceApplication(this@ConnectIqSimulatorClient, device, app)
            else
                DeviceApplication(
                    this@ConnectIqSimulatorClient,
                    device,
                    IQApp(appId, IQApp.IQAppStatus.NOT_INSTALLED, null,0))
        }

        fun send(data: ByteArray) {
            if (socket.isConnected && !socket.isOutputShutdown) {
                aapsLogger.info(LTag.GARMIN, "sending ${data.size} bytes to $device")
                socket.outputStream.write(data)
                socket.outputStream.flush()
            } else {
                aapsLogger.warn(LTag.GARMIN, "socket closed, cannot send $device")
            }
        }

        private fun run() {
            socket.soTimeout = 0
            socket.isInputShutdown
            while (!socket.isClosed && socket.isConnected) {
                try {
                    val data = readAvailable(socket.inputStream) ?: break
                    if (data.isNotEmpty()) {
                        kotlin.runCatching {
                            receiver.onReceiveMessage(this@ConnectIqSimulatorClient, device, app, data)
                        }
                    }
                } catch (e: SocketException) {
                  aapsLogger.warn(LTag.GARMIN, "socket read failed ${e.message}")
                }
            }
            aapsLogger.info(LTag.GARMIN, "disconnect ${device.name}" )
            connections.remove(this)
            receiver.onDisconnectDevice(this@ConnectIqSimulatorClient, device)
        }

        private fun readAvailable(input: InputStream): ByteArray? {
            val buffer = ByteArray(1 shl 14)
            aapsLogger.info(LTag.GARMIN, "$device reading")
            val len = input.read(buffer)
            aapsLogger.info(LTag.GARMIN, "$device read $len bytes")
            if (len < 0) {
                return null
            }
            val data = ByteArray(len)
            System.arraycopy(buffer, 0, data, 0, data.size)
            return data
        }

        override fun dispose() {
            aapsLogger.info(LTag.GARMIN, "close $device")

            @Suppress("EmptyCatchBlock")
            try {
                socket.close()
            } catch (e: SocketException) {
                aapsLogger.warn(LTag.GARMIN, "closing socket failed ${e.message}")
            }
        }

        override fun isDisposed() = socket.isClosed
    }

    init {
        executor.execute {
            runCatching(::listen).exceptionOrNull()?.let { e->
                aapsLogger.error(LTag.GARMIN, "listen failed", e)
            }
        }
    }

    private fun listen() {
        val ip = Inet4Address.getByAddress(byteArrayOf(127, 0, 0, 1))
        aapsLogger.info(LTag.GARMIN, "bind to $ip:$port")
        socket.bind(InetSocketAddress(ip, port))
        receiver.onConnect(this@ConnectIqSimulatorClient)
        while (!socket.isClosed) {
            val s = socket.accept()
            aapsLogger.info(LTag.GARMIN, "accept " + s.remoteSocketAddress)
            connections.add(Connection(s))
            connections.last().start()
        }
        receiver.onDisconnect(this@ConnectIqSimulatorClient)
    }

    override fun dispose() {
        connections.forEach { c -> c.dispose() }
        connections.clear()
        socket.close()
    }

    override fun isDisposed() = socket.isClosed

    override val connectedDevices: List<GarminDevice> get() = connections.map { c -> c.device }
    override val knownDevices: List<GarminDevice> get() = connections.map { c -> c.device }

    override fun retrieveApplicationInfo(device: GarminDevice, appId: String, appName: String) {
        connections.forEach { c ->
            receiver.onApplicationInfo(c.getDeviceApplication(app.applicationId))
        }
    }

    private fun getConnection(device: GarminDevice): Connection? {
        return connections.firstOrNull { c -> c.device.id == device.id }
    }

    override fun getDeviceStatus(device: GarminDevice): GarminDevice.Status {
        return if (getConnection(device) == null)
            GarminDevice.Status.NOT_CONNECTED
        else GarminDevice.Status.CONNECTED
    }

    override fun sendMessage(da: DeviceApplication, data: ByteArray) {
        val c = getConnection(da.device) ?: return
        try {
            c.send(data)
        } catch (e: SocketException) {
            aapsLogger.error(LTag.GARMIN, "sending failed '${e.message}'")
            c.dispose()
            connections.remove(c)
        }
    }

    override fun toString() = name
}