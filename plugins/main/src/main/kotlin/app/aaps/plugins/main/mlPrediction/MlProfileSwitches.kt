package app.aaps.plugins.main.mlPrediction

import java.time.OffsetDateTime
import java.time.ZoneOffset

data class MlProfileSwitches(
    val firstPermanent: MlProfileSwitch,
    val first: MlProfileSwitch,
    val switches: List<MlProfileSwitch>) {

    fun toBasal(
        from: OffsetDateTime,
        to: OffsetDateTime) = sequence {

        var current: MlProfileSwitch? = first
        var currentPermanent = firstPermanent
        var t = from
        val iter = switches.iterator()
        while (current != null) {
            val next = if (iter.hasNext()) iter.next() else null
            val nextStart = next?.start?.atOffset(ZoneOffset.UTC) ?: to
            if (current.duration != null) {
                val end = (t + current.duration).coerceAtMost(nextStart)
                yieldAll(current.toBasal(t, end))
                t = end
            } else {
                currentPermanent = current
            }
            yieldAll(currentPermanent.toBasal(t, nextStart))
            t = nextStart
            current = next
        }
    }
}