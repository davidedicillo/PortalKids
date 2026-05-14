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
)

@Entity(tableName = "daily_completions", primaryKeys = ["localDate", "taskId"])
data class DailyCompletionEntity(
    val localDate: String,
    val taskId: String,
    val completed: Boolean,
    val completedAt: String?,
    val clearedAt: String?,
)

@Entity(tableName = "pending_completions")
data class PendingCompletionEntity(
    @PrimaryKey val operationId: String,
    val taskId: String,
    val routineDate: String,
    val completed: Boolean,
    val changedAt: String,
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
)
