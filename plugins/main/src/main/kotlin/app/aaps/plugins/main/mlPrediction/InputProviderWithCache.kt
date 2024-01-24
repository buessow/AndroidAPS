package app.aaps.plugins.main.mlPrediction

import cc.buessow.glumagic.input.InputProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class InputProviderWithCache(private val base: InputProvider): InputProvider by base {

    private val lock = ReentrantReadWriteLock()
    private val cache = mutableMapOf<String, Int>()
    private fun key(at: Instant, threshold: Int, duration: Duration) =
        "${at.toEpochMilli()}-$threshold-${duration}"

    override suspend fun getLongHeartRates(
        at: Instant, threshold: Int, durations: List<Duration>): List<Int> {
        val atHourly = at.truncatedTo(ChronoUnit.HOURS)
        val missing = lock.read {
            durations.filter { d -> cache[key(atHourly, threshold, d)] == null }
        }
        if (missing.isNotEmpty()) {
            lock.write {
                base.getLongHeartRates(atHourly, threshold, missing)
                    .forEachIndexed { i, hr ->
                        val key = key(atHourly, threshold, durations[i])
                        cache[key] = hr
                    }
            }
        }
        return lock.read { durations.map { d -> cache[key(atHourly, threshold, d)] ?: 0 }}
    }
}