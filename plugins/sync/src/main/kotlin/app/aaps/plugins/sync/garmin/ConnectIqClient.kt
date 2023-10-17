package app.aaps.plugins.sync.garmin

import io.reactivex.rxjava3.disposables.Disposable

interface ConnectIqClient: Disposable {
    val name: String
    val connectedDevices: List<GarminDevice>
    val knownDevices: List<GarminDevice>
    fun getDeviceStatus(device: GarminDevice): GarminDevice.Status
    fun retrieveApplicationInfo(device: GarminDevice, appId: String, appName: String)
    fun sendMessage(da: DeviceApplication, data: ByteArray)
}