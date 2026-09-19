package com.morkstep.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the user's workout profiles, active profile id, and sensor mode. */
class ConfigStore(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mork_config")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val PROFILES_JSON = stringPreferencesKey("profilesJson")
        private val ACTIVE_ID = longPreferencesKey("activeProfileId")
        private val SIMULATED = booleanPreferencesKey("simulatedSensors")
        private val WEAR_HR = booleanPreferencesKey("wearHeartRate")
        private val WEAR_VIBRATE = booleanPreferencesKey("wearVibrate")
        private val HC_BACKFILL_HR = booleanPreferencesKey("healthConnectBackfillHr")
        private val DARK_MODE = stringPreferencesKey("darkMode")
        private val DEBUG_LOG = booleanPreferencesKey("debugLog")
        private val SHOW_DEBUG_LOG = booleanPreferencesKey("showDebugLog")
        private val FORCE_PHONE_PACE = booleanPreferencesKey("forcePhonePace")
        private val TEST_ADS = booleanPreferencesKey("testAds")
        private val PINNED_ADS = booleanPreferencesKey("pinnedAds")
        private val HISTORY_AD_ACCESSES = intPreferencesKey("historyAdAccesses")
    }

    /** Whether to use simulated sensor readings (developer testing). Default OFF. */
    val simulatedSensors: Flow<Boolean> = context.dataStore.data.map { it[SIMULATED] ?: false }

    suspend fun setSimulatedSensors(value: Boolean) = context.dataStore.edit { p ->
        p[SIMULATED] = value
    }

    /** Whether to take heart rate from the paired Wear companion instead of BLE. Default OFF. */
    val wearHr: Flow<Boolean> = context.dataStore.data.map { it[WEAR_HR] ?: false }

    suspend fun setWearHr(value: Boolean) = context.dataStore.edit { p ->
        p[WEAR_HR] = value
    }

    /** Whether cue vibrations should also be sent to the paired Wear companion. Default OFF. */
    val wearVibrate: Flow<Boolean> = context.dataStore.data.map { it[WEAR_VIBRATE] ?: false }

    /** Whether to backfill heart rate from Health Connect after workouts without the watch. Default ON. */
    val hcBackfillHr: Flow<Boolean> = context.dataStore.data.map { it[HC_BACKFILL_HR] ?: true }

    suspend fun setHcBackfillHr(value: Boolean) = context.dataStore.edit { p ->
        p[HC_BACKFILL_HR] = value
    }

    suspend fun setWearVibrate(value: Boolean) = context.dataStore.edit { p ->
        p[WEAR_VIBRATE] = value
    }

    /** Global dark-mode preference (system / dark / light), shared by the whole app. Default SYSTEM. */
    val darkMode: Flow<DarkMode> = context.dataStore.data.map { p ->
        runCatching { DarkMode.valueOf(p[DARK_MODE] ?: "SYSTEM") }.getOrDefault(DarkMode.SYSTEM)
    }

    suspend fun setDarkMode(mode: DarkMode) = context.dataStore.edit { p ->
        p[DARK_MODE] = mode.name
    }

    /** Whether pace-pipeline tracing, the on-screen debug text and log export are enabled. Default OFF. */
    val debugLog: Flow<Boolean> = context.dataStore.data.map { it[DEBUG_LOG] ?: false }

    suspend fun setDebugLog(value: Boolean) = context.dataStore.edit { p ->
        p[DEBUG_LOG] = value
    }

    /** Whether the captured debug log is displayed on the workout screen. Default OFF. */
    val showDebugLog: Flow<Boolean> = context.dataStore.data.map { it[SHOW_DEBUG_LOG] ?: false }

    suspend fun setShowDebugLog(value: Boolean) = context.dataStore.edit { p ->
        p[SHOW_DEBUG_LOG] = value
    }

    /** Debug: force the phone pedometer to drive pace (watch pace ignored). Default OFF. */
    val forcePhonePace: Flow<Boolean> = context.dataStore.data.map { it[FORCE_PHONE_PACE] ?: false }

    suspend fun setForcePhonePace(value: Boolean) = context.dataStore.edit { p ->
        p[FORCE_PHONE_PACE] = value
    }

    /**
     * Whether the app serves ads at all — the hidden **Test ads (debug)** switch in
     * Settings → General → Debug. Default OFF, and off means no ad is requested, loaded or
     * shown anywhere; on serves Google's test inventory ([com.morkstep.ads.AdUnits]).
     */
    val testAds: Flow<Boolean> = context.dataStore.data.map { it[TEST_ADS] ?: false }

    suspend fun setTestAds(value: Boolean) = context.dataStore.edit { p ->
        p[TEST_ADS] = value
    }

    /**
     * Which placement the app draws — the hidden **Pinned ads (debug)** switch in Settings →
     * General → Debug, meaningful only while [testAds] is on. Default OFF keeps the original
     * layout (a banner at the end of the Home column, a native card above the History list);
     * on moves the banner into the bottom bar, where no screen's scrolling can push it away,
     * and replaces the History card with a full-page native ad on every third History access.
     */
    val pinnedAds: Flow<Boolean> = context.dataStore.data.map { it[PINNED_ADS] ?: false }

    suspend fun setPinnedAds(value: Boolean) = context.dataStore.edit { p ->
        p[PINNED_ADS] = value
    }

    /**
     * History accesses counted toward the pinned placement's every-third full-page ad
     * (`Constants.HISTORY_FULL_PAGE_AD_EVERY_N_ACCESSES`). Persisted so the guard count
     * survives an app restart instead of resetting with the process; it only advances while
     * the pinned placement is on. Default 0.
     */
    val historyAdAccesses: Flow<Int> = context.dataStore.data.map { it[HISTORY_AD_ACCESSES] ?: 0 }

    suspend fun setHistoryAdAccesses(value: Int) = context.dataStore.edit { p ->
        p[HISTORY_AD_ACCESSES] = value
    }

    val profiles: Flow<List<WorkoutProfile>> = context.dataStore.data.map { p ->
        val raw = p[PROFILES_JSON]
        if (raw.isNullOrBlank()) listOf(defaultProfile()) else runCatching {
            // Every loaded profile is re-pinned to the disabled speed band:
            // legacy or imported profiles saved with real speed targets must
            // never re-arm the engine's speed warning cues.
            json.decodeFromString<List<WorkoutProfile>>(raw).map { it.withSpeedCuesDisabled() }
        }.getOrDefault(listOf(defaultProfile()))
    }

    val activeId: Flow<Long> = context.dataStore.data.map { p ->
        p[ACTIVE_ID] ?: 0L
    }

    suspend fun saveProfiles(list: List<WorkoutProfile>) = context.dataStore.edit { p ->
        p[PROFILES_JSON] = json.encodeToString(list)
    }

    suspend fun setActive(id: Long) = context.dataStore.edit { p ->
        p[ACTIVE_ID] = id
    }
}