package app.aaps.database.transactions

import app.aaps.database.entities.HeartRate

class SyncNsHeartRatesTransaction(private val heartRates: List<HeartRate>):
    Transaction<SyncNsHeartRatesTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        for (hr in heartRates) {
            val current = hr.interfaceIDs.nightscoutId?.let { nsId ->
                database.heartRateDao.findByNSId(nsId) }

            if (current != null) {
                if (!current.contentEqualsTo(hr)) {
                    hr.id = current.id
                    database.heartRateDao.updateExistingEntry(hr)
                    if (!hr.isValid && current.isValid) {
                        result.invalidated.add(hr)
                    } else {
                        result.updated.add(hr)
                    }
                }
            } else {
                database.heartRateDao.insertNewEntry(hr)
                result.inserted.add(hr)
            }
        }
        return result
    }

    class TransactionResult {
        val updated = mutableListOf<HeartRate>()
        val inserted = mutableListOf<HeartRate>()
        val invalidated = mutableListOf<HeartRate>()
    }
}