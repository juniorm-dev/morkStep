package com.morkstep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide, bounded debug log for sensor/wearable diagnostics: connection
 * events, message receipts, sensor registration and any other subsystem that
 * opts into it (pace, heart rate, GPS, BLE...).
 *
 * Each [log] call appends a local-time-stamped line and keeps only the most
 * recent [maxLines]; the joined block is exposed as [text] so any screen can
 * render it and exports can capture it (the workout screen shows it via the
 * engine's `LiveState.debugText`).
 *
 * Entirely gated behind [enabled] (driven by the settings "Debug tracing"
 * toggle): while disabled, [log] is a no-op and [text] stays empty, so none of
 * the debug features run or show. [clear] wipes captured lines (used when the
 * flag is turned off, so stale traces never linger on screen).
 *
 * The pipeline logs from several threads (Wearable message listener, sensor
 * callback, main), so [log] is synchronized and the local-time stamp is
 * produced by a single shared formatter protected by that lock.
 */
class DebugLog(private val maxLines: Int = 12) {
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** Debug gate from the settings toggle; starts ENABLED (default) so events
     *  are captured from process start even before the settings collector runs;
     *  the debugLog collector flips it off when the toggle is off. */
    @Volatile
    var enabled: Boolean = true

    /** Append a timestamped line, dropping the oldest when over [maxLines]. No-op while [enabled] is false. */
    @Synchronized
    fun log(message: String) {
        if (!enabled) return
        lines.addLast("${stamp()} $message")
        while (lines.size > maxLines) lines.removeFirst()
        _text.value = lines.joinToString("\n")
    }

    /** Wipe all captured lines and clear the exposed text. */
    @Synchronized
    fun clear() {
        lines.clear()
        _text.value = ""
    }

    companion object {
        // Local-time formatter, shared. log() is @Synchronized so a single
        // instance is safe; SimpleDateFormat is not thread-safe on its own.
        private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        /** HH:mm:ss LOCAL wall-clock stamp (not UTC). */
        private fun stamp(ms: Long = System.currentTimeMillis()): String = fmt.format(java.util.Date(ms))
    }
}