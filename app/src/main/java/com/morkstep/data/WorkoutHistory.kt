package com.morkstep.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One completed workout session, summarized for history. */
@Serializable
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    /** Total active duration in seconds. */
    val durationSec: Int,
    /** Completed fast (push) segment count. */
    val fastSegments: Int,
    /** Average push speed over the workout (mph), or null if none. */
    val avgFastSpeed: Float?,
    /** Average heart rate over the workout (bpm), or null. */
    val avgHeartRate: Int?,
    /** Seconds spent above the Push Min bpm during push segments. */
    val overCeilingSec: Int,
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
)

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    /** Bulk import from an export file; imported ids win over existing rows (restore semantics). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(workouts: List<WorkoutEntity>)

    @Update
    suspend fun update(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts ORDER BY startTime DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts ORDER BY startTime DESC LIMIT 1")
    fun observeLatest(): Flow<WorkoutEntity?>

    @Query("DELETE FROM workouts")
    suspend fun clear()
}

@Database(entities = [WorkoutEntity::class], version = 7, exportSchema = false)
abstract class MorkDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
}