package com.morkstep.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One completed workout session, summarized for history. */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    /** Total active duration in seconds. */
    val durationSec: Int,
    /** Completed fast (push) segment count. */
    val fastSegments: Int,
    /** Average push pace over the workout (mph), or null if none. */
    val avgFastPace: Float?,
    /** Average heart rate over the workout (bpm), or null. */
    val avgHeartRate: Int?,
    /** Seconds spent above the HR ceiling during push segments. */
    val overCeilingSec: Int,
    /** Distance covered, in miles. */
    val distanceMiles: Float,
)

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Query("SELECT * FROM workouts ORDER BY startTime DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts ORDER BY startTime DESC LIMIT 1")
    fun observeLatest(): Flow<WorkoutEntity?>

    @Query("DELETE FROM workouts")
    suspend fun clear()
}

@Database(entities = [WorkoutEntity::class], version = 2, exportSchema = false)
abstract class MorkDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        /** v1 stored distance in km (`distanceKm`); v2 renamed to `distanceMiles` in mph. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts RENAME COLUMN distanceKm TO distanceMiles")
            }
        }
    }
}