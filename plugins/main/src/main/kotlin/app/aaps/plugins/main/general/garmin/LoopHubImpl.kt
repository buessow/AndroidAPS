package info.nightscout.plugins.general.garmin

import androidx.annotation.VisibleForTesting
import info.nightscout.database.ValueWrapper
import info.nightscout.database.entities.EffectiveProfileSwitch
import info.nightscout.database.entities.GlucoseValue
import info.nightscout.database.entities.HeartRate
import info.nightscout.database.entities.OfflineEvent
import info.nightscout.database.entities.UserEntry
import info.nightscout.database.entities.ValueWithUnit
import info.nightscout.database.impl.AppRepository
import info.nightscout.database.impl.transactions.CancelCurrentOfflineEventIfAnyTransaction
import info.nightscout.database.impl.transactions.InsertOrUpdateHeartRateTransaction
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.aps.Loop
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.logging.UserEntryLogger
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.pump.DetailedBolusInfo
import info.nightscout.interfaces.queue.CommandQueue
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
class LoopHubImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val constraintChecker: Constraints,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val repo: AppRepository,
    private val userEntryLogger: UserEntryLogger,
) : LoopHub {

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    /** Returns the active insulin profile. */
    override val currentProfile: Profile? get() = profileFunction.getProfile()

    /** Returns the name of the active insulin profile. */
    override val currentProfileName: String
        get() = profileFunction.getProfileName()

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    override val glucoseUnit: GlucoseUnit
        get() = profileFunction.getProfile()?.units ?: GlucoseUnit.MGDL

    /** Returns the remaining bolus insulin on board. */
    override val insulinOnboard: Double
        get() = iobCobCalculator.calculateIobFromBolus().iob

    /** Returns true if the pump is connected. */
    override val isConnected: Boolean get() = !loop.isDisconnected

    /** Returns true if the current profile is set of a limited amount of time. */
    override val isTemporaryProfile: Boolean
        get() {
            val resp = repo.getEffectiveProfileSwitchActiveAt(clock.millis())
            val ps: EffectiveProfileSwitch? =
                (resp.blockingGet() as? ValueWrapper.Existing<EffectiveProfileSwitch>)?.value
            return ps != null && ps.originalDuration > 0
        }

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    override val temporaryBasal: Double
        get() {
            val apsResult = loop.lastRun?.constraintsProcessed
            return if (apsResult == null) Double.NaN else apsResult.percent / 100.0
        }

    /** Tells the loop algorithm that the pump is physicallly connected. */
    override fun connectPump() {
        repo.runTransaction(
            CancelCurrentOfflineEventIfAnyTransaction(clock.millis())
        ).subscribe()
        commandQueue.cancelTempBasal(true, null)
        userEntryLogger.log(UserEntry.Action.RECONNECT, UserEntry.Sources.GarminDevice)
    }

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    override fun disconnectPump(minutes: Int) {
        currentProfile?.let { p ->
            loop.goToZeroTemp(minutes, p, OfflineEvent.Reason.DISCONNECT_PUMP)
            userEntryLogger.log(
                UserEntry.Action.DISCONNECT,
                UserEntry.Sources.GarminDevice,
                ValueWithUnit.Minute(minutes)
            )
        }
    }

    /** Retrieves the glucose values starting at from. */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GlucoseValue> {
        return repo.compatGetBgReadingsDataFromTime(from.toEpochMilli(), ascending)
                   .blockingGet()
    }

    /** Notifies the system that carbs were eaten and stores the value. */
    override fun postCarbs(carbohydrates: Int) {
        aapsLogger.info(LTag.GARMIN, "post $carbohydrates g carbohydrates")
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(Constraint(carbohydrates)).value()
        userEntryLogger.log(
            UserEntry.Action.CARBS,
            UserEntry.Sources.GarminDevice,
            ValueWithUnit.Gram(carbsAfterConstraints)
        )
        val detailedBolusInfo = DetailedBolusInfo().apply {
            eventType = DetailedBolusInfo.EventType.CARBS_CORRECTION
            carbs = carbsAfterConstraints.toDouble()
        }
        commandQueue.bolus(detailedBolusInfo, null)
    }

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    override fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?) {
        val hr = HeartRate(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = avgHeartRate.toDouble(),
            device = device ?: "Garmin",
        )
        repo.runTransaction(InsertOrUpdateHeartRateTransaction(hr)).blockingAwait()
    }
}