package app.aaps.plugins.main.mlPrediction

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.main.R
import dagger.android.HasAndroidInjector
import javax.inject.Inject

class MlPredictionPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    rh: ResourceHelper,
    // private val modelPath: File =  File("/storage/emulated/0/Download/glucose_model.tflite")
): PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.ml_prediction)
        .shortName(R.string.ml_prediction_short),
    aapsLogger, rh) {

}