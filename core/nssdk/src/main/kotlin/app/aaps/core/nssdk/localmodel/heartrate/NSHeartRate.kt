package app.aaps.core.nssdk.localmodel.heartrate

import com.google.gson.annotations.SerializedName

data class NSHeartRate(
    @SerializedName("created_at")
    val createdAt: String,
    val device: String,
    val identifier: String?,
    val srvModified: Long? = null,
    val srvCreated: Long? = null,
    val utcOffset: Long,
    val isValid: Boolean,
    val date: Long,
    val duration: Long,
    val beatsPerMinute: Double,
)
