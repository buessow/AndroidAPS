package app.aaps.plugins.main.mlPrediction

import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant

interface DataProvider {
    fun getGlucoseReadings(from: Instant): Single<List<DateValue>>
    fun getHeartRates(from: Instant): Single<List<DateValue>>
    fun getCarbs(from: Instant): Single<List<DateValue>>
    fun getBoluses(from: Instant): Single<List<DateValue>>
    fun getLongHeartRates(at: Instant, threshold: Int, durations: List<Duration>): Single<List<Int>> {
        val from = at - (durations.maxOrNull() ?: return Single.just(emptyList()))
        return getHeartRates(from).map { hrs ->
            val counts = MutableList(durations.size) {0 }
            for (hr in hrs) {
                for ((i, period) in durations.withIndex()) {
                    if (hr.timestamp < at - period) continue
                    if (hr.value >= threshold) counts[i]++
                }
            }
            counts
        }
    }

    fun getBasalProfileSwitches(from: Instant): Maybe<MlProfileSwitches>

    fun getTemporaryBasalRates(from: Instant): Single<List<MlTemporaryBasalRate>>

}