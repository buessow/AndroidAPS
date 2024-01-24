package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import cc.buessow.glumagic.input.Config
import cc.buessow.glumagic.input.DataLoader
import cc.buessow.glumagic.input.InputProvider
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.Float.isNaN
import java.nio.ByteBuffer
import java.time.Instant

class Predictor private constructor(
    private val aapsLogger: AAPSLogger,
    val config: Config,
    private val interpreter: Interpreter): Closeable {

    companion object {
        private val interpreterOptions = Interpreter.Options().apply {
            numThreads = 1
            useNNAPI = false
        }

        fun create(
            aapsLogger: AAPSLogger,
            modelMetaInput: InputStream,
            modelBytesInput: InputStream): Predictor? {

            val modelBytes = modelBytesInput.readBytes()
            val modelByteBuf = ByteBuffer.allocateDirect(modelBytes.size).apply {
                put(modelBytes)
                rewind()
            }
            return Predictor(
                aapsLogger, Config.fromJson(modelMetaInput), Interpreter(modelByteBuf))
        }

        fun create(
            aapsLogger: AAPSLogger,
            modelPath: File = File("/storage/emulated/0/Download"),
            modelName: String = "glucose_model"
        ): Predictor? {
            val modelMetaFile = File(modelPath, "$modelName.json")
            val modelBytesFile = File(modelPath, "$modelName.tflite")

            if (!modelMetaFile.isFile) {
                aapsLogger.error(LTag.ML_PRED, "Meta file not found: $modelMetaFile")
                return null
            }
            if (!modelBytesFile.isFile) {
                aapsLogger.error(LTag.ML_PRED, "Model file not found: $modelBytesFile")
                return null
            }
            try {
                return Predictor(
                    aapsLogger,
                    Config.fromJson(modelMetaFile),
                    Interpreter(modelBytesFile))
            } catch (e: IOException) {
                aapsLogger.error(LTag.ML_PRED, "Failed to load model: $e", e)
            }
            return null
        }
    }

    val isValid: Boolean = ModelVerifier(aapsLogger, this).runAll()

    fun predictGlucoseSlopes(inputData: FloatArray): List<Double> {
        val cleanInput = inputData.map { f -> if (isNaN(f)) 0.0F else f }.toFloatArray()
        aapsLogger.debug(LTag.ML_PRED, "input: ${cleanInput.joinToString { "%.2f".format(it) }}")
        val inputTensor = TensorBuffer.createFixedSize(
            intArrayOf(1, inputData.size), DataType.FLOAT32)
        inputTensor.loadArray(cleanInput)
        val outputData = Array(1) { FloatArray(config.outputSize) }
        // interpreter.run(Array (1) { cleanInput }, outputData)
        interpreter.run(inputTensor.buffer, outputData)
        return outputData[0].map(Float::toDouble).toList()
    }

    fun predictGlucoseSlopes(at: Instant, dp: InputProvider): List<Double> {
        val (_, input) =  DataLoader.getInputVector(dp, at - config.trainingPeriod, config)
        return predictGlucoseSlopes(input)
    }
    private fun computeGlucose(lastGlucose: Double, slopes: List<Double>): List<Double> {
        var p = lastGlucose
        return slopes.map { s -> (5 * s + p).also { p = it } }
    }

    fun predictGlucose(at: Instant, dp: InputProvider): List<Double> {
        aapsLogger.info(LTag.ML_PRED, "Predicting glucose at $at")
        val (lastGlucose, input) = DataLoader.getInputVector(dp, at, config)
        return computeGlucose(lastGlucose.toDouble(), predictGlucoseSlopes(input)).also {
            aapsLogger.info(LTag.ML_PRED, "Output glucose: ${it.joinToString()}")
        }
    }

    override fun close() {
        interpreter.close()
    }
}