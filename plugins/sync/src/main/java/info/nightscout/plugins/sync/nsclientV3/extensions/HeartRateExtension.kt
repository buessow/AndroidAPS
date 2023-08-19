package info.nightscout.plugins.sync.nsclientV3.extensions

import info.nightscout.database.entities.HeartRate
import info.nightscout.database.entities.embedments.InterfaceIDs
import info.nightscout.sdk.localmodel.heartrate.NSHeartRate
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun HeartRate.toNSHeartRate() = NSHeartRate(
    createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(dateCreated)),
    device = device,
    identifier = interfaceIDs.nightscoutId,
    utcOffset = utcOffset / 60_000,
    isValid = isValid,
    date = timestamp,
    duration = duration,
    beatsPerMinute = beatsPerMinute)

internal fun NSHeartRate.toHeartRate() = HeartRate(
    dateCreated = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(createdAt)).toEpochMilli(),
    device = device,
    interfaceIDs_backing = InterfaceIDs(nightscoutId = identifier),
    utcOffset = utcOffset * 60_000,
    isValid = isValid,
    timestamp = date,
    duration = duration,
    beatsPerMinute = beatsPerMinute)