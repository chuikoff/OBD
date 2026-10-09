package com.chuikoff.obd.sensors

/**
 * Anti-flicker for CVT only.
 *  - valid frames: median of the last 3 valid °C (removes single-sample jitter only;
 *    a systematic offset is NOT hidden — the CVT debug card shows the per-frame value)
 *  - invalid frames (lost ISO-TP frame, NO DATA…): hold last displayed value, dimmed
 *    ([SensorReading.isStale]), at most [MAX_HELD] polls in a row; after that show "—"
 *    with the real error so a dead/broken read never looks like a live value.
 */
class CvtTempSmoother {
    private val ring = ArrayDeque<Double>(3)
    private var lastDisplayed: Double? = null
    private var heldCount = 0

    fun apply(reading: SensorReading): SensorReading {
        if (reading.sensorId != "cvt_temp") return reading

        val v = reading.value
        if (v != null) {
            heldCount = 0
            if (ring.size >= 3) ring.removeFirst()
            ring.addLast(v)
            val median = medianOf(ring)
            lastDisplayed = median
            return reading.copy(value = median, isStale = false)
        }

        val held = lastDisplayed
        if (held != null && heldCount < MAX_HELD) {
            heldCount++
            return reading.copy(
                value = held,
                isStale = true,
                note = listOfNotNull(
                    reading.note,
                    "кадр битый ($heldCount/$MAX_HELD) — держим последнее: ${reading.error ?: ""}"
                ).joinToString(" · "),
                error = null
            )
        }
        if (held != null) {
            // Too many invalid frames in a row: stop pretending, drop history
            ring.clear()
            lastDisplayed = null
        }
        return reading.copy(isStale = false)
    }

    fun reset() {
        ring.clear()
        lastDisplayed = null
        heldCount = 0
    }

    private fun medianOf(values: Collection<Double>): Double {
        val sorted = values.sorted()
        return when (sorted.size) {
            0 -> Double.NaN
            1 -> sorted[0]
            2 -> (sorted[0] + sorted[1]) / 2.0
            else -> sorted[sorted.size / 2]
        }
    }

    companion object {
        const val MAX_HELD = 5
    }
}
