package com.morkstep.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned backup envelope for saved workout profiles. */
@Serializable
data class ProfileExport(val version: Int = 1, val profiles: List<WorkoutProfile>)

/** Versioned backup envelope for completed workout history. */
@Serializable
data class WorkoutExport(val version: Int = 1, val workouts: List<WorkoutEntity>)

/** Shared JSON codec for profile/history transfer files (pretty-printed for humans). */
object TransferJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

/**
 * Reads and writes profile/history backup files through the SAF URIs the UI
 * produces (CreateDocument/OpenDocument). Import replaces the profile list
 * (restore semantics); history rows are merged, imported ids winning.
 */
class TransferIO(
    private val resolver: ContentResolver,
    private val store: ConfigStore,
    private val dao: WorkoutDao,
) {
    suspend fun writeProfiles(uri: Uri) {
        val payload = TransferJson.json.encodeToString(
            ProfileExport(profiles = store.profiles.first())
        )
        writeText(uri, payload)
    }

    /** Parse a profile export and make it the profile list; returns the imported count. */
    suspend fun readProfiles(uri: Uri): Int {
        val export = parse<ProfileExport>(uri, "profile")
        if (export.profiles.isEmpty()) throw IllegalArgumentException("file contains no profiles")
        val merged = mergeProfiles(store.profiles.first(), export.profiles)
        store.saveProfiles(merged)
        // Keep the previously active profile when it survived the import; else pick the first.
        val keepActive = merged.any { it.id == store.activeId.first() }
        store.setActive(if (keepActive) store.activeId.first() else merged.first().id)
        return merged.size
    }

    suspend fun writeWorkouts(uri: Uri) {
        val payload = TransferJson.json.encodeToString(
            WorkoutExport(workouts = dao.observeAll().first())
        )
        writeText(uri, payload)
    }

    /** Parse a workout-history export and merge it in; returns the imported count. */
    suspend fun readWorkouts(uri: Uri): Int {
        val export = parse<WorkoutExport>(uri, "workout history")
        if (export.workouts.isNotEmpty()) dao.insertAll(export.workouts)
        return export.workouts.size
    }

    private inline fun <reified T> parse(uri: Uri, what: String): T {
        val text = readText(uri)
        return runCatching { TransferJson.json.decodeFromString<T>(text) }
            .getOrElse { throw IllegalArgumentException("not a valid morkStep $what file") }
    }

    private fun writeText(uri: Uri, text: String) {
        val out = resolver.openOutputStream(uri)
            ?: throw IllegalArgumentException("cannot open destination")
        out.bufferedWriter().use { it.write(text) }
    }

    private fun readText(uri: Uri): String {
        val input = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("cannot open file")
        return input.bufferedReader().use { it.readText() }
    }
}

/**
 * Imported profiles replace the list (restore semantics). An imported id that
 * collides with an existing one is reassigned to a fresh id so an import over
 * a partially-same device never silently clobbers the existing active row.
 */
internal fun mergeProfiles(existing: List<WorkoutProfile>, imported: List<WorkoutProfile>): List<WorkoutProfile> {
    val existingIds = existing.map { it.id }.toMutableSet()
    var nextId = (existingIds.maxOrNull() ?: 0L) + 1
    return imported.map { p ->
        if (p.id in existingIds) p.copy(id = nextId++) else p
    }
}