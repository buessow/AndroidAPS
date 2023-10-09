package app.aaps.plugins.main.general.garmin


import android.content.Context
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.database.entities.GlucoseValue
import app.aaps.shared.tests.TestBase
import com.garmin.android.connectiq.IQApp
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.atMost
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.Condition

class GarminPluginTest: TestBase() {
    private lateinit var gp: GarminPlugin

    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var sp: SP
    @Mock private lateinit var context: Context
    @Mock private lateinit var loopHub: LoopHub
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))

    private var injector: HasAndroidInjector = HasAndroidInjector {
        AndroidInjector {
        }
    }

    @BeforeEach
    fun setup() {
        gp = GarminPlugin(injector, aapsLogger, rh, context, loopHub, rxBus, sp)
        gp.clock = clock
        `when`(loopHub.currentProfileName).thenReturn("Default")
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verify(loopHub, atMost(2)).currentProfileName
        verifyNoMoreInteractions(loopHub)
    }

    private val getGlucoseValuesFrom = clock.instant()
        .minus(2, ChronoUnit.HOURS)
        .minus(9, ChronoUnit.MINUTES)

    private fun createUri(params: Map<String, Any>): URI {
        return URI("http://foo?" + params.entries.joinToString(separator = "&") { (k, v) ->
            "$k=$v"})
    }

    private fun createHeartRate(heartRate: Int) = mapOf<String, Any>(
        "hr" to heartRate,
        "hrStart" to 1001L,
        "hrEnd" to 2001L,
        "device" to "Test_Device")

    private fun createGlucoseValue(timestamp: Instant, value: Double = 93.0) = GlucoseValue(
        timestamp = timestamp.toEpochMilli(), raw = 90.0, value = value,
        trendArrow = GlucoseValue.TrendArrow.FLAT, noise = null,
        sourceSensor = GlucoseValue.SourceSensor.RANDOM
    )

    private fun createDeviceApplication(): DeviceApplication {
        val client = mock(ConnectIqClient::class.java)
        val device = GarminDevice(1, "test device")
        `when`(client.knownDevices).thenReturn(listOf(device))
        val iqApp = IQApp("test app")
        val app =DeviceApplication(client, device, iqApp)
        gp.ciqMessenger = ConnectIqMessenger(
            aapsLogger, context, emptyMap(), { _ -> }, { _, _ -> }, false)

        gp.ciqMessenger!!.onConnect(app.client)
        return app
    }

    @Test
    fun testReceiveHeartRateMap() {
        val hr = createHeartRate(80)
        gp.receiveHeartRate(hr, false)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            80,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRateUri() {
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        gp.receiveHeartRate(uri)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRate_UriTestIsTrue() {
        val params = createHeartRate(99).toMutableMap()
        params["test"] = true
        val uri = createUri(params)
        gp.receiveHeartRate(uri)
    }

    @Test
    fun testGetGlucoseValues_NoLast() {
        val from = getGlucoseValuesFrom
        val prev = createGlucoseValue(clock.instant().minusSeconds(310))
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun testGetGlucoseValues_NoNewLast() {
        val from = getGlucoseValuesFrom
        val lastTimesteamp = clock.instant()
        val prev = createGlucoseValue(clock.instant())
        gp.newValue = mock(Condition::class.java)
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        gp.onNewBloodGlucose(EventNewBG(lastTimesteamp.toEpochMilli()))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())

        verify(gp.newValue).signalAll()
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun testOnGetBloodGlucose() {
        `when`(loopHub.isConnected).thenReturn(true)
        `when`(loopHub.insulinOnboard).thenReturn(3.14)
        `when`(loopHub.temporaryBasal).thenReturn(0.8)
        val from = getGlucoseValuesFrom
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(Instant.ofEpochSecond(1_000))))
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        val result = gp.onGetBloodGlucose(mock(SocketAddress::class.java), uri)
        assertEquals(
            "{\"encodedGlucose\":\"0A+6AQ==\"," +
                "\"remainingInsulin\":3.14," +
                "\"glucoseUnit\":\"mmoll\",\"temporaryBasalRate\":0.8," +
                "\"profile\":\"D\",\"connected\":true}",
            result.toString())
        verify(loopHub).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String)
    }

    @Test
    fun testOnGetBloodGlucose_Wait() {
        `when`(loopHub.isConnected).thenReturn(true)
        `when`(loopHub.insulinOnboard).thenReturn(3.14)
        `when`(loopHub.temporaryBasal).thenReturn(0.8)
        `when`(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        val from = getGlucoseValuesFrom
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(clock.instant().minusSeconds(330))))
        val params = createHeartRate(99).toMutableMap()
        params["wait"] = 10
        val uri = createUri(params)
        gp.newValue = mock(Condition::class.java)
        val result = gp.onGetBloodGlucose(mock(SocketAddress::class.java), uri)
        assertEquals(
            "{\"encodedGlucose\":\"/wS6AQ==\"," +
                "\"remainingInsulin\":3.14," +
                "\"glucoseUnit\":\"mmoll\",\"temporaryBasalRate\":0.8," +
                "\"profile\":\"D\",\"connected\":true}",
            result.toString())
        verify(gp.newValue).awaitNanos(anyLong())
        verify(loopHub, times(2)).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(params["hrStart"] as Long),
            Instant.ofEpochSecond(params["hrEnd"] as Long),
            99,
            params["device"] as String)
    }

    @Test
    fun testOnPostCarbs() {
        val uri = createUri(mapOf("carbs" to "12"))
        assertEquals("", gp.onPostCarbs(mock(SocketAddress::class.java), uri))
        verify(loopHub).postCarbs(12)
    }

    @Test
    fun testOnConnectPump_Disconnect() {
        val uri = createUri(mapOf("disconnectMinutes" to "20"))
        `when`(loopHub.isConnected).thenReturn(false)
        assertEquals("{\"connected\":false}", gp.onConnectPump(mock(SocketAddress::class.java), uri))
        verify(loopHub).disconnectPump(20)
        verify(loopHub).isConnected
    }

    @Test
    fun testOnConnectPump_Connect() {
        val uri = createUri(mapOf("disconnectMinutes" to "0"))
        `when`(loopHub.isConnected).thenReturn(true)
        assertEquals("{\"connected\":true}", gp.onConnectPump(mock(SocketAddress::class.java), uri))
        verify(loopHub).connectPump()
        verify(loopHub).isConnected
    }

    @Test
    fun testOnMessage_Ping() {
        val app = createDeviceApplication()
        gp.onMessage(app, mapOf("command" to "ping") as Any)
        val captor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(app.client, timeout(1000))!!.sendMessage(
            eq(app) ?: app, captor.capture() ?: ByteArray(0))
        assertEquals(
            mapOf<String, Any>("command" to "pong"),
            ConnectIqSerializer.deserialize(captor.value))
    }

    @Test
    fun testOnMessage_GetGlucose() {
        val app = createDeviceApplication()
        gp.onMessage(app, mapOf("command" to "get_glucose"))
        val captor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(app.client, timeout(1000))!!.sendMessage(
            eq(app) ?: app, captor.capture() ?: ByteArray(0))
        @Suppress("UNCHECKED_CAST")
        val r = ConnectIqSerializer.deserialize(captor.value) as Map<String, Any>
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun testOnMessage_Carbs() {
        val app = createDeviceApplication()
        gp.onMessage(app, mapOf("command" to "carbs", "carbs" to 123) as Any)
        verify(loopHub, timeout(1000)).postCarbs(123)
    }

    @Test
    fun testOnMessage_HeartRate() {
        val app = createDeviceApplication()
        val msg = createHeartRate(80).toMutableMap()
        msg["command"] = "heartrate"
        gp.onMessage(app, msg)
        verify(loopHub, timeout(1000)).storeHeartRate(
            Instant.ofEpochSecond(msg["hrStart"] as Long),
            Instant.ofEpochSecond(msg["hrEnd"] as Long),
            80,
            msg["device"] as String)
    }
}
