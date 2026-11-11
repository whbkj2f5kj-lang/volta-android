package de.jarvis.w204.vision

/** Kandidat für ein Schild, Koordinaten normiert (0..1). */
data class SignBox(val l: Float, val t: Float, val r: Float, val b: Float, val value: Int?, val label: String? = null)

/** Was die Schildererkennung meldet (erst nach Bestätigung in mehreren Bildern). */
sealed interface SignEvent {
    val key: String

    /** [note]: null, "bei Nässe", "Lkw" (gilt nicht für Pkw), "7–17 h" (zeitlich), [active] = gilt gerade */
    data class Limit(val value: Int, val note: String? = null, val active: Boolean = true) : SignEvent {
        override val key get() = "L$value|$note|$active"
    }
    data class EndLimit(val value: Int?) : SignEvent { override val key get() = "E$value" }
    data object EndAll : SignEvent { override val key get() = "EA" }
    data class TownEntry(val name: String?) : SignEvent { override val key get() = "T" }
    data class TownExit(val name: String?) : SignEvent { override val key get() = "X" }
    data object NoPassing : SignEvent { override val key get() = "NP" }
    data object EndNoPassing : SignEvent { override val key get() = "ENP" }
}
