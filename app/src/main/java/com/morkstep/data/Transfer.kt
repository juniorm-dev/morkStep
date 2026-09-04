package com.morkstep.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned backup envelope for saved workout profiles. [version] is carried
 * for forward compatibility so a future schema can migrate old exports; it is
 * currently 1 with no migration logic (the app has not shipped).
 */
@Serializable
data class ProfileExport(val version: Int = 1, val profiles: List<WorkoutProfile>)

/**
 * Versioned backup envelope for completed workout history. [version] is
 * carried for forward compatibility so a future schema can migrate old
 * exports; it is currently 1 with no migration logic (the app has not shipped).
 */
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
 * (restore semantics); history rows are merged. An imported id that collides
 * with an existing row is reassigned to a fresh id so an import over a
 * partially-same device never silently clobbers the existing active row.
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
        if (export.workouts.isEmpty()) throw IllegalArgumentException("file contains no workouts")
        val merged = mergeWorkouts(dao.observeAll().first(), export.workouts)
        dao.insertAll(merged)
        return merged.size
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
 * Reassigns [imported] ids that collide with any [existing] (or already-seen
 * imported) id to fresh values above every existing id, preserving order and
 * keeping every id unique in the merged result. Pure.
 */
private inline fun <reified T> reassignCollidingIds(
    existing: List<T>,
    imported: List<T>,
    idOf: (T) -> Long,
    withId: (T, Long) -> T,
): List<T> {
    val taken = existing.map { idOf(it) }.toMutableSet()
    var nextId = (taken.maxOrNull() ?: 0L) + 1
    return imported.map { item ->
        if (idOf(item) in taken) {
            val fresh = nextId++
            taken.add(fresh)
            withId(item, fresh)
        } else {
            taken.add(idOf(item))
            item
        }
    }
}

/**
 * Imported profiles replace the list (restore semantics). An imported id that
 * collides with an existing (or earlier-imported) one is reassigned to a fresh
 * id so an import over a partially-same device never silently clobbers the
 * existing active row.
 */
internal fun mergeProfiles(existing: List<WorkoutProfile>, imported: List<WorkoutProfile>): List<WorkoutProfile> =
    reassignCollidingIds(existing, imported, idOf = { it.id }, withId = { p, id -> p.copy(id = id) })

/**
 * Imported history rows are merged in. An imported id that collides with an
 * existing (or earlier-imported) row is reassigned to a fresh id, so importing
 * a backup from a second device never silently replaces local workouts.
 */
internal fun mergeWorkouts(existing: List<WorkoutEntity>, imported: List<WorkoutEntity>): List<WorkoutEntity> =
    reassignCollidingIds(existing, imported, idOf = { it.id }, withId = { w, id -> w.copy(id = id) })