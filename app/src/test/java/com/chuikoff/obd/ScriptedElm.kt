package com.chuikoff.obd

import com.chuikoff.obd.connection.ObdTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.util.Collections

/**
 * ELM327 model for tests: one reply per command, delivered after [latencyFor] ms.
 *  - [reply] returns the full text (with `>`), `""` = ELM busy and silent (never prompts until
 *    interrupted), null = no reply at all (idle).
 *  - bare CR on an idle ELM repeats the last command (real ELM327 behaviour);
 *    any other character while busy interrupts it → `STOPPED>`.
 *  - write/read behave like withContext(IO): they throw in a cancelled coroutine.
 */
class ScriptedElm(
    private val latencyFor: (String) -> Long = { 0L },
    private val reply: (String) -> String?
) : ObdTransport {
    override val name = "scripted"
    override val isConnected = MutableStateFlow(true)
    /** Raw commands as written, trailing CR removed ("" = bare CR, " " = space). */
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var pending: String? = null
    private var readyAt = 0L
    private var last = ""

    override suspend fun connect(deviceAddress: String) { isConnected.value = true }
    override suspend fun disconnect() { isConnected.value = false }

    override suspend fun write(data: ByteArray) {
        currentCoroutineContext().ensureActive()
        if (!isConnected.value) throw IOException("BT сокет мёртв (fake)")
        val raw = String(data, Charsets.US_ASCII)
        val cmd = raw.removeSuffix("\r")
        sent += cmd
        val now = System.currentTimeMillis()
        when {
            raw == "\r" -> { if (pending == null) { pending = reply(last); readyAt = now + latencyFor(last) } }
            cmd.isBlank() -> { if (pending != null) { pending = "STOPPED\r\r>"; readyAt = now } }
            else -> {
                last = cmd
                val r = reply(cmd)
                pending = r
                readyAt = if (r == "") Long.MAX_VALUE else now + latencyFor(cmd)
            }
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        currentCoroutineContext().ensureActive()
        if (!isConnected.value) throw IOException("BT сокет мёртв (fake)")
        val p = pending
        if (p == null) { delay(minOf(timeoutMs, 5L)); return "" }
        val wait = readyAt - System.currentTimeMillis()
        if (wait > timeoutMs) { delay(timeoutMs); return "" }
        if (wait > 0) delay(wait)
        pending = null
        return p
    }

    fun sentAfter(mark: Int): List<String> = synchronized(sent) { sent.drop(mark) }
}
