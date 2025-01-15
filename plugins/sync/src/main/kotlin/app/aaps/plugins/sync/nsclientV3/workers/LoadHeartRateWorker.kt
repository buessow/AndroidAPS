package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.nsclientV3.extensions.toHeartRate
import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import kotlinx.coroutines.Dispatchers
import java.lang.Long.max
import javax.inject.Inject

/** Loads modified heart rate values from Nightscout and updates the local database. */
class LoadHeartRateWorker (
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var storeDataForDb: StoreDataForDb

    //** Gets the records modification date string. */
    private fun mod(nsHr: NSHeartRate?): String? {
        val millis = nsHr?.srvModified?.takeUnless { it == 0L } ?: return null
        return dateUtil.toISOString(millis)
    }

    private fun updateLastLoadedSrvModified(millis: Long) {
        nsClientV3Plugin.lastLoadedSrvModified.collections.heartRate = millis
        nsClientV3Plugin.storeLastLoadedSrvModified()
    }

    override suspend fun doWorkAndLog(): Result {
        if (!sp.getBoolean(R.string.key_ns_receive_heart_rate, false)) {
            aapsLogger.info(LTag.NSCLIENT, "Loading HR from NS disabled")
            return Result.success()
        }
        if (!nsClientV3Plugin.supportsHeartRate) {
            rxBus.send(EventNSClientNewLog("SKIP", "Heart rate is not supported by Nightscout"))
            return Result.failure()
        }
        val client = nsClientV3Plugin.nsAndroidClient ?:
            return Result.failure(workDataOf("Error" to "AndroidClient is null"))

        var from = max(
            nsClientV3Plugin.lastLoadedSrvModified.collections.heartRate,
            dateUtil.now() - nsClientV3Plugin.maxAge)
        // If this is the first load we don't want to add records that were created before maxAge but
        // modified later.
        val skipBefore = if (nsClientV3Plugin.isFirstLoad(NsClient.Collection.HEART_RATE)) from else 0L
        do {
            val nsHeartRates = client.getHeartRatesModifiedSince(from, NSClientV3Plugin.RECORDS_TO_LOAD)
            if (nsHeartRates.isNotEmpty()) {
                rxBus.send(EventNSClientNewLog(
                    "◄ RECV",
                    "${nsHeartRates.size} HRs from NS start ${dateUtil.toISOString(from)} " +
                        "${mod(nsHeartRates.first())}..${mod(nsHeartRates.last())}"))
                storeDataForDb.addToHeartRates(
                    nsHeartRates.map(NSHeartRate::toHeartRate).filter { hr -> hr.dateCreated > skipBefore })
                storeDataForDb.storeHeartRatesToDb()
                from = nsHeartRates.last().srvModified!!
                updateLastLoadedSrvModified(from)
            }
        } while (nsHeartRates.size >= NSClientV3Plugin.RECORDS_TO_LOAD)

        rxBus.send(EventNSClientNewLog(
            "◄ RCV HR END", "No more data from ${dateUtil.toISOString(from)}"))
        return Result.success()
    }
}