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
        assertEquals(PointTotals(daily = 1, weekly = 2), childA.points)
        assertEquals(PointTotals(daily = 0, weekly = 1), childB.points)
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
        assertEquals(PointTotals(daily = 1, weekly = 1), board.children.single().points)
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
