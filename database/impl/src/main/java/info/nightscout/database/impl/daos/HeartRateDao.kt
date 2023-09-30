package info.nightscout.database.impl.daos

import androidx.room.Dao
import androidx.room.Query
import info.nightscout.database.entities.HeartRate
import info.nightscout.database.entities.TABLE_HEART_RATE

@Dao
internal interface HeartRateDao : TraceableDao<HeartRate> {

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE id = :id")
    override fun findById(id: Long): HeartRate?

    @Query("DELETE FROM $TABLE_HEART_RATE")
    override fun deleteAllEntries()

    @Query("DELETE FROM $TABLE_HEART_RATE WHERE timestamp < :than")
    override fun deleteOlderThan(than: Long): Int

    @Query("DELETE FROM $TABLE_HEART_RATE WHERE referenceId IS NOT NULL")
    override fun deleteTrackedChanges(): Int

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE timestamp >= :timestamp ORDER BY timestamp")
    fun getFromTime(timestamp: Long): List<HeartRate>

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE timestamp BETWEEN :startMillis AND :endMillis AND referenceId IS NULL ORDER BY timestamp")
    fun getFromTimeToTime(startMillis: Long, endMillis: Long): List<HeartRate>

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE timestamp > :since AND timestamp <= :until  ORDER BY TIMESTAMP LIMIT :limit OFFSET :offset")
    fun getNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): List<HeartRate>

    @Query("SELECT id FROM $TABLE_HEART_RATE ORDER BY id DESC LIMIT 1")
    fun getLastId(): Long?

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE id > :id ORDER BY id ASC LIMIT 1")
    fun getNextAfter(id: Long): HeartRate?

    @Query("SELECT * FROM $TABLE_HEART_RATE WHERE nightscoutId = :nsId ORDER BY VERSION DESC LIMIT 1")
    fun findByNSId(nsId: String): HeartRate?
}
