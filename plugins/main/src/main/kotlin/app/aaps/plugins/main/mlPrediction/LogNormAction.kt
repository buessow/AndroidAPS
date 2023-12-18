package app.aaps.plugins.main.mlPrediction

import java.time.Instant
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class LogNormAction(
  private var mu: Double, private val sigma: Double = 1.0) {

    val maxAge = Duration.ofHours(4)

    constructor(timeToPeak: Duration, sigma: Double = 1.0) : this(
        mu = ln(timeToPeak.toMillis() / 3600_000.0) + sigma*sigma,
        sigma = sigma)

    fun <T> valuesAt(
        values: List<T>,
        a: (T) -> Pair<Instant, Double>,
        times: Iterable<Instant>): List<Double> {

        val results = mutableListOf<Double>()

        var winStart = 0
        for (t in times) {
            // Move the first value in the time window we're interested in.
            while (winStart < values.size && t - maxAge > a(values[winStart]).first)
                winStart++

            var total = 0.0
            var i = winStart
            while (i < values.size) {
                val (ti, vi) = a(values[i])
                val td = Duration.between(ti, t)
                if (td <= Duration.ZERO) break
                val x = td.toMillis() / 3_600_000.0
                val exp = -(ln(x) - mu).pow(2) / (2 * sigma.pow(2))
                val y = 1 / (x * sigma * sqrt(2 * PI)) * exp(exp)
                if (!y.isFinite()) throw AssertionError()
                total += vi * y
                i++
            }
            results.add(total)
        }
        return results
    }
}