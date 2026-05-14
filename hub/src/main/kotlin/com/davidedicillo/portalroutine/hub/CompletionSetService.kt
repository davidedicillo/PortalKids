package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.RoutineRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

class CompletionSetService(
    private val repository: RoutineRepository,
    private val store: SqliteRoutineStore,
) {
    private val mutex = Mutex()

    suspend fun setCompletion(mutation: CompletionMutation, processedAt: LocalDateTime = LocalDateTime.now()): Boolean = mutex.withLock {
            if (store.completionOperationProcessed(mutation.operationId)) {
                return@withLock false
            }

        repository.setTaskCompletion(
            taskId = mutation.taskId,
            completed = mutation.completed,
            now = mutation.changedAt,
            routineDate = mutation.routineDate,
        )

        store.recordCompletionOperation(mutation, processedAt)
        true
    }
}
