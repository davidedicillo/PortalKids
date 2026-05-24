package com.davidedicillo.portalroutine.sync

import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.CompletionQueueReplayer
import com.davidedicillo.portalroutine.data.RewardRedemptionMutation
import com.davidedicillo.portalroutine.data.RoutineRepository
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.room.PendingCompletionEntity
import com.davidedicillo.portalroutine.data.room.PendingWalletMutationEntity
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
            flushPendingWalletMutations()
            localStore.replaceSnapshot(client().fetchSnapshot())
            lastStatus = SyncStatus.Online(LocalDateTime.now())
        } catch (error: Exception) {
            lastStatus = SyncStatus.Offline(error.message ?: "Hub unavailable", LocalDateTime.now())
        }
    }

    suspend fun setTaskCompletion(taskId: String, completed: Boolean, now: LocalDateTime) {
        setTaskCompletionCount(taskId, if (completed) 1 else 0, now)
    }

    suspend fun setTaskCompletionCount(taskId: String, count: Int, now: LocalDateTime) {
        val snapshot = localRepository.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val mutation = CompletionMutation(
            operationId = UUID.randomUUID().toString(),
            taskId = taskId,
            routineDate = routineDate,
            completed = count > 0,
            changedAt = now,
            deviceId = deviceSettings.deviceId,
            count = count,
        )
        localRepository.setTaskCompletionCount(taskId, count, now, routineDate)
        dao.upsertPendingCompletion(mutation.toEntity())
        syncOnce()
    }

    suspend fun redeemReward(childId: String, rewardId: String, now: LocalDateTime): Boolean {
        val mutation = RewardRedemptionMutation(
            operationId = UUID.randomUUID().toString(),
            childId = childId,
            rewardId = rewardId,
            createdAt = now,
            deviceId = deviceSettings.deviceId,
        )
        if (!localRepository.redeemReward(childId, rewardId, now, mutation.operationId)) {
            return false
        }
        dao.upsertPendingWalletMutation(mutation.toEntity())
        syncOnce()
        return true
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

    suspend fun pendingCompletionCount(): Int = dao.pendingCompletionCount() + dao.pendingWalletMutationCount()

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

    private suspend fun flushPendingWalletMutations() {
        val pending = dao.pendingWalletMutations().map { it.toMutation() }
        for (mutation in pending.sortedWith(compareBy({ it.createdAt }, { it.operationId }))) {
            client().redeemReward(mutation)
            dao.deletePendingWalletMutation(mutation.operationId)
        }
    }

    private fun client() = PortalHubClient(deviceSettings.hubUrl, deviceSettings.deviceId)

    private fun CompletionMutation.toEntity() = PendingCompletionEntity(
        operationId = operationId,
        taskId = taskId,
        routineDate = routineDate.toString(),
        completed = completed,
        changedAt = changedAt.toString(),
        deviceId = deviceId,
        count = count,
    )

    private fun PendingCompletionEntity.toMutation() = CompletionMutation(
        operationId = operationId,
        taskId = taskId,
        routineDate = LocalDate.parse(routineDate),
        completed = completed,
        changedAt = LocalDateTime.parse(changedAt),
        deviceId = deviceId,
        count = count,
    )

    private fun RewardRedemptionMutation.toEntity() = PendingWalletMutationEntity(
        operationId = operationId,
        childId = childId,
        rewardId = rewardId,
        createdAt = createdAt.toString(),
        deviceId = deviceId,
    )

    private fun PendingWalletMutationEntity.toMutation() = RewardRedemptionMutation(
        operationId = operationId,
        childId = childId,
        rewardId = rewardId,
        createdAt = LocalDateTime.parse(createdAt),
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
