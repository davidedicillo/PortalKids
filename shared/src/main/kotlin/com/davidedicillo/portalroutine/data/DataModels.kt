package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.ChildProgress
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ChildConfig(
    val id: String,
    val displayName: String,
    val color: String,
    val sortOrder: Int,
)

data class DailyCompletion(
    val localDate: LocalDate,
    val taskId: String,
    val completed: Boolean,
    val completedAt: LocalDateTime?,
    val clearedAt: LocalDateTime?,
)

data class CompletionMutation(
    val operationId: String,
    val taskId: String,
    val routineDate: LocalDate,
    val completed: Boolean,
    val changedAt: LocalDateTime,
    val deviceId: String,
)

data class RoutineSettings(
    val parentPinHash: String?,
    val dailyResetTime: LocalTime,
    val adminServerEnabled: Boolean,
    val manualActiveWindowOverride: ActiveWindowOverride?,
) {
    companion object {
        val Default = RoutineSettings(
            parentPinHash = null,
            dailyResetTime = LocalTime.of(5, 0),
            adminServerEnabled = true,
            manualActiveWindowOverride = null,
        )
    }
}

data class StoreSnapshot(
    val children: List<ChildConfig> = emptyList(),
    val windows: List<RoutineWindowConfig> = emptyList(),
    val tasks: List<RoutineTask> = emptyList(),
    val completions: List<DailyCompletion> = emptyList(),
    val settings: RoutineSettings = RoutineSettings.Default,
)

data class BoardTask(
    val task: RoutineTask,
    val completed: Boolean,
)

data class ChildBoardState(
    val child: ChildConfig,
    val tasks: List<BoardTask>,
    val progress: ChildProgress,
)

data class BoardState(
    val routineDate: LocalDate,
    val activeWindow: RoutineWindowConfig,
    val children: List<ChildBoardState>,
    val allComplete: Boolean,
    val settings: RoutineSettings,
)
