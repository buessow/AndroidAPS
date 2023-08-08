package info.nightscout.plugins.general.garmin


import android.content.Context
import com.garmin.android.connectiq.IQApp
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.TestBase
import info.nightscout.database.entities.GlucoseValue
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventNewBG
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
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
    @Mock private lateinit var rxBus: RxBus
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
        val s = { key: String, name: String, hr: Int ->
            `when`(sp.getString(eq("${key}_name") ?: "", anyString())).thenReturn(name)
            `when`(sp.getInt(eq("${key}_hr") ?: "", anyInt())).thenReturn(hr)
        }
        s("garmin_default_profile", "Default", 80)
        s("garmin_active_profile", "Sport_75", 100)
        s("garmin_sport_profile", "Sport_50", 110)

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
    fun testGetNewProfileSettings() {
        val pss = gp.profileSwitchSettings
        assertEquals(3, pss.size)
        assertEquals("Default", pss[0].name)
        assertEquals("D", pss[0].mnemonic)
        assertEquals(80, pss[0].triggerHeartRate)
        assertEquals(100, pss[1].triggerHeartRate)
        assertEquals(110, pss[2].triggerHeartRate)
    }

    @Test
    fun testGetNewProfileZeroHR() {
        val pss = gp.profileSwitchSettings
        assertEquals(pss[0], gp.computeNewProfile(pss[0], 0))
        assertEquals(pss[0], gp.computeNewProfile(pss[0], 9))
        assertEquals(pss[1], gp.computeNewProfile(pss[1], 0))
        assertEquals(pss[2], gp.computeNewProfile(pss[2], 0))
    }

    @Test
    fun testGetNewProfileStay() {
        val pss = gp.profileSwitchSettings
        assertEquals(pss[0], gp.computeNewProfile(pss[0], 100))
        assertEquals(pss[1], gp.computeNewProfile(pss[1], 90))
        assertEquals(pss[0], gp.computeNewProfile(pss[0], 90))
        assertEquals(pss[1], gp.computeNewProfile(pss[1], 105))
        assertEquals(pss[2], gp.computeNewProfile(pss[2], 105))
    }

    @Test
    fun testGetNewProfileRaise() {
        val pss = gp.profileSwitchSettings
        assertEquals(pss[0], gp.computeNewProfile(pss[0], 100))
        assertEquals(pss[1], gp.computeNewProfile(pss[0], 101))
        assertEquals(pss[2], gp.computeNewProfile(pss[0], 111))
        assertEquals(pss[2], gp.computeNewProfile(pss[1], 111))
    }

    @Test
    fun testGetNewProfileLower() {
        val pss = gp.profileSwitchSettings
        assertEquals(pss[0], gp.computeNewProfile(pss[1], 70))
        assertEquals(pss[0], gp.computeNewProfile(pss[2], 70))
        assertEquals(pss[1], gp.computeNewProfile(pss[2], 90))
    }

    @Test
    fun testReceiveHeartRateMap() {
        val hr = createHeartRate(80)
        assertEquals("D", gp.receiveHeartRate(hr, false))
        verify(loopHub).storeHeartRate(
            Instant.ofEpochMilli(hr["hrStart"] as Long),
            Instant.ofEpochMilli(hr["hrEnd"] as Long),
            80,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRateMap_SwitchProfile() {
        val hr = createHeartRate(105)
        assertEquals("A", gp.receiveHeartRate(hr, false))
        verify(loopHub).isTemporaryProfile
        verify(loopHub).storeHeartRate(
            Instant.ofEpochMilli(hr["hrStart"] as Long),
            Instant.ofEpochMilli(hr["hrEnd"] as Long),
            105,
            hr["device"] as String)
        verify(loopHub).switchProfile("Sport_75")
    }

    @Test
    fun testReceiveHeartRateMap_TemporaryProfile() {
        `when`(loopHub.isTemporaryProfile).thenReturn(true)
        val hr = createHeartRate(105)
        assertEquals("D", gp.receiveHeartRate(hr, false))
        verify(loopHub).isTemporaryProfile
        verify(loopHub).storeHeartRate(
            Instant.ofEpochMilli(hr["hrStart"] as Long),
            Instant.ofEpochMilli(hr["hrEnd"] as Long),
            105,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRateUri() {
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        assertEquals("D", gp.receiveHeartRate(uri))
        verify(loopHub).storeHeartRate(
            Instant.ofEpochMilli(hr["hrStart"] as Long),
            Instant.ofEpochMilli(hr["hrEnd"] as Long),
            99,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRate_UriTestIsTrue() {
        val params = createHeartRate(99).toMutableMap()
        params["test"] = true
        val uri = createUri(params)
        assertEquals("D", gp.receiveHeartRate(uri))
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
            Instant.ofEpochMilli(hr["hrStart"] as Long),
            Instant.ofEpochMilli(hr["hrEnd"] as Long),
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
            Instant.ofEpochMilli(params["hrStart"] as Long),
            Instant.ofEpochMilli(params["hrEnd"] as Long),
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
        assertEquals(
            mapOf<String, Any>(
                "command" to "glucose",
                "profile" to "D",
                "encodedGlucose" to "",
                "remainingInsulin" to 0.0,
                "glucoseUnit" to "mmoll",
                "temporaryBasalRate" to 0.0,
                "connected" to false,
                "timestamp" to clock.instant().epochSecond
            ),
            ConnectIqSerializer.deserialize(captor.value))
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
            Instant.ofEpochMilli(msg["hrStart"] as Long),
            Instant.ofEpochMilli(msg["hrEnd"] as Long),
            80,
            msg["device"] as String)
    }
}
