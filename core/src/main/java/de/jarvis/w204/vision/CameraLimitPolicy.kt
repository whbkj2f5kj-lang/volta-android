package de.jarvis.w204.vision

/** Confirmed detections only; no cached reading survives a camera gap or road transition. */
class CameraLimitPolicy {
    data class Choice(val limit: Int?, val status: String)
    private var choice: Choice? = null
    private var at = 0L
    private var anchorRoad: Long? = null
    private var frameAt: Long? = null
    @Synchronized fun frame(now: Long) { frameAt = now }
    @Synchronized fun reset() { choice = null; anchorRoad = null; frameAt = null }
    @Synchronized fun accept(event: SignEvent, now: Long, road: Long?) {
        when(event) {
            is SignEvent.Limit -> {
                choice = if (event.active && event.note == null) Choice(event.value, "camera") else Choice(null, "camera_conditional")
            }
            is SignEvent.EndLimit -> {
                if (event.value != null && choice?.limit != null && event.value != choice?.limit) return
                choice = Choice(null, "camera_end")
            }
            SignEvent.EndAll -> choice = Choice(null, "camera_end")
            is SignEvent.TownEntry, is SignEvent.TownExit -> choice = Choice(null, "camera_town")
            else -> return
        }
        at = now; anchorRoad = road
    }
    @Synchronized fun current(now: Long, road: Long?): Choice? {
        if (frameAt?.let { now - it in 0..3000 } != true || now - at !in 0..45_000 ||
            (anchorRoad != null && road != null && road != anchorRoad)) {
            choice = null; anchorRoad = null
            return null
        }
        if (anchorRoad == null && road != null) anchorRoad = road
        return choice
    }
}

/** Bounded scheduling shared by all USB callback paths. No frame queue builds up. */
class VisionFrameGate {
    private var last = Long.MIN_VALUE
    private var busy = false
    @Synchronized fun acquire(now: Long): Boolean {
        if (busy || (last != Long.MIN_VALUE && now - last < 160)) return false
        busy = true; last = now; return true
    }
    @Synchronized fun release() { busy = false }
}
