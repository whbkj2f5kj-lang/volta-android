package de.jarvis.w204.tpms

object TirePolicy {
    const val MAX_AGE = 300_000L
    fun valid(t: TireState?, connected: Boolean, now: Long): Boolean = connected && t != null && !t.noSignal && now - t.at in 0..MAX_AGE
    fun warning(t: TireState?, connected: Boolean, now: Long, targetBar: Double?): String {
        if (!valid(t, connected, now)) return ""
        t!!
        return when {
            t.leak -> "leak"
            t.kpa < 100 -> "critical_pressure"
            targetBar != null && t.bar < targetBar - .6 -> "critical_pressure"
            targetBar != null && t.bar < targetBar - .3 -> "low_pressure"
            t.tempC >= 85 -> "temperature"
            t.lowBattery -> "battery"
            else -> ""
        }
    }
    /** Bijektive Radzuordnung; beim Wechsel werden die beiden Kanaele getauscht. */
    fun assign(mapping: List<Int>, target: Int, channel: Int): List<Int> {
        require(mapping.sorted() == listOf(0,1,2,3) && target in 0..3 && channel in 0..3)
        val out=mapping.toMutableList();val old=out[target];val previous=out.indexOf(channel)
        out[target]=channel;out[previous]=old;return out
    }
}
