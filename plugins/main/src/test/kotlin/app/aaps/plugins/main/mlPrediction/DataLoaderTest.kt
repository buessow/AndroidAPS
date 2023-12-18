package app.aaps.plugins.main.mlPrediction

import app.aaps.database.entities.Bolus
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.HeartRate
import app.aaps.database.impl.AppRepository
import app.aaps.shared.tests.TestBase
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Duration.ofMinutes
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class DataLoaderTest: TestBase() {

    @Mock private lateinit var repo: AppRepository
    private lateinit var dataLoader: DataLoader
    private val config = Config(
        trainingPeriod = ofMinutes(30),
        predictionPeriod = ofMinutes(15),
        hrLong = listOf(Duration.ofHours(1), Duration.ofHours(2))
    )

    private val clock = Clock.fixed(Instant.parse("2013-12-13T20:00:00Z"), ZoneId.of("UTC"))
    private val now = clock.instant()

    @BeforeEach
    fun setup() {
        dataLoader = DataLoader(aapsLogger, DataProviderLocal(repo), clock.instant(), config)
    }

    private fun createGlucoseValue(timestamp: Instant, value: Double) =
        GlucoseValue(
            timestamp = timestamp.toEpochMilli(), raw = 90.0, value = value,
            trendArrow = GlucoseValue.TrendArrow.FLAT, noise = null,
            sourceSensor = GlucoseValue.SourceSensor.DEXCOM_G5_XDRIP
        )

    private fun createHeartRate(timestamp: Instant, value: Double) =
        HeartRate(
            timestamp = timestamp.toEpochMilli(), beatsPerMinute = value,
            device = "T", duration = 300_000)

    private fun createCarbs(timestamp: Instant, value: Double) =
        Carbs(timestamp = timestamp.toEpochMilli(), amount = value, duration = 300_000)

    private fun createBolus(timestamp: Instant, value: Double) =
        Bolus(timestamp = timestamp.toEpochMilli(), amount = value, type = Bolus.Type.NORMAL)

    private fun <T> createValues(c: (Instant, Double) -> T, vararg values: Pair<Int, Int>) =
        values.map { (t, v) -> c(now + ofMinutes(t.toLong()), v.toDouble()) }

    @Test
    fun align_empty() {
        val values = emptyList<DateValue>()
        val aligned = dataLoader.align(now - ofMinutes(10), values)
        assertCollectionEquals(aligned.toList(), Float.NaN, Float.NaN, eps = 1e-2)
    }

    @Test
    fun align_one() {
        val values1 = listOf(DateValue(now - ofMinutes(8), 8.0))
        val aligned1 = dataLoader.align(now - ofMinutes(10), values1)
        assertCollectionEquals(aligned1.toList(), 8F, 8F, eps = 1e-2)

        val values2 = listOf(DateValue(now - ofMinutes(11), 8.0))
        val aligned2 = dataLoader.align(now - ofMinutes(10), values2)
        assertCollectionEquals(aligned2.toList(), 8F, 8F, eps = 1e-2)
    }

    @Test
    fun align_more() {
        val values = listOf(
            DateValue(now - ofMinutes(14),15.0),
            DateValue(now - ofMinutes(6),5.0))
        val aligned = dataLoader.align(now - ofMinutes(10), values)
        assertCollectionEquals(aligned.toList(), 10F, 5F, eps = 1e-2)
    }

    @Test
    fun align_more2() {
        val values = listOf(
            DateValue(now - ofMinutes(23),25.0),
            DateValue(now - ofMinutes(19),20.0),
            DateValue(now - ofMinutes(14),15.0),
            DateValue (now - ofMinutes(6),5.0))
        val aligned = dataLoader.align(now - ofMinutes(15), values)
        assertCollectionEquals(aligned.toList(), 16F, 10F, 5F, eps = 1e-2)
    }

    @Test
    fun loadGlucoseReadings_empty() {
        whenever(repo.compatGetBgReadingsDataFromTime(any(), any())).thenReturn(Single.just(emptyList()))
        val values = dataLoader.loadGlucoseReadings().blockingGet()
        assertCollectionEquals(values, *Array<Float>(6) { Float.NaN }, eps = 1e-2)
        verify(repo).compatGetBgReadingsDataFromTime(
            (now - config.trainingPeriod - ofMinutes(6)).toEpochMilli(), true)
    }

    @Test
    fun loadGlucoseReadings() {
        whenever(repo.compatGetBgReadingsDataFromTime(any(), any())).thenReturn(
            Single.just(createValues(::createGlucoseValue, -25 to 80, -20 to 120)))
        val values = dataLoader.loadGlucoseReadings().blockingGet()
        assertCollectionEquals(values, Float.NaN, 80F, 120F, 120F, 120F, 120F, eps = 1e-2)
        verify(repo).compatGetBgReadingsDataFromTime(
            (now - config.trainingPeriod - ofMinutes(6)).toEpochMilli(),
            true)
    }

    @Test
    fun loadHeartRates_empty() {
        whenever(repo.getHeartRatesFromTime(any())).thenReturn(Single.just(emptyList()))
        val values = dataLoader.loadHeartRates().blockingGet()
        assertCollectionEquals(values, *Array<Float>(6) { Float.NaN }, 60.0F, 60.0F, 60.0F, eps = 1e-2)
        verify(repo).getHeartRatesFromTime(
            (now - config.trainingPeriod - ofMinutes(6)).toEpochMilli())
    }

    @Test
    fun loadHeartRates() {
        whenever(repo.getHeartRatesFromTime(any())).thenReturn(
            Single.just(createValues(::createHeartRate, -25 to 80, -20 to 120)))
        val values = dataLoader.loadHeartRates().blockingGet()
        assertCollectionEquals(
            values, Float.NaN, 80F, 120F, 120F, 120F, 120F, 60.0F, 60.0F, 60.0F, eps = 1e-2)
        verify(repo).getHeartRatesFromTime(
            (now - config.trainingPeriod - ofMinutes(6)).toEpochMilli())
    }

    @Test
    fun loadLongHeartRates() {
        whenever(repo.getHeartRatesFromTime(any())).thenReturn(
            Single.just(createValues(::createHeartRate, -80 to 80, -75 to 130, -15 to 120, -5 to 150)))
        val values = dataLoader.loadLongHeartRates().blockingGet()
        assertCollectionEquals(values, 2F, 3F, eps = 1e-2)
        verify(repo).getHeartRatesFromTime((now - Duration.ofHours(2)).toEpochMilli())
    }

    @Test
    fun loadCarbs_empty() {
        whenever(repo.getCarbsDataFromTime(any(), any())).thenReturn(Single.just(emptyList()))
        val values = dataLoader.loadCarbAction().blockingGet()
        assertCollectionEquals(values, *Array<Float>(9) { 0F }, eps = 1e-2)
        verify(repo).getCarbsDataFromTime(
            (now - config.trainingPeriod - Duration.ofHours(4)).toEpochMilli(), true)
    }

    @Test
    fun loadCarbs() {
        whenever(repo.getCarbsDataFromTime(any(), any())).thenReturn(
            Single.just(createValues(::createCarbs, -120 to 80, -45 to 120)))
        val values = dataLoader.loadCarbAction().blockingGet()
        assertCollectionEquals(
            values,
            38.811302F, 54.82804F, 77.4348F, 98.96094F, 114.48894F, 122.4908F, 123.627625F, 119.49986F, 111.85238F, eps = 1e-2)
        verify(repo).getCarbsDataFromTime(
            (now - config.trainingPeriod - Duration.ofHours(4)).toEpochMilli(), true)
    }

    @Test
    fun loadInsulinAction() {
        whenever(repo.getBolusesDataFromTime(any(), any())).thenReturn(
            Single.just(createValues(::createBolus, -120 to 80, -45 to 120)))
        val values = dataLoader.loadInsulinAction().blockingGet()
        assertCollectionEquals(
            values, 42.355312F, 44.484722F, 51.670033F, 62.434647F, 74.27621F, 84.97886F, 93.15497F, 98.23983F, 100.26634F, eps = 1e-3)
        verify(repo).getBolusesDataFromTime(
            (now - config.trainingPeriod - Duration.ofHours(4)).toEpochMilli(), true)
    }



    private fun firstMismatch(expected: Array<out Number>, actual: List<Number>, e: Double): Int? {
        if (expected.size != actual.size) return expected.size.coerceAtMost(actual.size)
        for (i in expected.indices) {
            if (abs(expected[i].toDouble() - actual[i].toDouble()) > e) return i
        }
        return null
    }

    private fun assertCollectionEquals(actual: Collection<Number>, vararg expected: Number, eps: Double) {
        val mismatch = firstMismatch(expected, actual.toList(), eps) ?: return
        val a = actual.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
        val e = expected.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
        throw AssertionError("expected [$e] but was [$a]")
    }
}