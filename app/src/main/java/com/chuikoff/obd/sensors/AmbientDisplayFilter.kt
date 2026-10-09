package com.chuikoff.obd.sensors

import kotlin.math.min

/**
 * Cluster-like display filter for the Outlander outdoor temperature (AWC 210D byte 8).
 *
 * Byte 8 of `610D` is the *raw* bumper sensor. At idle / in traffic it heat-soaks from the
 * radiator and the road (real session: raw 19 → 24 °C while the dashboard stayed at 19 °C).
 * The cluster shows a filtered value; this class reproduces the usual OEM logic:
 *
 *  - **Drop immediately**: raw lower than shown → show raw at once.
 *  - **Rise slowly**: at most [riseStepC] per [riseIntervalMs], never above raw, and only while
 *    the car is moving (SAE `010D` ≥ [minSpeedKmh]). Qualifying time must accumulate — time
 *    spent standing does not "bank" a rise for later.
 *  - **No speed available**: rise only after raw has been higher than shown for
 *    [noSpeedRiseAfterMs] continuously, then at the same slow rate.
 *  - **Hot restart**: the last shown value + timestamp are persisted via [store]. A new session
 *    within [restoreWindowMs] starts from the stored value if raw is higher (engine bay still
 *    hot), otherwise from raw.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
class AmbientDisplayFilter(
    private val store: Store? = null,
    private val riseStepC: Double = 1.0,
    private val riseIntervalMs: Long = 60_000L,
    private val minSpeedKmh: Int = 20,
    private val noSpeedRiseAfterMs: Long = 10 * 60_000L,
    private val restoreWindowMs: Long = 2 * 60 * 60_000L,
    private val saveEveryMs: Long = 15_000L
) {
    data class Saved(val valueC: Double, val atMs: Long)

    interface Store {
        fun load(): Saved?
        fun save(saved: Saved)
    }

    enum class State {
        /** Shown value equals raw. */
        TRACKING,
        /** Raw higher, rise allowed — climbing slowly. */
        RISING,
        /** Raw higher, rise blocked (standing / slow / waiting for the no-speed timeout). */
        HOLDING
    }

    data class Output(
        val displayC: Double,
        val rawC: Double,
        val state: State,
        /** True if this session started from the persisted value (hot restart). */
        val restored: Boolean
    )

    private var displayed: Double? = null
    private var restored = false
    /** Time with rise conditions met since the last step (accumulates only while allowed). */
    private var qualifyingMs = 0L
    private var lastUpdateMs: Long? = null
    /** Since when raw has been continuously above the shown value (null = not above). */
    private var higherSinceMs: Long? = null
    private var lastSaveMs: Long? = null
    private var lastSavedValue: Double? = null

    val displayedC: Double? get() = displayed

    /** True if the next update could use a fresh speed value (raw above shown). */
    fun needsSpeed(rawC: Double?): Boolean {
        val d = displayed ?: return false
        return rawC != null && rawC > d
    }

    /**
     * @param rawC     raw sensor °C from byte 8
     * @param nowMs    wall clock (ms)
     * @param speedKmh SAE 010D km/h, or null if unavailable
     */
    fun update(rawC: Double, nowMs: Long, speedKmh: Int?): Output {
        val prevUpdate = lastUpdateMs
        lastUpdateMs = nowMs
        val current = displayed

        if (current == null) {
            val saved = store?.load()
            val age = saved?.let { nowMs - it.atMs }
            val start = if (saved != null && age != null && age in 0..restoreWindowMs && rawC > saved.valueC) {
                restored = true
                saved.valueC
            } else {
                restored = false
                rawC
            }
            displayed = start
            qualifyingMs = 0L
            higherSinceMs = if (rawC > start) nowMs else null
            persist(start, nowMs, force = true)
            return Output(start, rawC, if (rawC > start) State.HOLDING else State.TRACKING, restored)
        }

        if (rawC <= current) {
            // Drop immediately (or equal): follow raw, reset rise bookkeeping.
            displayed = rawC
            qualifyingMs = 0L
            higherSinceMs = null
            persist(rawC, nowMs, force = rawC != current)
            return Output(rawC, rawC, State.TRACKING, restored)
        }

        // raw > displayed
        if (higherSinceMs == null) higherSinceMs = nowMs
        val dt = if (prevUpdate != null) (nowMs - prevUpdate).coerceIn(0L, riseIntervalMs) else 0L
        val allowed = if (speedKmh != null) {
            speedKmh >= minSpeedKmh
        } else {
            nowMs - (higherSinceMs ?: nowMs) >= noSpeedRiseAfterMs
        }

        var shown: Double = current
        if (allowed) {
            qualifyingMs += dt
            while (qualifyingMs >= riseIntervalMs && shown < rawC) {
                shown = min(rawC, shown + riseStepC)
                qualifyingMs -= riseIntervalMs
            }
            if (shown >= rawC) {
                qualifyingMs = 0L
                higherSinceMs = null
            }
        } else {
            qualifyingMs = 0L
        }
        displayed = shown
        persist(shown, nowMs, force = shown != current)
        val state = when {
            shown >= rawC -> State.TRACKING
            allowed -> State.RISING
            else -> State.HOLDING
        }
        return Output(shown, rawC, state, restored)
    }

    /** End of session (disconnect): save now, forget in-memory state. */
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        displayed?.let { persist(it, nowMs, force = true) }
        displayed = null
        restored = false
        qualifyingMs = 0L
        lastUpdateMs = null
        higherSinceMs = null
    }

    private fun persist(value: Double, nowMs: Long, force: Boolean) {
        val s = store ?: return
        val last = lastSaveMs
        if (force || last == null || nowMs - last >= saveEveryMs || value != lastSavedValue) {
            s.save(Saved(value, nowMs))
            lastSaveMs = nowMs
            lastSavedValue = value
        }
    }
}
