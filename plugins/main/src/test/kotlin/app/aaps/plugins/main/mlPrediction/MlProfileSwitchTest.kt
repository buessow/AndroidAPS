package app.aaps.plugins.main.mlPrediction

import app.aaps.plugins.main.mlPrediction.DataLoaderTest.Companion.assertCollectionEquals
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class MlProfileSwitchTest {

    private val from = Instant.now()

    @Test
    fun profileToBasal_empty() {
        val start = OffsetDateTime.parse("2013-12-13T10:00:00Z")
        val end = OffsetDateTime.parse("2013-12-14T10:00:00Z")
        assertThrows<IllegalArgumentException> {
            MlProfileSwitch(from, emptyList()).toBasal(start, end).toList() }
    }

    @Test
    fun profileToBasal_single() {
        val basals = listOf(Duration.ofHours(24) to 1.1)

        val start = OffsetDateTime.parse("2013-12-13T10:00:00Z")
        val end = OffsetDateTime.parse("2013-12-13T22:00:00Z")

        // zero duration
        val basalRates0 = MlProfileSwitch(from, basals).toBasal(start, start).toList()
        assertCollectionEquals(basalRates0)

        val basalRates1 = MlProfileSwitch(from, basals).toBasal(start, end).toList()
        assertCollectionEquals(basalRates1, DateValue(start.toInstant(), 1.1))

        val startTz = OffsetDateTime.parse("2013-12-13T10:00:00+01:00")
        val basalRatesTz = MlProfileSwitch(from, basals).toBasal(startTz, end).toList()
        assertCollectionEquals(
            basalRatesTz,
            DateValue(Instant.parse("2013-12-13T09:00:00Z"), 1.1))

        // profileToBasal will output at lest one basal rate per day
        val start2 = OffsetDateTime.parse("2013-12-12T10:00:00Z")
        val basalRates2 = MlProfileSwitch(from, basals).toBasal(start2, end).toList()
        assertCollectionEquals(
            basalRates2,
            DateValue(Instant.parse("2013-12-12T10:00:00Z"), 1.1),
            DateValue(Instant.parse("2013-12-13T00:00:00Z"), 1.1))

        val basalRates3 = MlProfileSwitch(from, basals, rate = 2.0).toBasal(start, end).toList()
        assertCollectionEquals(basalRates3, DateValue(start.toInstant(), 2.2))
    }

    @Test
    fun profileToBasal_more() {
        val basals = listOf(
            Duration.ofHours(6) to 1.1,
            Duration.ofHours(16) to 1.2,
            Duration.ofHours(2) to 0.9)
        val start1 = OffsetDateTime.parse("2013-12-13T10:00:00Z")
        val end = OffsetDateTime.parse("2013-12-14T10:00:00Z")
        val basalRates1 = MlProfileSwitch(from, basals).toBasal(start1, end).toList()
        assertCollectionEquals(
            basalRates1,
            DateValue(Instant.parse("2013-12-13T10:00:00Z"), 1.2),
            DateValue(Instant.parse("2013-12-13T22:00:00Z"), 0.9),
            DateValue(Instant.parse("2013-12-14T00:00:00Z"), 1.1),
            DateValue(Instant.parse("2013-12-14T06:00:00Z"), 1.2))

        val start2 = OffsetDateTime.parse("2013-12-12T23:00:00Z")
        val basalRates2 = MlProfileSwitch(from, basals).toBasal(start2, end).toList()
        assertCollectionEquals(
            basalRates2,
            DateValue(Instant.parse("2013-12-12T23:00:00Z"), 0.9),
            DateValue(Instant.parse("2013-12-13T00:00:00Z"), 1.1),
            DateValue(Instant.parse("2013-12-13T06:00:00Z"), 1.2),
            DateValue(Instant.parse("2013-12-13T22:00:00Z"), 0.9),
            DateValue(Instant.parse("2013-12-14T00:00:00Z"), 1.1),
            DateValue(Instant.parse("2013-12-14T06:00:00Z"), 1.2))
    }

    @Test
    fun toBasal_hourly() {
        val bps = MlProfileSwitch(
            start = Instant.parse("2013-12-13T10:00:00Z"),
            basalRates = (0 ..< 24).map { Duration.ofHours(1) to 1.0 + it / 100.0 })
        assertTrue(bps.isValid)
        val start1 = OffsetDateTime.parse("2013-12-13T15:00:00Z")
        val end = OffsetDateTime.parse("2013-12-14T01:00:00Z")
        val basalRates1 = bps.toBasal(start1, end).toList()
        assertCollectionEquals(
            basalRates1,
            DateValue(Instant.parse("2013-12-13T15:00:00Z"), 1.15),
            DateValue(Instant.parse("2013-12-13T16:00:00Z"), 1.16),
            DateValue(Instant.parse("2013-12-13T17:00:00Z"), 1.17),
            DateValue(Instant.parse("2013-12-13T18:00:00Z"), 1.18),
            DateValue(Instant.parse("2013-12-13T19:00:00Z"), 1.19),
            DateValue(Instant.parse("2013-12-13T20:00:00Z"), 1.20),
            DateValue(Instant.parse("2013-12-13T21:00:00Z"), 1.21),
            DateValue(Instant.parse("2013-12-13T22:00:00Z"), 1.22),
            DateValue(Instant.parse("2013-12-13T23:00:00Z"), 1.23),
            DateValue(Instant.parse("2013-12-14T00:00:00Z"), 1.00))
    }

}