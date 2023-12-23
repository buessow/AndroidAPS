package app.aaps.plugins.main.mlPrediction

import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class DataProviderWithCache(private val base: DataProvider): DataProvider by base {

    private val lock = ReentrantReadWriteLock()
    private val cache = mutableMapOf<String, Int>()
    private fun key(at: Instant, threshold: Int, duration: Duration) =
        "${at.toEpochMilli()}-$threshold-${duration}"

    override fun getLongHeartRates(
        at: Instant, threshold: Int, durations: List<Duration>): Single<List<Int>> {
        val atHourly = at.truncatedTo(ChronoUnit.HOURS)
        val missing = lock.read {
            durations.filter { d -> cache[key(atHourly, threshold, d)] == null }
        }
        if (missing.isNotEmpty()) {
            lock.write {
                base.getLongHeartRates(atHourly, threshold, missing)
                    .blockingGet()
                    .forEachIndexed { i, hr ->
                        val key = key(atHourly, threshold, durations[i])
                        cache[key] = hr
                    }
            }
        }
        return Single.just(lock.read {
            durations.map { d -> cache[key(atHourly, threshold, d)] ?: 0 }})
    }
}