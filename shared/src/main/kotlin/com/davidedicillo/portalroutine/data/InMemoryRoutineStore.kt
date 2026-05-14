package com.davidedicillo.portalroutine.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime

class InMemoryRoutineStore(initialSnapshot: StoreSnapshot = StoreSnapshot()) : RoutineStore {
    private val mutex = Mutex()
    private var current = initialSnapshot

    override suspend fun snapshot(): StoreSnapshot = mutex.withLock {
        current.copy(
            children = current.children.toList(),
            windows = current.windows.toList(),
            tasks = current.tasks.toList(),
            completions = current.completions.toList(),
        )
    }

    override suspend fun replaceSnapshot(snapshot: StoreSnapshot) {
        mutex.withLock {
            current = snapshot.copy(
                children = snapshot.children.sortedBy { it.sortOrder },
                windows = snapshot.windows.sortedBy { it.sortOrder },
                tasks = snapshot.tasks.sortedWith(compareBy({ it.childId }, { it.windowId }, { it.sortOrder })),
                completions = snapshot.completions.sortedWith(compareBy({ it.localDate }, { it.taskId })),
            )
        }
    }

    override suspend fun updateSettings(settings: RoutineSettings) {
        mutex.withLock {
            current = current.copy(settings = settings)
        }
    }

    override suspend fun upsertCompletion(completion: DailyCompletion) {
        mutex.withLock {
            current = current.copy(
                completions = current.completions
                    .filterNot { it.localDate == completion.localDate && it.taskId == completion.taskId }
                    .plus(completion)
                    .sortedWith(compareBy({ it.localDate }, { it.taskId })),
            )
        }
    }

    override suspend fun completion(localDate: LocalDate, taskId: String): DailyCompletion? = mutex.withLock {
        current.completions.firstOrNull { it.localDate == localDate && it.taskId == taskId }
    }

    override suspend fun resetDate(localDate: LocalDate, clearedAt: LocalDateTime) {
        mutex.withLock {
            current = current.copy(
                completions = current.completions.map { completion ->
                    if (completion.localDate == localDate && completion.completed) {
                        completion.copy(completed = false, clearedAt = clearedAt)
                    } else {
                        completion
                    }
                },
            )
        }
    }
}
