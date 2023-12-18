package app.aaps.plugins.main.mlPrediction

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.HeartRate
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.main.mlPrediction.DataLoader.Companion.rangeUntil
import app.aaps.shared.tests.TestBase
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.core.Single
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MlPredictionPluginTest: TestBase() {

    private val repo: AppRepository = org.mockito.Mockito.mock(AppRepository::class.java)
    private val rh: ResourceHelper = mock()
    private val injector: HasAndroidInjector = mock()
    private lateinit var plugin: MlPredictionPlugin

    private val config = Config(
        hrLong = listOf(Duration.ofHours(2), Duration.ofHours(3)))

    @Before
    fun setup() {
        val modelStream = this::class.java.classLoader!!
            .getResourceAsStream("glucose_model.tflite")!!
        val modelByteArray = modelStream.readAllBytes()
        val modelBytes = ByteBuffer.allocateDirect(modelByteArray.size)
        modelBytes.put(modelByteArray)
        aapsLogger.info(LTag.ML_PRED, "model: ${modelBytes.limit()}")
        plugin = MlPredictionPlugin(aapsLogger, repo, rh, injector, modelBytes)
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

    @Test
    fun predictSlope_zero() {
        assertArrayEquals(
            plugin.predictGlucoseSlopes(FloatArray(config.inputSize)),
            -0.0054453127F, -0.065370426F, -0.042105604F, -0.062820815F,
            -0.10666047F, -0.11986208F, 0.0045763664F, 0.17245716F, 0.36861256F,
            0.46455392F, 0.4717102F, 0.36503342F,
            eps = 1e-4)
    }

    @Test
    fun predict_basal() {
        val now = Instant.parse("2013-12-17T12:00:00Z")
        val trainStart = now.minus(config.trainingPeriod)
        val predEnd = now.plus(config.predictionPeriod)
        val glucoseValues = (trainStart..<now step config.freq).mapIndexed { i, t ->
            createGlucoseValue(t, 100.0 + i) }
        val hrLongStart = (now - config.hrLong.max())
        val heartRates = (hrLongStart..<predEnd step config.freq).map { t ->
            createHeartRate(t, 60.0) }
        val boluses = listOf(createBolus(
            now - Duration.ofMinutes(60), 10.0))

        whenever(repo.compatGetBgReadingsDataFromTime(any(), any()))
            .thenReturn(Single.just(glucoseValues))
        whenever(repo.getHeartRatesFromTime(any()))
            .thenReturn(Single.just(heartRates))
        whenever(repo.getBolusesDataFromTime(any(), any()))
            .thenReturn(Single.just(boluses))
        whenever(repo.getCarbsDataFromTime(any(), any()))
            .thenReturn(Single.just(emptyList()))
        val expectedInput = listOf(
            12.0000F, 0.0000F, 0.0000F, 0.0000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.2000F, 0.1000F, 0.0000F, 0.0200F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, -0.0100F, -0.0200F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0115F, 0.1508F, 0.6300F, 1.5203F, 2.6936F, 3.9383F, 5.0682F, 5.9672F, 6.5884F, 6.9355F, 7.0413F, 6.9517F, 6.7145F, 6.3739F, 5.9672F, 5.5243F, 5.0682F, 4.6157F, 4.1783F, 3.7639F, 3.3770F, 3.0200F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 0.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F, 60.0000F)
        val dataLoader = DataLoader(aapsLogger, DataProviderLocal(repo), now, config)

        assertArrayEquals(
            dataLoader.getInputVector(now).blockingGet(),
            *expectedInput.toTypedArray(),
            eps = 1e-4)


        val slopes = plugin.predictGlucoseSlopes(now)
        assertArrayEquals(
            slopes,
            0.045945156F, -0.4719823F, -0.6237778F, -0.5008936F, -0.47144464F, -0.51466185F, -0.40224606F, -0.21664491F, 0.09492423F, 0.43050218F, 0.54957306F, 0.5082869F,
            eps = 1e-4)
        //0.045945245772600174, -0.4719822108745575, -0.6237777471542358, -0.5008935332298279, -0.47144442796707153, -0.5146616697311401, -0.40224599838256836, -0.21664488315582275, 0.09492436796426773, 0.4305022358894348, 0.5495730638504028, 0.5082868933677673]
    }

    private fun firstMismatch(expected: Array<out Number>, actual: List<Number>, e: Double): Int? {
        if (expected.size != actual.size) return expected.size.coerceAtMost(actual.size)
        for (i in expected.indices) {
            if (abs(expected[i].toDouble() - actual[i].toDouble()) > e) return i
        }
        return null
    }
    private fun assertArrayEquals(actual: FloatArray, vararg expected: Number, eps: Double) {
      assertCollectionEquals(actual.toList(), *expected, eps = eps)
    }

    private fun assertCollectionEquals(actual: Collection<Number>, vararg expected: Number, eps: Double) {
        val mismatch = firstMismatch(expected, actual.toList(), eps) ?: return
        val a = actual.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
        val e = expected.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
        throw AssertionError("expected [$e] but was [$a]")
    }
}