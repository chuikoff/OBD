package com.chuikoff.obd.alerts

import com.chuikoff.obd.ui.TempColorScale

/** User-editable alert thresholds (°C, compared with the value as displayed, i.e. rounded). */
data class AlertSettings(
    val cvtEnabled: Boolean = true,
    val cvtHotC: Int = 100,
    val coolantEnabled: Boolean = true,
    val coolantHotC: Int = 105,
    val iceEnabled: Boolean = true,
    val iceC: Int = 3,
    val sound: Boolean = true,
    val vibrate: Boolean = true
) {
    companion object {
        val CVT_RANGE = 80..130
        val COOLANT_RANGE = 95..125
        val ICE_RANGE = -5..8
    }
}

enum class AlertKind(val titleRu: String) {
    CVT_HOT("Перегрев CVT"),
    COOLANT_HOT("Перегрев двигателя (ОЖ)"),
    ICE("Возможен гололёд")
}

data class AlertEvent(val kind: AlertKind, val valueC: Int, val thresholdC: Int, val atMs: Long) {
    val textRu: String
        get() = when (kind) {
            AlertKind.CVT_HOT -> "CVT $valueC °C (порог $thresholdC °C) — сбавь нагрузку, дай вариатору остыть"
            AlertKind.COOLANT_HOT -> "ОЖ $valueC °C (порог $thresholdC °C) — следи за стрелкой, снизь нагрузку"
            AlertKind.ICE -> "Снаружи $valueC °C — на дороге может быть лёд"
        }
}

/**
 * Threshold alerts without spam. Pure Kotlin (unit-tested).
 *
 * Hot alerts (CVT / coolant): fire after [confirmSamples] fresh readings in a row ≥ threshold;
 * re-armed only once the value drops to threshold − [hotHysteresisC]; and never more often than
 * [hotCooldownMs] even if it oscillates around the re-arm point.
 *
 * Ice: once per trip ([resetTrip] on a new connection). Fires after [confirmSamples] readings
 * ≤ threshold. Re-armed within the trip only if it really got warmer: ≥ threshold +
 * [iceHysteresisC] for at least [iceRearmMs] (e.g. drove out of a frosty valley).
 */
class AlertEngine(
    private val confirmSamples: Int = 2,
    private val hotHysteresisC: Int = 3,
    private val hotCooldownMs: Long = 10 * 60_000L,
    private val iceHysteresisC: Int = 2,
    private val iceRearmMs: Long = 10 * 60_000L
) {
    private class Hot {
        var armed = true
        var streak = 0
        var lastFiredMs: Long? = null
    }

    private val cvt = Hot()
    private val coolant = Hot()
    private var iceArmed = true
    private var iceStreak = 0
    private var warmSinceMs: Long? = null

    fun resetTrip() {
        listOf(cvt, coolant).forEach { it.armed = true; it.streak = 0; it.lastFiredMs = null }
        iceArmed = true
        iceStreak = 0
        warmSinceMs = null
    }

    /** Pass only fresh values (null = no new sample this tick: streaks are kept, nothing fires). */
    fun update(
        settings: AlertSettings,
        cvtC: Double?,
        coolantC: Double?,
        ambientC: Double?,
        nowMs: Long
    ): List<AlertEvent> {
        val out = mutableListOf<AlertEvent>()
        if (settings.cvtEnabled) hot(cvt, AlertKind.CVT_HOT, cvtC, settings.cvtHotC, nowMs)?.let(out::add)
        if (settings.coolantEnabled) hot(coolant, AlertKind.COOLANT_HOT, coolantC, settings.coolantHotC, nowMs)?.let(out::add)
        if (settings.iceEnabled) ice(ambientC, settings.iceC, nowMs)?.let(out::add)
        return out
    }

    private fun hot(s: Hot, kind: AlertKind, value: Double?, thr: Int, now: Long): AlertEvent? {
        if (value == null || value.isNaN()) return null
        val t = TempColorScale.displayed(value)
        if (t <= thr - hotHysteresisC) s.armed = true
        if (t >= thr) s.streak++ else s.streak = 0
        if (!s.armed || s.streak < confirmSamples) return null
        val last = s.lastFiredMs
        if (last != null && now - last < hotCooldownMs) return null
        s.armed = false
        s.lastFiredMs = now
        return AlertEvent(kind, t, thr, now)
    }

    private fun ice(value: Double?, thr: Int, now: Long): AlertEvent? {
        if (value == null || value.isNaN()) return null
        val t = TempColorScale.displayed(value)
        if (!iceArmed) {
            if (t >= thr + iceHysteresisC) {
                val since = warmSinceMs ?: now.also { warmSinceMs = it }
                if (now - since >= iceRearmMs) { iceArmed = true; warmSinceMs = null }
            } else {
                warmSinceMs = null
            }
        }
        if (t <= thr) iceStreak++ else iceStreak = 0
        if (!iceArmed || iceStreak < confirmSamples) return null
        iceArmed = false
        warmSinceMs = null
        return AlertEvent(AlertKind.ICE, t, thr, now)
    }
}
