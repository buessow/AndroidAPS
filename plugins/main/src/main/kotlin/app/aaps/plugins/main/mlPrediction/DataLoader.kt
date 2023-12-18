package app.aaps.plugins.main.mlPrediction

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.collect.FluentIterable.concat
import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class DataLoader(
    private val aapsLogger: AAPSLogger,
    private val dataProvider: DataProvider,
    time: Instant,
    private val config: Config = Config()) {

    private val at: Instant
    private val carbAction = LogNormAction(Duration.ofMinutes(45), sigma=0.5)
    private val insulinAction = LogNormAction(Duration.ofMinutes(60), sigma=0.5)

    init {
        at = time.truncatedTo(ChronoUnit.MINUTES)
    }

    companion object {
        operator fun Duration.div(d: Duration) = (seconds / d.seconds).toInt()

        class InstantProgression(
            override val start: Instant,
            override val endExclusive: Instant,
            private val step: Duration) : Iterable<Instant>, OpenEndRange<Instant> {
            override fun iterator(): Iterator<Instant> =
                object : Iterator<Instant> {
                    private var current = start
                    override fun hasNext() = current < endExclusive
                    override fun next() = current.apply { current += step }
                }

            infix fun step(step: Duration) = InstantProgression(start, endExclusive, step)
        }

        operator fun Instant.rangeUntil(other: Instant) =
            InstantProgression(this, other, Duration.ofMinutes(1))
    }

    @VisibleForTesting
    fun align(
        from: Instant, values: Iterable<DateValue>,
        interval: Duration = config.freq) = sequence {

        val start = from.truncatedTo(ChronoUnit.MINUTES)
        var t = start
        var last: DateValue? = null
        for (curr in values) {
            // Skip over values before the start.
            if (curr.timestamp < start) {
                last = curr
                continue
            }
            // Ignore duplicate values.
            if (curr.timestamp == last?.timestamp) continue
            while (t in (last?.timestamp ?: from) ..< curr.timestamp && t < at) {
                if (last != null) {
                    val d1 = Duration.between(last.timestamp, t).seconds
                    val d2 = Duration.between(t, curr.timestamp).seconds
                    // We weigh the value that is close to t higher.
                    val avg = (curr.value * d1 + last.value * d2) / (d1 + d2)
                    yield(avg.toFloat())
                } else {
                    yield(Float.NaN)
                }
                t += interval
            }
            last = curr
        }
        while (t in start ..< at) {
            yield(last?.value?.toFloat() ?: Float.NaN)
            t += interval
        }
    }

    fun loadGlucoseReadings(): Single<List<Float>> {
        val from = at - config.trainingPeriod
        return dataProvider.getGlucoseReadings(
            (from - Duration.ofMinutes(6))).map { gs ->
            align(from, gs).toList() }
    }

    fun loadHeartRates(): Single<List<Float>> {
        val from = at - config.trainingPeriod
        return dataProvider.getHeartRates(from - Duration.ofMinutes(6)).map { hrs ->
            val futureHeartRates = FloatArray(
                config.predictionPeriod / config.freq) { 60F }
            concat(
                align(from, hrs).asIterable(),
                futureHeartRates.asIterable()).toList()
        }
    }

    fun loadLongHeartRates(): Single<List<Float>> {
        val from = at - (config.hrLong.maxOrNull() ?: return Single.just(emptyList()))
        return dataProvider.getHeartRates(from).map { hrs ->
            val counts = IntArray(config.hrLong.size)
            for (hr in hrs) {
                for ((i, period) in config.hrLong.withIndex()) {
                    if (hr.timestamp < at - period) continue
                    if (hr.value >= config.hrLongThreshold) counts[i]++
                }
            }
            counts.map(Int::toFloat).toList()
        }
    }

    fun loadCarbAction(): Single<List<Float>> {
        val from = at - config.trainingPeriod
        val to = at + config.predictionPeriod
        return dataProvider.getCarbs(from - carbAction.maxAge).map { cs ->
            carbAction.valuesAt(
                cs,
                { c -> c.timestamp to c.value },
                from ..< to step config.freq).map(Double::toFloat)
        }
    }

    fun loadInsulinAction(): Single<List<Float>> {
        val from = at - config.trainingPeriod
        val to = at + config.predictionPeriod
        return dataProvider.getBoluses(from - carbAction.maxAge).map { cs ->
             insulinAction.valuesAt(
                cs,
                { c -> c.timestamp to c.value },
                from ..< to step config.freq).map(Double::toFloat)
        }
    }

    private fun slope(values: List<Float>): List<Float> {
        val minutes = 2 * config.freq.toMinutes()
        return List(values.size) { i ->
            when {
                i == 0 -> 0F
                i == values.size - 1 -> 0F
                else -> (values[i + 1] - values[i - 1]) / minutes
            }
        }
    }

    fun getInputVector(at: Instant): Single<FloatArray> =
        Single.zip(
            loadGlucoseReadings(), loadLongHeartRates(), loadHeartRates(),
            loadCarbAction(), loadInsulinAction()) { gl, hrl, hr, ca, ia ->

            val localTime = LocalDateTime.ofInstant(at, ZoneId.of("UTC"))
            val glSlope = slope(concat(gl, listOf(gl.last())).toList())
            val glSlop2 = slope(glSlope)

            val input = mutableListOf<Float>()
            input.add(localTime.hour.toFloat())
            input.addAll(hrl)
            input.addAll(glSlope.dropLast(1))
            input.addAll(glSlop2.dropLast(1))
            input.addAll(ia)
            input.addAll(ca)
            input.addAll(hr)

            assert (input.size == config.inputSize) {
                "Input size is ${input.size} instead of ${config.inputSize}" }
            input.toFloatArray()
        }
}