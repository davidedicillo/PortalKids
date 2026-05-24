package com.davidedicillo.portalroutine.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val color: String,
    val sortOrder: Int,
)

@Entity(tableName = "routine_windows")
data class RoutineWindowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startTime: String,
    val sortOrder: Int,
)

@Entity(tableName = "routine_tasks")
data class RoutineTaskEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val windowId: String,
    val title: String,
    val visualCue: String,
    val note: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val activeDays: String,
    val pointValue: Int,
    val repeatable: Boolean,
)

@Entity(tableName = "daily_completions", primaryKeys = ["localDate", "taskId"])
data class DailyCompletionEntity(
    val localDate: String,
    val taskId: String,
    val completed: Boolean,
    val completedAt: String?,
    val clearedAt: String?,
    val count: Int,
)

@Entity(tableName = "rewards")
data class RewardEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pointCost: Int,
    val enabled: Boolean,
    val sortOrder: Int,
    val note: String?,
)

@Entity(tableName = "wallet_entries")
data class WalletEntryEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val amount: Int,
    val kind: String,
    val reason: String,
    val createdAt: String,
    val sourceId: String?,
)

@Entity(tableName = "pending_completions")
data class PendingCompletionEntity(
    @PrimaryKey val operationId: String,
    val taskId: String,
    val routineDate: String,
    val completed: Boolean,
    val changedAt: String,
    val deviceId: String,
    val count: Int,
)

@Entity(tableName = "pending_wallet_mutations")
data class PendingWalletMutationEntity(
    @PrimaryKey val operationId: String,
    val childId: String,
    val rewardId: String,
    val createdAt: String,
    val deviceId: String,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val parentPinHash: String?,
    val dailyResetTime: String,
    val adminServerEnabled: Boolean,
    val overrideWindowId: String?,
    val overrideSetAt: String?,
    val walletInitializedAt: String?,
)
