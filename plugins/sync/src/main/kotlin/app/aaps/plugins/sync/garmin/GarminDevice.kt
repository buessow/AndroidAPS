package app.aaps.plugins.sync.garmin

import android.os.Parcel
import android.os.Parcelable

data class GarminDevice(
    val id: Long,
    val name: String): Parcelable {
    enum class Status {
        NOT_PAIRED,
        NOT_CONNECTED,
        CONNECTED,
        UNKNOWN,
    }

    constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readString() ?: "") {
        parcel.readString()  // skip status
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(name)
        parcel.writeString("UNKNOWN")
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        return this === other || id == (other as? GarminDevice)?.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String = "D[$name/$id]"

    companion object CREATOR : Parcelable.Creator<GarminDevice> {
        override fun createFromParcel(parcel: Parcel): GarminDevice {
            return GarminDevice(parcel)
        }

        override fun newArray(size: Int): Array<GarminDevice?> {
            return arrayOfNulls(size)
        }
    }
}