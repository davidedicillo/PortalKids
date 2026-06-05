package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoutineRepositoryTest {
    @Test
    fun seedDefaultsCreatesTwoChildrenRoutineWindowsAndTasks() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)

        repository.ensureSeedData()
        val snapshot = store.snapshot()

        assertEquals(listOf("child-a", "child-b"), snapshot.children.map { it.id })
        assertEquals(listOf("morning", "after-school", "evening"), snapshot.windows.map { it.id })
        assertTrue(snapshot.tasks.any { it.childId == "child-a" && it.windowId == "morning" })
        assertTrue(snapshot.tasks.any { it.childId == "child-b" && it.windowId == "evening" })
        assertTrue(snapshot.tasks.all { it.visualCue.isNotBlank() })
        assertFalse(snapshot.tasks.any { it.visualCue == "🪥" })
        assertEquals(LocalTime.of(5, 0), snapshot.settings.dailyResetTime)
    }

    @Test
    fun seedDefaultsBackfillsVisualCuesForExistingTasks() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()
        val existing = store.snapshot()
        store.replaceSnapshot(
            existing.copy(
                tasks = existing.tasks.map { task -> task.copy(visualCue = "") },
            ),
        )

        repository.ensureSeedData()

        assertTrue(store.snapshot().tasks.all { it.visualCue.isNotBlank() })
    }

    @Test
    fun seedDefaultsReplacesUnsupportedToothbrushCueOnUpgrade() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()
        val existing = store.snapshot()
        store.replaceSnapshot(
            existing.copy(
                tasks = existing.tasks.map { task ->
                    if ("brush" in task.id || "teeth" in task.title.lowercase()) task.copy(visualCue = "🪥") else task
                },
            ),
        )

        repository.ensureSeedData()

        assertFalse(store.snapshot().tasks.any { it.visualCue == "🪥" })
    }


    @Test
    fun toggleTaskCompletesAndClearsForEffectiveRoutineDate() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 12, 7, 0)
        repository.ensureSeedData()

        repository.toggleTask("a-morning-brush", now)
        var completion = store.completion(LocalDate.of(2026, 5, 12), "a-morning-brush")
        assertNotNull(completion)
        assertTrue(completion!!.completed)
        assertEquals(now, completion.completedAt)
        assertNull(completion.clearedAt)

        repository.toggleTask("a-morning-brush", now.plusMinutes(5))
        completion = store.completion(LocalDate.of(2026, 5, 12), "a-morning-brush")
        assertNotNull(completion)
        assertFalse(completion!!.completed)
        assertEquals(now, completion.completedAt)
        assertEquals(now.plusMinutes(5), completion.clearedAt)
    }

    @Test
    fun setTaskCompletionUsesExplicitStateAndRoutineDate() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        val routineDate = LocalDate.of(2026, 5, 11)
        repository.ensureSeedData()

        repository.setTaskCompletion(
            taskId = "a-morning-brush",
            completed = true,
            now = LocalDateTime.of(2026, 5, 12, 4, 30),
            routineDate = routineDate,
        )
        repository.setTaskCompletion(
            taskId = "a-morning-brush",
            completed = true,
            now = LocalDateTime.of(2026, 5, 12, 4, 35),
            routineDate = routineDate,
        )

        val completion = store.completion(routineDate, "a-morning-brush")
        assertNotNull(completion)
        assertTrue(completion!!.completed)
        assertEquals(LocalDateTime.of(2026, 5, 12, 4, 30), completion.completedAt)
    }

    @Test
    fun manualResetClearsOnlyCurrentRoutineDate() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()
        repository.toggleTask("a-morning-brush", LocalDateTime.of(2026, 5, 11, 7, 0))
        repository.toggleTask("a-morning-brush", LocalDateTime.of(2026, 5, 12, 7, 0))

        repository.resetDay(LocalDateTime.of(2026, 5, 12, 9, 0))

        assertTrue(store.completion(LocalDate.of(2026, 5, 11), "a-morning-brush")!!.completed)
        assertFalse(store.completion(LocalDate.of(2026, 5, 12), "a-morning-brush")!!.completed)
    }

    @Test
    fun boardStateUsesActiveWindowCompletionsAndProgress() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 12, 7, 0)
        repository.ensureSeedData()
        repository.toggleTask("a-morning-brush", now)

        val board = repository.boardState(now)
        val childA = board.children.first { it.child.id == "child-a" }
        val childB = board.children.first { it.child.id == "child-b" }

        assertEquals("morning", board.activeWindow.id)
        assertTrue(childA.tasks.all { it.task.visualCue.isNotBlank() })
        assertEquals(1, childA.progress.completed)
        assertEquals(3, childA.progress.total)
        assertEquals(0, childB.progress.completed)
        assertEquals(3, childB.progress.total)
        assertFalse(board.allComplete)
    }

    @Test
    fun boardStateShowsOnlyTasksActiveOnTheRoutineDate() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("after-school", "After School", LocalTime.of(13, 0), 0)),
                tasks = listOf(
                    RoutineTask(
                        id = "monday-only",
                        childId = "child-a",
                        windowId = "after-school",
                        title = "Monday pickup",
                        enabled = true,
                        sortOrder = 0,
                        activeDays = setOf(DayOfWeek.MONDAY),
                    ),
                    RoutineTask(
                        id = "tuesday-only",
                        childId = "child-a",
                        windowId = "after-school",
                        title = "Tuesday club",
                        enabled = true,
                        sortOrder = 1,
                        activeDays = setOf(DayOfWeek.TUESDAY),
                    ),
                    RoutineTask(
                        id = "every-day",
                        childId = "child-a",
                        windowId = "after-school",
                        title = "Snack",
                        enabled = true,
                        sortOrder = 2,
                    ),
                ),
            ),
        )
        val repository = RoutineRepository(store)

        val board = repository.boardState(LocalDateTime.of(2026, 5, 18, 13, 15))

        val child = board.children.single()
        assertEquals(listOf("monday-only", "every-day"), child.tasks.map { it.task.id })
        assertEquals(0, child.progress.completed)
        assertEquals(2, child.progress.total)
    }

    @Test
    fun reorderTasksUpdatesSortOrderWithinChildWindowOnly() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(
                    ChildConfig("child-a", "Kid A", "#1F8A70", 0),
                    ChildConfig("child-b", "Kid B", "#D95F59", 1),
                ),
                windows = listOf(
                    RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0),
                    RoutineWindowConfig("evening", "Evening", LocalTime.of(18, 30), 1),
                ),
                tasks = listOf(
                    RoutineTask("brush", "child-a", "morning", "Brush", enabled = true, sortOrder = 0),
                    RoutineTask("bag", "child-a", "morning", "Bag", enabled = true, sortOrder = 1),
                    RoutineTask("shoes", "child-a", "morning", "Shoes", enabled = true, sortOrder = 2),
                    RoutineTask("books", "child-a", "evening", "Books", enabled = true, sortOrder = 0),
                    RoutineTask("b-brush", "child-b", "morning", "Brush", enabled = true, sortOrder = 0),
                ),
            ),
        )
        val repository = RoutineRepository(store)

        repository.reorderTasks("child-a", "morning", listOf("shoes", "brush", "bag"))

        val snapshot = store.snapshot()
        assertEquals(
            listOf("shoes", "brush", "bag"),
            snapshot.tasks
                .filter { it.childId == "child-a" && it.windowId == "morning" }
                .sortedBy { it.sortOrder }
                .map { it.id },
        )
        assertEquals(0, snapshot.tasks.single { it.id == "books" }.sortOrder)
        assertEquals(0, snapshot.tasks.single { it.id == "b-brush" }.sortOrder)
    }

    @Test
    fun boardStateIncludesDailyAndMondaySundayWeeklyPointsByChild() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(
                    ChildConfig("child-a", "Kid A", "#1F8A70", 0),
                    ChildConfig("child-b", "Kid B", "#D95F59", 1),
                ),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("a-today", "child-a", "morning", "Today", enabled = true, sortOrder = 0),
                    RoutineTask("a-monday", "child-a", "morning", "Monday", enabled = true, sortOrder = 1),
                    RoutineTask("a-cleared", "child-a", "morning", "Cleared", enabled = true, sortOrder = 2),
                    RoutineTask("b-disabled", "child-b", "morning", "Disabled but earned", enabled = false, sortOrder = 0),
                    RoutineTask("b-next-week", "child-b", "morning", "Next week", enabled = true, sortOrder = 1),
                ),
                completions = listOf(
                    DailyCompletion(LocalDate.of(2026, 5, 13), "a-today", true, LocalDateTime.of(2026, 5, 13, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 11), "a-monday", true, LocalDateTime.of(2026, 5, 11, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 13), "a-cleared", false, LocalDateTime.of(2026, 5, 13, 7, 5), LocalDateTime.of(2026, 5, 13, 7, 10)),
                    DailyCompletion(LocalDate.of(2026, 5, 17), "b-disabled", true, LocalDateTime.of(2026, 5, 17, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 18), "b-next-week", true, LocalDateTime.of(2026, 5, 18, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 13), "deleted-task", true, LocalDateTime.of(2026, 5, 13, 7, 0), null),
                ),
            ),
        )
        val repository = RoutineRepository(store)

        val board = repository.boardState(LocalDateTime.of(2026, 5, 13, 8, 0))
        val childA = board.children.first { it.child.id == "child-a" }
        val childB = board.children.first { it.child.id == "child-b" }

        assertEquals(LocalDate.of(2026, 5, 11), board.weekStart)
        assertEquals(LocalDate.of(2026, 5, 17), board.weekEnd)
        assertEquals(PointTotals(daily = 1, weekly = 2, wallet = 2), childA.points)
        assertEquals(PointTotals(daily = 0, weekly = 1, wallet = 1), childB.points)
    }

    @Test
    fun walletInitializationSeedsCurrentWeekCompletionsOnly() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("last-week", "child-a", "morning", "Last week", enabled = true, sortOrder = 0, pointValue = 5),
                    RoutineTask("this-week", "child-a", "morning", "This week", enabled = true, sortOrder = 1, pointValue = 2),
                ),
                completions = listOf(
                    DailyCompletion(LocalDate.of(2026, 5, 10), "last-week", true, LocalDateTime.of(2026, 5, 10, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 11), "this-week", true, LocalDateTime.of(2026, 5, 11, 7, 0), null),
                ),
            ),
        )
        val repository = RoutineRepository(store)

        val board = repository.boardState(LocalDateTime.of(2026, 5, 13, 8, 0))

        assertEquals(2, board.children.single().points.wallet)
        assertEquals(
            listOf(2),
            store.snapshot().walletEntries.map { it.amount },
        )
        assertNotNull(store.snapshot().settings.walletInitializedAt)
    }

    @Test
    fun repeatableTaskCountUsesTaskPointValueForTotalsAndWallet() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask(
                        id = "reading",
                        childId = "child-a",
                        windowId = "morning",
                        title = "20 min reading",
                        enabled = true,
                        sortOrder = 0,
                        pointValue = 2,
                        repeatable = true,
                    ),
                ),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        repository.setTaskCompletionCount("reading", count = 3, now = now)
        var board = repository.boardState(now)

        assertEquals(PointTotals(daily = 6, weekly = 6, wallet = 6), board.children.single().points)
        assertEquals(3, store.completion(LocalDate.of(2026, 5, 13), "reading")!!.count)

        repository.setTaskCompletionCount("reading", count = 1, now = now.plusMinutes(5))
        board = repository.boardState(now.plusMinutes(5))

        assertEquals(PointTotals(daily = 2, weekly = 2, wallet = 2), board.children.single().points)
        assertEquals(1, store.snapshot().walletEntries.count { it.kind == WalletEntryKind.Earning })
    }

    @Test
    fun rewardRedemptionRequiresAffordableWalletAndRecordsSpend() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(RoutineTask("task-a", "child-a", "morning", "Task A", enabled = true, sortOrder = 0, pointValue = 4, repeatable = true)),
                rewards = listOf(RewardConfig("reward-a", "Movie night", pointCost = 5, enabled = true, sortOrder = 0)),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        repository.setTaskCompletion("task-a", completed = true, now = now)
        assertFalse(repository.redeemReward("child-a", "reward-a", now.plusMinutes(1), operationId = "too-expensive"))

        repository.setTaskCompletionCount("task-a", count = 2, now = now.plusMinutes(2))
        assertTrue(repository.redeemReward("child-a", "reward-a", now.plusMinutes(3), operationId = "redeem-1"))

        val board = repository.boardState(now.plusMinutes(4))
        assertEquals(3, board.children.single().points.wallet)
        assertEquals(listOf(8, -5), store.snapshot().walletEntries.map { it.amount }.sortedDescending())
    }

    @Test
    fun movingTaskToAnotherChildDoesNotMoveHistoricalWalletEarnings() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(
                    ChildConfig("child-a", "Kid A", "#1F8A70", 0),
                    ChildConfig("child-b", "Kid B", "#D95F59", 1),
                ),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("shared-task", "child-a", "morning", "Reading", enabled = true, sortOrder = 0, pointValue = 1, repeatable = true),
                ),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        repository.setTaskCompletionCount("shared-task", count = 1, now = now)
        repository.upsertTask(
            RoutineTask("shared-task", "child-b", "morning", "Reading", enabled = true, sortOrder = 0, pointValue = 1, repeatable = true),
        )
        repository.setTaskCompletionCount("shared-task", count = 2, now = now.plusMinutes(1))

        val board = repository.boardState(now.plusMinutes(2))
        assertEquals(1, board.children.first { it.child.id == "child-a" }.points.wallet)
        assertEquals(2, board.children.first { it.child.id == "child-b" }.points.wallet)
    }

    @Test
    fun existingLegacyEarningEntryForAnotherChildIsPreservedWhenTaskMoves() = runTest {
        val routineDate = LocalDate.of(2026, 5, 13)
        val legacyEarningId = "earning:$routineDate:shared-task"
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(
                    ChildConfig("child-a", "Kid A", "#1F8A70", 0),
                    ChildConfig("child-b", "Kid B", "#D95F59", 1),
                ),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("shared-task", "child-b", "morning", "Reading", enabled = true, sortOrder = 0, pointValue = 1, repeatable = true),
                ),
                completions = listOf(
                    DailyCompletion(routineDate, "shared-task", true, LocalDateTime.of(2026, 5, 13, 8, 0), null, count = 1),
                ),
                walletEntries = listOf(
                    WalletEntry(
                        id = legacyEarningId,
                        childId = "child-a",
                        amount = 1,
                        kind = WalletEntryKind.Earning,
                        reason = "Reading",
                        createdAt = LocalDateTime.of(2026, 5, 13, 8, 0),
                        sourceId = legacyEarningId,
                    ),
                ),
                settings = RoutineSettings.Default.copy(walletInitializedAt = LocalDateTime.of(2026, 5, 13, 8, 0)),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 5)

        repository.setTaskCompletionCount("shared-task", count = 2, now = now, routineDate = routineDate)

        val board = repository.boardState(now.plusMinutes(1))
        assertEquals(1, board.children.first { it.child.id == "child-a" }.points.wallet)
        assertEquals(2, board.children.first { it.child.id == "child-b" }.points.wallet)
        assertEquals(setOf("child-a", "child-b"), store.snapshot().walletEntries.map { it.childId }.toSet())
    }

    @Test
    fun parentDeductionClampsWalletAtZero() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(RoutineTask("task-a", "child-a", "morning", "Task A", enabled = true, sortOrder = 0, pointValue = 3)),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        repository.setTaskCompletion("task-a", completed = true, now = now)
        val deduction = repository.deductPoints("child-a", 10, "Misbehaving", now.plusMinutes(1))

        assertEquals(3, deduction)
        assertEquals(0, repository.boardState(now.plusMinutes(2)).children.single().points.wallet)
        assertEquals(-3, store.snapshot().walletEntries.single { it.kind == WalletEntryKind.Deduction }.amount)
    }

    @Test
    fun parentPointGrantAddsPositiveWalletEntry() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        val applied = repository.addPoints("child-a", 7, "Bonus", now, operationId = "grant-1")

        assertEquals(7, applied)
        assertEquals(7, repository.boardState(now.plusMinutes(1)).children.single().points.wallet)
        val entry = store.snapshot().walletEntries.single { it.kind == WalletEntryKind.ManualGrant }
        assertEquals(7, entry.amount)
        assertEquals("Bonus", entry.reason)
    }

    @Test
    fun rewardRedemptionRefundRestoresWalletOnce() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(RoutineTask("task-a", "child-a", "morning", "Task A", enabled = true, sortOrder = 0, pointValue = 5)),
                rewards = listOf(RewardConfig("reward-a", "Movie night", pointCost = 3, enabled = true, sortOrder = 0)),
            ),
        )
        val repository = RoutineRepository(store)
        val now = LocalDateTime.of(2026, 5, 13, 8, 0)

        repository.setTaskCompletion("task-a", completed = true, now = now)
        assertTrue(repository.redeemReward("child-a", "reward-a", now.plusMinutes(1), operationId = "redeem-1"))

        assertEquals(3, repository.refundRewardRedemption("redeem-1", now.plusMinutes(2), operationId = "refund-1"))
        assertEquals(0, repository.refundRewardRedemption("redeem-1", now.plusMinutes(3), operationId = "refund-2"))

        assertEquals(5, repository.boardState(now.plusMinutes(4)).children.single().points.wallet)
        val refund = store.snapshot().walletEntries.single { it.kind == WalletEntryKind.RewardRefund }
        assertEquals(3, refund.amount)
        assertEquals("redeem-1", refund.sourceId)
    }

    @Test
    fun weeklyPointsUseTheEffectiveRoutineDateBeforeDailyReset() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(
                    RoutineTask("sunday", "child-a", "morning", "Sunday", enabled = true, sortOrder = 0),
                    RoutineTask("monday", "child-a", "morning", "Monday", enabled = true, sortOrder = 1),
                ),
                completions = listOf(
                    DailyCompletion(LocalDate.of(2026, 5, 17), "sunday", true, LocalDateTime.of(2026, 5, 17, 7, 0), null),
                    DailyCompletion(LocalDate.of(2026, 5, 18), "monday", true, LocalDateTime.of(2026, 5, 18, 7, 0), null),
                ),
                settings = RoutineSettings.Default.copy(dailyResetTime = LocalTime.of(5, 0)),
            ),
        )
        val repository = RoutineRepository(store)

        val board = repository.boardState(LocalDateTime.of(2026, 5, 18, 4, 30))

        assertEquals(LocalDate.of(2026, 5, 17), board.routineDate)
        assertEquals(LocalDate.of(2026, 5, 11), board.weekStart)
        assertEquals(LocalDate.of(2026, 5, 17), board.weekEnd)
        assertEquals(PointTotals(daily = 1, weekly = 1, wallet = 1), board.children.single().points)
    }

    @Test
    fun manualWindowOverrideAffectsBoardUntilNextWindow() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()

        repository.setManualWindowOverride("morning", LocalDateTime.of(2026, 5, 12, 16, 0))

        assertEquals(
            "morning",
            repository.boardState(LocalDateTime.of(2026, 5, 12, 17, 0)).activeWindow.id,
        )
        assertEquals(
            "evening",
            repository.boardState(LocalDateTime.of(2026, 5, 12, 18, 45)).activeWindow.id,
        )
    }

    @Test
    fun parentPinIsStoredAsHashNotPlaintext() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()

        repository.setParentPin("1234")

        val hash = store.snapshot().settings.parentPinHash
        assertNotNull(hash)
        assertFalse(hash!!.contains("1234"))
        assertTrue(repository.verifyParentPin("1234"))
        assertFalse(repository.verifyParentPin("9999"))
    }

    @Test
    fun deleteChildRemovesAssignedTasksButKeepsAtLeastOneChild() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()

        repository.deleteChild("child-b")
        val afterDelete = store.snapshot()

        assertEquals(listOf("child-a"), afterDelete.children.map { it.id })
        assertFalse(afterDelete.tasks.any { it.childId == "child-b" })

        repository.deleteChild("child-a")

        assertEquals(listOf("child-a"), store.snapshot().children.map { it.id })
    }

    @Test
    fun deleteWindowRemovesAssignedTasksButKeepsAtLeastOneWindow() = runTest {
        val store = InMemoryRoutineStore()
        val repository = RoutineRepository(store)
        repository.ensureSeedData()

        repository.deleteWindow("after-school")
        val afterDelete = store.snapshot()

        assertEquals(listOf("morning", "evening"), afterDelete.windows.map { it.id })
        assertFalse(afterDelete.tasks.any { it.windowId == "after-school" })

        repository.deleteWindow("morning")
        repository.deleteWindow("evening")

        assertEquals(listOf("evening"), store.snapshot().windows.map { it.id })
    }
}
