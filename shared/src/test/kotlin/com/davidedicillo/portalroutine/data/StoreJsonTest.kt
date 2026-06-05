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
    fun stateJsonIncludesFullWalletEntriesForDeviceSync() = runTest {
        val child = ChildConfig("child-a", "Kid A", "#1F8A70", 0)
        val window = RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)
        val walletEntries = (0 until 125).map { index ->
            WalletEntry(
                id = "entry-$index",
                childId = child.id,
                amount = 1,
                kind = WalletEntryKind.Earning,
                reason = "Task $index",
                createdAt = LocalDateTime.of(2026, 5, 13, 7, 0).plusMinutes(index.toLong()),
                sourceId = "entry-$index",
            )
        }
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(child),
                windows = listOf(window),
                walletEntries = walletEntries,
            ),
        )
        val repository = RoutineRepository(store)
        val board = repository.boardState(LocalDateTime.of(2026, 5, 13, 8, 0))
        val snapshot = repository.snapshot()

        val json = StoreJson.stateJson(snapshot, board, "http://portal.local:8080")
        val restored = StoreJson.snapshotFromState(json)

        assertEquals(125, json.getJSONArray("walletEntries").length())
        assertEquals(100, json.getJSONArray("walletHistory").length())
        assertEquals(125, restored.walletEntries.size)
    }

    @Test
    fun stateJsonIncludesClaimedRewardRefundStatus() = runTest {
        val child = ChildConfig("child-a", "Kid A", "#1F8A70", 0)
        val redeemedAt = LocalDateTime.of(2026, 5, 13, 7, 0)
        val store = InMemoryRoutineStore(
            StoreSnapshot(
                children = listOf(child),
                windows = listOf(RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0)),
                walletEntries = listOf(
                    WalletEntry(
                        id = "redeem-1",
                        childId = child.id,
                        amount = -4,
                        kind = WalletEntryKind.RewardRedemption,
                        reason = "Movie night",
                        createdAt = redeemedAt,
                        sourceId = "reward-a",
                    ),
                    WalletEntry(
                        id = "refund-1",
                        childId = child.id,
                        amount = 4,
                        kind = WalletEntryKind.RewardRefund,
                        reason = "Refund: Movie night",
                        createdAt = redeemedAt.plusMinutes(5),
                        sourceId = "redeem-1",
                    ),
                ),
            ),
        )
        val repository = RoutineRepository(store)

        val json = StoreJson.stateJson(repository.snapshot(), repository.boardState(redeemedAt), "http://portal.local:8080")
        val claimedReward = json.getJSONArray("claimedRewards").getJSONObject(0)

        assertEquals("redeem-1", claimedReward.getString("id"))
        assertEquals("child-a", claimedReward.getString("childId"))
        assertEquals("reward-a", claimedReward.getString("rewardId"))
        assertEquals("Movie night", claimedReward.getString("title"))
        assertEquals(4, claimedReward.getInt("pointCost"))
        assertEquals(4, claimedReward.getInt("refundedAmount"))
        assertEquals(false, claimedReward.getBoolean("refundable"))
        assertEquals("refund-1", claimedReward.getString("refundEntryId"))
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
