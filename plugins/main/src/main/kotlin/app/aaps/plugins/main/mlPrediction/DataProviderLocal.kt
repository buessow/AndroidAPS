package app.aaps.plugins.main.mlPrediction

import app.aaps.database.impl.AppRepository
import io.reactivex.rxjava3.core.Single
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
}