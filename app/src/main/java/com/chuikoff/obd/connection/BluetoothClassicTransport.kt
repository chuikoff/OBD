package com.chuikoff.obd.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP transport (typical cheap ELM327 dongles).
 * Tries secure SPP, insecure SPP, then reflection channel-1 fallback.
 * Surfaces IOException when the socket dies so ViewModel can soft-recover / reconnect.
 */
class BluetoothClassicTransport(
    private val context: Context
) : ObdTransport {

    override val name: String = "Bluetooth Classic (SPP)"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private fun adapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter
    }

    private fun markDead(cause: Throwable? = null) {
        _isConnected.value = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    fun isSocketAlive(): Boolean {
        val s = socket
        return _isConnected.value && s != null && s.isConnected
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice, label: String, open: () -> BluetoothSocket): BluetoothSocket {
        val sock = open()
        try {
            sock.connect()
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw IOException("$label: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceAddress: String) = withContext(Dispatchers.IO) {
        disconnect()
        val adapter = adapter() ?: error("Bluetooth недоступен")
        if (!adapter.isEnabled) error("Bluetooth выключен")
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
        runCatching { adapter.cancelDiscovery() }

        val errors = mutableListOf<String>()
        val sock: BluetoothSocket = run {
            try {
                return@run tryConnect(device, "secure SPP") {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
            } catch (e: Exception) {
                errors += e.message ?: "secure fail"
            }
            try {
                return@run tryConnect(device, "insecure SPP") {
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
            } catch (e: Exception) {
                errors += e.message ?: "insecure fail"
            }
            try {
                return@run tryConnect(device, "RFCOMM ch1") {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    @Suppress("UNCHECKED_CAST")
                    m.invoke(device, 1) as BluetoothSocket
                }
            } catch (e: Exception) {
                errors += e.message ?: "ch1 fail"
            }
            error(
                "Classic SPP не подключился к $deviceAddress. " +
                    "Сопряги адаптер в настройках Bluetooth телефона (пин часто 1234 или 0000), " +
                    "потом Classic SPP → Поиск. Детали: ${errors.joinToString(" | ")}"
            )
        }

        socket = sock
        input = sock.inputStream
        output = sock.outputStream
        _isConnected.value = true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        _isConnected.value = false
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: error("Не подключено")
        val sock = socket
        if (sock == null || !sock.isConnected) {
            markDead()
            throw IOException("BT сокет мёртв (не подключён)")
        }
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            markDead(e)
            throw IOException("BT запись: сокет мёртв (${e.message})", e)
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: error("Не подключено")
        val sock = socket
        if (sock == null || !sock.isConnected) {
            markDead()
            throw IOException("BT сокет мёртв (не подключён)")
        }
        // Slightly longer effective wait while driving / bus busy (caller may also raise)
        val effectiveTimeout = timeoutMs.coerceAtLeast(2500L)
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + effectiveTimeout
        val tmp = ByteArray(256)
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!sock.isConnected) {
                    markDead()
                    throw IOException("BT сокет закрыт во время чтения")
                }
                val available = inp.available()
                if (available > 0) {
                    val n = inp.read(tmp, 0, minOf(available, tmp.size))
                    when {
                        n < 0 -> {
                            markDead()
                            throw IOException("BT сокет закрыт (EOF)")
                        }
                        n > 0 -> {
                            buf.append(String(tmp, 0, n, Charsets.US_ASCII))
                            if (buf.contains('>')) break
                        }
                    }
                } else {
                    Thread.sleep(20)
                }
            }
        } catch (e: IOException) {
            if (_isConnected.value) markDead(e)
            throw if (e.message?.contains("BT сокет") == true) e
            else IOException("BT чтение: сокет мёртв (${e.message})", e)
        }
        buf.toString()
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<DiscoveredDevice> {
        val adapter = adapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map {
            DiscoveredDevice(
                name = it.name,
                address = it.address,
                transportType = TransportType.CLASSIC_SPP
            )
        }
    }
}
