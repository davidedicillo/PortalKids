package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class StoreJsonTest {
    @Test
    fun stateJsonIncludesPointSummaryForAdminDisplay() = runTest {
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                tasks = listOf(RoutineTask("task-a", "child-a", "morning", "Task A", enabled = true, sortOrder = 0)),
                completions = listOf(
                    DailyCompletion(LocalDate.of(2026, 5, 13), "task-a", true, LocalDateTime.of(2026, 5, 13, 7, 0), null),
                ),
            ),
        )
        val repository = RoutineRepository(store)
        val snapshot = repository.snapshot()
        val board = repository.boardState(LocalDateTime.of(2026, 5, 13, 8, 0))

        val points = StoreJson.stateJson(snapshot, board, "http://portal.local:8080").getJSONObject("points")
        val childPoints = points.getJSONArray("children").getJSONObject(0)

        assertEquals("2026-05-11", points.getString("weekStart"))
        assertEquals("2026-05-17", points.getString("weekEnd"))
        assertEquals("child-a", childPoints.getString("childId"))
        assertEquals("Kid A", childPoints.getString("displayName"))
        assertEquals(1, childPoints.getInt("daily"))
        assertEquals(1, childPoints.getInt("weekly"))
    }

    @Test
    fun snapshotJsonRoundTripsTaskActiveDays() {
        val snapshot = StoreSnapshot(
            children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
            windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
            tasks = listOf(
                RoutineTask(
                    id = "task-a",
                    childId = "child-a",
                    windowId = "morning",
                    title = "Task A",
                    enabled = true,
                    sortOrder = 0,
                    activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                ),
            ),
        )

        val json = StoreJson.snapshotJson(snapshot)
        val activeDays = json.getJSONArray("tasks").getJSONObject(0).getJSONArray("activeDays")
        val restored = StoreJson.snapshotFromState(json)

        assertEquals(listOf("MONDAY", "WEDNESDAY"), (0 until activeDays.length()).map(activeDays::getString))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), restored.tasks.single().activeDays)
    }

    @Test
    fun snapshotFromStateDefaultsLegacyTasksToEveryDay() {
        val json = JSONObject()
            .put("children", JSONArray())
            .put("windows", JSONArray())
            .put(
                "tasks",
                JSONArray().put(
                    JSONObject()
                        .put("id", "legacy-task")
                        .put("childId", "child-a")
                        .put("windowId", "morning")
                        .put("title", "Legacy Task"),
                ),
            )

        val restored = StoreJson.snapshotFromState(json)

        assertEquals(DayOfWeek.entries.toSet(), restored.tasks.single().activeDays)
    }

    @Test
    fun snapshotJsonRoundTripsWalletRewardsTaskPointsAndCompletionCount() {
        val snapshot = StoreSnapshot(
            children = listOf(ChildConfig("child-a", "Kid A", "#1F8A70", 0)),
            windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
            tasks = listOf(
                RoutineTask(
                    id = "reading",
                    childId = "child-a",
                    windowId = "morning",
                    title = "Reading",
                    enabled = true,
                    sortOrder = 0,
                    pointValue = 2,
                    repeatable = true,
                ),
            ),
            completions = listOf(
                DailyCompletion(LocalDate.of(2026, 5, 13), "reading", true, LocalDateTime.of(2026, 5, 13, 7, 0), null, count = 3),
            ),
            rewards = listOf(RewardConfig("reward-a", "Movie night", pointCost = 5, enabled = true, sortOrder = 0, note = "Friday")),
            walletEntries = listOf(
                WalletEntry(
                    id = "entry-a",
                    childId = "child-a",
                    amount = 6,
                    kind = WalletEntryKind.Earning,
                    reason = "Reading",
                    createdAt = LocalDateTime.of(2026, 5, 13, 7, 0),
                    sourceId = "2026-05-13:reading",
                ),
            ),
        )

        val restored = StoreJson.snapshotFromState(StoreJson.snapshotJson(snapshot))

        assertEquals(2, restored.tasks.single().pointValue)
        assertTrue(restored.tasks.single().repeatable)
        assertEquals(3, restored.completions.single().count)
        assertEquals("Movie night", restored.rewards.single().title)
        assertEquals(WalletEntryKind.Earning, restored.walletEntries.single().kind)
    }
}
