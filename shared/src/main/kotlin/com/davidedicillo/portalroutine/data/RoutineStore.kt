package com.davidedicillo.portalroutine.data

import java.time.LocalDate
import java.time.LocalDateTime

interface RoutineStore {
    suspend fun snapshot(): StoreSnapshot
    suspend fun replaceSnapshot(snapshot: StoreSnapshot)
    suspend fun updateSettings(settings: RoutineSettings)
    suspend fun upsertCompletion(completion: DailyCompletion)
    suspend fun completion(localDate: LocalDate, taskId: String): DailyCompletion?
    suspend fun resetDate(localDate: LocalDate, clearedAt: LocalDateTime)
}
