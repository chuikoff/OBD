package com.chuikoff.obd.viewmodel

import android.app.Application
import android.bluetooth.le.ScanCallback
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chuikoff.obd.connection.BleTransport
import com.chuikoff.obd.connection.BluetoothClassicTransport
import com.chuikoff.obd.connection.DiscoveredDevice
import com.chuikoff.obd.connection.ObdTransport
import com.chuikoff.obd.connection.TransportType
import com.chuikoff.obd.data.ObdLiveData
import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.sensors.AmbientDisplayFilter
import com.chuikoff.obd.passport.Passport
import com.chuikoff.obd.passport.PassportData
import com.chuikoff.obd.passport.PassportReader
import com.chuikoff.obd.sensors.CvtDebugEntry
import com.chuikoff.obd.sensors.CvtDebugLog
import com.chuikoff.obd.sensors.CvtFormula
import com.chuikoff.obd.sensors.CvtSettings
import com.chuikoff.obd.sensors.CvtLockupDetector
import com.chuikoff.obd.sensors.CvtPollPace
import com.chuikoff.obd.sensors.CvtTempSmoother
import com.chuikoff.obd.sensors.SensorReading
import com.chuikoff.obd.sensors.VehicleSpeedReader
import com.chuikoff.obd.service.ObdMonitorService
import com.chuikoff.obd.vehicle.VehicleProfile
import com.chuikoff.obd.vehicle.VehicleProfileRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ObdUiState(
    val profiles: List<VehicleProfile> = VehicleProfileRegistry.all(),
    val selectedProfile: VehicleProfile = VehicleProfileRegistry.default,
    val preferredTransport: TransportType = TransportType.CLASSIC_SPP,
    val devices: List<DiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedAddress: String? = null,
    val readings: List<SensorReading> = emptyList(),
    val isPolling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    // Diagnostics
    val dtcCodes: List<String> = emptyList(),
    val dtcPending: List<String> = emptyList(),
    val dtcStatus: String? = null,
    val dtcError: String? = null,
    val dtcBusy: Boolean = false,
    /** True after at least one successful Mode 03/07 read this session. */
    val dtcScanned: Boolean = false,
    // CVT diagnostics
    val cvtFormula: CvtFormula = CvtFormula.OUT3_POLY,
    val cvtDebugExtra: Boolean = false,
    val cvtLog: List<CvtDebugEntry> = emptyList(),
    /** Non-null while the ELM link is being re-initialised. */
    val linkNote: String? = null,
    // Vehicle passport («Авто»)
    val passport: PassportData? = null,
    val passportBusy: Boolean = false,
    val passportStatus: String? = null
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
}

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val classic = BluetoothClassicTransport(app)
    private val ble = BleTransport(app)
    private val livePrefs = ObdLiveData.prefs(app)

    private var activeTransport: ObdTransport? = null
    private var elm: Elm327Client? = null
    private var pollJob: Job? = null
    private var bleScanCallback: ScanCallback? = null

    private var consecutiveHardFailures = 0
    private var reconnectUsedThisSession = false
    private val cvtTempSmoother = CvtTempSmoother()
    private val cvtLockup = CvtLockupDetector()

    /** Outlander ambient: cluster-like filter over raw AWC byte 8, persisted across sessions. */
    private val ambientPrefs = app.getSharedPreferences("ambient_filter", android.content.Context.MODE_PRIVATE)
    private val ambientFilter = AmbientDisplayFilter(store = object : AmbientDisplayFilter.Store {
        override fun load(): AmbientDisplayFilter.Saved? {
            if (!ambientPrefs.contains(K_AMB_VALUE) || !ambientPrefs.contains(K_AMB_AT)) return null
            val v = ambientPrefs.getFloat(K_AMB_VALUE, Float.NaN).toDouble()
            if (v.isNaN()) return null
            return AmbientDisplayFilter.Saved(v, ambientPrefs.getLong(K_AMB_AT, 0L))
        }
        override fun save(saved: AmbientDisplayFilter.Saved) {
            ambientPrefs.edit()
                .putFloat(K_AMB_VALUE, saved.valueC.toFloat())
                .putLong(K_AMB_AT, saved.atMs)
                .apply()
        }
    })
    private val speedReader = VehicleSpeedReader()
    private var ambientHeld = 0

    private val _ui = MutableStateFlow(ObdUiState())
    val ui: StateFlow<ObdUiState> = _ui.asStateFlow()

    private val cvtPrefs = app.getSharedPreferences("cvt_settings", android.content.Context.MODE_PRIVATE)

    init {
        val formula = runCatching {
            CvtFormula.valueOf(cvtPrefs.getString(K_CVT_FORMULA, CvtFormula.OUT3_POLY.name)!!)
        }.getOrDefault(CvtFormula.OUT3_POLY)
        val extra = cvtPrefs.getBoolean(K_CVT_DEBUG_EXTRA, false)
        CvtSettings.formula = formula
        CvtSettings.debugExtraRequests = extra
        val cachedPassport = Passport.deserialize(
            getApplication<Application>().getSharedPreferences("passport_cache", android.content.Context.MODE_PRIVATE)
                .getString("data", null)
        )
        _ui.update { it.copy(cvtFormula = formula, cvtDebugExtra = extra, passport = cachedPassport) }
        viewModelScope.launch {
            CvtDebugLog.entries.collect { list -> _ui.update { it.copy(cvtLog = list) } }
        }
    }

    fun setCvtFormula(formula: CvtFormula) {
        CvtSettings.formula = formula
        cvtPrefs.edit().putString(K_CVT_FORMULA, formula.name).apply()
        cvtTempSmoother.reset()
        _ui.update { it.copy(cvtFormula = formula) }
    }

    fun setCvtDebugExtra(enabled: Boolean) {
        CvtSettings.debugExtraRequests = enabled
        cvtPrefs.edit().putBoolean(K_CVT_DEBUG_EXTRA, enabled).apply()
        _ui.update { it.copy(cvtDebugExtra = enabled) }
    }

    fun clearCvtLog() = CvtDebugLog.clear()

    /** Plain-text export of the last ~50 CVT responses + context (coolant/ambient now). */
    fun cvtLogExportText(): String {
        val s = _ui.value
        fun r(id: String) = s.readings.find { it.sensorId == id }?.value
        val header = buildString {
            append("OBD Outlander ${com.chuikoff.obd.BuildConfig.VERSION_NAME} — CVT лог\n")
            append("Профиль: ${s.selectedProfile.displayNameRu}\n")
            append("Формула: ${s.cvtFormula.titleRu}\n")
            append("Сейчас: ОЖ ${r("coolant_temp")?.let { "%.0f".format(it) } ?: "—"} °C, ")
            append("снаружи ${r("ambient_temp")?.let { "%.0f".format(it) } ?: "—"} °C")
            s.readings.find { it.sensorId == "ambient_temp" }?.rawValue?.let { append(" (датчик %.0f)".format(it)) }
            append(", ")
            append("CVT (на экране) ${r("cvt_temp")?.let { "%.1f".format(it) } ?: "—"} °C")
        }
        return CvtDebugLog.exportText(header)
    }

    /**
     * On-demand passport read (VIN, calibration IDs, supported PIDs, readiness). Pauses sensor
     * polling, uses the same serialized Elm327Client I/O (mutex + recovery) and resumes polling.
     * Every item fails independently; the result is cached for offline display.
     */
    fun readPassport() {
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(passportStatus = "Нет подключения — показан сохранённый паспорт") }
            return
        }
        if (_ui.value.passportBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(passportBusy = true, passportStatus = "Чтение паспорта…") }
            val wasPolling = _ui.value.isPolling
            stopPolling()
            client.tolerateNoData = true
            try {
                client.prepareStandardObdBus()
                // Long multi-frame Mode 09 replies: default flow control, max per-frame wait
                runCatching { client.send("ATFCSM0", timeoutMs = 1500) }
                runCatching { client.send("ATAT1", timeoutMs = 1500) }
                runCatching { client.send("ATSTFF", timeoutMs = 1500) }
                val data = PassportReader.read(
                    _ui.value.selectedProfile.displayNameRu,
                    System.currentTimeMillis()
                ) { cmd, t -> client.send(cmd, timeoutMs = t) }
                getApplication<Application>().getSharedPreferences("passport_cache", android.content.Context.MODE_PRIVATE)
                    .edit().putString("data", Passport.serialize(data)).apply()
                _ui.update { it.copy(passport = data, passportStatus = "Обновлено") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(passportStatus = "Ошибка чтения: ${e.message ?: "неизвестно"}") }
            } finally {
                client.tolerateNoData = false
                // back to normal timing; CVT/AWC sensors re-send their own ATFCSH/FCSD/FCSM1/ATST pre-commands
                runCatching { client.send("ATST32", timeoutMs = 1500) }
                runCatching { client.restoreEngineEcuFilter() }
                _ui.update { it.copy(passportBusy = false) }
                if (wasPolling && _ui.value.connectionState is ConnectionState.Connected) startPolling()
            }
        }
    }

    fun passportExportText(): String =
        _ui.value.passport?.let { Passport.toText(it, com.chuikoff.obd.BuildConfig.VERSION_NAME) } ?: ""

    fun selectProfile(profile: VehicleProfile) {
        _ui.update { it.copy(selectedProfile = profile) }
        if (_ui.value.connectionState is ConnectionState.Connected) {
            restartPolling()
        }
    }

    fun setTransport(type: TransportType) {
        stopScan()
        _ui.update {
            it.copy(
                preferredTransport = type,
                devices = emptyList(),
                errorMessage = null,
                statusMessage = when (type) {
                    TransportType.CLASSIC_SPP ->
                        "Classic: сначала сопряги OBDII в настройках Bluetooth (пин 1234/0000), потом Поиск."
                    TransportType.BLE ->
                        "BLE: поиск адаптеров. Если таймаут — это часто Classic-донгл."
                }
            )
        }
        if (type == TransportType.CLASSIC_SPP) {
            refreshBondedClassic()
        }
    }

    fun refreshBondedClassic() {
        val bonded = classic.bondedDevices()
        _ui.update { state ->
            val merged = (bonded + state.devices)
                .distinctBy { it.address + it.transportType }
            state.copy(devices = merged, statusMessage = "Сопряжённые Classic: ${bonded.size}")
        }
    }

    fun startScan() {
        stopScan()
        _ui.update { it.copy(isScanning = true, errorMessage = null) }
        when (_ui.value.preferredTransport) {
            TransportType.CLASSIC_SPP -> {
                refreshBondedClassic()
                _ui.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = "Для Classic используйте уже сопряжённые устройства (настройки Bluetooth)."
                    )
                }
            }
            TransportType.BLE -> {
                bleScanCallback = ble.startScan { device ->
                    if (device.name.isNullOrBlank() && device.rssi == null) return@startScan
                    _ui.update { state ->
                        val list = (state.devices + device)
                            .distinctBy { it.address }
                            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                        state.copy(devices = list)
                    }
                }
                if (bleScanCallback == null) {
                    _ui.update {
                        it.copy(isScanning = false, errorMessage = "BLE сканер недоступен")
                    }
                } else {
                    viewModelScope.launch {
                        delay(12_000)
                        stopScan()
                    }
                }
            }
        }
    }

    fun stopScan() {
        ble.stopScan(bleScanCallback)
        bleScanCallback = null
        _ui.update { it.copy(isScanning = false) }
    }

    fun connect(device: DiscoveredDevice) {
        viewModelScope.launch {
            stopScan()
            stopPolling()
            reconnectUsedThisSession = false
            consecutiveHardFailures = 0
            cvtTempSmoother.reset()
            cvtLockup.reset()
            CvtPollPace.reset()
            lastCvtStatusLine = null
            lastCvtStatusArgb = null
            resetAmbient()
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    errorMessage = null,
                    statusMessage = "Подключение к ${device.name ?: device.address}…"
                )
            }
            publishLive(connected = false, status = "Подключение…")
            try {
                activeTransport?.disconnect()
                var transport: ObdTransport
                var usedType: TransportType
                when (device.transportType) {
                    TransportType.CLASSIC_SPP -> {
                        classic.connect(device.address)
                        transport = classic
                        usedType = TransportType.CLASSIC_SPP
                    }
                    TransportType.BLE -> {
                        try {
                            ble.connect(device.address)
                            transport = ble
                            usedType = TransportType.BLE
                        } catch (bleErr: Exception) {
                            _ui.update {
                                it.copy(
                                    statusMessage = "BLE не вышел (${bleErr.message}). Пробую Classic SPP…"
                                )
                            }
                            runCatching { ble.disconnect() }
                            classic.connect(device.address)
                            transport = classic
                            usedType = TransportType.CLASSIC_SPP
                        }
                    }
                }
                activeTransport = transport
                elm = Elm327Client(transport).also { watchLink(it) }
                elm!!.ensureInitialized()
                _ui.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        connectedAddress = device.address,
                        preferredTransport = usedType,
                        statusMessage = "ELM327 готов (${transport.name})",
                        dtcScanned = false,
                        dtcCodes = emptyList(),
                        dtcPending = emptyList()
                    )
                }
                publishLive(connected = true, status = "ELM327 готов")
                startMonitorService()
                startPolling()
            } catch (e: Exception) {
                runCatching { activeTransport?.disconnect() }
                activeTransport = null
                elm = null
                stopMonitorService()
                val msg = when {
                    e.message?.contains("Timed out", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Таймаут BLE. Этот OBDII скорее Classic: сопряги в настройках Bluetooth, " +
                            "переключись на Classic SPP и подключись. (${e.message})"
                    else -> e.message ?: "Ошибка подключения"
                }
                _ui.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        connectedAddress = null,
                        errorMessage = msg
                    )
                }
                publishLive(connected = false, status = msg)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopPolling()
            stopMonitorService()
            runCatching { activeTransport?.disconnect() }
            activeTransport = null
            elm = null
            consecutiveHardFailures = 0
            cvtTempSmoother.reset()
            cvtLockup.reset()
            CvtPollPace.reset()
            lastCvtStatusLine = null
            lastCvtStatusArgb = null
            resetAmbient()
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    connectedAddress = null,
                    isPolling = false,
                    readings = emptyList(),
                    dtcCodes = emptyList(),
                    dtcPending = emptyList(),
                    dtcStatus = null,
                    dtcError = null,
                    dtcScanned = false,
                    statusMessage = "Отключено"
                )
            }
            publishLive(connected = false, status = "Отключено")
        }
    }

    /** Mode 03 + Mode 07 DTC read. Temporarily pauses polling to avoid bus contention. */
    fun readDtcs() {
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(dtcError = "Нет подключения") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true, dtcError = null, dtcStatus = "Чтение DTC…") }
            val wasPolling = _ui.value.isPolling
            stopPolling()
            try {
                val (stored, rawStored) = client.readStoredDtcs()
                val (pending, rawPending) = runCatching { client.readPendingDtcs() }
                    .getOrElse { emptyList<String>() to "" }
                runCatching { client.restoreEngineEcuFilter() }
                val nodataHint = when {
                    stored.isEmpty() && pending.isEmpty() &&
                        (rawStored.contains("NODATA") || rawPending.contains("NODATA") ||
                            rawStored.isBlank()) ->
                        " (NODATA = кодов нет)"
                    else -> ""
                }
                _ui.update {
                    it.copy(
                        dtcCodes = stored,
                        dtcPending = pending,
                        dtcScanned = true,
                        dtcStatus = "Сохранённые: ${stored.size}, pending: ${pending.size}$nodataHint" +
                            if (rawStored.isNotBlank()) " | raw03=$rawStored" else "",
                        dtcError = null,
                        dtcBusy = false
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        dtcBusy = false,
                        dtcError = e.message ?: "Ошибка чтения DTC"
                    )
                }
            } finally {
                if (wasPolling && _ui.value.connectionState is ConnectionState.Connected) {
                    startPolling()
                }
            }
        }
    }

    /**
     * Mode 04 clear. [confirmed] must be true after AlertDialog.
     */
    fun clearDtcs(confirmed: Boolean) {
        if (!confirmed) return
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(dtcError = "Нет подключения") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true, dtcError = null, dtcStatus = "Сброс DTC (Mode 04)…") }
            val wasPolling = _ui.value.isPolling
            stopPolling()
            try {
                val raw = client.clearDtcs()
                val cleaned = Elm327Parser.clean(raw)
                val hex = Elm327Parser.hexDigits(raw)
                if (hex.contains("7F") && !hex.contains("44")) {
                    runCatching { client.restoreEngineEcuFilter() }
                    _ui.update {
                        it.copy(
                            dtcStatus = "Сброс отклонён ЭБУ (условия). Ответ: $cleaned",
                            dtcError = null,
                            dtcBusy = false
                        )
                    }
                } else if (hex.contains("44")) {
                    val (stored, _) = runCatching { client.readStoredDtcs() }
                        .getOrElse { emptyList<String>() to "" }
                    val (pending, _) = runCatching { client.readPendingDtcs() }
                        .getOrElse { emptyList<String>() to "" }
                    runCatching { client.restoreEngineEcuFilter() }
                    _ui.update {
                        it.copy(
                            dtcCodes = stored,
                            dtcPending = pending,
                            dtcScanned = true,
                            dtcStatus = "DTC сброшены. Ответ: $cleaned",
                            dtcError = null,
                            dtcBusy = false
                        )
                    }
                } else {
                    val (stored, _) = runCatching { client.readStoredDtcs() }
                        .getOrElse { emptyList<String>() to "" }
                    val (pending, _) = runCatching { client.readPendingDtcs() }
                        .getOrElse { emptyList<String>() to "" }
                    runCatching { client.restoreEngineEcuFilter() }
                    _ui.update {
                        it.copy(
                            dtcCodes = stored,
                            dtcPending = pending,
                            dtcScanned = true,
                            dtcStatus = "Ответ сброса неоднозначен: $cleaned (коды перечитаны)",
                            dtcError = null,
                            dtcBusy = false
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        dtcBusy = false,
                        dtcError = e.message ?: "Ошибка сброса DTC"
                    )
                }
            } finally {
                if (wasPolling && _ui.value.connectionState is ConnectionState.Connected) {
                    startPolling()
                }
            }
        }
    }

    private fun restartPolling() {
        stopPolling()
        if (_ui.value.connectionState is ConnectionState.Connected) {
            startPolling()
        }
    }

    private var pollTick = 0

    private fun startPolling() {
        pollJob?.cancel()
        pollTick = 0
        CvtPollPace.reset()
        pollJob = viewModelScope.launch {
            _ui.update { it.copy(isPolling = true) }
            startMonitorService()
            while (isActive) {
                val client = elm
                if (client == null) {
                    delay(1000)
                    continue
                }
                try {
                    pollTick++
                    val profile = _ui.value.selectedProfile
                    val outlander = profile.id == OUTLANDER_PROFILE_ID
                    val all = profile.sensors()
                    // Outlander: CVT every tick; coolant every 2nd; ambient every 3rd.
                    // CVT-only ticks stay on TCM (skipPostRestore) → ~0.7–1.5 s lock-up refresh.
                    val sensors = if (outlander) {
                        val want = linkedSetOf("cvt_temp")
                        if (pollTick % 2 == 0) want += "coolant_temp"
                        if (pollTick % 3 == 0) want += "ambient_temp"
                        all.filter { it.id in want }.sortedBy {
                            when (it.id) { "cvt_temp" -> 0; "coolant_temp" -> 1; else -> 2 }
                        }
                    } else all
                    val cvtOnly = outlander && sensors.size == 1 && sensors[0].id == "cvt_temp"
                    CvtPollPace.skipPostRestore = cvtOnly

                    val byId = _ui.value.readings.associateBy { it.sensorId }.toMutableMap()
                    for (sensor in sensors) {
                        val reading = try {
                            sensor.read(client)
                        } catch (e: Exception) {
                            SensorReading(
                                sensorId = sensor.id,
                                displayName = sensor.displayNameRu,
                                value = null,
                                unit = sensor.unit,
                                error = e.message ?: "Ошибка чтения"
                            )
                        }
                        val applied = when {
                            reading.sensorId == "cvt_temp" -> applyCvt(reading)
                            reading.sensorId == "ambient_temp" && outlander ->
                                applyAmbientFilter(client, reading)
                            else -> reading
                        }
                        byId[applied.sensorId] = applied
                        // Push CVT/lock-up to UI immediately — don't wait for coolant/ambient
                        if (applied.sensorId == "cvt_temp") {
                            val snap = orderReadings(all, byId)
                            _ui.update {
                                it.copy(readings = snap, statusMessage = "Опрос датчиков")
                            }
                            publishLiveFromReadings(snap, connected = true)
                        }
                    }
                    consecutiveHardFailures = 0
                    CvtPollPace.skipPostRestore = false
                    val smoothed = orderReadings(all, byId)
                    _ui.update {
                        it.copy(readings = smoothed, statusMessage = "Опрос датчиков")
                    }
                    publishLiveFromReadings(smoothed, connected = true)
                    // Short gap after CVT-only; a bit more after a full mix so the clone can breathe
                    delay(when {
                        cvtOnly -> 250L
                        outlander -> 500L
                        else -> 800L
                    })
                } catch (e: Exception) {
                    // Soft recover: keep loop alive, but do not fake/hold stale readings
                    CvtPollPace.reset()
                    consecutiveHardFailures++
                    val msg = e.message ?: "сбой цикла"
                    _ui.update {
                        it.copy(
                            statusMessage = "ошибка опроса, переподключение… ($msg)",
                            // Live-only: do not keep prior cycle values after a failed poll
                            readings = emptyList()
                        )
                    }
                    publishLive(
                        connected = true,
                        status = "ошибка опроса, переподключение…",
                        coolantC = null,
                        cvtC = null,
                        ambientC = null,
                        ambientNote = null
                    )
                    runCatching { client.flushReadBuffer() }
                    runCatching { client.restoreEngineEcuFilter() }

                    val deadSocket = msg.contains("сокет мёртв", ignoreCase = true) ||
                        msg.contains("BT сокет", ignoreCase = true) ||
                        (activeTransport is BluetoothClassicTransport &&
                            !(activeTransport as BluetoothClassicTransport).isSocketAlive())

                    if ((consecutiveHardFailures >= HARD_FAIL_RECONNECT_THRESHOLD || deadSocket) &&
                        !reconnectUsedThisSession
                    ) {
                        reconnectUsedThisSession = true
                        _ui.update { it.copy(statusMessage = "Переподключение к адаптеру…") }
                        val ok = reconnectSameDevice()
                        if (ok) {
                            consecutiveHardFailures = 0
                            _ui.update { it.copy(statusMessage = "Переподключено — опрос продолжен") }
                        } else {
                            _ui.update {
                                it.copy(statusMessage = "Переподключение не удалось: $msg")
                            }
                        }
                    }
                    delay(2000L)
                }
            }
        }
    }

    /** Keep profile sensor order when merging a partial poll into the last full snapshot. */
    private fun orderReadings(
        all: List<com.chuikoff.obd.sensors.SensorDefinition>,
        byId: Map<String, SensorReading>
    ): List<SensorReading> = all.mapNotNull { byId[it.id] }


    /** Disconnect + connect last address once. Returns true on success. */
    private suspend fun reconnectSameDevice(): Boolean {
        val address = _ui.value.connectedAddress ?: return false
        val type = _ui.value.preferredTransport
        return try {
            runCatching { activeTransport?.disconnect() }
            elm = null
            when (type) {
                TransportType.CLASSIC_SPP -> {
                    classic.connect(address)
                    activeTransport = classic
                }
                TransportType.BLE -> {
                    try {
                        ble.connect(address)
                        activeTransport = ble
                    } catch (_: Exception) {
                        classic.connect(address)
                        activeTransport = classic
                        _ui.update { it.copy(preferredTransport = TransportType.CLASSIC_SPP) }
                    }
                }
            }
            val transport = activeTransport ?: return false
            elm = Elm327Client(transport).also { watchLink(it) }
            elm!!.ensureInitialized()
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.Connected,
                    connectedAddress = address,
                    statusMessage = "ELM327 готов после reconnect (${transport.name})"
                )
            }
            startMonitorService()
            true
        } catch (e: Exception) {
            elm = null
            activeTransport = null
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    errorMessage = "Reconnect fail: ${e.message}"
                )
            }
            stopMonitorService()
            false
        }
    }

    /**
     * Raw AWC byte 8 → value the dashboard would show. SAE 010D is requested only while raw is
     * above the shown value (the only case where speed matters), at most every 5 s; the engine
     * header is already restored by the AWC sensor, so it is a single short request.
     */
    private var lastCvtStatusLine: String? = null
    private var lastCvtStatusArgb: Long? = null

    private fun applyCvt(r: SensorReading): SensorReading {
        val smoothed = cvtTempSmoother.apply(r)
        val sample = if (smoothed.cvtPrimaryRpm != null && smoothed.cvtEngineRpm != null) {
            CvtLockupDetector.Sample(
                smoothed.cvtPrimaryRpm!!,
                smoothed.cvtSecondaryRpm ?: 0,
                smoothed.cvtEngineRpm!!,
                smoothed.cvtEcuSlipByte
            )
        } else null
        if (smoothed.isStale && sample == null) {
            return smoothed.copy(statusLine = lastCvtStatusLine, statusArgb = lastCvtStatusArgb)
        }
        val out = cvtLockup.update(sample)
        lastCvtStatusLine = out.cardLine
        lastCvtStatusArgb = out.state.argb
        return smoothed.copy(
            statusLine = out.cardLine,
            statusArgb = out.state.argb,
            detail = listOfNotNull(smoothed.detail, out.techLine, CvtLockupDetector.EXPLAIN_RU)
                .joinToString("\n")
        )
    }

    private suspend fun applyAmbientFilter(client: Elm327Client, r: SensorReading): SensorReading {
        val raw = r.rawValue ?: r.value
        val now = System.currentTimeMillis()
        if (raw == null) {
            val shown = ambientFilter.displayedC
            if (shown != null && ambientHeld < AMBIENT_MAX_HELD) {
                ambientHeld++
                return r.copy(
                    value = shown,
                    isStale = true,
                    detail = "датчик: — (кадр битый $ambientHeld/$AMBIENT_MAX_HELD, держим панель)"
                )
            }
            return r
        }
        ambientHeld = 0
        val speed = if (ambientFilter.needsSpeed(raw)) speedReader.speedKmh(client, now) else speedReader.cached(now)
        val out = ambientFilter.update(raw, now, speed)
        val rawText = "датчик: %.0f °C".format(raw)
        val suffix = when (out.state) {
            AmbientDisplayFilter.State.TRACKING -> " (сырой)"
            AmbientDisplayFilter.State.HOLDING -> " (сырой, греется на стоянке)"
            AmbientDisplayFilter.State.RISING -> " (сырой; панель догоняет ≤1 °C/мин в движении)"
        }
        val speedText = speed?.let { " · $it км/ч" } ?: ""
        val restoredText = if (out.restored && out.state != AmbientDisplayFilter.State.TRACKING) " · с прошлой поездки" else ""
        return r.copy(
            value = out.displayC,
            rawValue = raw,
            detail = rawText + suffix + speedText + restoredText
        )
    }

    private var linkJob: Job? = null
    private fun watchLink(c: Elm327Client) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            c.linkNote.collect { n -> _ui.update { it.copy(linkNote = n) } }
        }
    }

    private fun resetAmbient() {
        ambientFilter.reset()
        speedReader.reset()
        ambientHeld = 0
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _ui.update { it.copy(isPolling = false) }
    }

    private fun startMonitorService() {
        runCatching { ObdMonitorService.start(getApplication()) }
    }

    private fun stopMonitorService() {
        runCatching { ObdMonitorService.stop(getApplication()) }
    }

    private fun publishLiveFromReadings(readings: List<SensorReading>, connected: Boolean) {
        fun byId(id: String) = readings.find { it.sensorId == id }
        val coolant = byId("coolant_temp")
        val cvt = byId("cvt_temp")
        val ambient = byId("ambient_temp")
        publishLive(
            connected = connected,
            status = if (connected) "Опрос датчиков" else "Отключено",
            coolantC = coolant?.value,
            cvtC = cvt?.value,
            ambientC = ambient?.value,
            ambientNote = ambient?.note,
            cvtLockup = cvt?.statusLine
        )
    }

    private fun publishLive(
        connected: Boolean,
        status: String,
        coolantC: Double? = ObdLiveData.snapshot.value.coolantC,
        cvtC: Double? = ObdLiveData.snapshot.value.cvtC,
        ambientC: Double? = ObdLiveData.snapshot.value.ambientC,
        ambientNote: String? = ObdLiveData.snapshot.value.ambientNote,
        cvtLockup: String? = ObdLiveData.snapshot.value.cvtLockup
    ) {
        ObdLiveData.update(
            connected = connected,
            statusText = status,
            coolantC = coolantC,
            cvtC = cvtC,
            ambientC = ambientC,
            ambientNote = ambientNote,
            cvtLockup = cvtLockup,
            prefs = livePrefs
        )
    }

    override fun onCleared() {
        stopScan()
        stopPolling()
        stopMonitorService()
        ambientFilter.reset()
        try {
            // transports closed by disconnect paths
        } finally {
            super.onCleared()
        }
    }

    companion object {
        private const val HARD_FAIL_RECONNECT_THRESHOLD = 5
        private const val K_CVT_FORMULA = "formula"
        private const val K_CVT_DEBUG_EXTRA = "debug_extra"
        private const val K_AMB_VALUE = "shown_c"
        private const val K_AMB_AT = "shown_at_ms"
        private const val AMBIENT_MAX_HELD = 10
        private const val OUTLANDER_PROFILE_ID = "mitsubishi_outlander_gf_gg"
    }
}
