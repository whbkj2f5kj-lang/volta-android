package de.jarvis.w204.obd

/** Request-specific selection across ALL reassembled PDUs, including response-pending. */
object DiagnosticAnswer {
    enum class Kind { POSITIVE, NEGATIVE, PENDING, NO_DATA, UNEXPECTED }
    data class Result(val kind: Kind, val pdu: String = "")
    fun select(raw: String, command: String): Result {
        val c = command.uppercase().replace(" ", "")
        require(c.length >= 2 && c.all { it in "0123456789ABCDEF" })
        val positive = "%02X".format(c.take(2).toInt(16) + 0x40) + c.drop(2)
        val negative = "7F" + c.take(2)
        val messages = DiagParser.messages(raw)
        messages.firstOrNull { it.startsWith(positive) }?.let { return Result(Kind.POSITIVE, it) }
        messages.firstOrNull { it.startsWith(negative) && it.length == 6 && it.takeLast(2) != "78" }
            ?.let { return Result(Kind.NEGATIVE, it) }
        messages.firstOrNull { it == negative + "78" }?.let { return Result(Kind.PENDING, it) }
        return Result(if (raw.isBlank() || raw.trim() == "NO DATA") Kind.NO_DATA else Kind.UNEXPECTED)
    }

    /** Transport-level pending detection only; SafeElm still verifies the actual responder. */
    internal fun pendingInRaw(raw: String, command: String): Boolean {
        if (!command.matches(Regex("(?:01|21|22)[0-9A-Fa-f]+"))) return false
        val sources = raw.uppercase().split('\r', '\n').map { it.filterNot(Char::isWhitespace) }
            .filter { it.length >= 7 && it.all { ch -> ch in "0123456789ABCDEF" } }
            .map { it.take(3).toInt(16) }.distinct()
        val pdus = DiagParser.messages(raw) + sources.flatMap { CanDiagnostic.messages(raw, it) }
        return select(pdus.joinToString("\r"), command).kind == Kind.PENDING
    }
}

/** Keeps waiting after NRC 78, without retransmitting, within the caller's original deadline. */
internal class ElmReplyBuffer(private val command: String) {
    private val text = StringBuilder()
    fun accept(c: Char): String? {
        if (c != '>') { text.append(c); return null }
        if (DiagnosticAnswer.pendingInRaw(text.toString(), command)) { text.append('\r'); return null }
        return text.toString().trim()
    }
    fun pendingAtDeadline(): String? = text.toString().trim().takeIf { DiagnosticAnswer.pendingInRaw(it, command) }
}
