package app.aaps.plugins.main.mlPrediction

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class MlProfileSwitchesTest {

    @Test
    fun toBasal_single() {
        val bps = MlProfileSwitch(
            start = Instant.parse("2013-12-13T10:00:00Z"),
            basalRates = listOf(Duration.ofHours(24) to 1.1))
        val bpss = MlProfileSwitches(bps, bps, emptyList())

        val start = OffsetDateTime.parse("2013-12-13T10:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T22:00:00Z")

        // zero duration
        val basalRates0 = bpss.toBasal(start, start).toList()
        DataLoaderTest.assertCollectionEquals(basalRates0)

        val basalRates1 = bpss.toBasal(start, end).toList()
        DataLoaderTest.assertCollectionEquals(basalRates1, DateValue(start.toInstant(), 1.1))
    }

    @Test
    fun toBasal_singleTemporary() {
        val bps = MlProfileSwitch(
            start = Instant.parse("2013-12-13T10:00:00Z"),
            basalRates = listOf(Duration.ofHours(24) to 1.1))
        val bpst = MlProfileSwitch(
            start = Instant.parse("2013-12-13T11:00:00Z"),
            basalRates = listOf(Duration.ofHours(24) to 2.1),
            duration = Duration.ofHours(2))
        val bpss = MlProfileSwitches(bps, bpst, emptyList())

        val start = OffsetDateTime.parse("2013-12-13T12:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T22:00:00Z")

        val basalRates1 = bpss.toBasal(start, end).toList()
        DataLoaderTest.assertCollectionEquals(
            basalRates1,
            DateValue(Instant.parse("2013-12-13T12:00:00Z"), 2.1),
            DateValue(Instant.parse("2013-12-13T14:00:00Z"), 1.1))
    }

    private fun makeProfile(start: Instant, rate: Double, duration: Duration? = null) =
        MlProfileSwitch(
            start = start,
            basalRates = (0 .. 23).map { Duration.ofHours(1) to rate + it / 100.0 },
            duration = duration)

    @Test
    fun toBasal_singleTemporary2() {
        val bps = makeProfile(Instant.parse("2013-12-13T10:00:00Z"), 1.0)
        val bpst = makeProfile(
            Instant.parse("2013-12-13T13:00:00Z"), 2.0, Duration.ofHours(2))
        val bpss = MlProfileSwitches(bps, bps, listOf(bpst))

        val start = OffsetDateTime.parse("2013-12-13T12:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T17:30:00Z")

        val basalRates1 = bpss.toBasal(start, end).toList()
        DataLoaderTest.assertCollectionEquals(
            basalRates1,
            DateValue(Instant.parse("2013-12-13T12:00:00Z"), 1.12),
            DateValue(Instant.parse("2013-12-13T13:00:00Z"), 2.13),
            DateValue(Instant.parse("2013-12-13T14:00:00Z"), 2.14),
            DateValue(Instant.parse("2013-12-13T15:00:00Z"), 1.15),
            DateValue(Instant.parse("2013-12-13T16:00:00Z"), 1.16),
            DateValue(Instant.parse("2013-12-13T17:00:00Z"), 1.17))
    }

    @Test
    fun toBasal_singleTemporaryInterruptedByTemporary() {
        val bps = makeProfile(Instant.parse("2013-12-13T10:00:00Z"), 1.0)
        val bpsNext = listOf(
            makeProfile(
                Instant.parse("2013-12-13T13:00:00Z"), 2.0, Duration.ofHours(2)),
            makeProfile(
                Instant.parse("2013-12-13T14:00:00Z"), 3.0, Duration.ofHours(2)))
        val bpss = MlProfileSwitches(bps, bps, bpsNext)

        val start = OffsetDateTime.parse("2013-12-13T12:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T17:30:00Z")
        val basalRates1 = bpss.toBasal(start, end).toList()
        DataLoaderTest.assertCollectionEquals(
            basalRates1,
            DateValue(Instant.parse("2013-12-13T12:00:00Z"), 1.12),
            DateValue(Instant.parse("2013-12-13T13:00:00Z"), 2.13),
            DateValue(Instant.parse("2013-12-13T14:00:00Z"), 3.14),
            DateValue(Instant.parse("2013-12-13T15:00:00Z"), 3.15),
            DateValue(Instant.parse("2013-12-13T16:00:00Z"), 1.16),
            DateValue(Instant.parse("2013-12-13T17:00:00Z"), 1.17))
    }

    @Test
    fun toBasal_singleTemporaryInterruptedByPermanent() {
        val bps = makeProfile(Instant.parse("2013-12-13T10:00:00Z"), 1.0)
        val bpsNext = listOf(
            makeProfile(Instant.parse("2013-12-13T13:00:00Z"), 2.0, Duration.ofHours(2)),
            makeProfile(Instant.parse("2013-12-13T14:00:00Z"), 3.0),
            makeProfile(Instant.parse("2013-12-13T16:00:00Z"), 4.0, Duration.ofHours(1)),
            makeProfile(Instant.parse("2013-12-13T16:30:00Z"), 5.0, Duration.ofHours(1)),
            makeProfile(Instant.parse("2013-12-13T18:00:00Z"), 6.0),
        )
        val bpss = MlProfileSwitches(bps, bps, bpsNext)

        val start = OffsetDateTime.parse("2013-12-13T12:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T20:00:00Z")
        DataLoaderTest.assertCollectionEquals(
            bpss.toBasal(start, end).toList(),
            DateValue(Instant.parse("2013-12-13T12:00:00Z"), 1.12),
            DateValue(Instant.parse("2013-12-13T13:00:00Z"), 2.13),
            DateValue(Instant.parse("2013-12-13T14:00:00Z"), 3.14),
            DateValue(Instant.parse("2013-12-13T15:00:00Z"), 3.15),
            DateValue(Instant.parse("2013-12-13T16:00:00Z"), 4.16),
            DateValue(Instant.parse("2013-12-13T16:30:00Z"), 5.16),
            DateValue(Instant.parse("2013-12-13T17:00:00Z"), 5.17),
            DateValue(Instant.parse("2013-12-13T17:30:00Z"), 3.17),
            DateValue(Instant.parse("2013-12-13T18:00:00Z"), 6.18),
            DateValue(Instant.parse("2013-12-13T19:00:00Z"), 6.19))
    }


}