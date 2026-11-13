package com.chuikoff.obd.diag

/** Response ids in a headers-on reply («7A8 02 7E 00» / «7A8027E00»), excluding the request id itself. */
object DiscoverParse {
    private val LINE = Regex("^([0-7][0-9A-F]{2})([0-9A-F]{2,})$")

    fun responders(raw: String, tx: String): List<String> =
        raw.uppercase().split('\r', '\n').map { it.replace(" ", "").trim('>') }
            .mapNotNull { LINE.matchEntire(it)?.groupValues?.get(1) }
            .filter { it != tx && it.startsWith("7") }
            .distinct()
}
