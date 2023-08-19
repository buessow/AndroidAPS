package info.nightscout.plugins.sync.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.android.ContributesAndroidInjector
import info.nightscout.interfaces.XDripBroadcast
import info.nightscout.interfaces.nsclient.NSSettingsStatus
import info.nightscout.interfaces.nsclient.ProcessedDeviceStatusData
import info.nightscout.interfaces.nsclient.StoreDataForDb
import info.nightscout.interfaces.sync.DataSyncSelectorXdrip
import info.nightscout.plugins.sync.nsShared.NSClientFragment
import info.nightscout.plugins.sync.nsShared.StoreDataForDbImpl
import info.nightscout.plugins.sync.nsclient.data.NSSettingsStatusImpl
import info.nightscout.plugins.sync.nsclient.data.ProcessedDeviceStatusDataImpl
import info.nightscout.plugins.sync.nsclient.services.NSClientService
import info.nightscout.plugins.sync.nsclient.workers.NSClientAddAckWorker
import info.nightscout.plugins.sync.nsclient.workers.NSClientAddUpdateWorker
import info.nightscout.plugins.sync.nsclient.workers.NSClientMbgWorker
import info.nightscout.plugins.sync.nsclient.workers.NSClientUpdateRemoveAckWorker
import info.nightscout.plugins.sync.nsclientV3.workers.DataSyncWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadBgWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadDeviceStatusWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadFoodsWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadHeartRateWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadLastModificationWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadProfileStoreWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadStatusWorker
import info.nightscout.plugins.sync.nsclientV3.workers.LoadTreatmentsWorker
import info.nightscout.plugins.sync.tidepool.TidepoolFragment
import info.nightscout.plugins.sync.xdrip.DataSyncSelectorXdripImpl
import info.nightscout.plugins.sync.xdrip.XdripFragment
import info.nightscout.plugins.sync.xdrip.XdripPlugin
import info.nightscout.plugins.sync.xdrip.workers.XdripDataSyncWorker

@Module(
    includes = [
        SyncModule.Binding::class,
        SyncModule.Provide::class
    ]
)

@Suppress("unused")
abstract class SyncModule {

    @ContributesAndroidInjector abstract fun contributesNSClientFragment(): NSClientFragment

    @ContributesAndroidInjector abstract fun contributesNSClientService(): NSClientService
    @ContributesAndroidInjector abstract fun contributesNSClientWorker(): NSClientAddUpdateWorker
    @ContributesAndroidInjector abstract fun contributesNSClientAddAckWorker(): NSClientAddAckWorker
    @ContributesAndroidInjector abstract fun contributesNSClientUpdateRemoveAckWorker(): NSClientUpdateRemoveAckWorker
    @ContributesAndroidInjector abstract fun contributesNSClientMbgWorker(): NSClientMbgWorker

    @ContributesAndroidInjector abstract fun contributesLoadStatusWorker(): LoadStatusWorker
    @ContributesAndroidInjector abstract fun contributesLoadLastModificationWorker(): LoadLastModificationWorker
    @ContributesAndroidInjector abstract fun contributesLoadBgWorker(): LoadBgWorker
    @ContributesAndroidInjector abstract fun contributesLoadFoodsWorker(): LoadFoodsWorker
    @ContributesAndroidInjector abstract fun contributesLoadProfileStoreWorker(): LoadProfileStoreWorker
    @ContributesAndroidInjector abstract fun contributesTreatmentWorker(): LoadTreatmentsWorker
    @ContributesAndroidInjector abstract fun contributesLoadDeviceStatusWorker(): LoadDeviceStatusWorker
    @ContributesAndroidInjector abstract fun contributesDataSyncWorker(): DataSyncWorker

    @ContributesAndroidInjector abstract fun contributesTidepoolFragment(): TidepoolFragment
    @ContributesAndroidInjector abstract fun contributesXdripFragment(): XdripFragment
    @ContributesAndroidInjector abstract fun contributesXdripDataSyncWorker(): XdripDataSyncWorker
    @ContributesAndroidInjector abstract fun contributesLoadHeartRateWorker(): LoadHeartRateWorker

    @Module
    open class Provide {

        @Reusable
        @Provides
        fun providesWorkManager(context: Context) = WorkManager.getInstance(context)
    }
    @Module
    interface Binding {

        @Binds fun bindProcessedDeviceStatusData(processedDeviceStatusDataImpl: ProcessedDeviceStatusDataImpl): ProcessedDeviceStatusData
        @Binds fun bindNSSettingsStatus(nsSettingsStatusImpl: NSSettingsStatusImpl): NSSettingsStatus
        @Binds fun bindDataSyncSelectorXdripInterface(dataSyncSelectorXdripImpl: DataSyncSelectorXdripImpl): DataSyncSelectorXdrip
        @Binds fun bindStoreDataForDb(storeDataForDbImpl: StoreDataForDbImpl): StoreDataForDb
        @Binds fun bindXDripBroadcastInterface(xDripBroadcastImpl: XdripPlugin): XDripBroadcast
    }

}