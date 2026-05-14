package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object StoreJson {
    fun snapshotJson(snapshot: StoreSnapshot): JSONObject {
        return JSONObject()
            .put("children", JSONArray(snapshot.children.map { it.toJson() }))
            .put("windows", JSONArray(snapshot.windows.map { it.toJson() }))
            .put("tasks", JSONArray(snapshot.tasks.map { it.toJson() }))
            .put("settings", snapshot.settings.toJson())
            .put("completions", JSONArray(snapshot.completions.map { it.toJson() }))
    }

    fun stateJson(
        snapshot: StoreSnapshot,
        board: BoardState,
        adminUrl: String,
    ): JSONObject {
        return JSONObject()
            .put("adminUrl", adminUrl)
            .put("hubUrl", adminUrl)
            .put("activeWindow", board.activeWindow.id)
            .put("routineDate", board.routineDate.toString())
            .put("points", board.pointsJson())
            .put("children", JSONArray(snapshot.children.map { it.toJson() }))
            .put("windows", JSONArray(snapshot.windows.map { it.toJson() }))
            .put("tasks", JSONArray(snapshot.tasks.map { it.toJson() }))
            .put("settings", snapshot.settings.toJson())
            .put("completions", JSONArray(snapshot.completions.map { it.toJson() }))
            .put("history", JSONArray(snapshot.completions.take(100).map { it.toJson() }))
            .put("deviceProfiles", JSONArray())
    }

    fun snapshotFromState(json: JSONObject): StoreSnapshot {
        return StoreSnapshot(
            children = json.array("children").mapObjects { child ->
                ChildConfig(
                    id = child.getString("id"),
                    displayName = child.getString("displayName"),
                    color = child.optString("color", "#1F8A70"),
                    sortOrder = child.optInt("sortOrder", 0),
                )
            },
            windows = json.array("windows").mapObjects { window ->
                RoutineWindowConfig(
                    id = window.getString("id"),
                    name = window.getString("name"),
                    startTime = LocalTime.parse(window.getString("startTime")),
                    sortOrder = window.optInt("sortOrder", 0),
                )
            },
            tasks = json.array("tasks").mapObjects { task ->
                RoutineTask(
                    id = task.getString("id"),
                    childId = task.getString("childId"),
                    windowId = task.getString("windowId"),
                    title = task.getString("title"),
                    enabled = task.optBoolean("enabled", true),
                    sortOrder = task.optInt("sortOrder", 0),
                    note = task.optString("note").ifBlank { null },
                    visualCue = task.optString("visualCue", "⭐").ifBlank { "⭐" },
                    activeDays = task.activeDays(),
                )
            },
            completions = (json.optJSONArray("completions") ?: json.optJSONArray("history") ?: JSONArray())
                .mapObjects { completion ->
                    DailyCompletion(
                        localDate = LocalDate.parse(completion.getString("localDate")),
                        taskId = completion.getString("taskId"),
                        completed = completion.optBoolean("completed", false),
                        completedAt = completion.optString("completedAt").ifBlank { null }?.let(LocalDateTime::parse),
                        clearedAt = completion.optString("clearedAt").ifBlank { null }?.let(LocalDateTime::parse),
                    )
                },
            settings = json.optJSONObject("settings")?.toSettings() ?: RoutineSettings.Default,
        )
    }

    private fun ChildConfig.toJson() = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("color", color)
        .put("sortOrder", sortOrder)

    private fun RoutineWindowConfig.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("startTime", startTime.toString())
        .put("sortOrder", sortOrder)

    private fun RoutineTask.toJson() = JSONObject()
        .put("id", id)
        .put("childId", childId)
        .put("windowId", windowId)
        .put("title", title)
        .put("visualCue", visualCue)
        .put("note", note ?: "")
        .put("enabled", enabled)
        .put("sortOrder", sortOrder)
        .put("activeDays", JSONArray(activeDays.sortedBy { it.value }.map { it.name }))

    private fun DailyCompletion.toJson() = JSONObject()
        .put("localDate", localDate.toString())
        .put("taskId", taskId)
        .put("completed", completed)
        .put("completedAt", completedAt?.toString() ?: "")
        .put("clearedAt", clearedAt?.toString() ?: "")

    private fun BoardState.pointsJson() = JSONObject()
        .put("weekStart", weekStart.toString())
        .put("weekEnd", weekEnd.toString())
        .put("children", JSONArray(children.map { childState ->
            JSONObject()
                .put("childId", childState.child.id)
                .put("displayName", childState.child.displayName)
                .put("color", childState.child.color)
                .put("daily", childState.points.daily)
                .put("weekly", childState.points.weekly)
        }))

    private fun RoutineSettings.toJson() = JSONObject()
        .put("dailyResetTime", dailyResetTime.toString())
        .put("adminServerEnabled", adminServerEnabled)
        .put("hasParentPin", !parentPinHash.isNullOrBlank())
        .put("manualActiveWindowOverride", manualActiveWindowOverride?.let { override ->
            JSONObject()
                .put("windowId", override.windowId)
                .put("setAt", override.setAt.toString())
        })

    private fun JSONObject.toSettings(): RoutineSettings {
        val override = optJSONObject("manualActiveWindowOverride")?.let { value ->
            val windowId = value.optString("windowId")
            val setAt = value.optString("setAt")
            if (windowId.isBlank() || setAt.isBlank()) null else ActiveWindowOverride(windowId, LocalDateTime.parse(setAt))
        }
        return RoutineSettings.Default.copy(
            dailyResetTime = optString("dailyResetTime", RoutineSettings.Default.dailyResetTime.toString()).let(LocalTime::parse),
            adminServerEnabled = optBoolean("adminServerEnabled", true),
            manualActiveWindowOverride = override,
        )
    }

    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray()

    private fun JSONObject.activeDays(): Set<DayOfWeek> {
        val days = optJSONArray("activeDays") ?: return DayOfWeek.entries.toSet()
        if (days.length() == 0) return DayOfWeek.entries.toSet()
        return (0 until days.length()).mapNotNull { index ->
            runCatching { DayOfWeek.valueOf(days.getString(index).uppercase()) }.getOrNull()
        }.toSet().ifEmpty { DayOfWeek.entries.toSet() }
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        return (0 until length()).map { index -> transform(getJSONObject(index)) }
    }
}
