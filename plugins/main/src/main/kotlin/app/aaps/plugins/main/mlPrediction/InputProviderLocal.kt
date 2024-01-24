package app.aaps.plugins.main.mlPrediction

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.PS
import app.aaps.core.interfaces.db.PersistenceLayer
import cc.buessow.glumagic.input.DateValue
import cc.buessow.glumagic.input.InputProvider
import cc.buessow.glumagic.input.MlProfileSwitch
import cc.buessow.glumagic.input.MlProfileSwitches
import cc.buessow.glumagic.input.MlTemporaryBasalRate
import java.time.Duration
import java.time.Instant

class InputProviderLocal(private val persistence: PersistenceLayer): InputProvider {
    override suspend fun getGlucoseReadings(from: Instant, upto: Instant?): List<DateValue> {
        val to = upto ?: Instant.now()
        return persistence
            .getBgReadingsDataFromTimeToTime(from.toEpochMilli(), to.toEpochMilli(), true)
            .map { g -> DateValue(g.timestamp, g.value) }
    }

    override suspend fun getHeartRates(from: Instant, upto: Instant?): List<DateValue> {
        val to = upto ?: Instant.now()
        return persistence.getHeartRatesFromTimeToTime(from.toEpochMilli(), to.toEpochMilli())
            .map { hr -> DateValue(hr.timestamp, hr.beatsPerMinute) }
    }

    override suspend fun getCarbs(from: Instant, upto: Instant?): List<DateValue> {
        val to = upto ?: Instant.now()
        return persistence
            .getCarbsFromTimeToTimeExpanded(from.toEpochMilli(), to.toEpochMilli(),true)
            .map { c -> DateValue(c.timestamp, c.amount) }
    }

    override suspend fun getBoluses(from: Instant, upto: Instant?): List<DateValue> {
        val to = upto ?: Instant.now()
        return persistence.getBolusesFromTimeToTime(from.toEpochMilli(), to.toEpochMilli(), true)
            .map { b -> DateValue(b.timestamp, b.amount) }
    }


    private fun toBasalProfileSwitch(ps: PS) =
        MlProfileSwitch(
            ps.profileName,
            Instant.ofEpochMilli(ps.timestamp),
            ps.basalBlocks.map { b -> Duration.ofMillis(b.duration) to b.amount },
            Duration.ofMillis(ps.duration).takeIf { it > Duration.ZERO },
            ps.percentage / 100.0)

    private fun toBasalProfileSwitch(eps: EPS) =
        MlProfileSwitch(
            eps.originalProfileName,
            Instant.ofEpochMilli(eps.timestamp),
            eps.basalBlocks.map { b -> Duration.ofMillis(b.duration) to b.amount },
            null,
            1.0)

    override suspend fun getBasalProfileSwitches(from: Instant, upto: Instant?): MlProfileSwitches? {
        val to = upto ?: Instant.now()
        val firstPermanent = persistence.getPermanentProfileSwitchActiveAt(from.toEpochMilli())
        val first = persistence.getEffectiveProfileSwitchActiveAt(from.toEpochMilli())
        if (first == null || firstPermanent == null) return null

        val pss = persistence.getEffectiveProfileSwitchesFromTimeToTime(
            from.toEpochMilli(), to.toEpochMilli(), true)
        return MlProfileSwitches(
            toBasalProfileSwitch(firstPermanent),
            toBasalProfileSwitch(first),
            pss.map(::toBasalProfileSwitch))
    }

    override suspend fun getTemporaryBasalRates(
        from: Instant, upto: Instant?): List<MlTemporaryBasalRate> {
        val to = upto ?: Instant.now()
        return persistence
            .getTemporaryBasalsActiveBetweenTimeAndTime(from.toEpochMilli(), to.toEpochMilli())
            .map { tbs ->
                MlTemporaryBasalRate(
                    Instant.ofEpochMilli(tbs.timestamp),
                    Duration.ofMillis(tbs.duration),
                    if (tbs.isAbsolute) 1.0 else tbs.rate / 100.0,
                    if (tbs.isAbsolute) tbs.rate else null)
            }
    }
}