package app.aaps.plugins.main.mlPrediction

import java.time.Duration
import java.time.Instant

data class MlTemporaryBasalRate(
    val timestamp: Instant,
    val duration: Duration,
    val rate: Double,
    val basal: Double? = null) {
    val end get() = timestamp + duration

    companion object {
        /** Adjusts duration of overlapping temporary basals to not end after
         * the next starts.*/
        fun adjustDuration(tempBasals: List<MlTemporaryBasalRate>) = sequence {
            for ((temp, tempNext) in tempBasals.zipWithNext()) {
                if (temp.end <= tempNext.timestamp) {
                    // Temporary basals do not overlap.
                    yield(temp)
                } else {
                    val remaining = Duration.between(temp.timestamp, tempNext.timestamp)
                    if (remaining > Duration.ZERO) {
                        // Output temporary basal with remaining duration unless
                        // they start at the same time and hence [remaining] is zero.
                        yield(temp.copy(duration = remaining))
                    }
                }
            }
            tempBasals.lastOrNull()?.let { yield(it) }
        }
    }
}
