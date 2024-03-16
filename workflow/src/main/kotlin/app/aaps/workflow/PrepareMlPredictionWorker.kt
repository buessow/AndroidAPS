package app.aaps.workflow

import android.content.Context
import android.graphics.Paint
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.Shape
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.main.mlPrediction.InputProviderLocal
import app.aaps.plugins.main.mlPrediction.InputProviderWithCache
import app.aaps.plugins.main.mlPrediction.Predictor
import app.aaps.core.ui.R
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class PrepareMlPredictionWorker(
    context: Context,
    params: WorkerParameters): LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var repo: PersistenceLayer
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileUtil: ProfileUtil

    data class WorkerState(
        val overviewData: OverviewData,
        var predictor: Predictor? = null,
        var dataProvider: InputProviderWithCache? = null)

    private fun createDataPoint(time: Instant, v: Double, colorRef: Int) = object : DataPointWithLabelInterface {
        override fun getX() = time.toEpochMilli().toDouble()
        override fun getY() = profileUtil.fromMgdlToUnits(v, profileUtil.units)
        override fun setY(y: Double) {}
        override val label = profileUtil.fromMgdlToStringInUnits(v)
        override val duration = 0L
        override val shape = Shape.BG
        override val size = 1F
        override val paintStyle: Paint.Style = Paint.Style.FILL
        override fun color(context: Context?): Int = rh.gac(context, colorRef)
    }

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.info(LTag.ML_PRED, "Running predictions")
        val dataKey = inputData.getLong(DataWorkerStorage.STORE_KEY, -1)
        val data = dataWorkerStorage.pickupObject(dataKey)  as WorkerState?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        if (data.predictor == null)
            data.predictor = Predictor.create(aapsLogger) ?: return Result.success()
        val p = data.predictor ?: return Result.success(). also {
                aapsLogger.error(LTag.ML_PRED, "Predictor failed to load")
            }
        if (!p.isValid) {
            aapsLogger.error(LTag.ML_PRED, "Predictor is not valid")
            return Result.success()
        }
        val dp = data.dataProvider ?: InputProviderWithCache(InputProviderLocal(repo))
        data.dataProvider = dp

        val now = Instant.now()
        data.overviewData.toTime = data.overviewData.endTime.coerceAtLeast(
            now.plus(Duration.ofMinutes(70)).toEpochMilli())
        data.overviewData.endTime = data.overviewData.endTime.coerceAtLeast(
            now.plus(Duration.ofMinutes(70)).toEpochMilli())

        val startColor = listOf(
            now - Duration.ofMinutes( 0) to R.attr.mlPredictionColor1,
            now - Duration.ofMinutes(30) to R.attr.mlPredictionColor2,
            now - Duration.ofMinutes(60) to R.attr.mlPredictionColor3)

        data.overviewData.mlPredictionGraphSeries = PointsWithLabelGraphSeries(
            startColor.map { (at, c) -> getPoints(p, dp, at, c) }.flatten().toTypedArray())

        return Result.success()
    }

    private fun getPoints(
        p: Predictor, dp: InputProviderWithCache, at: Instant, colorRef: Int):
        List<DataPointWithLabelInterface> {
        val predictedGlucose = p.predictGlucose(at, dp)
        return predictedGlucose.mapIndexed { i, glucose ->
            createDataPoint(at + p.config.freq.multipliedBy(i+0L), glucose, colorRef) }
    }
}