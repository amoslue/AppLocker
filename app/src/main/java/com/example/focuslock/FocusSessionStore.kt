package com.example.focuslock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FocusSession(
    val id: String,
    val packageNames: List<String>,
    val startedAt: Long,
    val endsAt: Long
)

object FocusSessionStore {
    private const val PREFS_NAME = "focus_sessions"
    private const val KEY_SESSIONS = "sessions"

    @Synchronized
    fun add(
        context: Context,
        packageNames: Collection<String>,
        startedAt: Long,
        endsAt: Long
    ): FocusSession {
        val storedSessions = currentAndUpcoming(context, startedAt)
        val requestedPackages = packageNames.distinct()
        val duration = endsAt - startedAt
        val scheduledStart = storedSessions
            .filter { session -> session.packageNames.any { it in requestedPackages } }
            .maxOfOrNull { it.endsAt }
            ?.coerceAtLeast(startedAt)
            ?: startedAt

        val session = FocusSession(
            id = UUID.randomUUID().toString(),
            packageNames = requestedPackages,
            startedAt = scheduledStart,
            endsAt = scheduledStart + duration
        )
        save(context, storedSessions + session)
        return session
    }

    @Synchronized
    fun active(context: Context, now: Long = System.currentTimeMillis()): List<FocusSession> {
        return currentAndUpcoming(context, now).filter { it.startedAt <= now }
    }

    @Synchronized
    fun currentAndUpcoming(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): List<FocusSession> {
        val storedSessions = read(context)
        val sessions = storedSessions.filter { it.endsAt > now && it.packageNames.isNotEmpty() }
        if (sessions.size != storedSessions.size) save(context, sessions)
        return sessions.sortedBy { it.startedAt }
    }

    private fun read(context: Context): List<FocusSession> {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val packages = item.getJSONArray("packages")
                    add(
                        FocusSession(
                            id = item.getString("id"),
                            packageNames = buildList {
                                for (packageIndex in 0 until packages.length()) {
                                    add(packages.getString(packageIndex))
                                }
                            },
                            startedAt = item.getLong("startedAt"),
                            endsAt = item.getLong("endsAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, sessions: List<FocusSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("packages", JSONArray(session.packageNames))
                    .put("startedAt", session.startedAt)
                    .put("endsAt", session.endsAt)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, array.toString())
            .apply()
    }
}
