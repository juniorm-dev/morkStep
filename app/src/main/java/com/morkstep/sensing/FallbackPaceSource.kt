package com.morkstep.sensing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pace source that prefers the Wear relay while it is producing values and
 * falls back to the phone pedometer after [staleAfterMs] of silence from the
 * watch.
 *
 * The watch stream wins for as long as it keeps emitting; a phone value only
 * passes through once the watch has been silent for the staleness window, and
 * is immediately displaced when the watch resumes. No watch connected = the
 * phone pedometer drives pace, which is what makes pace work without the Wear
 * companion at all.
 */
class FallbackPaceSource(
    private val wear: PaceSource,
    private val phone: PaceSource,
    private val staleAfterMs: Long = 15_000L,
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PaceSource {
    private val _pace = MutableStateFlow<Int?>(null)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    /** Start value makes the phone pedometer pass through before any watch sample. */
    private var lastWearAtMs = -staleAfterMs

    /** Observe both sources; call once from a scope tied to the engine's lifetime. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            wear.pace.collect { v ->
                if (v != null) {
                    lastWearAtMs = nowMs()
                    _pace.value = v
                }
            }
        }
        scope.launch {
            phone.pace.collect { v ->
                if (v != null && nowMs() - lastWearAtMs >= staleAfterMs) {
                    _pace.value = v
                }
            }
        }
    }
}