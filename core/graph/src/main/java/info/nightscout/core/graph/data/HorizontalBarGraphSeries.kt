package info.nightscout.core.graph.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.BaseSeries
import java.lang.Float.max

/**
 * A graph that shows horizontal rectangles. The width of the rectangle is given by the data point's
 * duration ([DataPointWithLabelInterface.duration]) and the height by [barHeight].
 */
class HorizontalBarGraphSeries(
    val context: Context,
    data: Array<DataPointWithLabelInterface> = emptyArray()):
    BaseSeries<DataPointWithLabelInterface> (data) {

    private var barHeight = 8

    override fun draw(graphView: GraphView, canvas: Canvas, isSecondScale: Boolean) {
        resetDataPoints()
        val paint = Paint()
        val maxX = graphView.viewport.getMaxX(false)
        val minX = graphView.viewport.getMinX(false)
        val (minY, maxY) = if (isSecondScale) {
            graphView.secondScale.minY to graphView.secondScale.maxY
        } else {
            graphView.viewport.getMinY(false) to graphView.viewport.getMaxY(false)
        }

        val graphLeft = graphView.graphContentLeft.toFloat()
        val graphWidth = graphView.graphContentWidth.toFloat()
        val factorX = graphWidth / (maxX - minX)

        val graphTop = graphView.graphContentTop.toFloat()
        val graphHeight = graphView.graphContentHeight.toFloat()
        val factorY = graphHeight / (maxY - minY)

        for (value in getValues(minX, maxX)) {
            val x = (graphLeft + factorX * (value.x - minX)).toFloat()
            val width = max(2f, (factorX * value.duration).toFloat())
            val y = (graphTop + factorY * (value.y - minY)).toFloat()

            if (x < graphLeft || x > graphLeft + graphWidth) continue
            if (y < graphTop || y > graphTop + graphHeight) continue

            registerDataPoint(x, y, value)
            paint.color = value.color(context)
            canvas.drawRect(x, y - barHeight/2, x + width, y + barHeight/2, paint)
        }
    }
}