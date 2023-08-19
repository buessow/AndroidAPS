package info.nightscout.plugins.sync.nsclientV3

import info.nightscout.androidaps.TestBase
import info.nightscout.database.entities.HeartRate
import info.nightscout.database.impl.AppRepository
import info.nightscout.interfaces.Config
import info.nightscout.interfaces.nsclient.StoreDataForDb
import info.nightscout.interfaces.plugin.ActivePlugin
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.sync.DataSyncSelector
import info.nightscout.interfaces.sync.NsClient
import info.nightscout.plugins.sync.R
import info.nightscout.rx.bus.RxBus
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.verifyNoMoreInteractions

class DataSyncSelectorV3Test: TestBase() {
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var nsClient: NsClient
    @Mock lateinit var sp: SP
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var config: Config
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var repo: AppRepository
    @Mock lateinit var rxBux: RxBus
    @Mock lateinit var storeDataForDb: StoreDataForDb

    private lateinit var dss: DataSyncSelectorV3

    @BeforeEach
    fun setup() {
        dss = DataSyncSelectorV3(sp, aapsLogger, dateUtil, profileFunction, activePlugin,
                                 repo, rxBux, storeDataForDb, config)
        `when`(activePlugin.activeNsClient).thenReturn(nsClient)
        `when`(nsClient.supportsHeartRate).thenReturn(true)
        verify(sp, atLeast(0)).getLong(anyInt(), anyLong())
        verifyNoMoreInteractions(sp)
    }

    @AfterEach
    fun verify() {
        verify(nsClient, atLeast(1)).supportsHeartRate
        verifyNoMoreInteractions(repo)
        verifyNoMoreInteractions(nsClient)
    }

    @Test
     fun processChangedHeartRate_addOne() = runBlocking {
        val hr = createHeartRate(1L)
        `when`(repo.getLastHeartRateId()).thenReturn(1L)
        `when`(sp.getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)).thenReturn(0L)
        `when`(repo.getNextSyncElementHeartRate(0L)).thenReturn(hr to null)
        `when`(nsClient.nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")).thenReturn(true)
        dss.processChangedHeartRate()

        verify(repo).getLastHeartRateId()
        verify(repo, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(nsClient).nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")
        verify(sp).putLong(R.string.key_ns_heart_rate_last_synced_id, 1L)
    }

    @Test
    fun processChangedHeartRate_addOneFails() = runBlocking {
        val hr = createHeartRate(1L)
        `when`(repo.getLastHeartRateId()).thenReturn(1L)
        `when`(sp.getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)).thenReturn(0L)
        `when`(repo.getNextSyncElementHeartRate(0L)).thenReturn( hr to null)
        `when`(nsClient.nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")).thenReturn(false)
        dss.processChangedHeartRate()

        verify(repo).getLastHeartRateId()
        verify(repo).getNextSyncElementHeartRate(0L)
        verify(nsClient).nsAdd(
            "heartrate",
            DataSyncSelector.PairHeartRate(hr, hr.id),
            "1/1")
        verify(sp).getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)
        verify(sp).putLong(R.string.key_ns_heart_rate_last_synced_id, 0L)
        verifyNoMoreInteractions(sp)
    }

    @Test
    fun processChangedHeartRate_ignoreNightscoutImport() = runBlocking {
        val hr = createHeartRate(1L).apply { interfaceIDs.nightscoutId = "foo" }
        `when`(repo.getLastHeartRateId()).thenReturn(1L)
        `when`(sp.getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)).thenReturn(0L)
        `when`(repo.getNextSyncElementHeartRate(0L)).thenReturn(hr to null)
        dss.processChangedHeartRate()
        verify(repo).getLastHeartRateId()
        verify(repo, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(sp).putLong(R.string.key_ns_heart_rate_last_synced_id, 1L)
    }

    @Test
    fun processChangedHeartRate_UpdateOne() = runBlocking {
        val newHr = createHeartRate(1L).apply { beatsPerMinute = 99.0; interfaceIDs.nightscoutId = "id1" }
        val refHr = createHeartRate(2L).apply { referenceId = 1L }
        `when`(repo.getLastHeartRateId()).thenReturn(2L)
        `when`(sp.getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)).thenReturn(1L)
        `when`(repo.getNextSyncElementHeartRate(1L)).thenReturn(newHr to refHr)
        `when`(nsClient.nsUpdate(
            "heartrate",
            DataSyncSelector.PairHeartRate(newHr, refHr.id),
            "2/2")).thenReturn(true)
        dss.processChangedHeartRate()

        verify(repo).getLastHeartRateId()
        verify(repo, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        verify(nsClient).nsUpdate(
            "heartrate",
            DataSyncSelector.PairHeartRate(newHr, refHr.id),
            "2/2")
        verify(sp).putLong(R.string.key_ns_heart_rate_last_synced_id, 2L)
    }

    @Test
    fun processChangedHeartRate_addMultiple() = runBlocking {
        val hrs = listOf(3L, 4L, 6L).map { id -> createHeartRate(id) }
        `when`(repo.getNextSyncElementHeartRate(anyLong())).thenAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            hrs.firstOrNull { hr -> hr.id > id }?.let { hr -> hr to null }
        }
        for (hr in hrs) {
            `when`(nsClient.nsAdd(
                "heartrate",
                DataSyncSelector.PairHeartRate(hr, hr.id),
                "${hr.id}/6")).thenReturn(true)
        }
        `when`(repo.getLastHeartRateId()).thenReturn(6L)
        `when`(sp.getLong(R.string.key_ns_heart_rate_last_synced_id, 0L)).thenReturn(2L)

        dss.processChangedHeartRate()

        verify(repo).getLastHeartRateId()
        verify(repo, atLeast(0)).getNextSyncElementHeartRate(anyLong())
        for (hr in hrs) {
            verify(nsClient).nsAdd(
                "heartrate", DataSyncSelector.PairHeartRate(hr, hr.id), "${hr.id}/6"
            )
        }
        verify(sp).putLong(R.string.key_ns_heart_rate_last_synced_id, 6L)
    }

    companion object {
        fun createHeartRate(id: Long, timestamp: Long? = null, beatsPerMinute: Double = 80.0) =
            HeartRate(
                id = id,
                timestamp = timestamp ?: System.currentTimeMillis(),
                duration = 60_0000L,
                beatsPerMinute = beatsPerMinute,
                device = "T",
            )
    }
}