package app.sona.notch

import kotlin.math.ceil
import kotlin.math.max

/**
 * Timer and stopwatch. Everything is based on the wall clock and saved to [Prefs], so a running timer or stopwatch
 * survives the service being killed and even a reboot.
 */
object Timers {

    // Stopwatch
    private var swStart = 0L
    private var swAccum = 0L
    var swRunning = false
        private set
    private val lapTotals = ArrayList<Long>()

    /** Elapsed time at each lap, oldest first. */
    val laps: List<Long> get() = lapTotals

    fun swElapsed(): Long = swAccum + if (swRunning) System.currentTimeMillis() - swStart else 0L

    fun swToggle() {
        if (swRunning) {
            swAccum = swElapsed()
            swRunning = false
        } else {
            swStart = System.currentTimeMillis()
            swRunning = true
        }
        save()
    }

    fun swReset() {
        swRunning = false
        swAccum = 0L
        lapTotals.clear()
        save()
    }

    fun lap() {
        if (!swRunning) return
        lapTotals += swElapsed()
        save()
    }

    /** "Lap 3 · +00:12.4": the lap number and the time since the previous lap. */
    fun lapLabel(): String {
        if (lapTotals.isEmpty()) return "Lap"
        val last = lapTotals.last()
        val prev = if (lapTotals.size > 1) lapTotals[lapTotals.size - 2] else 0L
        return "Lap ${lapTotals.size} · +${formatDuration(last - prev, tenths = true)}"
    }

    // Timer
    var tSetMs = 5 * 60_000L
        private set
    var tRunning = false
        private set
    private var tEnd = 0L
    private var tPausedLeft = tSetMs
    private var lastTickSec = -1

    fun tLeft(): Long =
        if (tRunning) max(0L, tEnd - System.currentTimeMillis()) else tPausedLeft

    fun tToggle() {
        if (tRunning) {
            tPausedLeft = tLeft()
            tRunning = false
        } else {
            if (tPausedLeft <= 0L) tPausedLeft = tSetMs
            tEnd = System.currentTimeMillis() + tPausedLeft
            tRunning = true
        }
        save()
    }

    fun tReset() {
        tRunning = false
        tPausedLeft = tSetMs
        lastTickSec = -1
        save()
    }

    fun tAdjust(deltaMs: Long) {
        if (tRunning) {
            tEnd += deltaMs
        } else {
            tSetMs = (tSetMs + deltaMs).coerceIn(60_000L, 99 * 60_000L)
            tPausedLeft = tSetMs
        }
        save()
    }

    /** true exactly once when the timer has just finished. */
    fun consumeFinished(): Boolean {
        if (tRunning && tLeft() <= 0L) {
            tRunning = false
            tPausedLeft = 0L
            save()
            return true
        }
        return false
    }

    /** true once for each of the last ten seconds of a running timer (the caller gives a haptic tick). */
    fun countdownTick(): Boolean {
        if (!tRunning) return false
        val sec = ceil(tLeft() / 1000.0).toInt()
        if (sec in 1..10 && sec != lastTickSec) {
            lastTickSec = sec
            return true
        }
        return false
    }

    // Persistence
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        swRunning = Prefs.long("sw_running", 0L) == 1L
        swStart = Prefs.long("sw_start", 0L)
        swAccum = Prefs.long("sw_accum", 0L)
        lapTotals.clear()
        Prefs.string("sw_laps", "").split(',').mapNotNullTo(lapTotals) { it.toLongOrNull() }
        tSetMs = Prefs.long("t_set", 5 * 60_000L)
        tRunning = Prefs.long("t_running", 0L) == 1L
        tEnd = Prefs.long("t_end", 0L)
        tPausedLeft = Prefs.long("t_left", tSetMs)
    }

    private fun save() {
        Prefs.setLong("sw_running", if (swRunning) 1L else 0L)
        Prefs.setLong("sw_start", swStart)
        Prefs.setLong("sw_accum", swAccum)
        Prefs.setString("sw_laps", lapTotals.joinToString(","))
        Prefs.setLong("t_set", tSetMs)
        Prefs.setLong("t_running", if (tRunning) 1L else 0L)
        Prefs.setLong("t_end", tEnd)
        Prefs.setLong("t_left", tPausedLeft)
    }
}
