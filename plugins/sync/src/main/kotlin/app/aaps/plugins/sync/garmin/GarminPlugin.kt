package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.database.entities.GlucoseValue
import app.aaps.plugins.sync.R
import com.google.gson.JsonObject
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.net.HttpURLConnection
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

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate and pump disconnect events from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 */
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
        .mainType(PluginType.SYNC)
        .pluginIcon(app.aaps.core.main.R.drawable.ic_watch)
        .pluginName(R.string.garmin)
        .shortName(R.string.garmin)
        .description(R.string.garmin_description)
        .preferencesId(R.xml.pref_garmin),
    aapsLogger, resourceHelper, injector
) {
    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null
    private var ciqMessenger: ConnectIqMessenger? = null

    /** Garmin ConnectIQ application id for native communication. Phone pushes values. */
    private val glucoseAppIds = mapOf(
       "c9e90ee7e6924829a8b45e7dafff5cb4" to "GlucoseWatch_Dev",
       "1107ca6c2d5644b998d4bcb3793f2b7c" to "GlucoseDataField_Dev",
       "928fe19a4d3a4259b50cb6f9ddaf0f4a" to "GlucoseWidget_Dev",
    )

    @VisibleForTesting
    private val disposable = CompositeDisposable()

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
            "communication_debug_mode" -> setupCiqMessenger()
            "communication_http", "communication_http_port" -> setupHttpServer()
        }
    }

    private fun setupCiqMessenger() {
        val enableDebug = sp.getBoolean("communication_ciq_debug_mode", false)
        ciqMessenger?.dispose()
        ciqMessenger = null
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        ciqMessenger = ConnectIqMessenger(
            aapsLogger, context, glucoseAppIds, ::onConnectDevice, ::onMessage, enableDebug).also { disposable.add(it) }
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
        setupHttpServer()
        setupCiqMessenger()
    }

    @VisibleForTesting
    fun setupHttpServer() {
        if (sp.getBoolean("communication_http", false)) {
            val port = sp.getInt("communication_http_port", 28891)
            if (server != null && server?.port == port) return
            aapsLogger.info(LTag.GARMIN, "starting HTTP server on $port")
            server?.close()
            server = HttpServer(aapsLogger, port).apply {
                registerEndpoint("/get", ::onGetBloodGlucose)
            }
        } else if (server != null) {
            aapsLogger.info(LTag.GARMIN, "stopping HTTP server")
            server?.close()
            server = null
        }
    }

    @VisibleForTesting
    public override fun onStop() {
        disposable.clear()
        aapsLogger.info(LTag.GARMIN, "Stop")
        server?.close()
        server = null
        super.onStop()
    }

    /** Receive new blood glucose events.
     *
     * Stores new blood glucose values in lastGlucoseValue to make sure we return
     * these values immediately when values are requested by Garmin device.
     * Sends a message to the Garmin devices via the ciqMessenger. */
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
        sendPhoneAppMessage(device)
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        ciqMessenger?.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        ciqMessenger?.sendMessage(getGlucoseMessage())
    }

    @VisibleForTesting
    fun getGlucoseMessage() = mapOf<String, Any>(
        "key" to sp.getString("garmin_aaps_key", ""),
        "command" to "glucose",
        "profile" to loopHub.currentProfileName.first().toString(),
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
        return encodedGlucose.encodedBase64()
    }

    /** Responses to get glucose value request by the device.
     *
     * Also, gets the heart rate readings from the device.
     */
    @VisibleForTesting
    @Suppress("UNUSED_PARAMETER")
    fun onGetBloodGlucose(caller: SocketAddress, uri: URI, requestBody: String?): Pair<Int, CharSequence> {
        aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
        val key = sp.getString("garmin_aaps_key", "")
        if (key.isNotEmpty()) {
            val deviceKey = getQueryParameter(uri, "key")
            if (key != deviceKey) {
                aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key, got '$deviceKey' want '$key'")
                sendPhoneAppMessage()
                Thread.sleep(5000L)
                return HttpURLConnection.HTTP_UNAUTHORIZED to "wrong key"
            }
        }
        receiveHeartRate(uri)
        val profileName = loopHub.currentProfileName
        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("glucoseUnit", glucoseUnitStr)
        loopHub.temporaryBasal.also {
            if (!it.isNaN()) jo.addProperty("temporaryBasalRate", it)
        }
        jo.addProperty("profile", profileName.first().toString())
        jo.addProperty("connected", loopHub.isConnected)
        return HttpURLConnection.HTTP_OK to jo.toString().also {
            aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
        }
    }

    private fun getQueryParameter(uri: URI, name: String) = (uri.query ?: "")
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

    @VisibleForTesting
    fun receiveHeartRate(uri: URI) {
        val avg: Int = getQueryParameter(uri, "hr", 0L).toInt()
        val samplingStartSec: Long = getQueryParameter(uri, "hrStart", 0L)
        val samplingEndSec: Long = getQueryParameter(uri, "hrEnd", 0L)
        val device: String? = getQueryParameter(uri, "device")
        receiveHeartRate(
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            avg, device, getQueryParameter(uri, "test", false))
    }

    private fun receiveHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avg: Int, device: String?, test: Boolean) {
        aapsLogger.info(LTag.GARMIN, "average heart rate $avg BPM test=$test")
        if (test) return
        if (avg > 10 && samplingStart > Instant.ofEpochMilli(0L) && samplingEnd > samplingStart) {
            loopHub.storeHeartRate(samplingStart, samplingEnd, avg, device)
        } else {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid HR $avg $samplingStart..$samplingEnd")
        }
    }
}
