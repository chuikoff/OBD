package com.chuikoff.obd.connection

/** Last adapter that connected successfully (persisted). */
data class LastAdapter(val address: String, val name: String?, val transport: TransportType)

enum class AutoTrigger(val labelRu: String) {
    APP_START("запуск приложения"),
    ANDROID_AUTO("Android Auto"),
    BLUETOOTH("Bluetooth машины / адаптера")
}

/** Pure decision rules for auto-connect (unit-tested). */
object AutoConnectPolicy {
    /** Same trigger again within this window is ignored (BT events come in bursts). */
    const val DEBOUNCE_MS = 60_000L
    /** After «Отключить» BT events don't reconnect for this long (app start / Android Auto still do). */
    const val MANUAL_QUIET_MS = 30 * 60_000L

    // android.bluetooth.BluetoothClass.Device constants (kept here so the policy stays JVM-testable)
    const val CLASS_CAR_AUDIO = 0x0420
    const val CLASS_HANDSFREE = 0x0408

    data class Input(
        val enabled: Boolean,
        val onBluetoothEvent: Boolean,
        val trigger: AutoTrigger,
        val last: LastAdapter?,
        val disconnected: Boolean,
        val permissionOk: Boolean,
        val manualDisconnectAtMs: Long?,
        val lastAutoAttemptMs: Long?,
        val nowMs: Long
    )

    /** null = go; otherwise the reason it was skipped. */
    fun skipReason(i: Input): String? = when {
        !i.enabled -> "автоподключение выключено"
        i.last == null -> "адаптер ещё ни разу не подключался"
        !i.permissionOk -> "нет разрешения Bluetooth"
        !i.disconnected -> "уже подключено / подключается"
        i.trigger == AutoTrigger.BLUETOOTH && !i.onBluetoothEvent -> "по событию Bluetooth — выключено"
        i.trigger == AutoTrigger.BLUETOOTH && i.manualDisconnectAtMs != null &&
            i.nowMs - i.manualDisconnectAtMs < MANUAL_QUIET_MS -> "недавно отключено вручную"
        i.lastAutoAttemptMs != null && i.nowMs - i.lastAutoAttemptMs < DEBOUNCE_MS -> "только что пробовали"
        else -> null
    }

    /** ACL_CONNECTED worth reacting to: the adapter itself, or a car head unit / hands-free. */
    fun relevantDevice(address: String?, deviceClass: Int?, last: LastAdapter?): Boolean {
        if (last == null || address == null) return false
        if (address.equals(last.address, ignoreCase = true)) return true
        return deviceClass == CLASS_CAR_AUDIO || deviceClass == CLASS_HANDSFREE
    }
}

/**
 * «Car is off» guard: the OBD port of many cars stays powered, so the adapter answers but no ECU
 * does. With no ECU value for [timeoutMs] the app disconnects instead of polling all night.
 * Adapter-only values (ATRV voltage) must not count as ECU answers.
 */
class IgnitionWatch(private val timeoutMs: Long = 3 * 60_000L) {
    private var lastOkMs: Long? = null
    private var startMs: Long? = null

    fun start(nowMs: Long) { startMs = nowMs; lastOkMs = null }
    fun stop() { startMs = null; lastOkMs = null }

    /** @return true when polling should stop (no ECU answer for [timeoutMs]). */
    fun update(ecuAnswered: Boolean, nowMs: Long): Boolean {
        val since = startMs ?: return false
        if (ecuAnswered) { lastOkMs = nowMs; return false }
        val ref = lastOkMs ?: since
        return nowMs - ref >= timeoutMs
    }
}
