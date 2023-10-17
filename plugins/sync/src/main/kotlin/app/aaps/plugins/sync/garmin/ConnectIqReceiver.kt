package app.aaps.plugins.sync.garmin

import com.garmin.android.connectiq.IQApp

/**
 * Callback interface for a @see ConnectIqClient.
 */
interface ConnectIqReceiver {
    /**
     * Notifies that the client is ready, i.e. the app client as bound to the Garmin
     * Android app.
     */
    fun onConnect(client: ConnectIqClient)
    fun onDisconnect(client: ConnectIqClient)

    /**
     * Notifies that a device is connected. This will be called for all connected devices
     * initially.
     */
    fun onConnectDevice(client: ConnectIqClient, device: GarminDevice)
    fun onDisconnectDevice(client: ConnectIqClient, device: GarminDevice)

    /**
     * Provides application info after a call to
     * {@link ConnectIqClient#retrieveApplicationInfo retrieveApplicationInfo}.
     */
    fun onApplicationInfo(deviceApplication: DeviceApplication)

    /**
     * Delivers received device app messages.
     */
    fun onReceiveMessage(client: ConnectIqClient, device: GarminDevice, app: IQApp, data: ByteArray)

    /**
     * Delivers status of @see ConnectIqClient#sendMessage requests.
     */
    fun onSendMessage(device: GarminDevice, app: IQApp, errorMessage: String?)
}