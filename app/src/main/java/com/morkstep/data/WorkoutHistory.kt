package com.morkstep.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Averages for one phase occurrence inside a workout — the warm-up, each push
 * round, each recovery round, and the cool-down — from the engine's own
 * per-tick samples. A metric the phase produced no sample for stays null (the
 * history chart leaves that point out rather than plotting a zero).
 */
@Serializable
data class PhaseAverages(
    val phase: PhaseType,
    /** Average speed during the phase, mph. */
    val avgSpeedMph: Float? = null,
    /** Average pedometer cadence during the phase, steps per minute. */
    val avgPaceSpm: Int? = null,
    /** Average heart rate during the phase, bpm. */
    val avgHrBpm: Int? = null,
)

/** One completed workout session, summarized for history. */
@Serializable
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    /** Total active duration in seconds. */
    val durationSec: Int,
    /** Completed push segment count. */
    val pushSegments: Int,
    /** Seconds spent above the Push Min bpm during push segments. */
    val overPushMinSec: Int,
    /** Distance covered, in miles. */
    val distanceMiles: Float,
    /** Average speed (mph) during push / recovery / overall. */
    val avgPushSpeed: Float?,
    val avgRecoverySpeed: Float?,
    val avgOverallSpeed: Float?,
    /** Average heart rate (bpm) during push / recovery / overall. */
    val avgPushHr: Int?,
    val avgRecoveryHr: Int?,
    val avgOverallHr: Int?,
    /** Average pedometer cadence (spm) during push / recovery / overall. */
    val avgPushPace: Int? = null,
    val avgRecoveryPace: Int? = null,
    val avgOverallPace: Int? = null,
    /** Min / max heart rate (bpm) — from Health Connect backfill when no real-time source was live. */
    val minHr: Int? = null,
    val maxHr: Int? = null,
    /** Name of the profile the session ran under; null for rows saved before it was recorded. */
    val profileName: String? = null,
    /**
     * Per-phase averages in workout order (warm-up → push/recovery pairs →
     * cool-down), driving the expanded history card and its line chart. Empty
     * for rows saved before per-phase recording, or when no sample arrived.
     * A Health Connect backfill fills in a phase's HR where the session had no
     * real-time HR rather than replacing the entry.
     */
    val phaseAverages: List<PhaseAverages> = emptyList(),
)

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    /** Bulk import from an export file; imported ids are pre-reassigned on collision, so plain insert. */
    @Insert
    suspend fun insertAll(workouts: List<WorkoutEntity>)

    @Update
    suspend fun update(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts ORDER BY startTime DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>
}

/**
 * Machine-read JSON codec for the [WorkoutEntity.phaseAverages] column:
 * compact, unlike the pretty-printed transfer files (nothing reads this by eye).
 */
private val PHASE_AVERAGES_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Stores [PhaseAverages] lists as JSON text in the workouts table. */
class PhaseAveragesConverters {
    @TypeConverter
    fun phaseAveragesToJson(value: List<PhaseAverages>): String = PHASE_AVERAGES_JSON.encodeToString(value)

    @TypeConverter
    fun phaseAveragesFromJson(value: String): List<PhaseAverages> = PHASE_AVERAGES_JSON.decodeFromString(value)
}

/**
 * Room schema v2. v1 rows are dropped rather than migrated on upgrade (the app
 * has not shipped; see the destructive fallback in `AppContainer`); v2 adds the
 * profile name and the per-phase average list.
 */
@Database(entities = [WorkoutEntity::class], version = 2, exportSchema = false)
@TypeConverters(PhaseAveragesConverters::class)
abstract class MorkDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
}