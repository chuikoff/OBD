package com.chuikoff.obd.sensors

data class SensorReading(
    val sensorId: String,
    val displayName: String,
    val value: Double?,
    val unit: String,
    val rawHex: String? = null,
    val note: String? = null,
    val isPlaceholder: Boolean = false,
    val error: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    /** True when showing last valid CVT value because current frame was invalid. */
    val isStale: Boolean = false,
    /** Unfiltered sensor value when [value] is a display-filtered one (Outlander ambient). */
    val rawValue: Double? = null,
    /** Extra small line under the big number (e.g. «датчик: 24 °C (сырой…)»). */
    val detail: String? = null
)
