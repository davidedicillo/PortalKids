package com.davidedicillo.portalroutine.data.room

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.DailyCompletion
import com.davidedicillo.portalroutine.data.RewardConfig
import com.davidedicillo.portalroutine.data.RoutineSettings
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.StoreSnapshot
import com.davidedicillo.portalroutine.data.WalletEntry
import com.davidedicillo.portalroutine.data.WalletEntryKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoomRoutineStore(private val dao: RoutineDao) : RoutineStore {
    override suspend fun snapshot(): StoreSnapshot {
        return StoreSnapshot(
            children = dao.children().map { it.toModel() },
            windows = dao.windows().map { it.toModel() },
            tasks = dao.tasks().map { it.toModel() },
            completions = dao.completions().map { it.toModel() },
            rewards = dao.rewards().map { it.toModel() },
            walletEntries = dao.walletEntries().map { it.toModel() },
            settings = dao.settings()?.toModel() ?: RoutineSettings.Default,
        )
    }

    override suspend fun replaceSnapshot(snapshot: StoreSnapshot) {
        dao.replaceConfig(
            children = snapshot.children.map { it.toEntity() },
            windows = snapshot.windows.map { it.toEntity() },
            tasks = snapshot.tasks.map { it.toEntity() },
            completions = snapshot.completions.map { it.toEntity() },
            rewards = snapshot.rewards.map { it.toEntity() },
            walletEntries = snapshot.walletEntries.map { it.toEntity() },
            settings = snapshot.settings.toEntity(),
        )
    }

    override suspend fun updateSettings(settings: RoutineSettings) {
        dao.upsertSettings(settings.toEntity())
    }

    override suspend fun upsertCompletion(completion: DailyCompletion) {
        dao.upsertCompletion(completion.toEntity())
    }

    override suspend fun completion(localDate: LocalDate, taskId: String): DailyCompletion? {
        return dao.completion(localDate.toString(), taskId)?.toModel()
    }

    override suspend fun resetDate(localDate: LocalDate, clearedAt: LocalDateTime) {
        dao.resetDate(localDate.toString(), clearedAt.toString())
    }

    override suspend fun upsertWalletEntry(entry: WalletEntry) {
        dao.upsertWalletEntry(entry.toEntity())
    }

    override suspend fun deleteWalletEntry(id: String) {
        dao.deleteWalletEntry(id)
    }

    override suspend fun walletEntry(id: String): WalletEntry? {
        return dao.walletEntry(id)?.toModel()
    }

    private fun ChildEntity.toModel() = ChildConfig(id, displayName, color, sortOrder)
    private fun ChildConfig.toEntity() = ChildEntity(id, displayName, color, sortOrder)

    private fun RoutineWindowEntity.toModel() = RoutineWindowConfig(
        id = id,
        name = name,
        startTime = LocalTime.parse(startTime),
        sortOrder = sortOrder,
    )

    private fun RoutineWindowConfig.toEntity() = RoutineWindowEntity(
        id = id,
        name = name,
        startTime = startTime.toString(),
        sortOrder = sortOrder,
    )

    private fun RoutineTaskEntity.toModel() = RoutineTask(
        id = id,
        childId = childId,
        windowId = windowId,
        title = title,
        enabled = enabled,
        sortOrder = sortOrder,
        note = note,
        visualCue = visualCue,
        activeDays = activeDays.toActiveDays(),
        pointValue = pointValue,
        repeatable = repeatable,
    )

    private fun RoutineTask.toEntity() = RoutineTaskEntity(
        id = id,
        childId = childId,
        windowId = windowId,
        title = title,
        visualCue = visualCue,
        note = note,
        enabled = enabled,
        sortOrder = sortOrder,
        activeDays = activeDays.toStorageValue(),
        pointValue = pointValue,
        repeatable = repeatable,
    )

    private fun DailyCompletionEntity.toModel() = DailyCompletion(
        localDate = LocalDate.parse(localDate),
        taskId = taskId,
        completed = completed,
        completedAt = completedAt?.let(LocalDateTime::parse),
        clearedAt = clearedAt?.let(LocalDateTime::parse),
        count = count,
    )

    private fun DailyCompletion.toEntity() = DailyCompletionEntity(
        localDate = localDate.toString(),
        taskId = taskId,
        completed = completed,
        completedAt = completedAt?.toString(),
        clearedAt = clearedAt?.toString(),
        count = count,
    )

    private fun RewardEntity.toModel() = RewardConfig(
        id = id,
        title = title,
        pointCost = pointCost,
        enabled = enabled,
        sortOrder = sortOrder,
        note = note,
    )

    private fun RewardConfig.toEntity() = RewardEntity(
        id = id,
        title = title,
        pointCost = pointCost,
        enabled = enabled,
        sortOrder = sortOrder,
        note = note,
    )

    private fun WalletEntryEntity.toModel() = WalletEntry(
        id = id,
        childId = childId,
        amount = amount,
        kind = kind.toWalletEntryKind(),
        reason = reason,
        createdAt = LocalDateTime.parse(createdAt),
        sourceId = sourceId,
    )

    private fun WalletEntry.toEntity() = WalletEntryEntity(
        id = id,
        childId = childId,
        amount = amount,
        kind = kind.name,
        reason = reason,
        createdAt = createdAt.toString(),
        sourceId = sourceId,
    )

    private fun SettingsEntity.toModel() = RoutineSettings(
        parentPinHash = parentPinHash,
        dailyResetTime = LocalTime.parse(dailyResetTime),
        adminServerEnabled = adminServerEnabled,
        manualActiveWindowOverride = if (overrideWindowId != null && overrideSetAt != null) {
            ActiveWindowOverride(overrideWindowId, LocalDateTime.parse(overrideSetAt))
        } else {
            null
        },
        walletInitializedAt = walletInitializedAt?.let(LocalDateTime::parse),
    )

    private fun RoutineSettings.toEntity() = SettingsEntity(
        parentPinHash = parentPinHash,
        dailyResetTime = dailyResetTime.toString(),
        adminServerEnabled = adminServerEnabled,
        overrideWindowId = manualActiveWindowOverride?.windowId,
        overrideSetAt = manualActiveWindowOverride?.setAt?.toString(),
        walletInitializedAt = walletInitializedAt?.toString(),
    )

    private fun String.toActiveDays(): Set<DayOfWeek> {
        val days = split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { value -> runCatching { DayOfWeek.valueOf(value.uppercase()) }.getOrNull() }
            .toSet()
        return days.ifEmpty { DayOfWeek.entries.toSet() }
    }

    private fun Set<DayOfWeek>.toStorageValue(): String {
        return if (isEmpty()) {
            DayOfWeek.entries.joinToString(",") { it.name }
        } else {
            sortedBy { it.value }.joinToString(",") { it.name }
        }
    }

    private fun String.toWalletEntryKind(): WalletEntryKind {
        return WalletEntryKind.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: WalletEntryKind.Earning
    }
}
