package com.morkstep.sensing

import com.morkstep.DebugLog
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
 *
 * [staleAfterMs] of 0 or less flips the roles for debugging: the phone
 * pedometer always drives and watch samples are ignored entirely (no watch
 * fallback at all).
 */
class FallbackPaceSource(
    private val wear: PaceSource,
    private val phone: PaceSource,
    private val staleAfterMs: Long = 15_000L,
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    /** App-wide debug log; null disables logging. */
    private val log: DebugLog? = null,
) : PaceSource {
    private val _pace = MutableStateFlow<Int?>(null)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    /** Start value makes the phone pedometer pass through before any watch sample. */
    private var lastWearAtMs = -staleAfterMs

    /** Observe both sources; call once from a scope tied to the engine's lifetime. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            wear.pace.collect { v ->
                if (v != null && staleAfterMs > 0) {
                    lastWearAtMs = nowMs()
                    _pace.value = v
                    log?.log("[pace-merge] watch pace $v spm drives")
                } else if (v != null) {
                    // staleAfterMs <= 0 = force the phone pedometer: the watch
                    // is deliberately ignored for pace, not just stalled on.
                    log?.log("[pace-merge] watch pace $v spm ignored (phone forced)")
                }
                // null watch samples are heartbeats, not pace data;
                // they must not reset the staleness timer or the
                // phone fallback never gets to drive pace.
            }
        }
        scope.launch {
            phone.pace.collect { v ->
                if (v != null && nowMs() - lastWearAtMs >= staleAfterMs) {
                    _pace.value = v
                    log?.log(
                        if (staleAfterMs <= 0) "[pace-merge] phone pace $v spm (forced)"
                        else "[pace-merge] phone pace $v spm (watch silent ${staleAfterMs / 1000}s)"
                    )
                }
            }
        }
    }
}