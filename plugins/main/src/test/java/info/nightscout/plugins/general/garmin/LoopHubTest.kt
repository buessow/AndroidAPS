package info.nightscout.plugins.general.garmin


import info.nightscout.androidaps.TestBase
import info.nightscout.database.ValueWrapper
import info.nightscout.database.entities.EffectiveProfileSwitch
import info.nightscout.database.entities.GlucoseValue
import info.nightscout.database.entities.HeartRate
import info.nightscout.database.entities.OfflineEvent
import info.nightscout.database.entities.UserEntry
import info.nightscout.database.entities.ValueWithUnit
import info.nightscout.database.entities.embedments.InsulinConfiguration
import info.nightscout.database.impl.AppRepository
import info.nightscout.database.impl.transactions.CancelCurrentOfflineEventIfAnyTransaction
import info.nightscout.database.impl.transactions.InsertOrUpdateHeartRateTransaction
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.aps.APSResult
import info.nightscout.interfaces.aps.Loop
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.iob.IobTotal
import info.nightscout.interfaces.logging.UserEntryLogger
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.pump.DetailedBolusInfo
import info.nightscout.interfaces.queue.CommandQueue
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class LoopHubTest: TestBase() {
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var constraints: Constraints
    @Mock lateinit var iobCobCalculator: IobCobCalculator
    @Mock lateinit var loop: Loop
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var repo: AppRepository
    @Mock lateinit var userEntryLogger: UserEntryLogger

    private lateinit var loopHub: LoopHubImpl
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))

    @BeforeEach
    fun setup() {
        loopHub = LoopHubImpl(
            aapsLogger, commandQueue, constraints, iobCobCalculator, loop,
            profileFunction, repo, userEntryLogger)
        loopHub.clock = clock
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verifyNoMoreInteractions(commandQueue)
        verifyNoMoreInteractions(constraints)
        verifyNoMoreInteractions(iobCobCalculator)
        verifyNoMoreInteractions(loop)
        verifyNoMoreInteractions(profileFunction)
        verifyNoMoreInteractions(repo)
        verifyNoMoreInteractions(userEntryLogger)
    }

    @Test
    fun testCurrentProfile() {
        val profile = mock(Profile::class.java)
        `when`(profileFunction.getProfile()).thenReturn(profile)
        assertEquals(profile, loopHub.currentProfile)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testCurrentProfileName() {
        `when`(profileFunction.getProfileName()).thenReturn("pro")
        assertEquals("pro", loopHub.currentProfileName)
        verify(profileFunction, times(1)).getProfileName()
    }

    @Test
    fun testGlucoseUnit() {
        val profile = mock(Profile::class.java)
        `when`(profile.units).thenReturn(GlucoseUnit.MMOL)
        `when`(profileFunction.getProfile()).thenReturn(profile)
        assertEquals(GlucoseUnit.MMOL, loopHub.glucoseUnit)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testGlucoseUnitNullProfile() {
        `when`(profileFunction.getProfile()).thenReturn(null)
        assertEquals(GlucoseUnit.MGDL, loopHub.glucoseUnit)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testInsulinOnBoard() {
        val iobTotal = IobTotal(time = 0).apply { iob = 23.9 }
        `when`(iobCobCalculator.calculateIobFromBolus()).thenReturn(iobTotal)
        assertEquals(23.9, loopHub.insulinOnboard, 1e-10)
        verify(iobCobCalculator, times(1)).calculateIobFromBolus()
    }

    @Test
    fun testIsConnected() {
        `when`(loop.isDisconnected).thenReturn(false)
        assertEquals(true, loopHub.isConnected)
        verify(loop, times(1)).isDisconnected
    }

    private fun effectiveProfileSwitch(duration: Long) = EffectiveProfileSwitch(
        timestamp = 100,
        basalBlocks = emptyList(),
        isfBlocks = emptyList(),
        icBlocks = emptyList(),
        targetBlocks = emptyList(),
        glucoseUnit = EffectiveProfileSwitch.GlucoseUnit.MGDL,
        originalProfileName = "foo",
        originalCustomizedName = "bar",
        originalTimeshift = 0,
        originalPercentage = 100,
        originalDuration = duration,
        originalEnd = 100 + duration,
        insulinConfiguration = InsulinConfiguration(
            "label", 0, 0
        )
    )

    @Test
    fun testIsTemporaryProfileTrue() {
        val eps = effectiveProfileSwitch(10)
        `when`(repo.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(
            Single.just(ValueWrapper.Existing(eps)))
        assertEquals(true, loopHub.isTemporaryProfile)
        verify(repo, times(1)).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testIsTemporaryProfileFalse() {
        val eps = effectiveProfileSwitch(0)
        `when`(repo.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(
            Single.just(ValueWrapper.Existing(eps)))
        assertEquals(false, loopHub.isTemporaryProfile)
        verify(repo).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testTemporaryBasal() {
        val apsResult = mock(APSResult::class.java)
        `when`(apsResult.percent).thenReturn(45)
        val lastRun = Loop.LastRun().apply { constraintsProcessed = apsResult }
        `when`(loop.lastRun).thenReturn(lastRun)
        assertEquals(0.45, loopHub.temporaryBasal, 1e-6)
        verify(loop).lastRun
    }

    @Test
    fun testTemporaryBasalNoRun() {
        `when`(loop.lastRun).thenReturn(null)
        assertTrue(loopHub.temporaryBasal.isNaN())
        verify(loop, times(1)).lastRun
    }

    @Test
    fun testConnectPump() {
        val c = mock(Completable::class.java)
        val dummy = CancelCurrentOfflineEventIfAnyTransaction(0)
        val matcher = {
            argThat<CancelCurrentOfflineEventIfAnyTransaction> { t -> t.timestamp == clock.millis() }}
        `when`(repo.runTransaction(matcher() ?: dummy)).thenReturn(c)
        loopHub.connectPump()
        verify(repo).runTransaction(matcher() ?: dummy)
        verify(commandQueue).cancelTempBasal(true, null)
        verify(userEntryLogger).log(UserEntry.Action.RECONNECT, UserEntry.Sources.GarminDevice)
    }

    @Test
    fun testDisconnectPump() {
        val profile = mock(Profile::class.java)
        `when`(profileFunction.getProfile()).thenReturn(profile)
        loopHub.disconnectPump(23)
        verify(profileFunction).getProfile()
        verify(loop).goToZeroTemp(23, profile, OfflineEvent.Reason.DISCONNECT_PUMP)
        verify(userEntryLogger).log(
            UserEntry.Action.DISCONNECT,
            UserEntry.Sources.GarminDevice,
            ValueWithUnit.Minute(23))
    }

    @Test
    fun testGetGlucoseValues() {
        val glucoseValues = listOf(
            GlucoseValue(
                timestamp = 1_000_000L, raw = 90.0, value = 93.0,
                trendArrow = GlucoseValue.TrendArrow.FLAT, noise = null,
                sourceSensor = GlucoseValue.SourceSensor.DEXCOM_G5_XDRIP))
        `when`(repo.compatGetBgReadingsDataFromTime(1001_000, false))
            .thenReturn(Single.just(glucoseValues))
        assertArrayEquals(
            glucoseValues.toTypedArray(),
            loopHub.getGlucoseValues(Instant.ofEpochMilli(1001_000), false).toTypedArray())
        verify(repo).compatGetBgReadingsDataFromTime(1001_000, false)
    }

    @Test
    fun testPostCarbs() {
        val constraint = { carbs: Int -> argThat<Constraint<Int>> { c -> c.value() == carbs } ?: Constraint(0)}
        `when`(constraints.applyCarbsConstraints(constraint(100) )).thenReturn(Constraint(99))
        loopHub.postCarbs(100)
        verify(constraints).applyCarbsConstraints(constraint(100))
        verify(userEntryLogger).log(
            UserEntry.Action.CARBS,
            UserEntry.Sources.GarminDevice,
            ValueWithUnit.Gram(99))
        verify(commandQueue).bolus(
            argThat { b ->
                b!!.eventType == DetailedBolusInfo.EventType.CARBS_CORRECTION &&
                b.carbs == 99.0 }?: DetailedBolusInfo() ,
            isNull())
    }

    @Test
    fun testStoreHeartRate() {
        val samplingStart = Instant.ofEpochMilli(1_001_000)
        val samplingEnd = Instant.ofEpochMilli(1_101_000)
        val hr = HeartRate(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = 101.0,
            device = "Test Device")
        `when`(repo.runTransaction(InsertOrUpdateHeartRateTransaction(hr))).thenReturn(
            Completable.fromCallable {
                InsertOrUpdateHeartRateTransaction.TransactionResult(
                    emptyList(), emptyList())})
        loopHub.storeHeartRate(
            samplingStart, samplingEnd, 101, "Test Device")
        verify(repo).runTransaction(InsertOrUpdateHeartRateTransaction(hr))
    }
}