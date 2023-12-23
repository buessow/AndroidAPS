package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.main.R
import dagger.android.HasAndroidInjector
import javax.inject.Inject

class MlPredictionPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val repo: AppRepository,
    rh: ResourceHelper,
    injector: HasAndroidInjector
    // private val modelPath: File =  File("/storage/emulated/0/Download/glucose_model.tflite")
): PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.ml_prediction)
        .shortName(R.string.ml_prediction_short),
    aapsLogger, rh, injector) {

    private var predictor: Predictor?


    init {
        predictor = Predictor.create(aapsLogger)
    }

    // private fun initializeInterpreter() {
    //     interpreter = Interpreter(modelBytes)
    //     assert(ModelVerifier(aapsLogger, config, interpreter).runAll())
    // }
    //
    //
    // fun predictGlucoseSlopes(at: Instant): FloatArray {
    //     val dataLoader = DataLoader(aapsLogger, DataProviderLocal(repo), at, config)
    //     val input = dataLoader.getInputVector(at).blockingGet()
    //     return predictGlucoseSlopes(input)
    // }
    //
    // @VisibleForTesting
    // fun predictGlucoseSlopes(inputData: FloatArray): FloatArray {
    //     aapsLogger.info(LTag.ML_PRED, "input: ${inputData.joinToString { "%.2f".format(it) }}")
    //     val outputData = Array(1) { FloatArray(config.outputSize) }
    //     interpreter.inputTensorCount
    //     interpreter.run(Array (1) {inputData }, outputData)
    //     return outputData[0]
    // }
}