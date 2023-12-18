package app.aaps.plugins.main.mlPrediction

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LogNormActionTest {

    @Test
    fun create() {
        val lna = LogNormAction(Duration.ofHours(3))
        val t = Instant.parse("2013-12-13T20:00:00Z")
        assertEquals(listOf(0.0), lna.valuesAt(listOf(t to 5.0), { dv -> dv }, listOf(t)))

        val r = lna.valuesAt(listOf(t to 5.0), { dv -> dv }, listOf(t + Duration.ofMinutes(30)))
        assertEquals(1, r.size)
        assertEquals(0.08099, r[0], 1e-4)
    }

    @Test
    fun valuesAt() {
        val lna = LogNormAction(Duration.ofHours(3))
        val t = Instant.parse("2013-12-13T20:00:00Z")

        val r = lna.valuesAt(
            listOf(t to 5.0), { dv -> dv },
            listOf(30L, 120L, 180L, 230L).map { m -> t + Duration.ofMinutes(m) }.toList())

        val e = doubleArrayOf(
            0.08099936958648521, 0.3714600764604711, 0.4032845408652389, 0.39134904494144485)
        assertArrayEquals(e, r.toDoubleArray(), 1e-6)
    }
}