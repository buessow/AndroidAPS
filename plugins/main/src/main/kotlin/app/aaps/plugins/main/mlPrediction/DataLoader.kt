package app.aaps.plugins.main.mlPrediction

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.collect.FluentIterable.concat
import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit

class DataLoader(
    private val aapsLogger: AAPSLogger,
    private val dataProvider: DataProvider,
    time: Instant,
    private val config: Config,
    private val tz: ZoneOffset = UTC) {

    private val inputFrom: Instant = time.truncatedTo(ChronoUnit.MINUTES)
    private val inputUpTo = inputFrom + config.trainingPeriod + config.predictionPeriod

    private val carbAction = LogNormAction(Duration.ofMinutes(45), sigma=0.5)
    private val insulinAction = LogNormAction(Duration.ofMinutes(60), sigma=0.5)

    companion object {
        @VisibleForTesting
        fun applyTemporaryBasals(
            basals: List<DateValue>,
            tempBasals: List<MlTemporaryBasalRate>,
            to: Instant
        ): List<DateValue> {
            val result = mutableListOf<DateValue>()

            val itTemp = MlTemporaryBasalRate.adjustDuration(tempBasals).iterator()
            var temp = itTemp.nextOrNull()
            for (i in basals.indices) {
                val dv = basals[i]
                val nextStart = basals.elementAtOrNull(i + 1)?.timestamp ?: to
                // Search for first temporary basal that can have an impact on this
                // basal.
                while (temp != null && temp.end < dv.timestamp) {
                    temp = itTemp.nextOrNull()
                }
                // No temporary basal already active when this basal starts, so keep
                // basal at start time.
                if (temp == null || temp.timestamp > dv.timestamp) {
                    // Temporary adjustment starts later.
                    result.add(dv)
                }
                // Iterate over all temporary basal that start before this basal
                // ends (i.e. the next starts).
                while (temp != null && temp.timestamp < nextStart) {
                    result.add(DateValue(
                        // Temporary basal might have started earlier than this basal,
                        // don't move start time.
                        temp.timestamp.coerceAtLeast(dv.timestamp),
                        // Apply temporary adjustment.
                        (temp.basal ?: dv.value) * temp.rate))

                    // Temporary basal until the end of this basal: we are done.
                    if (temp.end >= nextStart) break

                    // We need to revert to normal basal until end of basal or next
                    // temporary basal unless their is no gap between this and next
                    // temporary basal.
                    val tempEnd = temp.end
                    temp = itTemp.nextOrNull()
                    if (temp == null || tempEnd < temp.timestamp) {
                        result.add(dv.copy(timestamp = tempEnd))
                    }
                }
            }
            return result
        }
    }

    @VisibleForTesting
    fun align(
        from: Instant,
        values: Iterable<DateValue>,
        to: Instant = from + config.trainingPeriod,
        interval: Duration = config.freq) = sequence {

        var t = from
        var last: DateValue? = null
        for (curr in values) {
            // Skip over values before the start but remember the last value
            // for averaging.
            if (curr.timestamp < from) {
                last = curr
                continue
            }
            while (t < curr.timestamp && t < to) {
                if (last == null) {
                    yield(Float.NaN)
                } else {
                    val d1 = Duration.between(last.timestamp, t).seconds
                    val d2 = Duration.between(t, curr.timestamp).seconds
                    // We weigh the value that is close to t higher.
                    val avg = (curr.value * d1 + last.value * d2) / (d1 + d2)
                    yield(avg.toFloat())
                }

                t += interval
            }
            last = curr
        }

        // Output the last value if we are missing values at the end.
        while (t < to) {
            yield(last?.value?.toFloat() ?: Float.NaN)
            t += interval
        }
    }

    fun loadGlucoseReadings(): Single<List<Float>> {
        val loadFrom: Instant = inputFrom - Duration.ofMinutes(6)
        return dataProvider.getGlucoseReadings(
            (loadFrom)).map { gs -> align(inputFrom, gs).toList() }
    }

    fun loadHeartRates(): Single<List<Float>> {
        val loadFrom: Instant = inputFrom - Duration.ofMinutes(6)
        return dataProvider.getHeartRates(loadFrom).map { hrs ->
            val futureHeartRates = FloatArray(config.predictionPeriod / config.freq) { 60F }
            concat(
                align(inputFrom, hrs).asIterable(),
                futureHeartRates.asIterable()).toList()
        }
    }

    /** Gets a duration of fractions of an hour. */
    private fun toHours(d: Duration) = d.seconds / 3600.0

    /** Adjusts basal rates to insulin injection in [Config.freq] frequency. Note
     * that the pump likely injects insulin more often but higher accuracy
     * unlikely matters for the model.*/
    private fun adjustRates(basals: List<DateValue>): List<DateValue> {
        val result = mutableListOf<DateValue>()
        var ts = basals.firstOrNull()?.timestamp ?: return result
        // In case a basal rate ends in the middle of an interval, we need to add
        // up values from adjacent basal rates. We keep the time a rate reaches
        // into the next interval and the remaining insulin and add it to the
        // next output in [restTs] and [restInsulin].
        var restTs = Duration.ZERO
        var restInsulin = 0.0
        for (i in basals.indices) {
            val nextStart = basals.elementAtOrNull(i + 1)?.timestamp ?: inputUpTo
            val basalRate = basals[i].value
            while (ts + config.freq <= nextStart) {
                result.add(DateValue(
                    ts, restInsulin + (toHours(config.freq - restTs) * basalRate)))
                ts += config.freq
                restTs = Duration.ZERO
                restInsulin = 0.0
            }
            restTs = Duration.between(ts, nextStart)
            restInsulin = toHours(restTs) * basalRate
        }
        if (restInsulin > 0.0) {
            result.add(DateValue(ts, restInsulin))
        }
        return result
    }

    fun loadBasalRates(): Single<List<DateValue>> {
        val default = listOf(DateValue(inputFrom, 0.0))
        val basalsM = dataProvider.getBasalProfileSwitches(inputFrom).map { bpss ->
            bpss.toBasal(inputFrom.atOffset(tz), inputUpTo.atOffset(tz))
                .toList()
                .takeUnless(List<DateValue>::isEmpty) ?: default
        }
        val basals = basalsM.defaultIfEmpty(default)
        val tempBasals = dataProvider.getTemporaryBasalRates(inputFrom)
        return Single.zip(basals, tempBasals) { b, t ->
            adjustRates(applyTemporaryBasals(b, t, inputUpTo)) }
    }

    private val intervals = inputFrom..<inputUpTo step config.freq

    fun loadBasalActions(): Single<List<Float>> =
        loadBasalRates().map { basals ->
            insulinAction.valuesAt(basals, intervals).map(Double::toFloat) }

    fun loadLongHeartRates(): Single<List<Float>> {
        return dataProvider
            .getLongHeartRates(
                inputFrom + config.trainingPeriod,
                config.hrHighThreshold,
                config.hrLong)
            .map { counts -> counts.map(Int::toFloat).toList() }
    }

    fun loadCarbAction(): Single<List<Float>> {
        return dataProvider.getCarbs(inputFrom - carbAction.maxAge).map { cs ->
            carbAction.valuesAt(cs, intervals).map(Double::toFloat)
        }
    }

    fun loadInsulinAction(): Single<List<Float>> {
        return dataProvider.getBoluses(inputFrom - carbAction.maxAge).map { cs ->
             insulinAction.valuesAt(cs, intervals).map(Double::toFloat)
        }
    }

    private fun slope(values: List<Float>): List<Float> {
        val minutes = 2F * config.freq.toMinutes()
        return List(values.size) { i ->
            when (i) {
                0               -> 0F
                values.size - 1 -> 0F
                else            -> (values[i + 1] - values[i - 1]) / minutes
            }
        }
    }

    fun getInputVector(at: Instant): Single<Pair<Float, FloatArray>> =
        Single.zip(
            loadGlucoseReadings(), loadLongHeartRates(), loadHeartRates(),
            loadCarbAction(), loadInsulinAction()) { gl, hrl, hr, ca, ia ->

            val localTime = OffsetDateTime.ofInstant(at, ZoneId.of("UTC"))
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
            gl.last() to input.toFloatArray()
        }
}