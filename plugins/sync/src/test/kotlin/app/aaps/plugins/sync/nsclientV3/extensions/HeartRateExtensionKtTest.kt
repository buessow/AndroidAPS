package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate
import app.aaps.database.entities.HeartRate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeartRateExtensionKtTest {

    @Test
    fun convert() {
        val hr = HeartRate(
            dateCreated = 1002L,
            timestamp = 1002L,
            duration = 60_0000L,
            beatsPerMinute = 77.5,
            device = "T",
            isValid = true,
            utcOffset = 3_600_000L).apply { interfaceIDs.nightscoutId = "id1" }
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01.002Z",
            date = 1002L,
            duration = 60_0000L,
            beatsPerMinute = 77.5,
            device = "T",
            isValid = true,
            utcOffset = 60L,
            identifier = "id1")

        assertEquals(hr, nsHr.toHeartRate())
        assertEquals(nsHr, hr.toNSHeartRate())
    }
}