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
        val updatedAtMs: Long = 0L
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
        cvtLockup: String? = null
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
            updatedAtMs = now
        )
        _snapshot.value = snap
    }
}
