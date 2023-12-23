package app.aaps.plugins.main.mlPrediction

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.shared.tests.TestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictorTest: TestBase() {
    @Test
    fun loadAndVerifyModel() {
        val cl = this::class.java.classLoader!!
        Predictor.create(
            aapsLogger,
            cl.getResourceAsStream("glucose_model.json")!!,
            cl.getResourceAsStream("glucose_model.tflite")!!
        )!!.use { p ->
            assertTrue(p.isValid)
        }
    }
}