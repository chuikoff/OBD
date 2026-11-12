package com.chuikoff.obd.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared in-memory live snapshot for phone UI and Android Auto Car App.
 * ViewModel writes; the Car screen observes it. Not persisted: a fresh process has no live link,
 * so Android Auto shows «Нет связи» instead of yesterday's values.
 */
object ObdLiveData {

    data class Snapshot(
        val connected: Boolean = false,
        val statusText: String = "Не подключено",
        val coolantC: Double? = null,
        val cvtC: Double? = null,
        val ambientC: Double? = null,
        val ambientNote: String? = null,
        val cvtLockup: String? = null,
        val engine: EngineLive? = null,
        val updatedAtMs: Long = 0L
    )

    /** Extra engine values as already formatted on the phone card. */
    data class EngineLive(
        val voltageText: String?,
        val voltageArgb: Long?,
        val rpmText: String?,
        val fuelText: String?
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun update(
        connected: Boolean,
        statusText: String,
        coolantC: Double?,
        cvtC: Double?,
        ambientC: Double?,
        ambientNote: String? = null,
        cvtLockup: String? = null,
        engine: EngineLive? = null
    ) {
        val now = System.currentTimeMillis()
        val snap = Snapshot(
            connected = connected,
            statusText = statusText,
            coolantC = coolantC,
            cvtC = cvtC,
            ambientC = ambientC,
            ambientNote = ambientNote,
            cvtLockup = cvtLockup,
            engine = engine,
            updatedAtMs = now
        )
        _snapshot.value = snap
    }
}
