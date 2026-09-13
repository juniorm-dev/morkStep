package com.morkstep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide, bounded debug log for sensor/wearable diagnostics: connection
 * events, message receipts, sensor registration and any other subsystem that
 * opts into it (pace, heart rate, GPS, BLE, Health Connect...).
 *
 * Each [log] call appends a local-time-stamped line and keeps only the most
 * recent [maxLines]. Two views of the same buffer are exposed: [text] is the
 * whole retained trace, which exports capture, and [displayText] the newest
 * [displayLines] only, which the workout screen mirrors through the engine's
 * `LiveState.debugText` so a long trace never fills the screen.
 *
 * Entirely gated behind [enabled] (driven by the settings "Debug tracing"
 * toggle): while disabled, [log] is a no-op and both views stay empty, so none
 * of the debug features run or show. [clear] wipes captured lines (used when
 * the flag is turned off, so stale traces never linger on screen).
 *
 * The pipeline logs from several threads (Wearable message listener, sensor
 * callback, main), so [log] is synchronized and the local-time stamp is
 * produced by a single shared formatter protected by that lock.
 */
class DebugLog(
    /** Lines retained for export — the whole trace, not only what fits on screen. */
    private val maxLines: Int = Constants.DEBUG_LOG_MAX_LINES,
    /** Lines mirrored to the on-screen view. */
    private val displayLines: Int = Constants.DEBUG_LOG_DISPLAY_LINES,
) {
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _displayText = MutableStateFlow("")
    /** Newest [displayLines] lines — the on-screen trace. */
    val displayText: StateFlow<String> = _displayText.asStateFlow()

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
        _displayText.value = lines.takeLast(displayLines).joinToString("\n")
    }

    /** Wipe all captured lines and clear both exposed views. */
    @Synchronized
    fun clear() {
        lines.clear()
        _text.value = ""
        _displayText.value = ""
    }

    companion object {
        // Local-time formatter, shared. log() is @Synchronized so a single
        // instance is safe; SimpleDateFormat is not thread-safe on its own.
        private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        /** HH:mm:ss LOCAL wall-clock stamp (not UTC). */
        private fun stamp(ms: Long = System.currentTimeMillis()): String = fmt.format(java.util.Date(ms))
    }
}