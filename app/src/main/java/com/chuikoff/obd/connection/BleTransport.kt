package com.chuikoff.obd.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE UART-style transport for ELM327 clones.
 * Tries several well-known UUID sets, then falls back to any write+notify pair.
 */
data class BleUuids(
    val service: UUID,
    val rxChar: UUID, // phone -> device (write)
    val txChar: UUID, // device -> phone (notify)
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
    val label: String = "custom"
) {
    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Nordic UART Service — Vgate / some modern dongles */
        val NUS = BleUuids(
            service = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            rxChar = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            txChar = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            label = "NUS"
        )

        /** Very common Chinese ELM327 BLE (FFF0 / FFF1 notify / FFF2 write) */
        val FFF0 = BleUuids(
            service = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            rxChar = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            txChar = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            label = "FFF0"
        )

        /** FFF0 with swapped RX/TX */
        val FFF0_SWAPPED = BleUuids(
            service = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            rxChar = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            txChar = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            label = "FFF0-swapped"
        )

        /** FFE0 / FFE1 style */
        val FFE0 = BleUuids(
            service = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            rxChar = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            txChar = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            label = "FFE0"
        )

        /** Microchip transparent UART */
        val MICROCHIP = BleUuids(
            service = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455"),
            rxChar = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3"),
            txChar = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616"),
            label = "Microchip"
        )

        val KNOWN: List<BleUuids> = listOf(NUS, FFF0, FFF0_SWAPPED, FFE0, MICROCHIP)
    }
}

private data class ResolvedChars(
    val write: BluetoothGattCharacteristic,
    val notify: BluetoothGattCharacteristic,
    val label: String
)

class BleTransport(
    private val context: Context
) : ObdTransport {

    override val name: String = "Bluetooth LE"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val incoming = ConcurrentLinkedQueue<Byte>()
    private val ready = AtomicBoolean(false)

    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun canWrite(c: BluetoothGattCharacteristic): Boolean {
        val p = c.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun canNotify(c: BluetoothGattCharacteristic): Boolean {
        val p = c.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun resolveUart(services: List<BluetoothGattService>): ResolvedChars? {
        for (profile in BleUuids.KNOWN) {
            val service = services.find { it.uuid == profile.service } ?: continue
            val write = service.getCharacteristic(profile.rxChar)
            val notify = service.getCharacteristic(profile.txChar)
            if (write != null && notify != null && canWrite(write) && canNotify(notify)) {
                return ResolvedChars(write, notify, profile.label)
            }
            // Same UUID for write+notify (FFE1 style)
            if (write != null && canWrite(write) && canNotify(write)) {
                return ResolvedChars(write, write, profile.label)
            }
        }

        // Heuristic: any service with one writable + one notifiable char
        for (service in services) {
            val chars = service.characteristics ?: continue
            val writers = chars.filter { canWrite(it) }
            val notifiers = chars.filter { canNotify(it) }
            if (writers.isEmpty() || notifiers.isEmpty()) continue
            val write = writers.firstOrNull { w -> notifiers.any { it.uuid != w.uuid } } ?: writers.first()
            val notify = notifiers.firstOrNull { it.uuid != write.uuid } ?: notifiers.first()
            return ResolvedChars(write, notify, "auto:${service.uuid}")
        }
        return null
    }

    private fun serviceSummary(services: List<BluetoothGattService>): String {
        if (services.isEmpty()) return "сервисов нет"
        return services.joinToString("; ") { s ->
            val chars = s.characteristics.orEmpty().joinToString(",") { c ->
                val flags = buildString {
                    if (canWrite(c)) append("W")
                    if (canNotify(c)) append("N")
                    if (isEmpty()) append("-")
                }
                "${c.uuid.toString().take(8)}($flags)"
            }
            "${s.uuid.toString().take(8)}[$chars]"
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, notify: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(notify, true)
        val desc = notify.getDescriptor(BleUuids.CCCD) ?: return
        val useIndicate =
            (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = if (useIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value)
        } else {
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceAddress: String) = withContext(Dispatchers.Main) {
        disconnect()
        val adapter = adapter() ?: error("Bluetooth недоступен")
        if (!adapter.isEnabled) error("Bluetooth выключен")
        val device = adapter.getRemoteDevice(deviceAddress)
        withTimeout(20_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { g.requestMtu(512) }
                            g.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            _isConnected.value = false
                            ready.set(false)
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException(
                                        if (status != BluetoothGatt.GATT_SUCCESS) {
                                            "BLE отключён (status=$status). Попробуй Classic SPP после сопряжения в настройках телефона."
                                        } else {
                                            "BLE отключён"
                                        }
                                    )
                                )
                            }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        // Services may already be discovered; no-op.
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("discoverServices failed: $status")
                                )
                            }
                            return
                        }
                        val services = g.services.orEmpty()
                        val resolved = resolveUart(services)
                        if (resolved == null) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException(
                                        "UART BLE не найден. Сервисы: ${serviceSummary(services)}. " +
                                            "Часто такие OBDII — Classic BT: переключись на Classic SPP и сопряги адаптер в настройках Android."
                                    )
                                )
                            }
                            return
                        }
                        writeCharacteristic = resolved.write
                        enableNotify(g, resolved.notify)
                        ready.set(true)
                        _isConnected.value = true
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        @Suppress("DEPRECATION")
                        characteristic.value?.forEach { incoming.offer(it) }
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        value.forEach { incoming.offer(it) }
                    }
                }
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, callback)
                }
                cont.invokeOnCancellation { disconnectBlocking() }
            }
        }
    }

    private fun disconnectBlocking() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        ready.set(false)
        _isConnected.value = false
        incoming.clear()
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        disconnectBlocking()
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.Main) {
        val g = gatt ?: error("Не подключено")
        val rx = writeCharacteristic ?: error("Write characteristic отсутствует")
        val writeType =
            if ((rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(rx, data, writeType)
            Unit
        } else {
            @Suppress("DEPRECATION")
            rx.value = data
            @Suppress("DEPRECATION")
            rx.writeType = writeType
            @Suppress("DEPRECATION")
            g.writeCharacteristic(rx)
            Unit
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var b = incoming.poll()
            while (b != null) {
                buf.append(b.toInt().toChar())
                if (buf.contains('>')) return@withContext buf.toString()
                b = incoming.poll()
            }
            Thread.sleep(20)
        }
        buf.toString()
    }

    @SuppressLint("MissingPermission")
    fun startScan(onResult: (DiscoveredDevice) -> Unit): ScanCallback? {
        val scanner = adapter()?.bluetoothLeScanner ?: return null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                onResult(
                    DiscoveredDevice(
                        name = result.scanRecord?.deviceName ?: d.name,
                        address = d.address,
                        transportType = TransportType.BLE,
                        rssi = result.rssi
                    )
                )
            }
        }
        scanner.startScan(cb)
        return cb
    }

    @SuppressLint("MissingPermission")
    fun stopScan(callback: ScanCallback?) {
        if (callback == null) return
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(callback) }
    }
}
