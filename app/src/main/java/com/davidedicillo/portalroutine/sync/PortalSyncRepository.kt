package com.davidedicillo.portalroutine.sync

import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.CompletionQueueReplayer
import com.davidedicillo.portalroutine.data.RoutineRepository
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.room.PendingCompletionEntity
import com.davidedicillo.portalroutine.data.room.RoutineDao
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PortalSyncRepository(
    private val localRepository: RoutineRepository,
    private val localStore: RoutineStore,
    private val dao: RoutineDao,
    private val deviceSettings: DeviceSettings,
) {
    private val queueReplayer = CompletionQueueReplayer()

    var lastStatus: SyncStatus = SyncStatus.Unknown
        private set

    val hubUrl: String
        get() = deviceSettings.hubUrl

    val mode: PortalMode
        get() = deviceSettings.portalMode

    suspend fun initialize() {
        localRepository.ensureSeedData()
        if (deviceSettings.portalMode == PortalMode.StandalonePortal) {
            lastStatus = SyncStatus.Standalone
            return
        }
        syncOnce()
    }

    suspend fun syncOnce() {
        if (deviceSettings.portalMode == PortalMode.StandalonePortal) {
            lastStatus = SyncStatus.Standalone
            return
        }
        try {
            flushPendingCompletions()
            localStore.replaceSnapshot(client().fetchSnapshot())
            lastStatus = SyncStatus.Online(LocalDateTime.now())
        } catch (error: Exception) {
            lastStatus = SyncStatus.Offline(error.message ?: "Hub unavailable", LocalDateTime.now())
        }
    }

    suspend fun setTaskCompletion(taskId: String, completed: Boolean, now: LocalDateTime) {
        val snapshot = localRepository.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val mutation = CompletionMutation(
            operationId = UUID.randomUUID().toString(),
            taskId = taskId,
            routineDate = routineDate,
            completed = completed,
            changedAt = now,
            deviceId = deviceSettings.deviceId,
        )
        localRepository.setTaskCompletion(taskId, completed, now, routineDate)
        dao.upsertPendingCompletion(mutation.toEntity())
        syncOnce()
    }

    suspend fun loginParent(pin: String): String {
        val token = client().login(pin)
        syncOnce()
        return token
    }

    suspend fun resetDay(token: String) {
        client().resetDay(token)
        syncOnce()
    }

    suspend fun setManualWindowOverride(token: String, windowId: String) {
        client().setManualWindowOverride(token, windowId)
        syncOnce()
    }

    suspend fun clearManualWindowOverride(token: String) {
        client().clearManualWindowOverride(token)
        syncOnce()
    }

    suspend fun pendingCompletionCount(): Int = dao.pendingCompletionCount()

    suspend fun updateHubUrl(url: String) {
        deviceSettings.hubUrl = url
        syncOnce()
    }

    suspend fun connectToHub(
        url: String,
        pin: String,
        migration: HubMigrationDirection,
    ) {
        deviceSettings.hubUrl = url
        val hubClient = client()
        val token = hubClient.login(pin)
        when (migration) {
            HubMigrationDirection.UseHubData -> {
                localStore.replaceSnapshot(hubClient.fetchSnapshot())
            }
            HubMigrationDirection.SeedHubFromPortal -> {
                hubClient.replaceState(token, localRepository.snapshot())
                localStore.replaceSnapshot(hubClient.fetchSnapshot())
            }
        }
        deviceSettings.portalMode = PortalMode.HubClient
        lastStatus = SyncStatus.Online(LocalDateTime.now())
    }

    fun switchToStandalone() {
        deviceSettings.portalMode = PortalMode.StandalonePortal
        lastStatus = SyncStatus.Standalone
    }

    private suspend fun flushPendingCompletions() {
        val pending = dao.pendingCompletions().map { it.toMutation() }
        val acknowledged = queueReplayer.replayInOrder(pending) { client().setCompletion(it) }
        acknowledged.forEach { dao.deletePendingCompletion(it.operationId) }
    }

    private fun client() = PortalHubClient(deviceSettings.hubUrl, deviceSettings.deviceId)

    private fun CompletionMutation.toEntity() = PendingCompletionEntity(
        operationId = operationId,
        taskId = taskId,
        routineDate = routineDate.toString(),
        completed = completed,
        changedAt = changedAt.toString(),
        deviceId = deviceId,
    )

    private fun PendingCompletionEntity.toMutation() = CompletionMutation(
        operationId = operationId,
        taskId = taskId,
        routineDate = LocalDate.parse(routineDate),
        completed = completed,
        changedAt = LocalDateTime.parse(changedAt),
        deviceId = deviceId,
    )
}

sealed class SyncStatus {
    data object Standalone : SyncStatus()
    data object Unknown : SyncStatus()
    data class Online(val checkedAt: LocalDateTime) : SyncStatus()
    data class Offline(val reason: String, val checkedAt: LocalDateTime) : SyncStatus()
}

enum class HubMigrationDirection {
    UseHubData,
    SeedHubFromPortal,
}
