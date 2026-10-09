package com.chuikoff.obd.vehicle

import com.chuikoff.obd.sensors.SensorDefinition

/**
 * Describes a vehicle model: display info + sensor set.
 * Add new profiles by implementing this interface and registering in [VehicleProfileRegistry].
 */
interface VehicleProfile {
    val id: String
    val displayNameRu: String
    val years: String
    val notesRu: String

    fun sensors(): List<SensorDefinition>

    /** Profile-specific CVT temperature command (may be a placeholder). */
    fun cvtTempCommand(): CvtTempCommand
}

/**
 * How to request CVT fluid temperature for a given vehicle.
 */
data class CvtTempCommand(
    /** Main ELM request, e.g. "2103" or "221101". */
    val request: String,
    /** DID / PID hex without spaces, e.g. "03" for Mode 21 PID byte. */
    val pidOrDid: String,
    val parseMode: ParseMode,
    /** AT / addressing commands before the main request (e.g. ATSH7E1). */
    val preCommands: List<String> = emptyList(),
    /** AT commands after the read to restore engine ECU addressing. */
    val postCommands: List<String> = emptyList(),
    val isPlaceholder: Boolean,
    val caveatRu: String,
    val decode: (ByteArray) -> Double?,
    /** Optional: human-readable decode candidates for the CVT debug log. */
    val describeCandidates: ((ByteArray) -> String)? = null,
    /** Optional extra Mode 21 reads sent (same addressing) only when CVT debug is enabled. */
    val debugRequests: List<DebugRequest> = emptyList()
) {
    data class DebugRequest(
        val request: String,
        val pidByte: Int,
        val describe: (ByteArray) -> String
    )

    enum class ParseMode { MODE01, MODE21, MODE22, RAW_FIRST_BYTE }
}
