package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.IDs
import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun HR.toNSHeartRate() = NSHeartRate(
    createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(dateCreated)),
    device = device,
    identifier = ids.nightscoutId,
    utcOffset = utcOffset / 60_000,
    isValid = isValid,
    date = timestamp,
    duration = duration,
    beatsPerMinute = beatsPerMinute)

internal fun NSHeartRate.toHeartRate() = HR(
    dateCreated = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(createdAt)).toEpochMilli(),
    device = device,
    ids = IDs(nightscoutId = identifier),
    utcOffset = utcOffset * 60_000,
    isValid = isValid,
    timestamp = date,
    duration = duration,
    beatsPerMinute = beatsPerMinute)