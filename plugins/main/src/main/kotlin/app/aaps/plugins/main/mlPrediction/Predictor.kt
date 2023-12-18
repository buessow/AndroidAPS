package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.Instant

class Predictor(
    private val aapsLogger: AAPSLogger,
    private val modelMetaInput: InputStream,
    private val modelBytesInput: InputStream) {
    constructor(
        aapsLogger: AAPSLogger,
        modelPath: File =  File("/storage/emulated/0/Download"),
        modelName: String = "glucose_model"): this(
            aapsLogger,
            modelMetaInput = File(modelPath, "$modelName.json").inputStream(),
            modelBytesInput = File(modelPath, "$modelName.tflite").inputStream())

    val config: Config
    private val interpreter: Interpreter
    val isValid: Boolean

    init {
        modelBytesInput.use {
            modelMetaInput.use {
                val modelMetaJson = String(modelMetaInput.readBytes())
                config = Config.fromJson(modelMetaJson)
            }
            val modelBytes = modelBytesInput.readBytes()
            val modelByteBuf = ByteBuffer.allocateDirect(modelBytes.size).apply {
                put(modelBytes)
                rewind()
            }
            interpreter = Interpreter(modelByteBuf)
        }
        isValid = ModelVerifier(aapsLogger, this).runAll()
    }

    fun predictGlucoseSlopes(inputData: FloatArray): List<Double> {
        aapsLogger.debug(LTag.ML_PRED, "input: ${inputData.joinToString { "%.2f".format(it) }}")
        val outputData = Array(1) { FloatArray(config.outputSize) }
        interpreter.run(Array (1) {inputData }, outputData)
        return outputData[0].map(Float::toDouble).toList()
    }

    fun predictGlucoseSlopes(at: Instant, dp: DataProvider): List<Double> {
        val dataLoader = DataLoader(aapsLogger, dp, at - config.trainingPeriod, config)
        val (_, input) = dataLoader.getInputVector(at).blockingGet()
        return predictGlucoseSlopes(input)
    }
    private fun computeGlucose(lastGlucose: Double, slopes: List<Double>): List<Double> {
        var p = lastGlucose
        return slopes.map { s -> (5 * s + p).also { p = it } }
    }

    fun predictGlucose(at: Instant, dp: DataProvider): List<Double> {
        val dataLoader = DataLoader(aapsLogger, dp, at - config.trainingPeriod, config)
        val (lastGlucose, input) = dataLoader.getInputVector(at).blockingGet()
        return computeGlucose(lastGlucose.toDouble(), predictGlucoseSlopes(input))
    }

}