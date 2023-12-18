package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

class ModelVerifier(
    private val aapsLogger: AAPSLogger,
    private val predictor: Predictor) {

    private fun runInput(testData: Config.TestData): Boolean {
        aapsLogger.info(LTag.ML_PRED, "input ${testData.name}")
        val dataProvider = DataProviderForTestData(testData)
        val dataLoader = DataLoader(
            aapsLogger, dataProvider, testData.at, predictor.config)
        val mismatch = ArrayApproxCompare.getMismatch(
            dataLoader.getInputVector(testData.at).blockingGet().second.toList(),
            testData.inputVector.toList(), 1e-4) ?: return true
        aapsLogger.error(LTag.ML_PRED, "Mismatch '${testData.name}': $mismatch")
        return false
    }

    private fun runSlopes(testData: Config.TestData): Boolean {
        aapsLogger.info(LTag.ML_PRED, "inference ${testData.name}")
        val slopes = predictor.predictGlucoseSlopes(testData.inputVector)
        val mismatch = ArrayApproxCompare.getMismatch(
            slopes.toList(), testData.outputSlopes, eps = 0.1) ?: return true
        aapsLogger.error(LTag.ML_PRED, "Mismatch '${testData.name}': $mismatch")
        return false
    }

    private fun runGlucose(testData: Config.TestData): Boolean {
        aapsLogger.info(LTag.ML_PRED, "glucose ${testData.name}")
        val dataProvider = DataProviderForTestData(testData)
        val glucose = predictor.predictGlucose(testData.at, dataProvider)
        val mismatch = ArrayApproxCompare.getMismatch(
            glucose, testData.outputGlucose, eps = 1e-4) ?: return true
        aapsLogger.error(LTag.ML_PRED, "Mismatch '${testData.name}': $mismatch")
        return false
    }

    fun runAll(): Boolean {
        return predictor.config.testData.all { runInput(it) }
            && predictor.config.testData.all { runSlopes(it) }
            && predictor.config.testData.all { runGlucose(it) }
    }
}