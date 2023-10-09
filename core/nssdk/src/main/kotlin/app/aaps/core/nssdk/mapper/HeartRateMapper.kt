package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.remotemodel.RemoteHeartRate
import com.google.gson.Gson
import app.aaps.core.nssdk.localmodel.heartrate.NSHeartRate

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