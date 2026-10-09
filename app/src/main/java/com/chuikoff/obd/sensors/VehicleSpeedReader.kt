package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser

/**
 * SAE Mode 01 PID 0D (vehicle speed, km/h = A) — read only when the ambient filter needs it
 * (raw above the shown value), throttled to one request per [minIntervalMs]. After
 * [failuresBeforeBackoff] failures in a row speed is treated as unavailable and only retried
 * every [backoffMs], so a car/adapter without 010D costs nothing in the poll loop.
 */
class VehicleSpeedReader(
    private val minIntervalMs: Long = 5_000L,
    private val failuresBeforeBackoff: Int = 3,
    private val backoffMs: Long = 60_000L
) {
    private var lastKmh: Int? = null
    private var lastAttemptMs: Long = Long.MIN_VALUE / 2
    private var lastOkMs: Long = Long.MIN_VALUE / 2
    private var failures = 0

    /** Last good speed if fresh enough, else null. */
    fun cached(nowMs: Long): Int? = if (nowMs - lastOkMs <= minIntervalMs * 3) lastKmh else null

    /** Returns fresh/cached km/h, or null if unavailable. Assumes engine ECU header (7E0). */
    suspend fun speedKmh(client: Elm327Client, nowMs: Long): Int? {
        val interval = if (failures >= failuresBeforeBackoff) backoffMs else minIntervalMs
        if (nowMs - lastAttemptMs < interval) return cached(nowMs)
        lastAttemptMs = nowMs
        val kmh = runCatching {
            val raw = client.send("010D", timeoutMs = 1500)
            parse(raw)
        }.getOrNull()
        if (kmh != null) {
            failures = 0
            lastKmh = kmh
            lastOkMs = nowMs
        } else {
            failures++
        }
        return kmh ?: cached(nowMs)
    }

    fun reset() {
        lastKmh = null
        lastAttemptMs = Long.MIN_VALUE / 2
        lastOkMs = Long.MIN_VALUE / 2
        failures = 0
    }

    companion object {
        fun parse(raw: String): Int? {
            val p = Elm327Parser.parseMode01Payload(raw, 0x0D) ?: return null
            if (p.isEmpty()) return null
            return Elm327Parser.toUnsigned(p[0])
        }
    }
}
