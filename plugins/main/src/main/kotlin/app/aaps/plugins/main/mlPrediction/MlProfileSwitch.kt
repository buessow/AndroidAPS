package app.aaps.plugins.main.mlPrediction

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

data class MlProfileSwitch(
    val start: Instant,
    val basalRates: List<Pair<Duration, Double>>,
    val duration: Duration? = null,
    val rate: Double = 1.0) {

    val isValid = basalRates.sumOf { (d, _) -> d.seconds } == 24 * 3600L

    fun toBasal(
        from: OffsetDateTime,
        to: OffsetDateTime) = sequence {
        if (!isValid) throw IllegalArgumentException("Invalid basal profile")
        if (to <= from) return@sequence

        var i = 0
        var t = from.truncatedTo(ChronoUnit.DAYS)
        while (t < to) {
            val (d, amount) = basalRates[i]
            if (t > from) {
                yield (DateValue(t.toInstant(), amount * rate))
            } else if (t + d > from) {
                yield (DateValue(from.toInstant(), amount * rate))
            }
            if (d == Duration.ZERO) break
            t += d
            i = (i + 1) % basalRates.size
        }
    }
}