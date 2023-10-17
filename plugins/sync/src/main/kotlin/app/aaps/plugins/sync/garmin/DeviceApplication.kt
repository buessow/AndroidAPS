package app.aaps.plugins.sync.garmin

import com.garmin.android.connectiq.IQApp

data class DeviceApplication(
    val client: ConnectIqClient,
    val device: GarminDevice,
    val app: IQApp) {

    val isInstalled = app.status == IQApp.IQAppStatus.INSTALLED

    override fun toString() = "$client-$device ${app.applicationId} $app"

    override fun equals(other: Any?): Boolean {
        return if (other is DeviceApplication) {
            (device.id == other.device.id
                && app.applicationId == other.app.applicationId)
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return device.id.hashCode() xor app.applicationId.hashCode()
    }
}

