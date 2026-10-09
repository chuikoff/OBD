package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client

/**
 * Extensible sensor contract. Each sensor knows how to request and decode data
 * via an [Elm327Client], optionally using vehicle-profile-specific commands.
 */
interface SensorDefinition {
    val id: String
    val displayNameRu: String
    val unit: String

    /** Optional warning shown in UI (e.g. uncertain manufacturer PID). */
    val caveatRu: String? get() = null

    suspend fun read(client: Elm327Client): SensorReading
}
