package info.nightscout.plugins.general.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceFragmentCompat
import com.google.gson.JsonObject
import dagger.android.HasAndroidInjector
import info.nightscout.database.entities.GlucoseValue
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.plugin.PluginBase
import info.nightscout.interfaces.plugin.PluginDescription
import info.nightscout.interfaces.plugin.PluginType
import info.nightscout.plugins.R
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventNewBG
import info.nightscout.rx.events.EventPreferenceChange
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

@Singleton
class GarminPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    private val context: Context,
    private val loopHub: LoopHub,
    private val rxBus: RxBus,
    private val sp: SP,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.garmin)
        .shortName(R.string.garmin)
        .description(R.string.garmin_description)
        .preferencesId(R.xml.pref_garmin),
    aapsLogger, resourceHelper, injector
) {
    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    /** Garmin ConnectIQ application id for native communication. Phone pushes values. */
    private val glucoseAppIds = mapOf(
       "526ff91a542247a99e6656f3404156bb" to "GlucoseWatch",
       "c9e90ee7e6924829a8b45e7dafff5cb4" to "GlucoseWatch2",
       "1107ca6c2d5644b998d4bcb3793f2b7c" to "GlucoseDataField",
       "928fe19a4d3a4259b50cb6f9ddaf0f4a" to "GlucoseWidget",
    )

    @VisibleForTesting
    var ciqMessenger: ConnectIqMessenger? = null
    private val disposable = CompositeDisposable()

    /** Profiles for automatic profile switch when active. */
    @VisibleForTesting
    val profileSwitchSettings = listOf(
        ProfileSwitchSetting(
            sp.getString("garmin_default_profile_name", "Default"),
            "D",
            sp.getInt("garmin_default_profile_hr", 80)),
        ProfileSwitchSetting(
            sp.getString("garmin_active_profile_name", "Sport_75"),
            "A",
            sp.getInt("garmin_active_profile_hr", 90)),
        ProfileSwitchSetting(
            sp.getString("garmin_sport_profile_name", "Sport_50"),
            "S",
            sp.getInt("garmin_sport_profile_hr", 110))
    )

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    private val valueLock = ReentrantLock()
    @VisibleForTesting
    var newValue: Condition = valueLock.newCondition()
    private var lastGlucoseValueTimestamp: Long? = null
    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"

    private fun onPreferenceChange(event: EventPreferenceChange) {
        aapsLogger.info(LTag.GARMIN, "preferences change ${event.changedKey}")
        when (event.changedKey) {
            "communication_debug_mode" -> initializeCiqMessenger()
        }
    }

    private fun initializeCiqMessenger() {
        val enableDebug = sp.getBoolean("communication_debug_mode", false)
        ciqMessenger?.dispose()
        ciqMessenger = null
        if (sp.getBoolean("communication_ciq", false)) {
            aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
            ciqMessenger = ConnectIqMessenger(
                aapsLogger, context, glucoseAppIds, ::onConnectDevice, ::onMessage, enableDebug
            ).also { disposable.add(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        disposable.add(
            rxBus
                .toObservable(EventPreferenceChange::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onPreferenceChange)
        )
        disposable.add(
            rxBus
                .toObservable(EventNewBG::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onNewBloodGlucose)
        )
        server = HttpServer(aapsLogger, 28891)
        server!!.registerEndpoint("/get", object : HttpServer.Endpoint {
            override fun onRequest(
                caller: SocketAddress, uri: URI, requestBody: String?): CharSequence {
                return onGetBloodGlucose(caller, uri)
            }
        })
        server!!.registerEndpoint("/carbs", object : HttpServer.Endpoint {
            override fun onRequest(
                caller: SocketAddress, uri: URI, requestBody: String?): CharSequence {
                return onPostCarbs(caller, uri)
            }
        })
        server!!.registerEndpoint("/connect", object : HttpServer.Endpoint {
            override fun onRequest(caller: SocketAddress, uri: URI, requestBody: String?): CharSequence {
                return onConnectPump(caller, uri)
            }
        })

       /* initializeCiqMessenger() */
    }

    override fun onStop() {
        disposable.clear()
        aapsLogger.info(LTag.GARMIN, "Stop")
        server?.close()
        server = null
        super.onStop()
    }

    /** Receive new blood glucose events.
     *
     * Stores new blood glucose values in lastGlucoseValue to make sure we return
     * these values immediately when values are requested by Garmin device. Also
     * sends a message to the Garmin devices via the ciqMessenger. */
    @VisibleForTesting
    fun onNewBloodGlucose(event: EventNewBG) {
        val timestamp = event.glucoseValueTimestamp ?: return
        aapsLogger.info(LTag.GARMIN, "onNewBloodGlucose ${Date(timestamp)}")
        valueLock.withLock {
            if ((lastGlucoseValueTimestamp?: 0) >= timestamp) return
            lastGlucoseValueTimestamp = timestamp
            newValue.signalAll()
        }

        if (ciqMessenger != null) {
            Schedulers.io().scheduleDirect(
                { ciqMessenger?.sendMessage(getGlucoseMessage()) },
                3,
                TimeUnit.SECONDS
            )
        }
    }

    @VisibleForTesting
    fun onMessage(app: DeviceApplication, msg: Any) {
        if (msg is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            onMessage(app, msg as Map<String, Any>)
        } else {
            aapsLogger.error(LTag.GARMIN, "unsupported message from $app ${msg.javaClass}")
        }
    }

    private fun onConnectDevice(device: GarminDevice) {
        aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
        ciqMessenger!!.sendMessage(device, getGlucoseMessage())
    }

    private fun onMessage(app: DeviceApplication, msg: Map<String, Any>) {
        val cmd = msg["command"] ?: "unknown"
        val s = msg.entries.joinToString(",") { (k, v) -> "$k:${v.toString().take(100)}" }
        aapsLogger.info(LTag.GARMIN, "onMessage $app $cmd {$s}")
        Schedulers.io().scheduleDirect {
            when (cmd) {
                "ping"        ->
                    ciqMessenger!!.sendMessage(app, mapOf("command" to "pong"))
                "get_glucose" ->
                    ciqMessenger!!.sendMessage(app, getGlucoseMessage())
                "heartrate"   -> receiveHeartRate(msg, app.client.name == "Sim")
                "carbs"       -> loopHub.postCarbs(msg["carbs"] as Int)
            }
        }
    }

    @VisibleForTesting
    fun getGlucoseMessage() = mapOf<String, Any>(
        "command" to "glucose",
        "profile" to getProfileSwitchSetting(loopHub.currentProfileName).mnemonic,
        "encodedGlucose" to encodedGlucose(getGlucoseValues()),
        "remainingInsulin" to loopHub.insulinOnboard,
        "glucoseUnit" to glucoseUnitStr,
        "temporaryBasalRate" to
            (loopHub.temporaryBasal.takeIf(java.lang.Double::isFinite) ?: 1.0),
        "connected" to loopHub.isConnected,
        "timestamp" to clock.instant().epochSecond
    )

    /** Gets the last 2+ hours of glucose values. */
    @VisibleForTesting
    fun getGlucoseValues(): List<GlucoseValue> {
        val from = clock.instant().minus(Duration.ofHours(2).plusMinutes(9))
        return loopHub.getGlucoseValues(from, true)
    }

    /** Get the last 2+ hours of glucose values and waits in case a new value should arrive soon. */
    private fun getGlucoseValues(maxWait: Duration): List<GlucoseValue> {
        val glucoseFrequency = Duration.ofMinutes(5)
        val glucoseValues = getGlucoseValues()
        val last = glucoseValues.lastOrNull() ?: return emptyList()
        val delay = Duration.ofMillis(clock.millis() - last.timestamp)
        return if (!maxWait.isZero
            && delay > glucoseFrequency
            && delay < glucoseFrequency.plusMinutes(1)) {
            valueLock.withLock {
                aapsLogger.debug(LTag.GARMIN, "waiting for new glucose (delay=$delay)")
                newValue.awaitNanos(maxWait.toNanos())
            }
            getGlucoseValues()
        } else {
            glucoseValues
        }
    }

    private fun encodedGlucose(glucoseValues: List<GlucoseValue>): String {
        val encodedGlucose = DeltaVarEncodedList(glucoseValues.size * 16, 2)
        for (glucose: GlucoseValue in glucoseValues) {
            val timeSec: Int = (glucose.timestamp / 1000).toInt()
            val glucoseMgDl: Int = glucose.value.roundToInt()
            encodedGlucose.add(timeSec, glucoseMgDl)
        }
        aapsLogger.info(
            LTag.GARMIN,
            "retrieved ${glucoseValues.size} last ${Date(glucoseValues.lastOrNull()?.timestamp ?: 0L)} ${encodedGlucose.size}"
        )
        return encodedGlucose.encodedBase64()
    }

    /** Responses to get glucose value request by the device.
     *
     * Also, gets the heart rate readings from the device and adjusts the insulin profile
     * accordingly.
     */
    @VisibleForTesting
    fun onGetBloodGlucose(caller: SocketAddress, uri: URI): CharSequence {
        aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
        val profileMnemonic = receiveHeartRate(uri)
        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("glucoseUnit", glucoseUnitStr)
        loopHub.temporaryBasal.also {
            if (!it.isNaN()) jo.addProperty("temporaryBasalRate", it)
        }
        jo.addProperty("profile", profileMnemonic)
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString().also {
            aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
        }
    }

    private fun getProfileSwitchSetting(name: String) =
        profileSwitchSettings.firstOrNull { it.name.equals(name, true) }
            ?: profileSwitchSettings.first()

    /** Computes the new target profile based on the current profile and the heart rate. */
    @VisibleForTesting
    fun computeNewProfile(current: ProfileSwitchSetting, hr: Int): ProfileSwitchSetting {
        if (hr < 10) return current
        val lowerTo = profileSwitchSettings.firstOrNull {
            current.triggerHeartRate > it.triggerHeartRate &&  it.triggerHeartRate > hr }
        val raiseTo = profileSwitchSettings.lastOrNull {
            current.triggerHeartRate < it.triggerHeartRate && it.triggerHeartRate < hr }
        val target = raiseTo ?: lowerTo ?: current
        if (current != target) {
            aapsLogger.info(LTag.GARMIN, "HR: $hr profile: $current ->$target")
        }
        return target
    }

    private fun updateProfile(hr: Int): String {
        val current = getProfileSwitchSetting(loopHub.currentProfileName)
        return updateProfile(current, computeNewProfile(current, hr))
    }

    @VisibleForTesting
    fun updateProfile(current: ProfileSwitchSetting, target: ProfileSwitchSetting): String {
        if (current == target || loopHub.isTemporaryProfile) return current.mnemonic
        loopHub.switchProfile(target.name)
        return target.mnemonic
    }

    private fun getQueryParameter(uri: URI, name: String) = uri.query
        .split("&")
        .map { kv -> kv.split("=") }
        .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)

    private fun getQueryParameter(
        uri: URI,
        @Suppress("SameParameterValue") name: String,
        @Suppress("SameParameterValue") defaultValue: Boolean): Boolean {
        return when (getQueryParameter(uri, name)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }
    }

    private fun getQueryParameter(
        uri: URI, name: String,
        @Suppress("SameParameterValue") defaultValue: Long
    ): Long {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toLong()
        } catch (e: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
    }

    private fun toLong(v: Any?, @Suppress("SameParameterValue") default: Long): Long {
        return when (v) {
            is Long -> v
            is Int  -> v.toLong()
            else    -> default
        }
    }

    @VisibleForTesting
    fun receiveHeartRate(msg: Map<String, Any>, test: Boolean): String {
        val avg: Int = msg.getOrDefault("hr", 0) as Int
        val samplingStart: Long = toLong(msg["hrStart"], 0L)
        val samplingEnd: Long = toLong(msg["hrEnd"], 0L)
        val device: String? = msg["device"] as String?
        return receiveHeartRate(avg, samplingStart, samplingEnd, device, test)
    }

    @VisibleForTesting
    fun receiveHeartRate(uri: URI): String {
        val avg: Int = getQueryParameter(uri, "hr", 0L).toInt()
        val samplingStart: Long = getQueryParameter(uri, "hrStart", 0L)
        val samplingEnd: Long = getQueryParameter(uri, "hrEnd", 0L)
        val device: String? = getQueryParameter(uri, "device")
        return receiveHeartRate(
            avg, samplingStart, samplingEnd, device,
            getQueryParameter(uri, "test", false)
        )
    }

    private fun receiveHeartRate(
        avg: Int, samplingStart: Long, samplingEnd: Long, device: String?, test: Boolean): String {
        aapsLogger.info(LTag.GARMIN, "average heart rate $avg BPM test=$test")
        if (avg > 10 && !test) {
            loopHub.storeHeartRate(
                Instant.ofEpochMilli(samplingStart),
                Instant.ofEpochMilli(samplingEnd),
                avg,
                device)
        }
        return updateProfile(avg)
    }

    /** Handles carb notification from the device. */
    @VisibleForTesting
    fun onPostCarbs(caller: SocketAddress, uri: URI): CharSequence {
        aapsLogger.info(LTag.GARMIN, "carbs from $caller, req: $uri")
        postCarbs(getQueryParameter(uri, "carbs", 0L).toInt())
        return ""
    }

    private fun postCarbs(carbs: Int) {
        if (carbs > 0) {
            loopHub.postCarbs(carbs)
        }
    }

    /** Handles pump connected notification that the user entered on the Garmin device. */
    @VisibleForTesting
    fun onConnectPump(caller: SocketAddress, uri: URI): CharSequence {
        aapsLogger.info(LTag.GARMIN, "connect from $caller, req: $uri")
        val minutes = getQueryParameter(uri, "disconnectMinutes", 0L).toInt()
        if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
       }

        val jo = JsonObject()
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {}

    @VisibleForTesting
    data class ProfileSwitchSetting(
        /** Profile name. */
        val name: String,
        /** 1 letter abbreviation of the profile name, to be shown on the device. */
        val mnemonic: String,
        val triggerHeartRate: Int) {
        override fun toString(): String {
            return name
        }
    }
}
