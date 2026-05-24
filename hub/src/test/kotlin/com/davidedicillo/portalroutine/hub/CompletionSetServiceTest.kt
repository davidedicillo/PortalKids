package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.RewardConfig
import com.davidedicillo.portalroutine.data.RoutineRepository
import com.davidedicillo.portalroutine.data.StoreSnapshot
import com.davidedicillo.portalroutine.data.WalletEntryKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    @Test
    fun completionCountUpdatesWalletEarningOnce() = runTest {
        val store = SqliteRoutineStore(Files.createTempFile("portalkids-hub", ".db").toFile())
        val repository = RoutineRepository(store)
        val service = CompletionSetService(repository, store)
        repository.storeSnapshot(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("reading", "child-a", "morning", "Reading", enabled = true, sortOrder = 0, pointValue = 2, repeatable = true),
                ),
            ),
        )

        service.setCompletion(
            CompletionMutation(
                operationId = "reading-3",
                taskId = "reading",
                routineDate = LocalDate.of(2026, 5, 12),
                completed = true,
                changedAt = LocalDateTime.of(2026, 5, 12, 7, 0),
                deviceId = "test-portal",
                count = 3,
            ),
        )

        val snapshot = store.snapshot()
        assertEquals(3, snapshot.completions.single().count)
        assertEquals(6, snapshot.walletEntries.single { it.kind == WalletEntryKind.Earning }.amount)
        assertEquals(6, repository.boardState(LocalDateTime.of(2026, 5, 12, 8, 0)).children.single().points.wallet)
    }

    @Test
    fun rewardRedemptionOperationIdIsIdempotent() = runTest {
        val store = SqliteRoutineStore(Files.createTempFile("portalkids-hub", ".db").toFile())
        val repository = RoutineRepository(store)
        repository.storeSnapshot(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(RoutineTask("task-a", "child-a", "morning", "Task A", enabled = true, sortOrder = 0, pointValue = 5)),
                rewards = listOf(RewardConfig("reward-a", "Movie night", pointCost = 3, enabled = true, sortOrder = 0)),
            ),
        )
        repository.setTaskCompletion("task-a", completed = true, now = LocalDateTime.of(2026, 5, 12, 7, 0))

        assertTrue(repository.redeemReward("child-a", "reward-a", LocalDateTime.of(2026, 5, 12, 7, 5), "same-reward-op"))
        assertFalse(repository.redeemReward("child-a", "reward-a", LocalDateTime.of(2026, 5, 12, 7, 6), "same-reward-op"))

        val rewardSpends = store.snapshot().walletEntries.filter { it.kind == WalletEntryKind.RewardRedemption }
        assertEquals(1, rewardSpends.size)
        assertEquals(-3, rewardSpends.single().amount)
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
