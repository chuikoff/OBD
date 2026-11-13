package com.chuikoff.obd.viewmodel

import android.app.Application
import android.bluetooth.le.ScanCallback
import com.chuikoff.obd.alerts.AlertEngine
import com.chuikoff.obd.alerts.AlertNotifier
import com.chuikoff.obd.alerts.AlertSettings
import com.chuikoff.obd.connection.AutoConnectPolicy
import com.chuikoff.obd.connection.AutoTrigger
import com.chuikoff.obd.connection.IgnitionWatch
import com.chuikoff.obd.connection.LastAdapter
import com.chuikoff.obd.connection.BleTransport
import com.chuikoff.obd.sensors.CvtOilDegradation
import com.chuikoff.obd.sensors.EngineExtras
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
import com.chuikoff.obd.sensors.CretaAtf
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
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val dtcReport: com.chuikoff.obd.diag.DtcReport? = null,
    val dtcLog: List<com.chuikoff.obd.diag.DtcLogLine> = emptyList(),
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
    val passportStatus: String? = null,
    // 1.14: alerts, auto-connect, CVT oil degradation (experimental)
    val alertSettings: AlertSettings = AlertSettings(),
    val autoConnect: Boolean = true,
    val autoConnectOnBluetooth: Boolean = true,
    val lastAdapter: LastAdapter? = null,
    /** Last successful 2110 read (persisted). */
    val cvtOil: CvtOilDegradation.Result? = null,
    /** Last 2110 attempt, also failed ones (raw for «Диагностика»). */
    val cvtOilAttempt: CvtOilDegradation.Result? = null,
    val cvtOilBusy: Boolean = false,
    /** «Сохранить диагностику» in progress / last result. */
    val diagBusy: Boolean = false,
    val diagStatus: String? = null,
    val diagSaved: com.chuikoff.obd.diag.DiagFile.Saved? = null,
    val cvtOilStatus: String? = null
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
}

/**
 * Connection + polling for the whole process (not tied to the Activity), so Android Auto and the
 * Bluetooth auto-connect receiver can start a session without the phone UI. One instance: [get].
 */
class ObdController private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
    private val settingsPrefs = app.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)

    private val alertEngine = AlertEngine()
    private val lastAlertSampleTs = mutableMapOf<String, Long>()
    private val engineExtras = EngineExtras()
    private val ignition = IgnitionWatch()
    private var autoJob: Job? = null
    private var lastAutoAttemptMs: Long? = null
    private var manualDisconnectAtMs: Long? = null
    private var connectedAtMs = 0L
    private var oilReadThisConnection = false

    private val poller = Poller(scope, object : Poller.Host {
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
            onPublished(readings)
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
                coolantC = null, cvtC = null, ambientC = null, ambientNote = null, cvtLockup = null, engine = null)
            checkIgnition(ecuAnswered = false)
        }
        override suspend fun reconnect(attempt: Int, reason: String): Boolean = reconnectSameDevice(attempt, reason)
        override fun reconnectGaveUp(reason: String) = onReconnectGaveUp(reason)
        override fun pollingChanged(running: Boolean) {
            _ui.update { it.copy(isPolling = running) }
            if (running) startMonitorService()
        }
        override fun canResume(): Boolean = _ui.value.connectionState is ConnectionState.Connected
        override fun extraSensors() = engineExtras.sensors()
    })

    init {
        val formula = runCatching {
            CvtFormula.valueOf(cvtPrefs.getString(K_CVT_FORMULA, CvtFormula.OUT3_POLY.name)!!)
        }.getOrDefault(CvtFormula.OUT3_POLY)
        val extra = cvtPrefs.getBoolean(K_CVT_DEBUG_EXTRA, false)
        CvtSettings.formula = formula
        CvtSettings.debugExtraRequests = extra
        val cachedPassport = Passport.deserialize(
            app.getSharedPreferences("passport_cache", android.content.Context.MODE_PRIVATE)
                .getString("data", null)
        )
        _ui.update {
            it.copy(
                cvtFormula = formula, cvtDebugExtra = extra, passport = cachedPassport,
                alertSettings = loadAlertSettings(),
                autoConnect = settingsPrefs.getBoolean(K_AUTO, true),
                autoConnectOnBluetooth = settingsPrefs.getBoolean(K_AUTO_BT, true),
                lastAdapter = loadLastAdapter(),
                cvtOil = CvtOilDegradation.deserialize(settingsPrefs.getString(K_OIL, null))
            )
        }
        scope.launch {
            CvtDebugLog.entries.collect { list -> _ui.update { it.copy(cvtLog = list) } }
        }
        // «Стоп» in the foreground-service notification really disconnects
        scope.launch {
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

    init {
        // connection log for the diagnostics file: every status / error / state change
        scope.launch {
            var last: Triple<String, String?, String?>? = null
            _ui.collect { st ->
                val cur = Triple(st.connectionState::class.simpleName ?: "?", st.statusMessage, st.errorMessage)
                if (cur != last) {
                    last = cur
                    com.chuikoff.obd.diag.DiagLog.event(
                        "состояние=${cur.first}; статус=${cur.second ?: "—"}" + (cur.third?.let { "; ОШИБКА=$it" } ?: "")
                    )
                }
            }
        }
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
            r(CretaAtf.SENSOR_ID)?.let { append(", АКПП (на экране) %.0f °C".format(it)) }
            CretaAtf.selected?.let { append("\nАКПП источник: ${it.label} — ${it.sourceRu}") }
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
        scope.launch {
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
                        app.getSharedPreferences("passport_cache", android.content.Context.MODE_PRIVATE)
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
    private fun busExclusive(): Boolean =
        _ui.value.dtcBusy || _ui.value.passportBusy || _ui.value.cvtOilBusy || _ui.value.diagBusy

    fun passportExportText(): String =
        _ui.value.passport?.let { Passport.toText(it, com.chuikoff.obd.BuildConfig.VERSION_NAME) } ?: ""

    fun selectProfile(profile: VehicleProfile) {
        _ui.update { it.copy(selectedProfile = profile) }
        CretaAtf.reset()
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
                    scope.launch {
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
        autoJob?.cancel()
        autoJob = null
        connectJob?.cancel()
        connectJob = scope.launch { doConnect(device, auto = false) }
    }

    private suspend fun doConnect(device: DiscoveredDevice, auto: Boolean) {
        run {
            stopScan()
            poller.stop()
            cvtTempSmoother.reset()
            cvtLockup.reset()
            CvtPollPace.reset()
            CretaAtf.reset()
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
                engineExtras.reset(readSupportedPids(elm!!))
                val last = LastAdapter(device.address, device.name, usedType)
                saveLastAdapter(last)
                alertEngine.resetTrip()
                lastAlertSampleTs.clear()
                oilReadThisConnection = false
                connectedAtMs = System.currentTimeMillis()
                ignition.start(connectedAtMs)
                _ui.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        connectedAddress = device.address,
                        preferredTransport = usedType,
                        lastAdapter = last,
                        errorMessage = null,
                        statusMessage = "ELM327 готов (${transport.name})" + if (auto) " — автоподключение" else "",
                        dtcScanned = false,
                        dtcReport = null
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
                        // auto attempts: no red banner, the auto-connect status line explains
                        errorMessage = if (auto) null else msg,
                        statusMessage = if (auto) "Автоподключение: $msg" else it.statusMessage
                    )
                }
                publishLive(connected = false, status = msg)
            }
        }
    }

    fun disconnect() {
        manualDisconnectAtMs = System.currentTimeMillis()
        autoJob?.cancel()
        autoJob = null
        disconnectInternal()
    }

    private fun disconnectInternal(status: String = "Отключено", error: String? = null) {
        connectJob?.cancel()
        ignition.stop()
        scope.launch {
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
            CretaAtf.reset()
            lastCvtStatusLine = null
            lastCvtStatusArgb = null
            resetAmbient()
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    connectedAddress = null,
                    isPolling = false,
                    readings = emptyList(),
                    dtcReport = null,
                    dtcStatus = null,
                    dtcError = null,
                    dtcScanned = false,
                    statusMessage = status,
                    errorMessage = error
                )
            }
            publishLive(connected = false, status = "Отключено")
        }
    }

    /** Quick OBD check (0101 + 03 + 07 + 0A, all ECUs). Polling is paused (joined) for the duration. */
    fun readDtcs() = runDtc(full = false)

    /** Full check: quick + UDS/KWP DTC read of every known module (engine, TCM, ABS, SRS, cluster, BCM…). */
    fun readDtcsFull() = runDtc(full = true)

    private fun runDtc(full: Boolean) {
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(dtcError = "Нет подключения") }
            return
        }
        if (busExclusive()) return
        val log = mutableListOf<com.chuikoff.obd.diag.DtcLogLine>()
        _ui.update {
            it.copy(dtcBusy = true, dtcError = null, dtcLog = emptyList(),
                dtcStatus = if (full) "Полная проверка: опрос всех блоков (~1 мин)…" else "Чтение ошибок OBD…")
        }
        scope.launch {
            try {
                poller.paused(client) {
                    val scanner = com.chuikoff.obd.diag.DtcScanner(client) { line ->
                        log += line
                        _ui.update { it.copy(dtcLog = log.toList()) }
                    }
                    val report = if (full) scanner.full { p -> _ui.update { it.copy(dtcStatus = "Полная проверка: $p…") } }
                    else scanner.quick()
                    _ui.update {
                        it.copy(dtcReport = report, dtcScanned = true, dtcStatus = report.summaryRu(), dtcError = null,
                            dtcLog = log.toList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(dtcError = e.message ?: "Ошибка чтения DTC", dtcLog = log.toList()) }
            } finally {
                _ui.update { it.copy(dtcBusy = false) }
            }
        }
    }

    fun dtcExportText(): String {
        val s = _ui.value
        return buildString {
            append("OBD ${com.chuikoff.obd.BuildConfig.VERSION_NAME} — ошибки (DTC)\n")
            append("Профиль: ${s.selectedProfile.displayNameRu}\n")
            s.dtcReport?.let { r ->
                append(r.summaryRu()).append("\n")
                r.mil.forEach { append("MIL ${it.ecuId ?: "?"}: ${if (it.milOn) "ГОРИТ" else "нет"}, кодов ${it.dtcCount}\n") }
                r.allCodes.forEach { c ->
                    append("${c.code} — ${c.ecuName} — ${c.kind.titleRu}${c.statusRu?.let { " — $it" } ?: ""}\n")
                }
                r.modules?.forEach { m ->
                    append("[${m.module.tx}→${m.module.rx}] ${m.module.name}: ")
                    append(if (!m.answered) "не ответил" else "${m.protocol ?: "—"}, кодов ${m.codes.size}" +
                        (if (m.ignored > 0) ", неактивных ${m.ignored}" else ""))
                    m.note?.let { append(" ($it)") }
                    append("\n")
                }
            }
            append("\nRAW (${s.dtcLog.size}):\n")
            s.dtcLog.forEach { append(it.toText()).append("\n") }
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
        val log = mutableListOf<com.chuikoff.obd.diag.DtcLogLine>()
        scope.launch {
            try {
                poller.paused(client) {
                    val raw = client.clearDtcs()
                    log += com.chuikoff.obd.diag.DtcLogLine(System.currentTimeMillis(), null, "04", Elm327Parser.visibleRaw(raw))
                    val cleaned = Elm327Parser.clean(raw)
                    val hex = Elm327Parser.hexDigits(raw)
                    if (hex.contains("7F") && !hex.contains("44")) {
                        runCatching { client.restoreEngineEcuFilter() }
                        _ui.update {
                            it.copy(dtcStatus = "Сброс отклонён ЭБУ (условия). Ответ: $cleaned", dtcError = null,
                                dtcLog = log.toList())
                        }
                    } else {
                        val report = runCatching {
                            com.chuikoff.obd.diag.DtcScanner(client) { log += it }.quick()
                        }.getOrNull()
                        val status = if (hex.contains("44")) "DTC сброшены. Ответ: $cleaned"
                        else "Ответ сброса неоднозначен: $cleaned (коды перечитаны)"
                        _ui.update {
                            it.copy(
                                dtcReport = report ?: it.dtcReport,
                                dtcScanned = report != null || it.dtcScanned,
                                dtcStatus = status + (report?.let { r -> "\n" + r.summaryRu() } ?: ""),
                                dtcError = null,
                                dtcLog = log.toList()
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

    /**
     * «Сохранить диагностику»: if connected, pause polling and actively read adapter info, responding
     * ECUs / supported PIDs and (6AT profiles) a wide gearbox sweep; then write ONE .txt with all logs
     * (DTC, gearbox/CVT, ELM traffic, connection events, readings, passport) and open the share sheet.
     */
    fun saveDiagnostics() {
        if (_ui.value.diagBusy) return
        if (busExclusive()) { _ui.update { it.copy(diagStatus = "Подождите: идёт другое чтение") }; return }
        _ui.update { it.copy(diagBusy = true, diagStatus = "Сбор диагностики…") }
        scope.launch {
            try {
                val client = elm
                var adapter: List<com.chuikoff.obd.diag.DiagSweep.Line> = emptyList()
                var gearbox: List<com.chuikoff.obd.diag.DiagSweep.Line> = emptyList()
                if (client != null && _ui.value.connectionState is ConnectionState.Connected) {
                    poller.paused(client) {
                        val sweep = com.chuikoff.obd.diag.DiagSweep(client) { p -> _ui.update { it.copy(diagStatus = "Сбор диагностики: $p…") } }
                        adapter = sweep.adapterInfo()
                        if (_ui.value.selectedProfile.id != Poller.OUTLANDER_PROFILE_ID) gearbox = sweep.gearbox()
                    }
                }
                val text = diagnosticsText(adapter, gearbox)
                val name = com.chuikoff.obd.diag.DiagFile.fileName()
                val saved = withContext(Dispatchers.IO) { com.chuikoff.obd.diag.DiagFile.save(app, name, text) }
                _ui.update { it.copy(diagSaved = saved, diagStatus = "Сохранено: ${saved.shownPath}") }
                runCatching { com.chuikoff.obd.diag.DiagFile.share(app, saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(diagStatus = "Не удалось сохранить: ${e.message}") }
            } finally {
                _ui.update { it.copy(diagBusy = false) }
            }
        }
    }

    fun shareDiagnostics() {
        _ui.value.diagSaved?.let { runCatching { com.chuikoff.obd.diag.DiagFile.share(app, it) } }
    }

    private fun diagnosticsText(
        adapter: List<com.chuikoff.obd.diag.DiagSweep.Line>,
        gearbox: List<com.chuikoff.obd.diag.DiagSweep.Line>
    ): String = buildString {
        val s = _ui.value
        fun section(t: String) { append("\n\n===== ").append(t).append(" =====\n") }
        append("OBD ${com.chuikoff.obd.BuildConfig.VERSION_NAME} (${com.chuikoff.obd.BuildConfig.VERSION_CODE}) — файл диагностики\n")
        append("Создан: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}\n")
        append("Телефон: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        append("Профиль: ${s.selectedProfile.displayNameRu} (${s.selectedProfile.id})\n")
        append("Связь: ${s.connectionState::class.simpleName}, адаптер ${s.connectedAddress ?: s.lastAdapter?.address ?: "—"}, транспорт ${activeTransport?.name ?: "—"}\n")
        append("Статус: ${s.statusMessage ?: "—"}${s.errorMessage?.let { " | ошибка: $it" } ?: ""}\n")
        append("Поддерживаемые PID (двигатель): ${engineExtras.supported?.sorted()?.joinToString(" ") { "%02X".format(it) } ?: "не прочитаны"}\n")
        com.chuikoff.obd.sensors.CretaAtf.selected?.let { append("АКПП источник: ${it.label} — ${it.sourceRu}\n") }
            ?: append("АКПП источник: не найден (probed=${com.chuikoff.obd.sensors.CretaAtf.probed})\n")

        section("ПОКАЗАНИЯ")
        s.readings.forEach { r ->
            append("${r.displayName} [${r.sensorId}]: ${r.displayText ?: r.value?.let { "%.1f".format(it) } ?: "—"} ${r.unit}")
            r.error?.let { append(" | ошибка: $it") }
            r.note?.let { append(" | $it") }
            r.rawHex?.let { append(" | RAW $it") }
            append('\n')
        }
        if (adapter.isNotEmpty()) { section("АДАПТЕР И ОТВЕЧАЮЩИЕ ЭБУ (прочитано сейчас)"); adapter.forEach { append(it.toText()).append('\n') } }
        if (gearbox.isNotEmpty()) { section("КОРОБКА: ШИРОКИЙ ОПРОС 7E1/7E0 (прочитано сейчас)"); gearbox.forEach { append(it.toText()).append('\n') } }
        section("ОШИБКИ (последняя проверка)")
        append(if (s.dtcReport == null && s.dtcLog.isEmpty()) "проверку ещё не запускали\n" else dtcExportText())
        section("ЛОГ КОРОБКИ / CVT")
        append(cvtLogExportText())
        section("ПАСПОРТ")
        append(passportExportText().ifEmpty { "не читали" })
        section("СОБЫТИЯ ПОДКЛЮЧЕНИЯ (${com.chuikoff.obd.diag.DiagLog.eventSnapshot().size})")
        with(com.chuikoff.obd.diag.DiagLog) { eventSnapshot().forEach { append(it.toText()).append('\n') } }
        val traffic = com.chuikoff.obd.diag.DiagLog.trafficSnapshot()
        section("ВЕСЬ ОБМЕН С ELM (последние ${traffic.size} из ${com.chuikoff.obd.diag.DiagLog.TRAFFIC_CAPACITY})")
        with(com.chuikoff.obd.diag.DiagLog) { traffic.forEach { append(it.toText()).append('\n') } }
    }

    private fun restartPolling() {
        if (busExclusive()) return // the exclusive job resumes polling with the new profile itself
        scope.launch {
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
            ignition.start(System.currentTimeMillis())
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
        ignition.stop()
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


    // ---------------------------------------------------------------- 1.14: alerts / ignition / oil

    /** After every publish: threshold alerts on fresh values, «car off» guard, one 2110 per connection. */
    private fun onPublished(readings: List<SensorReading>) {
        val now = System.currentTimeMillis()
        fun fresh(id: String): Double? {
            val r = readings.find { it.sensorId == id } ?: return null
            if (r.value == null || r.isStale) return null
            if (lastAlertSampleTs[id] == r.timestampMs) return null // same sample published twice per tick
            lastAlertSampleTs[id] = r.timestampMs
            return r.value
        }
        val settings = _ui.value.alertSettings
        alertEngine.update(settings, fresh("cvt_temp") ?: fresh(CretaAtf.SENSOR_ID), fresh("coolant_temp"), fresh("ambient_temp"), now)
            .forEach { AlertNotifier.show(app, it, settings) }
        // ATRV voltage is measured by the adapter itself → not an ECU answer
        checkIgnition(readings.any {
            it.value != null && !it.isStale && it.sensorId != EngineExtras.ID_VOLTAGE && it.timestampMs >= now - 60_000
        })
        maybeAutoReadOil(now)
    }

    private fun checkIgnition(ecuAnswered: Boolean) {
        if (!ignition.update(ecuAnswered, System.currentTimeMillis())) return
        ignition.stop()
        disconnectInternal(
            status = "Отключено: ЭБУ не отвечает 3 мин",
            error = "Нет ответа ЭБУ 3 минуты — похоже, зажигание выключено. Отключился, чтобы не сажать батарею."
        )
    }

    private fun maybeAutoReadOil(now: Long) {
        if (oilReadThisConnection) return
        if (_ui.value.selectedProfile.id != Poller.OUTLANDER_PROFILE_ID) return
        if (now - connectedAtMs < OIL_AUTO_DELAY_MS || busExclusive()) return
        oilReadThisConnection = true
        readCvtOil()
    }

    /** EXPERIMENTAL 2110 @7E1 oil degradation counter: once per connection + button on «Авто». */
    fun readCvtOil() {
        val client = elm
        if (client == null || _ui.value.connectionState !is ConnectionState.Connected) {
            _ui.update { it.copy(cvtOilStatus = "Нет подключения — показано последнее значение") }
            return
        }
        if (busExclusive()) return
        _ui.update { it.copy(cvtOilBusy = true, cvtOilStatus = "Чтение 2110…") }
        scope.launch {
            try {
                val r = poller.paused(client) { CvtOilDegradation.read(client) }
                if (r.value != null) {
                    settingsPrefs.edit().putString(K_OIL, CvtOilDegradation.serialize(r)).apply()
                    _ui.update { it.copy(cvtOil = r, cvtOilAttempt = r, cvtOilStatus = "Обновлено") }
                } else {
                    _ui.update { it.copy(cvtOilAttempt = r, cvtOilStatus = "Не прочиталось: ${r.error ?: "нет данных"}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(cvtOilStatus = "Ошибка: ${e.message ?: "неизвестно"}") }
            } finally {
                _ui.update { it.copy(cvtOilBusy = false) }
            }
        }
    }

    /** 0100/0120/0140 right after init (functional 7DF); fallback: PID list of the saved passport. */
    private suspend fun readSupportedPids(client: Elm327Client): Set<Int>? {
        val replies = mutableListOf<String>()
        for ((i, base) in listOf(0x00, 0x20, 0x40).withIndex()) {
            // the very first OBD request after ATSP0 may run the protocol search («SEARCHING…»)
            val raw = runCatching { client.send("01%02X".format(base), timeoutMs = if (i == 0) 6000 else 2500) }
                .getOrElse { if (it is CancellationException) throw it; "" }
            replies += raw
            val got = com.chuikoff.obd.sensors.EngineMath.supportedPids(replies)
            if (got == null || !got.contains(base + 0x20)) break
        }
        return com.chuikoff.obd.sensors.EngineMath.supportedPids(replies)
            ?: _ui.value.passport?.pids01?.toSet()
    }

    fun setAlertSettings(s: AlertSettings) {
        _ui.update { it.copy(alertSettings = s) }
        settingsPrefs.edit()
            .putBoolean(K_AL_CVT_ON, s.cvtEnabled).putInt(K_AL_CVT, s.cvtHotC)
            .putBoolean(K_AL_COOL_ON, s.coolantEnabled).putInt(K_AL_COOL, s.coolantHotC)
            .putBoolean(K_AL_ICE_ON, s.iceEnabled).putInt(K_AL_ICE, s.iceC)
            .putBoolean(K_AL_SOUND, s.sound).putBoolean(K_AL_VIBRO, s.vibrate)
            .apply()
    }

    private fun loadAlertSettings(): AlertSettings {
        val d = AlertSettings()
        return AlertSettings(
            cvtEnabled = settingsPrefs.getBoolean(K_AL_CVT_ON, d.cvtEnabled),
            cvtHotC = settingsPrefs.getInt(K_AL_CVT, d.cvtHotC),
            coolantEnabled = settingsPrefs.getBoolean(K_AL_COOL_ON, d.coolantEnabled),
            coolantHotC = settingsPrefs.getInt(K_AL_COOL, d.coolantHotC),
            iceEnabled = settingsPrefs.getBoolean(K_AL_ICE_ON, d.iceEnabled),
            iceC = settingsPrefs.getInt(K_AL_ICE, d.iceC),
            sound = settingsPrefs.getBoolean(K_AL_SOUND, d.sound),
            vibrate = settingsPrefs.getBoolean(K_AL_VIBRO, d.vibrate)
        )
    }

    /** Test alert from «Профиль» (sound / vibration / Android Auto check without overheating anything). */
    fun testAlert() {
        val s = _ui.value.alertSettings
        AlertNotifier.show(app, com.chuikoff.obd.alerts.AlertEvent(com.chuikoff.obd.alerts.AlertKind.ICE, s.iceC, s.iceC, System.currentTimeMillis()), s)
    }

    // ---------------------------------------------------------------- 1.14: auto-connect

    /** Manual connect to the remembered adapter (Android Auto «Подключить»). */
    fun connectLast(): Boolean {
        val last = _ui.value.lastAdapter ?: return false
        if (_ui.value.connectionState !is ConnectionState.Disconnected) return true
        _ui.update { it.copy(preferredTransport = last.transport) }
        connect(DiscoveredDevice(last.name, last.address, last.transport))
        return true
    }

    fun setAutoConnect(on: Boolean) {
        settingsPrefs.edit().putBoolean(K_AUTO, on).apply()
        _ui.update { it.copy(autoConnect = on) }
        if (!on) { autoJob?.cancel(); autoJob = null }
    }

    fun setAutoConnectOnBluetooth(on: Boolean) {
        settingsPrefs.edit().putBoolean(K_AUTO_BT, on).apply()
        _ui.update { it.copy(autoConnectOnBluetooth = on) }
    }

    private fun loadLastAdapter(): LastAdapter? {
        val a = settingsPrefs.getString(K_LAST_ADDR, null) ?: return null
        val t = runCatching { TransportType.valueOf(settingsPrefs.getString(K_LAST_TYPE, null)!!) }
            .getOrDefault(TransportType.CLASSIC_SPP)
        return LastAdapter(a, settingsPrefs.getString(K_LAST_NAME, null), t)
    }

    private fun saveLastAdapter(l: LastAdapter) {
        settingsPrefs.edit().putString(K_LAST_ADDR, l.address).putString(K_LAST_NAME, l.name)
            .putString(K_LAST_TYPE, l.transport.name).apply()
    }

    private fun hasBtPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Connect the remembered adapter (app start, Android Auto session, car/adapter Bluetooth event).
     * Same limit as the 1.13 reconnect: [Poller.MAX_RECONNECT_ATTEMPTS] tries with its backoff, then stop.
     * Rules in [AutoConnectPolicy]. Any manual connect / disconnect cancels it.
     */
    fun autoConnect(trigger: AutoTrigger, btAddress: String? = null, btClass: Int? = null) {
        val s = _ui.value
        if (trigger == AutoTrigger.BLUETOOTH && !AutoConnectPolicy.relevantDevice(btAddress, btClass, s.lastAdapter)) return
        val now = System.currentTimeMillis()
        val skip = AutoConnectPolicy.skipReason(
            AutoConnectPolicy.Input(
                enabled = s.autoConnect, onBluetoothEvent = s.autoConnectOnBluetooth, trigger = trigger,
                last = s.lastAdapter, disconnected = s.connectionState is ConnectionState.Disconnected && autoJob?.isActive != true,
                permissionOk = hasBtPermission(), manualDisconnectAtMs = manualDisconnectAtMs,
                lastAutoAttemptMs = lastAutoAttemptMs, nowMs = now
            )
        )
        if (skip != null) return
        val last = s.lastAdapter ?: return
        lastAutoAttemptMs = now
        val device = DiscoveredDevice(last.name, last.address, last.transport)
        autoJob = scope.launch {
            val max = Poller.MAX_RECONNECT_ATTEMPTS
            for (attempt in 1..max) {
                if (_ui.value.connectionState !is ConnectionState.Disconnected) return@launch
                _ui.update {
                    it.copy(
                        preferredTransport = last.transport,
                        statusMessage = "Автоподключение (${trigger.labelRu}) к ${last.name ?: last.address}: попытка $attempt из $max…"
                    )
                }
                connectJob?.cancel()
                val j = scope.launch { doConnect(device, auto = true) }
                connectJob = j
                j.join()
                if (_ui.value.connectionState is ConnectionState.Connected) return@launch
                if (attempt < max) {
                    val wait = Poller.reconnectBackoffMs(attempt)
                    _ui.update {
                        it.copy(statusMessage = "Автоподключение: адаптер не ответил (попытка $attempt из $max), повтор через ${wait / 1000} с")
                    }
                    delay(wait)
                }
            }
            _ui.update {
                it.copy(statusMessage = "Автоподключение: адаптер не ответил за $max попыток. Подключись вручную.")
            }
        }
    }

    private var linkJob: Job? = null
    private fun watchLink(c: Elm327Client) {
        linkJob?.cancel()
        linkJob = scope.launch {
            c.linkNote.collect { n -> _ui.update { it.copy(linkNote = n) } }
        }
    }

    private fun resetAmbient() {
        ambientFilter.reset()
        speedReader.reset()
        ambientHeld = 0
    }

    private fun startMonitorService() {
        runCatching { ObdMonitorService.start(app) }
    }

    private fun stopMonitorService() {
        runCatching { ObdMonitorService.stop(app) }
    }

    private fun publishLiveFromReadings(readings: List<SensorReading>, connected: Boolean) {
        fun byId(id: String) = readings.find { it.sensorId == id }
        val coolant = byId("coolant_temp")
        val cvt = byId("cvt_temp") ?: byId(CretaAtf.SENSOR_ID)
        val ambient = byId("ambient_temp")
        publishLive(
            connected = connected,
            status = if (connected) "Опрос датчиков" else "Отключено",
            coolantC = coolant?.value,
            cvtC = cvt?.value,
            ambientC = ambient?.value,
            ambientNote = ambient?.note,
            cvtLockup = cvt?.statusLine,
            engine = ObdLiveData.EngineLive(
                voltageText = byId(EngineExtras.ID_VOLTAGE)?.displayText,
                voltageArgb = byId(EngineExtras.ID_VOLTAGE)?.valueArgb,
                rpmText = byId(EngineExtras.ID_RPM)?.displayText,
                fuelText = byId(EngineExtras.ID_FUEL)?.displayText
            ).takeIf { it.voltageText != null || it.rpmText != null || it.fuelText != null }
        )
    }

    private fun publishLive(
        connected: Boolean,
        status: String,
        coolantC: Double? = ObdLiveData.snapshot.value.coolantC,
        cvtC: Double? = ObdLiveData.snapshot.value.cvtC,
        ambientC: Double? = ObdLiveData.snapshot.value.ambientC,
        ambientNote: String? = ObdLiveData.snapshot.value.ambientNote,
        cvtLockup: String? = ObdLiveData.snapshot.value.cvtLockup,
        engine: ObdLiveData.EngineLive? = ObdLiveData.snapshot.value.engine
    ) {
        ObdLiveData.update(
            connected = connected,
            statusText = status,
            coolantC = coolantC,
            cvtC = cvtC,
            ambientC = ambientC,
            ambientNote = ambientNote,
            cvtLockup = cvtLockup,
            engine = engine,
            gearboxTitle = if (_ui.value.selectedProfile.id == Poller.OUTLANDER_PROFILE_ID) "CVT" else "Коробка"
        )
    }

    /** Activity destroyed: the connection stays (Android Auto / notification «Стоп»), only scanning stops. */
    fun onUiDestroyed() {
        stopScan()
    }

    /** Readable reason; missing «Устройства поблизости» permission gets an actionable hint. */
    private fun permissionAware(e: Throwable): String =
        if (e is SecurityException) PERMISSION_HINT else e.message ?: "Ошибка подключения"

    companion object {
        @Volatile private var instance: ObdController? = null

        fun get(context: Context): ObdController = instance ?: synchronized(this) {
            instance ?: ObdController(context.applicationContext as Application).also { instance = it }
        }

        const val PERMISSION_HINT =
            "Нет разрешения «Устройства поблизости» (Bluetooth). Разреши его: Настройки → Приложения → OBD → Разрешения."
        private const val K_CVT_FORMULA = "formula"
        private const val K_CVT_DEBUG_EXTRA = "debug_extra"
        private const val K_AMB_VALUE = "shown_c"
        private const val K_AMB_AT = "shown_at_ms"
        private const val AMBIENT_MAX_HELD = 10
        private const val OIL_AUTO_DELAY_MS = 30_000L
        private const val K_AUTO = "auto_connect"
        private const val K_AUTO_BT = "auto_connect_bt"
        private const val K_LAST_ADDR = "last_adapter_address"
        private const val K_LAST_NAME = "last_adapter_name"
        private const val K_LAST_TYPE = "last_adapter_transport"
        private const val K_OIL = "cvt_oil_last"
        private const val K_AL_CVT_ON = "alert_cvt_on"
        private const val K_AL_CVT = "alert_cvt_c"
        private const val K_AL_COOL_ON = "alert_coolant_on"
        private const val K_AL_COOL = "alert_coolant_c"
        private const val K_AL_ICE_ON = "alert_ice_on"
        private const val K_AL_ICE = "alert_ice_c"
        private const val K_AL_SOUND = "alert_sound"
        private const val K_AL_VIBRO = "alert_vibrate"
    }
}
