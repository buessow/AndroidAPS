package app.aaps.plugins.main.mlPrediction

import app.aaps.plugins.main.mlPrediction.DataLoader.Companion.div
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Config(
    @SerializedName("trainingPeriodMinutes")
    val trainingPeriod: Duration,
    @SerializedName("predictionPeriodMinutes")
    val predictionPeriod: Duration,
    @SerializedName("hrLongDurationMinutes")
    val hrLong: List<Duration>, // = listOf(Duration.ofHours(24), Duration.ofHours(48)),
    val hrHighThreshold: Int ,
    @SerializedName("freqMinutes")
    val freq: Duration = Duration.ofMinutes(5),
    val testData: List<TestData> = emptyList(),
) {
    val inputSize get() =
        // hour of day and long heart rates
        1 + hrLong.size +
        // glucose slope and slope of slope
        2 * (trainingPeriod / freq) +
        // carb, insulin and heart rate
        3 * ((trainingPeriod + predictionPeriod) / freq)

    val outputSize get() = predictionPeriod / freq  // glucose slope prediction

    data class TestInputData(
        val minute: List<Int>,
        val glucose: List<Double>,
        @SerializedName("gl_slope")
        val glSlopes1: List<Double>,
        @SerializedName("gl_slop2")
        val glSlopes2: List<Double>,
        @SerializedName("heart_rate")
        val heartRates: List<Double>,
        val ca: List<Double>,
        val ia: List<Double>,
    )

    data class TestData(
        val name: String,
        val at: Instant,
        val inputData: TestInputData,
        val inputVector: FloatArray,
        val outputSlopes: List<Float>,
        val outputGlucose: List<Float>,
        val heartRates: List<Double>,
        val carbEvents: List<DateValue>,
        val insulinEvents: List<DateValue>,
        val hrLongCounts: List<Int>,
    ) {
        override fun equals(other: Any?) = super.equals(other)
        override fun hashCode() = super.hashCode()
    }

    companion object {
        fun fromJson(jsonFile: File): Config {
          return fromJson(jsonFile.readText())
        }

        fun fromJson(json: String): Config {
            val builder = GsonBuilder()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxxxx")

            builder.registerTypeAdapter(
                LocalDateTime::class.java,
                object: TypeAdapter<LocalDateTime>() {
                    override fun write(out: JsonWriter, value: LocalDateTime?) {
                        if (value != null) out.value(formatter.format(value))
                        else out.nullValue()
                    }
                    override fun read(jr: JsonReader) =
                        LocalDateTime.parse(jr.nextString(), formatter)
                })
            builder.registerTypeAdapter(
                Instant::class.java,
                object: TypeAdapter<Instant>() {
                    override fun write(out: JsonWriter, value: Instant?) {
                        if (value != null) out.value(formatter.format(value))
                        else out.nullValue()
                    }
                    override fun read(jr: JsonReader) =
                        LocalDateTime.parse(jr.nextString(), formatter).toInstant(ZoneOffset.UTC)
                })
            builder.registerTypeAdapter(
                Duration::class.java,
                object: TypeAdapter<Duration>() {
                    override fun write(out: JsonWriter, value: Duration?) {
                        if (value != null) out.value(value.toMinutes())
                        else out.nullValue()
                    }
                    override fun read(jr: JsonReader) =
                        Duration.ofMinutes(jr.nextLong())
                })
            return builder.create().fromJson(json, Config::class.java)
        }
    }
}