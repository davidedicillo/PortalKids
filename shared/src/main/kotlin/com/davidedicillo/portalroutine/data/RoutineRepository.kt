package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoutineRepository(private val store: RoutineStore) {
    suspend fun snapshot(): StoreSnapshot {
        ensureSeedData()
        return store.snapshot()
    }

    suspend fun storeSnapshot(snapshot: StoreSnapshot) {
        store.replaceSnapshot(snapshot)
        ensureSeedData()
    }

    suspend fun hasParentPin(): Boolean {
        return !snapshot().settings.parentPinHash.isNullOrBlank()
    }

    suspend fun ensureSeedData() {
        val snapshot = store.snapshot()
        if (snapshot.children.isNotEmpty() && snapshot.windows.isNotEmpty()) {
            if (snapshot.tasks.any { needsVisualCueBackfill(it) }) {
                store.replaceSnapshot(
                    snapshot.copy(tasks = snapshot.tasks.map { task ->
                        if (needsVisualCueBackfill(task)) {
                            task.copy(visualCue = visualCueFor(task.id, task.title))
                        } else {
                            task
                        }
                    }),
                )
            }
            return
        }

        store.replaceSnapshot(
            snapshot.copy(
                children = defaultChildren(),
                windows = defaultWindows(),
                tasks = defaultTasks(),
                settings = snapshot.settings,
            ),
        )
    }

    suspend fun boardState(now: LocalDateTime): BoardState {
        ensureSeedData()
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val activeWindow = RoutineEngine.activeWindow(
            windows = snapshot.windows,
            now = now,
            override = snapshot.settings.manualActiveWindowOverride,
        )
        val completedTaskIds = snapshot.completions
            .filter { it.localDate == routineDate && it.completed }
            .map { it.taskId }
            .toSet()
        val childIds = snapshot.children.map { it.id }
        val childStates = snapshot.children.sortedBy { it.sortOrder }.map { child ->
            val visibleTasks = snapshot.tasks
                .filter { it.enabled && it.childId == child.id && it.windowId == activeWindow.id }
                .sortedBy { it.sortOrder }
                .map { task -> BoardTask(task, task.id in completedTaskIds) }
            ChildBoardState(
                child = child,
                tasks = visibleTasks,
                progress = RoutineEngine.childProgress(snapshot.tasks, child.id, activeWindow.id, completedTaskIds),
            )
        }

        return BoardState(
            routineDate = routineDate,
            activeWindow = activeWindow,
            children = childStates,
            allComplete = RoutineEngine.isRoutineComplete(snapshot.tasks, childIds, activeWindow.id, completedTaskIds),
            settings = snapshot.settings,
        )
    }

    suspend fun toggleTask(taskId: String, now: LocalDateTime) {
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val current = store.completion(routineDate, taskId)
        val next = if (current?.completed == true) {
            current.copy(completed = false, clearedAt = now)
        } else {
            DailyCompletion(
                localDate = routineDate,
                taskId = taskId,
                completed = true,
                completedAt = current?.completedAt ?: now,
                clearedAt = null,
            )
        }
        store.upsertCompletion(next)
    }

    suspend fun setTaskCompletion(
        taskId: String,
        completed: Boolean,
        now: LocalDateTime,
        routineDate: LocalDate? = null,
    ) {
        val snapshot = store.snapshot()
        val targetDate = routineDate ?: RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val current = store.completion(targetDate, taskId)
        val next = if (completed) {
            DailyCompletion(
                localDate = targetDate,
                taskId = taskId,
                completed = true,
                completedAt = current?.completedAt ?: now,
                clearedAt = null,
            )
        } else {
            DailyCompletion(
                localDate = targetDate,
                taskId = taskId,
                completed = false,
                completedAt = current?.completedAt,
                clearedAt = current?.clearedAt ?: now,
            )
        }
        store.upsertCompletion(next)
    }

    suspend fun resetDay(now: LocalDateTime) {
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        store.resetDate(routineDate, now)
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = null))
    }

    suspend fun setManualWindowOverride(windowId: String, now: LocalDateTime) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = ActiveWindowOverride(windowId, now)))
    }

    suspend fun clearManualWindowOverride() {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = null))
    }

    suspend fun setParentPin(pin: String) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(parentPinHash = PinHasher.hash(pin)))
    }

    suspend fun verifyParentPin(pin: String): Boolean {
        return PinHasher.verify(pin, store.snapshot().settings.parentPinHash)
    }

    suspend fun updateChild(child: ChildConfig) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                children = snapshot.children
                    .filterNot { it.id == child.id }
                    .plus(child)
                    .sortedBy { it.sortOrder },
            ),
        )
    }

    suspend fun deleteChild(childId: String) {
        val snapshot = store.snapshot()
        if (snapshot.children.size <= 1) return

        store.replaceSnapshot(
            snapshot.copy(
                children = snapshot.children.filterNot { it.id == childId }.sortedBy { it.sortOrder },
                tasks = snapshot.tasks.filterNot { it.childId == childId },
            ),
        )
    }

    suspend fun updateWindow(window: RoutineWindowConfig) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                windows = snapshot.windows
                    .filterNot { it.id == window.id }
                    .plus(window)
                    .sortedBy { it.sortOrder },
            ),
        )
    }

    suspend fun deleteWindow(windowId: String) {
        val snapshot = store.snapshot()
        if (snapshot.windows.size <= 1) return

        val nextOverride = snapshot.settings.manualActiveWindowOverride
            ?.takeUnless { it.windowId == windowId }
        store.replaceSnapshot(
            snapshot.copy(
                windows = snapshot.windows.filterNot { it.id == windowId }.sortedBy { it.sortOrder },
                tasks = snapshot.tasks.filterNot { it.windowId == windowId },
                settings = snapshot.settings.copy(manualActiveWindowOverride = nextOverride),
            ),
        )
    }

    suspend fun upsertTask(task: RoutineTask) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                tasks = snapshot.tasks
                    .filterNot { it.id == task.id }
                    .plus(task)
                    .sortedWith(compareBy({ it.childId }, { it.windowId }, { it.sortOrder })),
            ),
        )
    }

    suspend fun deleteTask(taskId: String) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(snapshot.copy(tasks = snapshot.tasks.filterNot { it.id == taskId }))
    }

    suspend fun updateDailyResetTime(time: LocalTime) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(dailyResetTime = time))
    }

    private fun defaultChildren(): List<ChildConfig> = listOf(
        ChildConfig("child-a", "Kid A", "#1F8A70", 0),
        ChildConfig("child-b", "Kid B", "#D95F59", 1),
    )

    private fun defaultWindows(): List<RoutineWindowConfig> = listOf(
        RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0),
        RoutineWindowConfig("after-school", "After School", LocalTime.of(15, 0), 1),
        RoutineWindowConfig("evening", "Evening", LocalTime.of(18, 30), 2),
    )

    private fun defaultTasks(): List<RoutineTask> = listOf(
        RoutineTask("a-morning-brush", "child-a", "morning", "Brush teeth", true, 0, visualCue = "😁"),
        RoutineTask("a-morning-dress", "child-a", "morning", "Get dressed", true, 1, visualCue = "👕"),
        RoutineTask("a-morning-bag", "child-a", "morning", "Pack backpack", true, 2, visualCue = "🎒"),
        RoutineTask("b-morning-brush", "child-b", "morning", "Brush teeth", true, 0, visualCue = "😁"),
        RoutineTask("b-morning-dress", "child-b", "morning", "Get dressed", true, 1, visualCue = "👕"),
        RoutineTask("b-morning-bag", "child-b", "morning", "Pack backpack", true, 2, visualCue = "🎒"),
        RoutineTask("a-after-school-shoes", "child-a", "after-school", "Put shoes away", true, 0, visualCue = "👟"),
        RoutineTask("a-after-school-snack", "child-a", "after-school", "Snack and water", true, 1, visualCue = "🍎"),
        RoutineTask("a-after-school-homework", "child-a", "after-school", "Homework check", true, 2, visualCue = "✏️"),
        RoutineTask("b-after-school-shoes", "child-b", "after-school", "Put shoes away", true, 0, visualCue = "👟"),
        RoutineTask("b-after-school-snack", "child-b", "after-school", "Snack and water", true, 1, visualCue = "🍎"),
        RoutineTask("b-after-school-homework", "child-b", "after-school", "Homework check", true, 2, visualCue = "✏️"),
        RoutineTask("a-evening-pajamas", "child-a", "evening", "Pajamas", true, 0, visualCue = "🛏️"),
        RoutineTask("a-evening-teeth", "child-a", "evening", "Brush teeth", true, 1, visualCue = "😁"),
        RoutineTask("a-evening-books", "child-a", "evening", "Choose books", true, 2, visualCue = "📚"),
        RoutineTask("b-evening-pajamas", "child-b", "evening", "Pajamas", true, 0, visualCue = "🛏️"),
        RoutineTask("b-evening-teeth", "child-b", "evening", "Brush teeth", true, 1, visualCue = "😁"),
        RoutineTask("b-evening-books", "child-b", "evening", "Choose books", true, 2, visualCue = "📚"),
    )

    private fun needsVisualCueBackfill(task: RoutineTask): Boolean {
        return task.visualCue.isBlank() || task.visualCue == "🪥"
    }

    private fun visualCueFor(taskId: String, title: String): String {
        val normalized = "$taskId $title".lowercase()
        return when {
            "brush" in normalized || "teeth" in normalized -> "😁"
            "dress" in normalized -> "👕"
            "bag" in normalized || "backpack" in normalized -> "🎒"
            "shoe" in normalized -> "👟"
            "snack" in normalized || "water" in normalized -> "🍎"
            "homework" in normalized -> "✏️"
            "pajama" in normalized -> "🛏️"
            "book" in normalized -> "📚"
            else -> "⭐"
        }
    }
}
