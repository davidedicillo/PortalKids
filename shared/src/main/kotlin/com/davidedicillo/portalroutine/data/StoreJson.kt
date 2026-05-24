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
            .put("rewards", JSONArray(snapshot.rewards.map { it.toJson() }))
            .put("walletEntries", JSONArray(snapshot.walletEntries.map { it.toJson() }))
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
            .put("rewards", JSONArray(snapshot.rewards.map { it.toJson() }))
            .put("walletHistory", JSONArray(snapshot.walletEntries.sortedByDescending { it.createdAt }.take(100).map { it.toJson() }))
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
                    pointValue = task.optInt("pointValue", 1).coerceAtLeast(0),
                    repeatable = task.optBoolean("repeatable", false),
                )
            },
            completions = (json.optJSONArray("completions") ?: json.optJSONArray("history") ?: JSONArray())
                .mapObjects { completion ->
                    val count = if (completion.has("count")) {
                        completion.optInt("count", 0).coerceAtLeast(0)
                    } else if (completion.optBoolean("completed", false)) {
                        1
                    } else {
                        0
                    }
                    DailyCompletion(
                        localDate = LocalDate.parse(completion.getString("localDate")),
                        taskId = completion.getString("taskId"),
                        completed = completion.optBoolean("completed", count > 0),
                        completedAt = completion.optString("completedAt").ifBlank { null }?.let(LocalDateTime::parse),
                        clearedAt = completion.optString("clearedAt").ifBlank { null }?.let(LocalDateTime::parse),
                        count = count,
                    )
                },
            rewards = json.array("rewards").mapObjects { reward ->
                RewardConfig(
                    id = reward.getString("id"),
                    title = reward.getString("title"),
                    pointCost = reward.optInt("pointCost", 0).coerceAtLeast(0),
                    enabled = reward.optBoolean("enabled", true),
                    sortOrder = reward.optInt("sortOrder", 0),
                    note = reward.optString("note").ifBlank { null },
                )
            },
            walletEntries = (json.optJSONArray("walletEntries") ?: json.optJSONArray("walletHistory") ?: JSONArray())
                .mapObjects { entry ->
                    WalletEntry(
                        id = entry.getString("id"),
                        childId = entry.getString("childId"),
                        amount = entry.optInt("amount", 0),
                        kind = entry.walletKind(),
                        reason = entry.optString("reason"),
                        createdAt = LocalDateTime.parse(entry.getString("createdAt")),
                        sourceId = entry.optString("sourceId").ifBlank { null },
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
        .put("pointValue", pointValue)
        .put("repeatable", repeatable)

    private fun DailyCompletion.toJson() = JSONObject()
        .put("localDate", localDate.toString())
        .put("taskId", taskId)
        .put("completed", completed)
        .put("count", count)
        .put("completedAt", completedAt?.toString() ?: "")
        .put("clearedAt", clearedAt?.toString() ?: "")

    private fun RewardConfig.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("pointCost", pointCost)
        .put("enabled", enabled)
        .put("sortOrder", sortOrder)
        .put("note", note ?: "")

    private fun WalletEntry.toJson() = JSONObject()
        .put("id", id)
        .put("childId", childId)
        .put("amount", amount)
        .put("kind", kind.name)
        .put("reason", reason)
        .put("createdAt", createdAt.toString())
        .put("sourceId", sourceId ?: "")

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
                .put("wallet", childState.points.wallet)
        }))

    private fun RoutineSettings.toJson() = JSONObject()
        .put("dailyResetTime", dailyResetTime.toString())
        .put("adminServerEnabled", adminServerEnabled)
        .put("hasParentPin", !parentPinHash.isNullOrBlank())
        .put("walletInitializedAt", walletInitializedAt?.toString() ?: "")
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
            walletInitializedAt = optString("walletInitializedAt").ifBlank { null }?.let(LocalDateTime::parse),
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

    private fun JSONObject.walletKind(): WalletEntryKind {
        val value = optString("kind", WalletEntryKind.Earning.name)
        return WalletEntryKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: WalletEntryKind.Earning
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        return (0 until length()).map { index -> transform(getJSONObject(index)) }
    }
}
