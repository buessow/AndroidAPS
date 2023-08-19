package info.nightscout.database.impl.transactions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import info.nightscout.database.impl.AppDatabase
import info.nightscout.database.impl.AppRepository
import info.nightscout.database.impl.HeartRateDaoTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncNsHeartRatesTransactionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = AppRepository(db)
    }

    @After
    fun shutdown() {
        db.close()
    }

    @Test
    fun createNewEntry() {
        val hr1 = HeartRateDaoTest.createHeartRate().apply { interfaceIDs.nightscoutId = "ns1" }
        val result = repo.runTransactionForResult(SyncNsHeartRatesTransaction(listOf(hr1))).blockingGet()
        assertEquals(listOf(hr1), result.inserted)
        assertTrue(result.updated.isEmpty())
        assertTrue(result.invalidated.isEmpty())
    }

    @Test
    fun updateEntry() {
        val v0 = HeartRateDaoTest.createHeartRate().apply { interfaceIDs.nightscoutId = "ns1" }
        val id = db.heartRateDao.insertNewEntry(v0.copy())
        val v1 = v0.copy(beatsPerMinute = 181.0)
        val result = repo.runTransactionForResult(SyncNsHeartRatesTransaction(listOf(v1.copy()))).blockingGet()
        assertEquals(1, result.updated.size)
        assertEquals(id, result.updated[0].id)
        assertEquals(181.0, result.updated[0].beatsPerMinute, 0.1)
        assertTrue(result.inserted.isEmpty())
        assertTrue(result.invalidated.isEmpty())

        val v0Retrieved = db.heartRateDao.getFromTime(0L).first { hr -> hr.referenceId == id }
        val v1Retrieved = db.heartRateDao.findById(id)!!
        assertEquals(v0.beatsPerMinute, v0Retrieved.beatsPerMinute, 0.1)
        assertEquals(0, v0Retrieved.version)
        assertEquals(181.0, v1Retrieved.beatsPerMinute, 0.1)
        assertEquals(1, v1Retrieved.version)
    }

    @Test
    fun invalidateEntry() {
        val v0 = HeartRateDaoTest.createHeartRate().apply { interfaceIDs.nightscoutId = "ns1" }
        val id = db.heartRateDao.insertNewEntry(v0.copy())
        val v1 = v0.copy(isValid = false)
        val result = repo.runTransactionForResult(SyncNsHeartRatesTransaction(listOf(v1.copy()))).blockingGet()
        assertEquals(1, result.invalidated.size)
        assertEquals(id, result.invalidated[0].id)
        assertFalse(result.invalidated[0].isValid)
        assertTrue(result.inserted.isEmpty())
        assertTrue(result.updated.isEmpty())

        val v0Retrieved = db.heartRateDao.getFromTime(0L).first { hr -> hr.referenceId == id }
        val v1Retrieved = db.heartRateDao.findById(id)!!
        assertTrue(v0Retrieved.isValid)
        assertFalse(v1Retrieved.isValid)
        assertEquals(0, v0Retrieved.version)
        assertEquals(1, v1Retrieved.version)
    }
}