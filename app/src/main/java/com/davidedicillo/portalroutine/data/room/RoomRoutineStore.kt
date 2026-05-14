package com.davidedicillo.portalroutine.data.room

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.DailyCompletion
import com.davidedicillo.portalroutine.data.RoutineSettings
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.StoreSnapshot
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
            settings = dao.settings()?.toModel() ?: RoutineSettings.Default,
        )
    }

    override suspend fun replaceSnapshot(snapshot: StoreSnapshot) {
        dao.replaceConfig(
            children = snapshot.children.map { it.toEntity() },
            windows = snapshot.windows.map { it.toEntity() },
            tasks = snapshot.tasks.map { it.toEntity() },
            completions = snapshot.completions.map { it.toEntity() },
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
    )

    private fun DailyCompletionEntity.toModel() = DailyCompletion(
        localDate = LocalDate.parse(localDate),
        taskId = taskId,
        completed = completed,
        completedAt = completedAt?.let(LocalDateTime::parse),
        clearedAt = clearedAt?.let(LocalDateTime::parse),
    )

    private fun DailyCompletion.toEntity() = DailyCompletionEntity(
        localDate = localDate.toString(),
        taskId = taskId,
        completed = completed,
        completedAt = completedAt?.toString(),
        clearedAt = clearedAt?.toString(),
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
    )

    private fun RoutineSettings.toEntity() = SettingsEntity(
        parentPinHash = parentPinHash,
        dailyResetTime = dailyResetTime.toString(),
        adminServerEnabled = adminServerEnabled,
        overrideWindowId = manualActiveWindowOverride?.windowId,
        overrideSetAt = manualActiveWindowOverride?.setAt?.toString(),
    )
}
