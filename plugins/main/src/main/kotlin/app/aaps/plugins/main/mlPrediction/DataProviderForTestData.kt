package app.aaps.plugins.main.mlPrediction

import io.reactivex.rxjava3.core.Single
import java.time.Duration
import java.time.Instant

class DataProviderForTestData(private val testData: Config.TestData): DataProvider {
    private val input = testData.inputData

    private fun toDateValue(m: Int, v: Double) =
        DateValue(testData.at + Duration.ofMinutes(m.toLong()), v)
    override fun getGlucoseReadings(from: Instant) = Single.just(
        input.minute.zip(input.glucose).map { (m, v) -> toDateValue(m, v) })

    override fun getHeartRates(from: Instant) = Single.just(
        input.minute.zip(testData.heartRates).map { (m, v) -> toDateValue(m, v) })

    override fun getLongHeartRates(at: Instant, threshold: Int, durations: List<Duration>) =
        Single.just(testData.hrLongCounts)

    override fun getCarbs(from: Instant) = Single.just(testData.carbEvents)

    override fun getBoluses(from: Instant) = Single.just(testData.insulinEvents)
}