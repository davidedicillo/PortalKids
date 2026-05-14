package com.davidedicillo.portalroutine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoutineEngineTest {
    private val windows = listOf(
        RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0),
        RoutineWindowConfig("after-school", "After School", LocalTime.of(15, 0), 1),
        RoutineWindowConfig("evening", "Evening", LocalTime.of(18, 30), 2),
    )

    @Test
    fun selectsEarliestWindowBeforeFirstStart() {
        val active = RoutineEngine.activeWindow(
            windows = windows,
            now = LocalDateTime.of(2026, 5, 12, 5, 45),
            override = null,
        )

        assertEquals("morning", active.id)
    }

    @Test
    fun selectsLatestStartedWindowDuringDay() {
        val active = RoutineEngine.activeWindow(
            windows = windows,
            now = LocalDateTime.of(2026, 5, 12, 16, 15),
            override = null,
        )

        assertEquals("after-school", active.id)
    }

    @Test
    fun keepsLastWindowAfterFinalStart() {
        val active = RoutineEngine.activeWindow(
            windows = windows,
            now = LocalDateTime.of(2026, 5, 12, 22, 0),
            override = null,
        )

        assertEquals("evening", active.id)
    }

    @Test
    fun overrideExpiresAtNextWindowStart() {
        val beforeNextWindow = RoutineEngine.activeWindow(
            windows = windows,
            now = LocalDateTime.of(2026, 5, 12, 17, 0),
            override = ActiveWindowOverride("morning", LocalDateTime.of(2026, 5, 12, 16, 0)),
        )
        val afterNextWindow = RoutineEngine.activeWindow(
            windows = windows,
            now = LocalDateTime.of(2026, 5, 12, 18, 45),
            override = ActiveWindowOverride("morning", LocalDateTime.of(2026, 5, 12, 16, 0)),
        )

        assertEquals("morning", beforeNextWindow.id)
        assertEquals("evening", afterNextWindow.id)
    }

    @Test
    fun calculatesChildProgressForActiveTasks() {
        val progress = RoutineEngine.childProgress(
            tasks = listOf(
                RoutineTask("brush", "child-a", "morning", "Brush teeth", enabled = true, sortOrder = 0),
                RoutineTask("bag", "child-a", "morning", "Pack bag", enabled = true, sortOrder = 1),
                RoutineTask("shoes", "child-a", "after-school", "Shoes away", enabled = true, sortOrder = 2),
                RoutineTask("hidden", "child-a", "morning", "Hidden", enabled = false, sortOrder = 3),
            ),
            childId = "child-a",
            windowId = "morning",
            completions = setOf("brush"),
        )

        assertEquals(2, progress.total)
        assertEquals(1, progress.completed)
        assertFalse(progress.isComplete)
    }

    @Test
    fun routineIsCompleteOnlyWhenEveryVisibleChildTaskIsDone() {
        val tasks = listOf(
            RoutineTask("a1", "child-a", "evening", "Pajamas", enabled = true, sortOrder = 0),
            RoutineTask("b1", "child-b", "evening", "Pajamas", enabled = true, sortOrder = 0),
        )

        assertFalse(
            RoutineEngine.isRoutineComplete(
                tasks = tasks,
                childIds = listOf("child-a", "child-b"),
                windowId = "evening",
                completions = setOf("a1"),
            )
        )
        assertTrue(
            RoutineEngine.isRoutineComplete(
                tasks = tasks,
                childIds = listOf("child-a", "child-b"),
                windowId = "evening",
                completions = setOf("a1", "b1"),
            )
        )
    }

    @Test
    fun resetDateRollsBackBeforeDailyResetTime() {
        assertEquals(
            LocalDate.of(2026, 5, 11),
            RoutineEngine.effectiveRoutineDate(
                now = LocalDateTime.of(2026, 5, 12, 4, 30),
                dailyResetTime = LocalTime.of(5, 0),
            )
        )
        assertEquals(
            LocalDate.of(2026, 5, 12),
            RoutineEngine.effectiveRoutineDate(
                now = LocalDateTime.of(2026, 5, 12, 5, 0),
                dailyResetTime = LocalTime.of(5, 0),
            )
        )
    }
}
