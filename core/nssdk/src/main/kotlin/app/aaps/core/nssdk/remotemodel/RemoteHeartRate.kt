package info.nightscout.sdk.remotemodel

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteHeartRate(
    @SerializedName("created_at")
    val createdAt: String,
    val device: String,
    val identifier: String?,
    val srvModified: Long? = null,
    val srvCreated: Long? = null,
    val utcOffset: Long,
    val isValid: Boolean = true,
    var app: String? = null,
    val date: Long,
    val duration: Long,
    val beatsPerMinute: Double,
)