package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide rolling logs for the diagnostics file: every ELM exchange (command → exact reply,
 * time, outcome) and connection events. In memory only; written out by «Сохранить диагностику».
 */
object DiagLog {
    const val TRAFFIC_CAPACITY = 6000
    const val EVENT_CAPACITY = 600

    data class Exchange(val ts: Long, val cmd: String, val raw: String, val ms: Long, val outcome: String)
    data class Event(val ts: Long, val text: String)

    private val lock = Any()
    private val traffic = ArrayDeque<Exchange>()
    private val events = ArrayDeque<Event>()

    fun exchange(cmd: String, raw: String, ms: Long, outcome: String) = synchronized(lock) {
        if (traffic.size >= TRAFFIC_CAPACITY) traffic.removeFirst()
        traffic.addLast(Exchange(System.currentTimeMillis(), cmd, raw, ms, outcome))
    }

    fun event(text: String) = synchronized(lock) {
        if (events.size >= EVENT_CAPACITY) events.removeFirst()
        events.addLast(Event(System.currentTimeMillis(), text))
    }

    fun trafficSnapshot(): List<Exchange> = synchronized(lock) { traffic.toList() }
    fun eventSnapshot(): List<Event> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { traffic.clear(); events.clear() }

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    fun time(ts: Long): String = fmt.get()!!.format(Date(ts))

    fun Exchange.toText(): String =
        "[${time(ts)}] $cmd → ${Elm327Parser.visibleRaw(raw).ifEmpty { "(пусто)" }}  {$outcome, ${ms} мс}"

    fun Event.toText(): String = "[${time(ts)}] $text"
}
