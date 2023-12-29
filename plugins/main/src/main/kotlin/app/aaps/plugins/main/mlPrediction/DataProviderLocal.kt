package app.aaps.plugins.main.mlPrediction

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.PS
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant

class DataProviderLocal(val perstence: PersistenceLayer): DataProvider {
    override fun getGlucoseReadings(from: Instant): Single<List<DateValue>> {
        return perstence.getBgReadingsDataFromTime(from.toEpochMilli(), true)
            .map { gs -> gs.map { g -> DateValue(g.timestamp, g.value) }}
    }

    override fun getHeartRates(from: Instant): Single<List<DateValue>> =
        Single.just(perstence.getHeartRatesFromTime(from.toEpochMilli())
            .map { hr -> DateValue(hr.timestamp, hr.beatsPerMinute) })

    override fun getCarbs(from: Instant): Single<List<DateValue>> =
        perstence.getCarbsFromTime(from.toEpochMilli(), true)
            .map { cs -> cs.map { c -> DateValue(c.timestamp, c.amount) }}

    override fun getBoluses(from: Instant): Single<List<DateValue>> =
        perstence.getBolusesFromTime(from.toEpochMilli(), true)
            .map { boli -> boli.map { b -> DateValue(b.timestamp, b.amount) }}


    private fun toBasalProfileSwitch(ps: PS) =
        MlProfileSwitch(
            Instant.ofEpochMilli(ps.timestamp),
            ps.basalBlocks.map { b -> Duration.ofMillis(b.duration) to b.amount },
            Duration.ofMillis(ps.duration).takeIf { it > Duration.ZERO },
            ps.percentage / 100.0)

    private fun toBasalProfileSwitch(eps: EPS) =
        MlProfileSwitch(
            Instant.ofEpochMilli(eps.timestamp),
            eps.basalBlocks.map { b -> Duration.ofMillis(b.duration) to b.amount },
            null,
            1.0)

    override fun getBasalProfileSwitches(from: Instant): Maybe<MlProfileSwitches> {
        val firstPermanent = perstence.getPermanentProfileSwitchActiveAt(from.toEpochMilli())
        val first = perstence.getEffectiveProfileSwitchActiveAt(from.toEpochMilli())
        if (first == null || firstPermanent == null) {
            return Maybe.empty()
        }
        return perstence.getProfileSwitchesFromTime(from.toEpochMilli(), true)
            .map { pss ->
                MlProfileSwitches(
                    toBasalProfileSwitch(firstPermanent),
                    toBasalProfileSwitch(first),
                    pss.map(::toBasalProfileSwitch)) }.toMaybe()
    }

    override fun getTemporaryBasalRates(from: Instant) =
        Single.just(perstence.getTemporaryBasalsActiveBetweenTimeAndTime(
            from.toEpochMilli(), Instant.now().toEpochMilli())
            .map { tbs -> MlTemporaryBasalRate(
                Instant.ofEpochMilli(tbs.timestamp),
                Duration.ofMillis(tbs.duration),
                if (tbs.isAbsolute) 1.0 else tbs.rate / 100.0,
                if (tbs.isAbsolute) tbs.rate else null) })
}