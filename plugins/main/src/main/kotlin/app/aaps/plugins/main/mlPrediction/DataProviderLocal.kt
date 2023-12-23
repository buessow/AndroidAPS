package app.aaps.plugins.main.mlPrediction

import app.aaps.database.entities.ProfileSwitch
import app.aaps.database.impl.AppRepository
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant

class DataProviderLocal(val repo: AppRepository): DataProvider {
    override fun getGlucoseReadings(from: Instant): Single<List<DateValue>> {
        return repo.compatGetBgReadingsDataFromTime(from.toEpochMilli(), true)
            .map { gs -> gs.map { g -> DateValue(g.timestamp, g.value) }}
    }

    override fun getHeartRates(from: Instant): Single<List<DateValue>> =
        repo.getHeartRatesFromTime(from.toEpochMilli())
            .map { hrs -> hrs.map { hr -> DateValue(hr.timestamp, hr.beatsPerMinute) }}

    override fun getCarbs(from: Instant): Single<List<DateValue>> =
        repo.getCarbsDataFromTime(from.toEpochMilli(), true)
            .map { cs -> cs.map { c -> DateValue(c.timestamp, c.amount) }}

    override fun getBoluses(from: Instant): Single<List<DateValue>> =
        repo.getBolusesDataFromTime(from.toEpochMilli(), true)
            .map { bs -> bs.map { b -> DateValue(b.timestamp, b.amount) }}


    private fun toBasalProfileSwitch(ps: ProfileSwitch) =
        MlProfileSwitch(
            Instant.ofEpochMilli(ps.timestamp),
            ps.basalBlocks.map { b -> Duration.ofMillis(b.duration) to b.amount },
            Duration.ofMillis(ps.duration).takeIf { it > Duration.ZERO },
            ps.percentage / 100.0)

    override fun getBasalProfileSwitches(from: Instant): Maybe<MlProfileSwitches> {
        val firstPermanent = repo.getPermanentProfileSwitch(from.toEpochMilli())
        val first = repo.getActiveProfileSwitch(from.toEpochMilli())
        if (first == null || firstPermanent == null) {
            return Maybe.empty()
        }
        return repo.getProfileSwitchDataFromTime(from.toEpochMilli(), true)
            .map { pss ->
                MlProfileSwitches(
                    toBasalProfileSwitch(firstPermanent),
                    toBasalProfileSwitch(first),
                    pss.map(::toBasalProfileSwitch)) }.toMaybe()
    }

    override fun getTemporaryBasalRates(from: Instant): Single<List<MlTemporaryBasalRate>> =
        repo.getTemporaryBasalsDataActiveBetweenTimeAndTime(from.toEpochMilli(), Instant.now().toEpochMilli())
            .map { tbss -> tbss.map { tbs -> MlTemporaryBasalRate(
                Instant.ofEpochMilli(tbs.timestamp),
                Duration.ofMillis(tbs.duration),
                if (tbs.isAbsolute) 1.0 else tbs.rate / 100.0,
                if (tbs.isAbsolute) tbs.rate else null) }}
}