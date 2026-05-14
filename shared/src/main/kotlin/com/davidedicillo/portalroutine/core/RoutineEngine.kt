package com.davidedicillo.portalroutine.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class RoutineWindowConfig(
    val id: String,
    val name: String,
    val startTime: LocalTime,
    val sortOrder: Int,
)

data class ActiveWindowOverride(
    val windowId: String,
    val setAt: LocalDateTime,
)

data class RoutineTask(
    val id: String,
    val childId: String,
    val windowId: String,
    val title: String,
    val enabled: Boolean,
    val sortOrder: Int,
    val note: String? = null,
    val visualCue: String = "",
    val activeDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    fun isActiveOn(date: LocalDate): Boolean {
        return activeDays.isEmpty() || date.dayOfWeek in activeDays
    }
}

data class ChildProgress(
    val completed: Int,
    val total: Int,
) {
    val isComplete: Boolean
        get() = total > 0 && completed == total
}

object RoutineEngine {
    fun activeWindow(
        windows: List<RoutineWindowConfig>,
        now: LocalDateTime,
        override: ActiveWindowOverride?,
    ): RoutineWindowConfig {
        val ordered = windows.sortedWith(compareBy<RoutineWindowConfig> { it.sortOrder }.thenBy { it.startTime })
        require(ordered.isNotEmpty()) { "At least one routine window is required" }

        val derived = derivedWindow(ordered, now.toLocalTime())
        val activeOverride = override?.takeIf { manualOverrideStillActive(ordered, now, it) }
        return activeOverride?.let { selected ->
            ordered.firstOrNull { it.id == selected.windowId }
        } ?: derived
    }

    fun childProgress(
        tasks: List<RoutineTask>,
        childId: String,
        windowId: String,
        completions: Set<String>,
        routineDate: LocalDate? = null,
    ): ChildProgress {
        val visible = tasks.filter { task ->
            task.enabled &&
                task.childId == childId &&
                task.windowId == windowId &&
                (routineDate == null || task.isActiveOn(routineDate))
        }
        return ChildProgress(
            completed = visible.count { it.id in completions },
            total = visible.size,
        )
    }

    fun isRoutineComplete(
        tasks: List<RoutineTask>,
        childIds: List<String>,
        windowId: String,
        completions: Set<String>,
        routineDate: LocalDate? = null,
    ): Boolean {
        if (childIds.isEmpty()) return false
        return childIds.all { childId ->
            childProgress(tasks, childId, windowId, completions, routineDate).isComplete
        }
    }

    fun effectiveRoutineDate(now: LocalDateTime, dailyResetTime: LocalTime): LocalDate {
        return if (now.toLocalTime().isBefore(dailyResetTime)) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    private fun derivedWindow(windows: List<RoutineWindowConfig>, time: LocalTime): RoutineWindowConfig {
        return windows.lastOrNull { !time.isBefore(it.startTime) } ?: windows.first()
    }

    private fun manualOverrideStillActive(
        windows: List<RoutineWindowConfig>,
        now: LocalDateTime,
        override: ActiveWindowOverride,
    ): Boolean {
        if (override.setAt.toLocalDate() != now.toLocalDate()) return false

        val setAtTime = override.setAt.toLocalTime()
        val nowTime = now.toLocalTime()
        return windows.none { window ->
            window.startTime.isAfter(setAtTime) && !window.startTime.isAfter(nowTime)
        }
    }
}
