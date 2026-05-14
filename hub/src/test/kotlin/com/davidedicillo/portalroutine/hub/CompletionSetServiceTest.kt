package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.RoutineRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

class CompletionSetServiceTest {
    @Test
    fun duplicateOperationIdDoesNotApplyTheCompletionTwice() = runTest {
        val store = SqliteRoutineStore(Files.createTempFile("portalkids-hub", ".db").toFile())
        val repository = RoutineRepository(store)
        val service = CompletionSetService(repository, store)
        repository.ensureSeedData()
        val mutation = mutation(
            operationId = "same-op",
            completed = true,
            changedAt = LocalDateTime.of(2026, 5, 12, 7, 0),
        )

        assertTrue(service.setCompletion(mutation))
        assertFalse(service.setCompletion(mutation.copy(completed = false, changedAt = LocalDateTime.of(2026, 5, 12, 7, 5))))

        val completion = store.completion(LocalDate.of(2026, 5, 12), "a-morning-brush")
        assertTrue(completion!!.completed)
        assertEquals(LocalDateTime.of(2026, 5, 12, 7, 0), completion.completedAt)
    }

    @Test
    fun completionClearUsesTheExplicitRoutineDate() = runTest {
        val store = SqliteRoutineStore(Files.createTempFile("portalkids-hub", ".db").toFile())
        val repository = RoutineRepository(store)
        val service = CompletionSetService(repository, store)
        repository.ensureSeedData()

        service.setCompletion(mutation("set", true, LocalDateTime.of(2026, 5, 12, 4, 30), LocalDate.of(2026, 5, 11)))
        service.setCompletion(mutation("clear", false, LocalDateTime.of(2026, 5, 12, 4, 35), LocalDate.of(2026, 5, 11)))

        val completion = store.completion(LocalDate.of(2026, 5, 11), "a-morning-brush")
        assertFalse(completion!!.completed)
        assertEquals(LocalDateTime.of(2026, 5, 12, 4, 35), completion.clearedAt)
    }

    private fun mutation(
        operationId: String,
        completed: Boolean,
        changedAt: LocalDateTime,
        routineDate: LocalDate = LocalDate.of(2026, 5, 12),
    ) = CompletionMutation(
        operationId = operationId,
        taskId = "a-morning-brush",
        routineDate = routineDate,
        completed = completed,
        changedAt = changedAt,
        deviceId = "test-portal",
    )
}
