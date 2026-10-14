package com.chuikoff.obd.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared live snapshot for phone UI and Android Auto Car App.
 * ViewModel writes; Car screen / prefs readers consume.
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

    private const val PREFS = "obd_live"
    private const val K_CONNECTED = "connected"
    private const val K_STATUS = "status"
    private const val K_COOLANT = "coolant"
    private const val K_CVT = "cvt"
    private const val K_AMBIENT = "ambient"
    private const val K_AMBIENT_NOTE = "ambient_note"
    private const val K_CVT_LOCKUP = "cvt_lockup"
    private const val K_UPDATED = "updated"

    fun update(
        connected: Boolean,
        statusText: String,
        coolantC: Double?,
        cvtC: Double?,
        ambientC: Double?,
        ambientNote: String? = null,
        cvtLockup: String? = null,
        prefs: SharedPreferences? = null
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
        prefs?.edit()?.apply {
            putBoolean(K_CONNECTED, connected)
            putString(K_STATUS, statusText)
            if (coolantC != null) putFloat(K_COOLANT, coolantC.toFloat()) else remove(K_COOLANT)
            if (cvtC != null) putFloat(K_CVT, cvtC.toFloat()) else remove(K_CVT)
            if (ambientC != null) putFloat(K_AMBIENT, ambientC.toFloat()) else remove(K_AMBIENT)
            putString(K_AMBIENT_NOTE, ambientNote)
            putString(K_CVT_LOCKUP, cvtLockup)
            putLong(K_UPDATED, now)
            apply()
        }
    }

    fun loadFromPrefs(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun optFloat(key: String): Double? =
            if (p.contains(key)) p.getFloat(key, Float.NaN).toDouble().takeUnless { it.isNaN() } else null
        val snap = Snapshot(
            connected = p.getBoolean(K_CONNECTED, false),
            statusText = p.getString(K_STATUS, "Не подключено") ?: "Не подключено",
            coolantC = optFloat(K_COOLANT),
            cvtC = optFloat(K_CVT),
            ambientC = optFloat(K_AMBIENT),
            ambientNote = p.getString(K_AMBIENT_NOTE, null),
            cvtLockup = p.getString(K_CVT_LOCKUP, null),
            updatedAtMs = p.getLong(K_UPDATED, 0L)
        )
        _snapshot.update { snap }
        return snap
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
