package app.aaps.database.impl.transactions

import app.aaps.database.entities.HeartRate

class UpdateNsIdHeartRateTransaction(private val heartRates: List<HeartRate>) : Transaction<UpdateNsIdHeartRateTransaction.TransactionResult>() {

    val result = TransactionResult()

    override fun run(): TransactionResult {
        for (hr in heartRates) {
            val current = database.heartRateDao.findById(hr.id)
            if (current != null && current.interfaceIDs.nightscoutId != hr.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = hr.interfaceIDs.nightscoutId
                database.heartRateDao.updateExistingEntry(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {
        val updatedNsId = mutableListOf<HeartRate>()
    }
}