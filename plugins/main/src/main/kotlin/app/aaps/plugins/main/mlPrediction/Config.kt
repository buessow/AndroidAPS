package app.aaps.plugins.main.mlPrediction

import app.aaps.plugins.main.mlPrediction.DataLoader.Companion.div
import java.time.Duration

data class Config(
    val trainingPeriod: Duration = Duration.ofHours(2),
    val predictionPeriod: Duration = Duration.ofHours(1),
    val hrLong: List<Duration> = listOf(Duration.ofHours(24), Duration.ofHours(48)),
    val hrLongThreshold: Int = 120,
    val freq: Duration = Duration.ofMinutes(5),
) {
    val inputSize get() =
        // hour of day and long heart rates
        1 + hrLong.size +
        // glucose slope and slope of slope
        2 * (trainingPeriod / freq) +
        // carb, insulin and heart rate
        3 * ((trainingPeriod + predictionPeriod) / freq)

    val outputSize get() = predictionPeriod / freq  // glucose slope prediction
}