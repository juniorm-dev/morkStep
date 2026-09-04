package com.morkstep

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.morkstep.data.ConfigStore
import com.morkstep.data.MorkDatabase

/** Application-scoped singletons. */
class MorkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val configStore = ConfigStore(context)
    val database: MorkDatabase = Room.databaseBuilder(context, MorkDatabase::class.java, "mork.db")
        // No migrations: the app has not shipped, so any pre-release workout
        // history is discarded rather than migrated (schema v1 is the first).
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    val workoutDao = database.workoutDao()
}