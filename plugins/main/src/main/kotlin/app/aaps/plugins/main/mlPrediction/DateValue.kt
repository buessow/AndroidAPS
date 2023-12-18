package app.aaps.plugins.main.mlPrediction

import java.time.Instant

data class DateValue(val timestamp: Instant, val value: Double) {
    constructor(timestamp: Long, value: Double):
        this(Instant.ofEpochMilli(timestamp), value)
}