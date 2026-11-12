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
import com.chuikoff.obd.polling.Poller
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private var activeTransport: ObdTransport? = null
    private var elm: Elm327Client? = null
    private var bleScanCallback: ScanCallback? = null
    private var connectJob: Job? = null
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

    private val poller = Poller(viewModelScope, object : Poller.Host {
        override val client: Elm327Client? get() = elm
        override val profile: VehicleProfile get() = _ui.value.selectedProfile
        override fun transportAlive(): Boolean = activeTransport?.isConnected?.value == true
        override fun previousReadings(): List<SensorReading> = _ui.value.readings
        override suspend fun process(client: Elm327Client, reading: SensorReading, outlander: Boolean): SensorReading =
            when {
                reading.sensorId == "cvt_temp" -> applyCvt(reading)
                reading.sensorId == "ambient_temp" && outlander -> applyAmbientFilter(client, reading)
                else -> reading
            }
        override fun publish(readings: List<SensorReading>) {
            _ui.update { it.copy(readings = readings, statusMessage = "Опрос датчиков") }
            publishLiveFromReadings(readings, connected = true)
        }
        override fun tickFailed(message: String) {
            _ui.update {
                it.copy(
                    statusMessage = "ошибка опроса, переподключение… ($message)",
                    // Live-only: do not keep prior cycle values after a failed poll
                    readings = emptyList()
                )
            }
            publishLive(connected = true, status = "ошибка опроса, переподключение…",
                coolantC = null, cvtC = null, ambientC = null, ambientNote = null, cvtLockup = null)
        }
        override suspend fun reconnect(attempt: Int, reason: String): Boolean = reconnectSameDevice(attempt, reason)
        override fun reconnectGaveUp(reason: String) = onReconnectGaveUp(reason)
        override fun pollingChanged(running: Boolean) {
            _ui.update { it.copy(isPolling = running) }
            if (running) startMonitorService()
        }
        override fun canResume(): Boolean = _ui.value.connectionState is ConnectionState.Connected
    })

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
        // «Стоп» in the foreground-service notification really disconnects
        viewModelScope.launch {
            ObdMonitorService.stopRequests.collect { disconnect() }
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
        if (busExclusive()) return
        _ui.update { it.copy(passportBusy = true, passportStatus = "Чтение паспорта…") }
        viewModelScope.launch {
            try {
                poller.paused(client) {
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
                    } finally {
                        client.tolerateNoData = false
                        // back to normal timing; CVT/AWC sensors re-send their own ATFCSH/FCSD/FCSM1 pre-commands
                        runCatching { client.send("ATST32", timeoutMs = 1500) }
                        runCatching { client.restoreEngineEcuFilter() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(passportStatus = "Ошибка чтения: ${e.message ?: "неизвестно"}") }
            } finally {
                _ui.update { it.copy(passportBusy = false) }
            }
        }
    }

    /** DTC and passport reads share the bus: only one at a time (the buttons of the other tab stay enabled). */
    private fun busExclusive(): Boolean = _ui.value.dtcBusy || _ui.value.passportBusy

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
        val bonded = try {
            classic.bondedDevices()
        } catch (e: SecurityException) {
            _ui.update { it.copy(isScanning = false, errorMessage = PERMISSION_HINT) }
            return
        }
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
                bleScanCallback = try { ble.startScan { device ->
                    if (device.name.isNullOrBlank() && device.rssi == null) return@startScan
                    _ui.update { state ->
                        val list = (state.devices + device)
                            .distinctBy { it.address }
                            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                        state.copy(devices = list)
                    }
                } } catch (e: SecurityException) {
                    _ui.update { it.copy(isScanning = false, errorMessage = PERMISSION_HINT) }
                    return
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
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            stopScan()
            poller.stop()
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
                poller.start()
            } catch (e: CancellationException) {
                // «Отменить» while connecting: close whatever socket the blocking connect opened
                withContext(NonCancellable) {
                    runCatching { classic.disconnect() }
                    runCatching { ble.disconnect() }
                }
                throw e
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
                    else -> permissionAware(e)
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
        connectJob?.cancel()
        viewModelScope.launch {
            poller.stop()
            stopMonitorService()
            // both: a reconnect cancelled mid-way may have opened the other transport
            runCatching { classic.disconnect() }
            runCatching { ble.disconnect() }
            activeTransport = null
            elm = null
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

    /** Mode 03 + Mode 07 DTC read. Polling is paused (joined) for the duration. */
    fun readDtcs() {
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(dtcError = "Нет подключения") }
            return
        }
        if (busExclusive()) return
        _ui.update { it.copy(dtcBusy = true, dtcError = null, dtcStatus = "Чтение DTC…") }
        viewModelScope.launch {
            try {
                poller.paused(client) {
                    val (stored, rawStored) = client.readStoredDtcs()
                    val (pending, rawPending) = try {
                        client.readPendingDtcs()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList<String>() to ""
                    }
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
                            dtcError = null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(dtcError = e.message ?: "Ошибка чтения DTC") }
            } finally {
                _ui.update { it.copy(dtcBusy = false) }
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
        if (busExclusive()) return
        _ui.update { it.copy(dtcBusy = true, dtcError = null, dtcStatus = "Сброс DTC (Mode 04)…") }
        viewModelScope.launch {
            try {
                poller.paused(client) {
                    val raw = client.clearDtcs()
                    val cleaned = Elm327Parser.clean(raw)
                    val hex = Elm327Parser.hexDigits(raw)
                    if (hex.contains("7F") && !hex.contains("44")) {
                        runCatching { client.restoreEngineEcuFilter() }
                        _ui.update {
                            it.copy(dtcStatus = "Сброс отклонён ЭБУ (условия). Ответ: $cleaned", dtcError = null)
                        }
                    } else {
                        val (stored, _) = runCatching { client.readStoredDtcs() }
                            .getOrElse { emptyList<String>() to "" }
                        val (pending, _) = runCatching { client.readPendingDtcs() }
                            .getOrElse { emptyList<String>() to "" }
                        runCatching { client.restoreEngineEcuFilter() }
                        val status = if (hex.contains("44")) "DTC сброшены. Ответ: $cleaned"
                        else "Ответ сброса неоднозначен: $cleaned (коды перечитаны)"
                        _ui.update {
                            it.copy(
                                dtcCodes = stored,
                                dtcPending = pending,
                                dtcScanned = true,
                                dtcStatus = status,
                                dtcError = null
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(dtcError = e.message ?: "Ошибка сброса DTC") }
            } finally {
                _ui.update { it.copy(dtcBusy = false) }
            }
        }
    }

    private fun restartPolling() {
        if (busExclusive()) return // the exclusive job resumes polling with the new profile itself
        viewModelScope.launch {
            poller.stop()
            if (_ui.value.connectionState is ConnectionState.Connected) poller.start()
        }
    }

    /** Disconnect + connect the last address (called by [Poller] after a link drop). */
    private suspend fun reconnectSameDevice(attempt: Int, reason: String): Boolean {
        val address = _ui.value.connectedAddress ?: return false
        val type = _ui.value.preferredTransport
        _ui.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                statusMessage = "Переподключение к адаптеру… (попытка $attempt из ${Poller.MAX_RECONNECT_ATTEMPTS}): $reason"
            )
        }
        publishLive(connected = false, status = "Переподключение…")
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        runCatching { ble.disconnect() }
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
                    errorMessage = null,
                    statusMessage = "ELM327 готов после переподключения (${transport.name})"
                )
            }
            publishLive(connected = true, status = "ELM327 готов")
            startMonitorService()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            elm = null
            activeTransport = null
            _ui.update {
                it.copy(
                    statusMessage = "Переподключение не удалось (попытка $attempt): ${permissionAware(e)} — " +
                        "повтор через ${Poller.reconnectBackoffMs(attempt) / 1000} с"
                )
            }
            false
        }
    }

    private fun onReconnectGaveUp(reason: String) {
        elm = null
        activeTransport = null
        cvtTempSmoother.reset()
        cvtLockup.reset()
        lastCvtStatusLine = null
        lastCvtStatusArgb = null
        _ui.update {
            it.copy(
                connectionState = ConnectionState.Disconnected,
                isPolling = false,
                readings = emptyList(),
                errorMessage = "Связь с адаптером потеряна ($reason). Подключись заново.",
                statusMessage = "Отключено"
            )
        }
        publishLive(connected = false, status = "Связь с адаптером потеряна")
        stopMonitorService()
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
            cvtLockup = cvtLockup
        )
    }

    override fun onCleared() {
        stopScan()
        poller.cancel()
        stopMonitorService()
        ambientFilter.reset()
        // Android Auto must not keep showing «Подключено» with the last values
        publishLive(connected = false, status = "Отключено", coolantC = null, cvtC = null,
            ambientC = null, ambientNote = null, cvtLockup = null)
        super.onCleared()
    }

    /** Readable reason; missing «Устройства поблизости» permission gets an actionable hint. */
    private fun permissionAware(e: Throwable): String =
        if (e is SecurityException) PERMISSION_HINT else e.message ?: "Ошибка подключения"

    companion object {
        const val PERMISSION_HINT =
            "Нет разрешения «Устройства поблизости» (Bluetooth). Разреши его: Настройки → Приложения → OBD → Разрешения."
        private const val K_CVT_FORMULA = "formula"
        private const val K_CVT_DEBUG_EXTRA = "debug_extra"
        private const val K_AMB_VALUE = "shown_c"
        private const val K_AMB_AT = "shown_at_ms"
        private const val AMBIENT_MAX_HELD = 10
    }
}
