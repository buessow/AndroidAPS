package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.testing.TestListenableWorkerBuilder
import dagger.android.AndroidInjector
import app.aaps.database.entities.HeartRate
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.extensions.toHeartRate
import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate
import app.aaps.core.nssdk.remotemodel.LastModified
import app.aaps.shared.impl.utils.DateUtilImpl
import app.aaps.shared.tests.TestBase
import dagger.android.HasAndroidInjector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.verifyNoMoreInteractions
import java.time.Instant

class LoadHeartRateWorkerTest: TestBase() {

    abstract class ContextWithInjector : Context(), HasAndroidInjector

    @Mock private lateinit var androidClient: NSAndroidClient
    @Mock private lateinit var context: ContextWithInjector
    @Mock private lateinit var fabricPrivacy: FabricPrivacy
    @Mock private lateinit var sp: SP
    @Mock private lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Mock private lateinit var storeDataForDb: StoreDataForDb
    private lateinit var worker: LoadHeartRateWorker
    private lateinit var lastModified: LastModified
    private val hrToStore = mutableListOf<HeartRate>()

    private val now = 100_000_000L
    private val maxAge = 10_000L

    private val injector = HasAndroidInjector {
        AndroidInjector {
            if (it is LoadHeartRateWorker) {
                it.aapsLogger = aapsLogger
                it.fabricPrivacy = fabricPrivacy
                it.sp = sp
                it.rxBus = rxBus
                it.dateUtil = spy(DateUtilImpl(context))
                it.nsClientV3Plugin = nsClientV3Plugin
                it.storeDataForDb = storeDataForDb
            }
        }
    }

    @BeforeEach
    fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.androidInjector()).thenReturn(injector.androidInjector())
        lastModified = LastModified(LastModified.Collections())
        worker = TestListenableWorkerBuilder<LoadHeartRateWorker>(context).build().apply {
            `when`(sp.getBoolean(R.string.key_ns_receive_heart_rate, false)).thenReturn(true)
            `when`(nsClientV3Plugin.supportsHeartRate).thenReturn(true)
            `when`(nsClientV3Plugin.nsAndroidClient).thenReturn(androidClient)
            `when`(nsClientV3Plugin.lastLoadedSrvModified).thenReturn(lastModified)
            `when`(nsClientV3Plugin.maxAge).thenReturn(maxAge)
            `when`(dateUtil.now()).thenReturn(now)
            `when`(storeDataForDb.heartRates).thenReturn(hrToStore)
        }
    }

    @AfterEach
    fun cleanup() {
        hrToStore.clear()

        verify(worker.nsClientV3Plugin, atLeast(1)).supportsHeartRate
        verify(worker.nsClientV3Plugin, atLeast(1)).nsAndroidClient
        verify(worker.nsClientV3Plugin, atLeast(1)).isFirstLoad(
            NsClient.Collection.HEART_RATE)
        verify(worker.nsClientV3Plugin, atLeast(1)).lastLoadedSrvModified
        verify(worker.storeDataForDb, atLeast(1)).heartRates
        verify(worker.nsClientV3Plugin, atLeast(1)).maxAge

        verifyNoMoreInteractions(androidClient)
        verifyNoMoreInteractions(worker.nsClientV3Plugin)
        verifyNoMoreInteractions(worker.storeDataForDb)
    }

    @Test
    fun firstLoadOne() = runBlocking<Unit> {
        assertNotNull(worker)
        val nsHr = createNSHeartRate(now - 100L)
        `when`(worker.nsClientV3Plugin.isFirstLoad(NsClient.Collection.HEART_RATE)).thenReturn(true)
        `when`(androidClient.getHeartRatesModifiedSince(now - maxAge, NSClientV3Plugin.RECORDS_TO_LOAD))
                   .thenReturn(listOf(nsHr))
        // Make sure state changes happen before state is saved.
        doAnswer { assertEquals(listOf(nsHr.toHeartRate()), hrToStore) }
            .`when`(worker.storeDataForDb).storeHeartRatesToDb()
        doAnswer { assertEquals(now - 100L, lastModified.collections.heartRate) }
            .`when`(worker.nsClientV3Plugin).storeLastLoadedSrvModified()

        worker.doWorkAndLog()

        assertEquals(listOf(nsHr.toHeartRate()), hrToStore)
        assertEquals(now - 100L, lastModified.collections.heartRate)
        verify(worker.storeDataForDb).storeHeartRatesToDb()
        verify(worker.nsClientV3Plugin).storeLastLoadedSrvModified()
        verify(androidClient).getHeartRatesModifiedSince(
            now - maxAge, NSClientV3Plugin.RECORDS_TO_LOAD)
    }

    @Test
    fun updateLoadOne() = runBlocking<Unit> {
        assertNotNull(worker)
        val from = now - maxAge + 11
        lastModified.collections.heartRate = from
        val nsHr = createNSHeartRate(from + 2)
        `when`(worker.nsClientV3Plugin.isFirstLoad(NsClient.Collection.HEART_RATE)).thenReturn(false)
        `when`(androidClient.getHeartRatesModifiedSince(from, NSClientV3Plugin.RECORDS_TO_LOAD))
            .thenReturn(listOf(nsHr))
        doAnswer { assertEquals(listOf(nsHr.toHeartRate()), hrToStore) }
            .`when`(worker.storeDataForDb).storeHeartRatesToDb()
        doAnswer { assertEquals(from + 2, lastModified.collections.heartRate) }
            .`when`(worker.nsClientV3Plugin).storeLastLoadedSrvModified()

        worker.doWorkAndLog()

        assertEquals(listOf(nsHr.toHeartRate()), hrToStore)
        assertEquals(from + 2, lastModified.collections.heartRate)
        verify(worker.storeDataForDb).storeHeartRatesToDb()
        verify(worker.nsClientV3Plugin).storeLastLoadedSrvModified()
        verify(androidClient).getHeartRatesModifiedSince(from, NSClientV3Plugin.RECORDS_TO_LOAD)
    }

    @Test
    fun loadMany() = runBlocking<Unit> {
        val from1 = now - maxAge
        val nsHrs1 = createNSHeartRates(from1, NSClientV3Plugin.RECORDS_TO_LOAD)
        val from2 = nsHrs1.last().srvModified!!
        val nsHrs2 = createNSHeartRates(from2 + 1, 10)
        val from3 = nsHrs2.last().srvModified!!

        `when`(worker.nsClientV3Plugin.isFirstLoad(NsClient.Collection.HEART_RATE)).thenReturn(false)
        `when`(androidClient.getHeartRatesModifiedSince(from1, NSClientV3Plugin.RECORDS_TO_LOAD))
            .thenReturn(nsHrs1)
        `when`(androidClient.getHeartRatesModifiedSince(from2, NSClientV3Plugin.RECORDS_TO_LOAD))
            .thenReturn(nsHrs2)

        val expectedHr = ArrayDeque(listOf(
            nsHrs1.map(NSHeartRate::toHeartRate), nsHrs2.map(NSHeartRate::toHeartRate)))
        doAnswer { assertEquals(expectedHr.removeFirst(), hrToStore); hrToStore.clear() }
            .`when`(worker.storeDataForDb).storeHeartRatesToDb()

        val expectedFrom = ArrayDeque(listOf(from2, from3))
        doAnswer { assertEquals(expectedFrom.removeFirst(), lastModified.collections.heartRate) }
            .`when`(worker.nsClientV3Plugin).storeLastLoadedSrvModified()

        worker.doWorkAndLog()

        assertEquals(nsHrs2.last().srvModified, lastModified.collections.heartRate)
        verify(worker.nsClientV3Plugin, times(2)).storeLastLoadedSrvModified()
        verify(worker.storeDataForDb, times(2)).storeHeartRatesToDb()
        verify(androidClient).getHeartRatesModifiedSince(from1, NSClientV3Plugin.RECORDS_TO_LOAD)
        verify(androidClient).getHeartRatesModifiedSince(from2, NSClientV3Plugin.RECORDS_TO_LOAD)
    }

    @Test
    fun loadManyFirstLoad() = runBlocking<Unit> {
        val from1 = now - maxAge
        val nsHrs1 = createNSHeartRates(from1 - 20, NSClientV3Plugin.RECORDS_TO_LOAD)
        val from2 = nsHrs1.last().srvModified!!
        val nsHrs2 = createNSHeartRates(from2 + 1, 10)
        val from3 = nsHrs2.last().srvModified!!

        `when`(worker.nsClientV3Plugin.isFirstLoad(NsClient.Collection.HEART_RATE)).thenReturn(true)
        `when`(androidClient.getHeartRatesModifiedSince(from1, NSClientV3Plugin.RECORDS_TO_LOAD))
            .thenReturn(nsHrs1)
        `when`(androidClient.getHeartRatesModifiedSince(from2, NSClientV3Plugin.RECORDS_TO_LOAD))
            .thenReturn(nsHrs2)

        val expectedHr = ArrayDeque(listOf(
            nsHrs1.map(NSHeartRate::toHeartRate).drop(21), nsHrs2.map(NSHeartRate::toHeartRate)))
        doAnswer { assertEquals(expectedHr.removeFirst().size, hrToStore.size); hrToStore.clear() }
            .`when`(worker.storeDataForDb).storeHeartRatesToDb()

        val expectedFrom = ArrayDeque(listOf(from2, from3))
        doAnswer { assertEquals(expectedFrom.removeFirst(), lastModified.collections.heartRate) }
            .`when`(worker.nsClientV3Plugin).storeLastLoadedSrvModified()

        worker.doWorkAndLog()

        assertEquals(nsHrs2.last().srvModified, lastModified.collections.heartRate)
        verify(worker.nsClientV3Plugin, times(2)).storeLastLoadedSrvModified()
        verify(worker.storeDataForDb, times(2)).storeHeartRatesToDb()
        verify(androidClient).getHeartRatesModifiedSince(from1, NSClientV3Plugin.RECORDS_TO_LOAD)
        verify(androidClient).getHeartRatesModifiedSince(from2, NSClientV3Plugin.RECORDS_TO_LOAD)
    }

    companion object {
        fun createNSHeartRates(startTimestamp: Long, count: Int) =
            (0 until count).map { i ->
                createNSHeartRate(startTimestamp + i, 50.0 + i)
            }

        fun createNSHeartRate(timestamp: Long, beatsPerMinute: Double = 80.0) =
            NSHeartRate(
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                srvModified = timestamp,
                date = timestamp,
                duration = 60_0000L,
                beatsPerMinute = beatsPerMinute,
                device = "T",
                isValid = true,
                utcOffset = 0L,
                identifier = "id1")
    }
}