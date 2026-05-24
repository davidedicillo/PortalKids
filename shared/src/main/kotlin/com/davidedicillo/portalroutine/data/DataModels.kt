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
    val count: Int = if (completed) 1 else 0,
)

data class CompletionMutation(
    val operationId: String,
    val taskId: String,
    val routineDate: LocalDate,
    val completed: Boolean,
    val changedAt: LocalDateTime,
    val deviceId: String,
    val count: Int = if (completed) 1 else 0,
)

data class RewardRedemptionMutation(
    val operationId: String,
    val childId: String,
    val rewardId: String,
    val createdAt: LocalDateTime,
    val deviceId: String,
)

data class RewardConfig(
    val id: String,
    val title: String,
    val pointCost: Int,
    val enabled: Boolean,
    val sortOrder: Int,
    val note: String? = null,
)

enum class WalletEntryKind {
    Earning,
    RewardRedemption,
    Deduction,
}

data class WalletEntry(
    val id: String,
    val childId: String,
    val amount: Int,
    val kind: WalletEntryKind,
    val reason: String,
    val createdAt: LocalDateTime,
    val sourceId: String? = null,
)

data class RoutineSettings(
    val parentPinHash: String?,
    val dailyResetTime: LocalTime,
    val adminServerEnabled: Boolean,
    val manualActiveWindowOverride: ActiveWindowOverride?,
    val walletInitializedAt: LocalDateTime?,
) {
    companion object {
        val Default = RoutineSettings(
            parentPinHash = null,
            dailyResetTime = LocalTime.of(5, 0),
            adminServerEnabled = true,
            manualActiveWindowOverride = null,
            walletInitializedAt = null,
        )
    }
}

data class StoreSnapshot(
    val children: List<ChildConfig> = emptyList(),
    val windows: List<RoutineWindowConfig> = emptyList(),
    val tasks: List<RoutineTask> = emptyList(),
    val completions: List<DailyCompletion> = emptyList(),
    val rewards: List<RewardConfig> = emptyList(),
    val walletEntries: List<WalletEntry> = emptyList(),
    val settings: RoutineSettings = RoutineSettings.Default,
)

data class BoardTask(
    val task: RoutineTask,
    val completed: Boolean,
    val count: Int = if (completed) 1 else 0,
)

data class PointTotals(
    val daily: Int = 0,
    val weekly: Int = 0,
    val wallet: Int = 0,
)

data class ChildBoardState(
    val child: ChildConfig,
    val tasks: List<BoardTask>,
    val progress: ChildProgress,
    val points: PointTotals,
)

data class BoardState(
    val routineDate: LocalDate,
    val activeWindow: RoutineWindowConfig,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val children: List<ChildBoardState>,
    val rewards: List<RewardConfig>,
    val allComplete: Boolean,
    val settings: RoutineSettings,
)
