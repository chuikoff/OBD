package com.chuikoff.obd.connection

import kotlinx.coroutines.flow.StateFlow

/**
 * Common transport for ELM327-style adapters (Bluetooth Classic SPP or BLE).
 */
interface ObdTransport {
    val name: String
    val isConnected: StateFlow<Boolean>

    suspend fun connect(deviceAddress: String)
    suspend fun disconnect()

    /** Write ASCII command bytes (typically ending with \\r). */
    suspend fun write(data: ByteArray)

    /** Read available response until timeout or prompt character '>'. */
    suspend fun readUntilPrompt(timeoutMs: Long = 3000): String
}

enum class TransportType {
    CLASSIC_SPP,
    BLE
}

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val transportType: TransportType,
    val rssi: Int? = null
)
