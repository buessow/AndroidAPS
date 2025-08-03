package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsShared.StoreDataForDbImpl
import app.aaps.plugins.sync.nsclientV3.keys.NsclientLongKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Maybe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.internal.verification.Times
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import kotlin.collections.remove

class DataSyncSelectorV3Test : TestBaseWithProfile() {

    @Mock lateinit var nsClient: NsClient
    @Mock lateinit var sp: SP
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var virtualPump: VirtualPump
    @Mock lateinit var nsClientSource: NSClientSource
    @Mock lateinit var rxBux: RxBus

    private lateinit var storeDataForDb: StoreDataForDb
    private lateinit var sut: DataSyncSelectorV3

    @BeforeEach
    fun setUp() {
        storeDataForDb = StoreDataForDbImpl(aapsLogger, rxBus, persistenceLayer, preferences, uel, config, nsClientSource, virtualPump)
        sut = DataSyncSelectorV3(preferences, aapsLogger, dateUtil, profileFunction, activePlugin, persistenceLayer, rxBus, storeDataForDb, config)
        `when`(activePlugin.activeNsClient).thenReturn(nsClient)
        `when`(nsClient.supportsHeartRate).thenReturn(true)
        verifyNoMoreInteractions(sp)
    }

    @AfterEach
    fun verifyCalls() {
    }

    @Test
     fun processChangedHeartRate_addOne(): Unit = runBlocking {
        val hr = createHeartRate(1L)
        `when`(persistenceLayer.getLastHeartRateId()).thenReturn(1L)
        `when`(preferences.get(NsclientLongKey.HeartRateLastSyncId)).thenReturn(0L)
        `when`(persistenceLayer.getNextSyncElementHeartRate(anyLong())).thenReturn(Maybe.empty())
        `when`(persistenceLayer.getNextSyncElementHeartRate(0L)).thenReturn(Maybe.just(hr to null))
        `when`(nsClient.nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")).thenReturn(true)
        sut.processChangedHeartRate()

        verify(persistenceLayer).getLastHeartRateId()
        verify(persistenceLayer, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(nsClient).nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")
        verify(preferences).put(NsclientLongKey.HeartRateLastSyncId, 1L)
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(persistenceLayer)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
    fun processChangedHeartRate_addOneFails(): Unit = runBlocking {
        val hr = createHeartRate(1L)
        `when`(persistenceLayer.getLastHeartRateId()).thenReturn(1L)
        `when`(preferences.get(NsclientLongKey.HeartRateLastSyncId)).thenReturn(0L)
        `when`(persistenceLayer.getNextSyncElementHeartRate(0L)).thenReturn(Maybe.just(hr to null))
        `when`(nsClient.nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")).thenReturn(false)
        sut.processChangedHeartRate()

        verify(persistenceLayer).getLastHeartRateId()
        verify(persistenceLayer).getNextSyncElementHeartRate(0L)
        verify(nsClient).nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")
        verify(preferences).get(NsclientLongKey.HeartRateLastSyncId)
        verify(preferences).put(NsclientLongKey.HeartRateLastSyncId, 0L)
        verifyNoMoreInteractions(sp)
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(persistenceLayer)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
    fun processChangedHeartRate_ignoreNightscoutImport(): Unit = runBlocking {
        val hr = createHeartRate(1L).apply { ids.nightscoutId = "foo" }
        `when`(persistenceLayer.getLastHeartRateId()).thenReturn(1L)
        `when`(preferences.get(NsclientLongKey.HeartRateLastSyncId)).thenReturn(0L)
        `when`(persistenceLayer.getNextSyncElementHeartRate(anyLong())).thenReturn(Maybe.empty())
        `when`(persistenceLayer.getNextSyncElementHeartRate(0L)).thenReturn(Maybe.just(hr to null))
        sut.processChangedHeartRate()
        verify(persistenceLayer).getLastHeartRateId()
        verify(persistenceLayer, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(preferences).put(NsclientLongKey.HeartRateLastSyncId, 1L)
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(persistenceLayer)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
    fun processChangedHeartRate_UpdateOne(): Unit = runBlocking {
        val newHr = createHeartRate(1L).apply { beatsPerMinute = 99.0; ids.nightscoutId = "id1" }
        val refHr = createHeartRate(2L).apply { referenceId = 1L }
        `when`(persistenceLayer.getNextSyncElementHeartRate(anyLong())).thenReturn(Maybe.empty())
        `when`(persistenceLayer.getLastHeartRateId()).thenReturn(2L)
        `when`(preferences.get(NsclientLongKey.HeartRateLastSyncId)).thenReturn(1L)
        `when`(persistenceLayer.getNextSyncElementHeartRate(1L)).thenReturn(Maybe.just(newHr to refHr))
        `when`(nsClient.nsUpdate(
            "heartrate",
            DataSyncSelector.PairHeartRate(newHr, refHr.id),
            "2/2")).thenReturn(true)
        sut.processChangedHeartRate()

        verify(persistenceLayer).getLastHeartRateId()
        verify(persistenceLayer, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(nsClient).nsUpdate(
            "heartrate",
            DataSyncSelector.PairHeartRate(newHr, refHr.id),
            "2/2")
        verify(preferences).put(NsclientLongKey.HeartRateLastSyncId, 2L)
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(persistenceLayer)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
    fun processChangedHeartRate_addMultiple(): Unit = runBlocking {
        val hrs = listOf(3L, 4L, 6L).map { id -> createHeartRate(id) }
        `when`(persistenceLayer.getNextSyncElementHeartRate(anyLong())).thenAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            hrs.firstOrNull { hr -> hr.id > id }
                ?.let { hr -> Maybe.just(hr to null) }
                ?: Maybe.empty<Pair<HR, HR?>>()
        }
        for (hr in hrs) {
            `when`(nsClient.nsAdd(
                "heartrate",
                DataSyncSelector.PairHeartRate(hr, hr.id),
                "${hr.id}/6")).thenReturn(true)
        }
        `when`(persistenceLayer.getLastHeartRateId()).thenReturn(6L)
        `when`(preferences.get(NsclientLongKey.HeartRateLastSyncId)).thenReturn(2L)

        sut.processChangedHeartRate()

        verify(persistenceLayer).getLastHeartRateId()
        verify(persistenceLayer, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        for (hr in hrs) {
            verify(nsClient).nsAdd(
                "heartrate", DataSyncSelector.PairHeartRate(hr, hr.id), "${hr.id}/6"
            )
        }
        verify(preferences).put(NsclientLongKey.HeartRateLastSyncId, 6L)
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(persistenceLayer)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
    fun bgUploadEnabledTest() {
        class NSClientSourcePlugin() : NSClientSource, BgSource {

            override fun isEnabled(): Boolean = true
            override fun detectSource(glucoseValue: GV) {}
        }
        val nsClientSourcePlugin = NSClientSourcePlugin()

        class AnotherSourcePlugin() : BgSource
        val anotherSourcePlugin = AnotherSourcePlugin()

        `when`(preferences.get(BooleanKey.BgSourceUploadToNs)).thenReturn(false)
        `when`(activePlugin.activeBgSource).thenReturn(nsClientSourcePlugin)
        assertThat(sut.bgUploadEnabled).isFalse()

        `when`(preferences.get(BooleanKey.BgSourceUploadToNs)).thenReturn(true)
        `when`(activePlugin.activeBgSource).thenReturn(nsClientSourcePlugin)
        assertThat(sut.bgUploadEnabled).isFalse()

        `when`(preferences.get(BooleanKey.BgSourceUploadToNs)).thenReturn(true)
        `when`(activePlugin.activeBgSource).thenReturn(anotherSourcePlugin)
        assertThat(sut.bgUploadEnabled).isTrue()
    }

    @Test
    fun resetToNextFullSyncTest() {
        `when`(persistenceLayer.getLastDeviceStatusId()).thenReturn(1)
        sut.resetToNextFullSync()
        verify(preferences, Times(1)).remove(NsclientLongKey.GlucoseValueLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.TemporaryBasalLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.TemporaryTargetLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.ExtendedBolusLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.FoodLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.BolusLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.CarbsLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.BolusCalculatorLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.TherapyEventLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.ProfileSwitchLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.RunningModeLastSyncedId)
        verify(preferences, Times(1)).remove(NsclientLongKey.ProfileStoreLastSyncedId)
        verify(preferences, Times(1)).put(NsclientLongKey.DeviceStatusLastSyncedId, 1)

        `when`(persistenceLayer.getLastDeviceStatusId()).thenReturn(null)
        sut.resetToNextFullSync()
        verify(preferences, Times(1)).remove(NsclientLongKey.DeviceStatusLastSyncedId)
    }

    @Test
    fun confirmLastTest() {
        // Bolus
        `when`(preferences.get(NsclientLongKey.BolusLastSyncedId)).thenReturn(2)
        sut.confirmLastBolusIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.BolusLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.BolusLastSyncedId)).thenReturn(1)
        sut.confirmLastBolusIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.BolusLastSyncedId, 2)
        // Carbs
        `when`(preferences.get(NsclientLongKey.CarbsLastSyncedId)).thenReturn(2)
        sut.confirmLastCarbsIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.CarbsLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.CarbsLastSyncedId)).thenReturn(1)
        sut.confirmLastCarbsIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.CarbsLastSyncedId, 2)
        // BolusCalculatorResults
        `when`(preferences.get(NsclientLongKey.BolusCalculatorLastSyncedId)).thenReturn(2)
        sut.confirmLastBolusCalculatorResultsIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.BolusCalculatorLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.BolusCalculatorLastSyncedId)).thenReturn(1)
        sut.confirmLastBolusCalculatorResultsIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.BolusCalculatorLastSyncedId, 2)
        // TempTargets
        `when`(preferences.get(NsclientLongKey.TemporaryTargetLastSyncedId)).thenReturn(2)
        sut.confirmLastTempTargetsIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.TemporaryTargetLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.TemporaryTargetLastSyncedId)).thenReturn(1)
        sut.confirmLastTempTargetsIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.TemporaryTargetLastSyncedId, 2)
        // Food
        `when`(preferences.get(NsclientLongKey.FoodLastSyncedId)).thenReturn(2)
        sut.confirmLastFoodIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.FoodLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.FoodLastSyncedId)).thenReturn(1)
        sut.confirmLastFoodIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.FoodLastSyncedId, 2)
        // GlucoseValue
        `when`(preferences.get(NsclientLongKey.GlucoseValueLastSyncedId)).thenReturn(2)
        sut.confirmLastGlucoseValueIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.GlucoseValueLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.GlucoseValueLastSyncedId)).thenReturn(1)
        sut.confirmLastGlucoseValueIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.GlucoseValueLastSyncedId, 2)
        // TherapyEvent
        `when`(preferences.get(NsclientLongKey.TherapyEventLastSyncedId)).thenReturn(2)
        sut.confirmLastTherapyEventIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.TherapyEventLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.TherapyEventLastSyncedId)).thenReturn(1)
        sut.confirmLastTherapyEventIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.TherapyEventLastSyncedId, 2)
        // DeviceStatus
        `when`(preferences.get(NsclientLongKey.DeviceStatusLastSyncedId)).thenReturn(2)
        sut.confirmLastDeviceStatusIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.DeviceStatusLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.DeviceStatusLastSyncedId)).thenReturn(1)
        sut.confirmLastDeviceStatusIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.DeviceStatusLastSyncedId, 2)
        // TemporaryBasal
        `when`(preferences.get(NsclientLongKey.TemporaryBasalLastSyncedId)).thenReturn(2)
        sut.confirmLastTemporaryBasalIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.TemporaryBasalLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.TemporaryBasalLastSyncedId)).thenReturn(1)
        sut.confirmLastTemporaryBasalIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.TemporaryBasalLastSyncedId, 2)
        // ExtendedBolus
        `when`(preferences.get(NsclientLongKey.ExtendedBolusLastSyncedId)).thenReturn(2)
        sut.confirmLastExtendedBolusIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.ExtendedBolusLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.ExtendedBolusLastSyncedId)).thenReturn(1)
        sut.confirmLastExtendedBolusIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.ExtendedBolusLastSyncedId, 2)
        // ProfileSwitch
        `when`(preferences.get(NsclientLongKey.ProfileSwitchLastSyncedId)).thenReturn(2)
        sut.confirmLastProfileSwitchIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.ProfileSwitchLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.ProfileSwitchLastSyncedId)).thenReturn(1)
        sut.confirmLastProfileSwitchIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.ProfileSwitchLastSyncedId, 2)
        // EffectiveProfileSwitch
        `when`(preferences.get(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)).thenReturn(2)
        sut.confirmLastEffectiveProfileSwitchIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.EffectiveProfileSwitchLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)).thenReturn(1)
        sut.confirmLastEffectiveProfileSwitchIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.EffectiveProfileSwitchLastSyncedId, 2)
        // OfflineEvent
        `when`(preferences.get(NsclientLongKey.RunningModeLastSyncedId)).thenReturn(2)
        sut.confirmLastRunningModeIdIfGreater(2)
        verify(preferences, Times(0)).put(NsclientLongKey.RunningModeLastSyncedId, 2)
        `when`(preferences.get(NsclientLongKey.RunningModeLastSyncedId)).thenReturn(1)
        sut.confirmLastRunningModeIdIfGreater(2)
        verify(preferences, Times(1)).put(NsclientLongKey.RunningModeLastSyncedId, 2)
        // ProfileStore
        sut.confirmLastProfileStore(2)
        verify(preferences, Times(1)).put(NsclientLongKey.ProfileStoreLastSyncedId, 2)
    }

    @Test
    fun processChangedBolusesAfterDbResetTest() = runBlocking {
        `when`(persistenceLayer.getLastBolusId()).thenReturn(0)
        `when`(preferences.get(NsclientLongKey.BolusLastSyncedId)).thenReturn(1)
        `when`(persistenceLayer.getNextSyncElementBolus(0)).thenReturn(Maybe.empty())
        sut.processChangedBoluses()
        verify(preferences, Times(1)).put(NsclientLongKey.BolusLastSyncedId, 0)
        verify(activePlugin, Times(0)).activeNsClient
        clearInvocations(preferences, activePlugin)
    }

    companion object {
        fun createHeartRate(id: Long, timestamp: Long? = null, beatsPerMinute: Double = 80.0) =
            HR(
                id = id,
                timestamp = timestamp ?: System.currentTimeMillis(),
                duration = 60_0000L,
                beatsPerMinute = beatsPerMinute,
                device = "T",
            )
    }
}