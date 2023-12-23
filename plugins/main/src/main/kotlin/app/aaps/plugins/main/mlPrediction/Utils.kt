package app.aaps.plugins.main.mlPrediction

import java.time.Duration
import java.time.Instant

internal fun <T> Iterator<T>.nextOrNull() = if (hasNext()) next() else null

internal operator fun Duration.div(d: Duration) = (seconds / d.seconds).toInt()

internal class TimeInstantProgression(
    override val start: Instant,
    override val endExclusive: Instant,
    private val step: Duration
) : Iterable<Instant>, OpenEndRange<Instant> {
    override fun iterator(): Iterator<Instant> =
        object : Iterator<Instant> {
            private var current = start
            override fun hasNext() = current < endExclusive
            override fun next() = current.apply { current += step }
        }

    infix fun step(step: Duration) =
        TimeInstantProgression(start, endExclusive, step)
}

internal operator fun Instant.rangeUntil(other: Instant) =
    TimeInstantProgression(this, other, Duration.ofMinutes(1))