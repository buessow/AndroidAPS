package info.nightscout.sdk.mapper

import com.google.gson.Gson
import info.nightscout.sdk.localmodel.heartrate.NSHeartRate
import info.nightscout.sdk.remotemodel.RemoteHeartRate

fun String.toNSHeartRate(): NSHeartRate? =
    Gson().fromJson(this, RemoteHeartRate::class.java)?.toNSHeartRate()

fun RemoteHeartRate.toNSHeartRate() = NSHeartRate(
    createdAt = createdAt,
    device = device,
    identifier = identifier,
    srvModified = srvModified,
    srvCreated = srvCreated,
    utcOffset = utcOffset,
    isValid = isValid,
    date = date,
    duration = duration,
    beatsPerMinute = beatsPerMinute)

fun NSHeartRate.toRemoteHeartRate() = RemoteHeartRate(
    createdAt = createdAt,
    device = device,
    identifier = identifier,
    srvModified = srvModified,
    srvCreated = srvCreated,
    utcOffset = utcOffset,
    isValid = isValid,
    date = date,
    duration = duration,
    beatsPerMinute = beatsPerMinute)