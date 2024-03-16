package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import cc.buessow.glumagic.input.Config
import cc.buessow.glumagic.input.DataLoader
import cc.buessow.glumagic.input.InputProvider
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.Float.isNaN
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset

class Predictor private constructor(
    private val aapsLogger: AAPSLogger,
    val config: Config,
    private val interpreter: Interpreter): Closeable {

    companion object {
        fun create(
            aapsLogger: AAPSLogger,
            modelMetaInput: InputStream,
            modelBytesInput: InputStream): Predictor {

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

    fun predictGlucose(inputData: FloatArray): List<Double> {
        val cleanInput = inputData.map { f -> if (isNaN(f)) 0.0F else f }.toFloatArray()
        aapsLogger.debug("input: ${cleanInput.joinToString { "%.2f".format(it) }}")
        val outputData = Array(1) { FloatArray(config.outputSize) }
        interpreter.run(Array (1) { cleanInput }, outputData)
        return outputData[0].map(Float::toDouble).toList()
    }

    fun predictGlucose(at: Instant, dp: InputProvider): List<Double> {
        val local = at.atZone(ZoneOffset.systemDefault())
        aapsLogger.info(LTag.ML_PRED, "Predicting glucose at $local")
        val (_, input) = DataLoader.getInputVector(dp, at - config.trainingPeriod, config)
        return predictGlucose(input).also {
            aapsLogger.info(LTag.ML_PRED, "Output glucose: ${it.joinToString()}")
        }
    }

    override fun close() {
        interpreter.close()
    }
}