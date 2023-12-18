package app.aaps.plugins.main.mlPrediction

import io.reactivex.rxjava3.core.Single
import java.time.Instant

interface DataProvider {
    fun getGlucoseReadings(from: Instant): Single<List<DateValue>>
    fun getHeartRates(from: Instant): Single<List<DateValue>>
    fun getCarbs(from: Instant): Single<List<DateValue>>
    fun getBoluses(from: Instant): Single<List<DateValue>>
}