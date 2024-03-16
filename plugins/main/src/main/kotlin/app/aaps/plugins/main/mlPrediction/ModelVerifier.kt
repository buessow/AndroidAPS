package app.aaps.plugins.main.mlPrediction

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import cc.buessow.glumagic.input.Config
import cc.buessow.glumagic.input.DataLoader
import cc.buessow.glumagic.input.InputProviderForTestInput

class ModelVerifier(
    private val aapsLogger: AAPSLogger,
    private val predictor: Predictor) {

    private fun runInput(testData: Config.TestData): Boolean {
        aapsLogger.info(LTag.ML_PRED, "input ${testData.name}")
        val dataProvider = InputProviderForTestInput(testData)
        val mismatch = ArrayApproxCompare.getMismatch(
            DataLoader.getInputVector(dataProvider, testData.at, predictor.config).second.toList(),
            testData.inputVector.toList(), 1e-4) ?: return true
        aapsLogger.error(LTag.ML_PRED, "Mismatch '${testData.name}': $mismatch")
        return false
    }

    private fun runGlucose(testData: Config.TestData): Boolean {
        aapsLogger.info(LTag.ML_PRED, "glucose ${testData.name}")
        val dataProvider = InputProviderForTestInput(testData)
        val glucose = predictor.predictGlucose(
            testData.at + predictor.config.trainingPeriod, dataProvider)
        val mismatch = ArrayApproxCompare.getMismatch(
            glucose.map(Double::toFloat),
            testData.outputGlucose, eps = 1e-4) ?: return true
        aapsLogger.error(LTag.ML_PRED, "Mismatch '${testData.name}': $mismatch")
        return false
    }

    fun runAll(): Boolean {
        return predictor.config.testData.all { runInput(it) }
            && predictor.config.testData.all { runGlucose(it) }
    }
}