package com.chuikoff.obd.polling

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.sensors.CvtPollPace
import com.chuikoff.obd.sensors.SensorDefinition
import com.chuikoff.obd.sensors.SensorReading
import com.chuikoff.obd.vehicle.VehicleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Sensor poll loop + exclusive bus access for DTC / passport reads. Plain Kotlin (no Android), so the
 * cancel/resume and reconnect paths are unit-tested with a fake transport.
 *
 *  - [stop] waits for the loop to finish (cancelAndJoin); cancellation is never turned into an
 *    error reading, so a pause does not wipe the cards or touch the adapter.
 *  - [paused] runs one exclusive job (Mode 03/04/07/09) with polling stopped, engine addressing
 *    restored first, and resumes polling afterwards. Exclusive jobs never overlap.
 *  - A dead BT link (socket / GATT down, IOException) → [Host.reconnect] with backoff, every time it
 *    happens (no once-per-session limit); after [MAX_RECONNECT_ATTEMPTS] failures in a row → give up.
 */
class Poller(
    private val scope: CoroutineScope,
    private val host: Host,
    private val backoffMs: (attempt: Int) -> Long = ::reconnectBackoffMs
) {
    interface Host {
        val client: Elm327Client?
        val profile: VehicleProfile
        /** True while the BT socket / GATT link is up. */
        fun transportAlive(): Boolean
        /** Last snapshot; a partial tick (CVT only) is merged into it. */
        fun previousReadings(): List<SensorReading>
        /** CVT smoothing + lock-up, ambient display filter… */
        suspend fun process(client: Elm327Client, reading: SensorReading, outlander: Boolean): SensorReading
        fun publish(readings: List<SensorReading>)
        /** A tick failed for a real reason (not a pause). */
        fun tickFailed(message: String)
        /** Reconnect the same adapter; true on success (host replaces [client]). */
        suspend fun reconnect(attempt: Int, reason: String): Boolean
        fun reconnectGaveUp(reason: String)
        fun pollingChanged(running: Boolean)
        /** Resume polling after an exclusive job only while still connected. */
        fun canResume(): Boolean
    }

    private var job: Job? = null
    private val exclusive = Mutex()
    private var tick = 0
    private var hardFailures = 0
    private var reconnectAttempts = 0

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            tick = 0
            hardFailures = 0
            reconnectAttempts = 0
            CvtPollPace.reset()
            host.pollingChanged(true)
            loop()
        }
    }

    /** Stop and wait until the loop has really finished (no command of it is in flight afterwards). */
    suspend fun stop() {
        val j = job
        job = null
        j?.cancelAndJoin()
        CvtPollPace.reset()
        host.pollingChanged(false)
    }

    /** Non-suspending cancel for ViewModel.onCleared (scope is going away anyway). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Run [block] with exclusive use of the adapter: polling stopped (joined), engine ECU addressing
     * restored (a CVT-only tick may have left TCM 7E1 settings), polling resumed afterwards.
     */
    suspend fun <T> paused(client: Elm327Client, block: suspend () -> T): T = exclusive.withLock {
        val wasRunning = job?.isActive == true
        stop()
        try {
            try {
                client.restoreEngineEcuFilter()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            block()
        } finally {
            if (wasRunning && host.canResume()) start()
        }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val client = host.client
            if (client == null || !host.transportAlive()) {
                if (!reconnectOrGiveUp("нет связи с адаптером")) return
                continue
            }
            try {
                runTick(client)
                hardFailures = 0
                reconnectAttempts = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CvtPollPace.reset()
                hardFailures++
                val msg = e.message ?: "сбой цикла"
                host.tickFailed(msg)
                val dead = e is IOException || !host.transportAlive()
                if (dead || hardFailures >= HARD_FAIL_RECONNECT_THRESHOLD) {
                    if (!reconnectOrGiveUp(msg)) return
                } else {
                    // soft recover: resync the ELM and go back to the engine ECU
                    try {
                        client.flushReadBuffer()
                        client.restoreEngineEcuFilter()
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (_: Exception) {
                    }
                    delay(2000L)
                }
            }
        }
    }

    /** @return false when the loop must end (gave up). */
    private suspend fun reconnectOrGiveUp(reason: String): Boolean {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            host.reconnectGaveUp(reason)
            return false
        }
        reconnectAttempts++
        val ok = host.reconnect(reconnectAttempts, reason)
        if (ok) {
            hardFailures = 0
            reconnectAttempts = 0
            CvtPollPace.reset()
            delay(500L)
        } else {
            delay(backoffMs(reconnectAttempts))
        }
        return true
    }

    private suspend fun runTick(client: Elm327Client) {
        tick++
        val profile = host.profile
        val outlander = profile.id == OUTLANDER_PROFILE_ID
        val all = profile.sensors()
        val sensors = sensorsForTick(all, tick, outlander)
        val cvtOnly = outlander && sensors.size == 1 && sensors[0].id == "cvt_temp"
        // CVT-only ticks stay on TCM (skipPostRestore) → fast lock-up refresh
        CvtPollPace.skipPostRestore = cvtOnly

        val byId = host.previousReadings().associateBy { it.sensorId }.toMutableMap()
        try {
            for (sensor in sensors) {
                val reading = try {
                    sensor.read(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SensorReading(
                        sensorId = sensor.id,
                        displayName = sensor.displayNameRu,
                        value = null,
                        unit = sensor.unit,
                        error = e.message ?: "Ошибка чтения"
                    )
                }
                val applied = host.process(client, reading, outlander)
                byId[applied.sensorId] = applied
                // Push CVT/lock-up to UI immediately — don't wait for coolant/ambient
                if (applied.sensorId == "cvt_temp") host.publish(orderReadings(all, byId))
            }
        } finally {
            CvtPollPace.skipPostRestore = false
        }
        // Sensors turn I/O errors into "нет данных" cards; the link state tells us the socket died
        if (!host.transportAlive()) throw IOException("BT сокет мёртв (связь с адаптером потеряна)")
        host.publish(orderReadings(all, byId))
        // Short gap after CVT-only; a bit more after a full mix so the clone can breathe
        delay(
            when {
                cvtOnly -> 250L
                outlander -> 500L
                else -> 800L
            }
        )
    }

    companion object {
        const val OUTLANDER_PROFILE_ID = "mitsubishi_outlander_gf_gg"
        const val HARD_FAIL_RECONNECT_THRESHOLD = 5
        const val MAX_RECONNECT_ATTEMPTS = 10

        /** 2, 4, 8, 16, 30, 30… s */
        fun reconnectBackoffMs(attempt: Int): Long =
            minOf(30_000L, 2_000L shl (attempt - 1).coerceIn(0, 4))

        /** Outlander: CVT every tick (first), coolant every 2nd, ambient every 3rd; others: all. */
        fun sensorsForTick(all: List<SensorDefinition>, tick: Int, outlander: Boolean): List<SensorDefinition> {
            if (!outlander) return all
            val want = linkedSetOf("cvt_temp")
            if (tick % 2 == 0) want += "coolant_temp"
            if (tick % 3 == 0) want += "ambient_temp"
            return all.filter { it.id in want }.sortedBy {
                when (it.id) { "cvt_temp" -> 0; "coolant_temp" -> 1; else -> 2 }
            }
        }

        /** Keep profile sensor order when merging a partial poll into the last full snapshot. */
        fun orderReadings(all: List<SensorDefinition>, byId: Map<String, SensorReading>): List<SensorReading> =
            all.mapNotNull { byId[it.id] }
    }
}
