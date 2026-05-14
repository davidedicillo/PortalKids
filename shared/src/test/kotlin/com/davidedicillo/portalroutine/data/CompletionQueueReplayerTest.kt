package com.davidedicillo.portalroutine.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CompletionQueueReplayerTest {
    @Test
    fun replaysQueuedCompletionsOldestFirst() = runTest {
        val first = mutation("first", LocalDateTime.of(2026, 5, 12, 7, 0))
        val second = mutation("second", LocalDateTime.of(2026, 5, 12, 7, 1))
        val sent = mutableListOf<String>()

        val acknowledged = CompletionQueueReplayer().replayInOrder(listOf(second, first)) {
            sent += it.operationId
        }

        assertEquals(listOf("first", "second"), sent)
        assertEquals(listOf(first, second), acknowledged)
    }

    @Test
    fun stopsReplayAtFirstFailure() = runTest {
        val first = mutation("first", LocalDateTime.of(2026, 5, 12, 7, 0))
        val second = mutation("second", LocalDateTime.of(2026, 5, 12, 7, 1))
        val sent = mutableListOf<String>()

        try {
            CompletionQueueReplayer().replayInOrder(listOf(first, second)) {
                sent += it.operationId
                if (it.operationId == "first") error("hub offline")
            }
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("first"), sent)
    }

    private fun mutation(id: String, changedAt: LocalDateTime) = CompletionMutation(
        operationId = id,
        taskId = "a-morning-brush",
        routineDate = LocalDate.of(2026, 5, 12),
        completed = true,
        changedAt = changedAt,
        deviceId = "portal",
    )
}
