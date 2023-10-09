package info.nightscout.sdk.mapper

import app.aaps.core.nssdk.mapper.toNSHeartRate
import app.aaps.core.nssdk.mapper.toRemoteHeartRate
import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate
import app.aaps.core.nssdk.remotemodel.RemoteHeartRate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HeartRateMapperTest {

    @Test
    fun jsonToNSHeartRate() {
        val nsHr = """{
            "created_at": "1970-01-01T00:00:01.111Z",
            "device": "foo",
            "identifier": "id",
            "utcOffset": 2,
            "isValid": true,
            "date": 1112,
            "duration": 60000,
            "beatsPerMinute": 111.2 }""".toNSHeartRate()
        assertEquals(
            NSHeartRate(
                createdAt = "1970-01-01T00:00:01.111Z",
                device = "foo",
                identifier = "id",
                utcOffset = 2L,
                isValid = true,
                date = 1112L,
                duration = 60_000L,
                beatsPerMinute = 111.2),
            nsHr)
    }

    @Test
    fun toHeartRate() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01.111Z",
            device = "foo",
            identifier = "id",
            utcOffset = 2L,
            isValid = true,
            date = 1112L,
            duration = 60_000L,
            beatsPerMinute = 111.2)
        val remoteHr = RemoteHeartRate(
            createdAt = "1970-01-01T00:00:01.111Z",
            device = "foo",
            identifier = "id",
            utcOffset = 2L,
            isValid = true,
            date = 1112L,
            duration = 60_000L,
            beatsPerMinute = 111.2)
        assertEquals(nsHr, remoteHr.toNSHeartRate())
        assertEquals(remoteHr, nsHr.toRemoteHeartRate())
    }
}