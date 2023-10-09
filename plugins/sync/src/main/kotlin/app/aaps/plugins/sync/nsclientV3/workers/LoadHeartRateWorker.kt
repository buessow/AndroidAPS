package info.nightscout.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import info.nightscout.core.utils.worker.LoggingWorker
import info.nightscout.interfaces.nsclient.StoreDataForDb
import info.nightscout.interfaces.sync.NsClient
import info.nightscout.plugins.sync.R
import info.nightscout.plugins.sync.nsclientV3.NSClientV3Plugin
import info.nightscout.plugins.sync.nsclientV3.extensions.toHeartRate
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventNSClientNewLog
import info.nightscout.rx.logging.LTag
import info.nightscout.sdk.localmodel.heartrate.NSHeartRate
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
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
                storeDataForDb.heartRates.addAll(
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