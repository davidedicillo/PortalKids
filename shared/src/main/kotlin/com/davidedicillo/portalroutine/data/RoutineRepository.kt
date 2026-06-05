package com.davidedicillo.portalroutine.data

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.min

class RoutineRepository(private val store: RoutineStore) {
    suspend fun snapshot(): StoreSnapshot {
        ensureSeedData()
        return store.snapshot()
    }

    suspend fun storeSnapshot(snapshot: StoreSnapshot) {
        store.replaceSnapshot(snapshot)
        ensureSeedData()
    }

    suspend fun hasParentPin(): Boolean {
        return !snapshot().settings.parentPinHash.isNullOrBlank()
    }

    suspend fun ensureSeedData() {
        val snapshot = store.snapshot()
        if (snapshot.children.isNotEmpty() && snapshot.windows.isNotEmpty()) {
            if (snapshot.tasks.any { needsVisualCueBackfill(it) }) {
                store.replaceSnapshot(
                    snapshot.copy(tasks = snapshot.tasks.map { task ->
                        if (needsVisualCueBackfill(task)) {
                            task.copy(visualCue = visualCueFor(task.id, task.title))
                        } else {
                            task
                        }
                    }),
                )
            }
            return
        }

        store.replaceSnapshot(
            snapshot.copy(
                children = defaultChildren(),
                windows = defaultWindows(),
                tasks = defaultTasks(),
                settings = snapshot.settings,
            ),
        )
    }

    suspend fun boardState(now: LocalDateTime): BoardState {
        ensureSeedData()
        ensureWalletInitialized(now)
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val activeWindow = RoutineEngine.activeWindow(
            windows = snapshot.windows,
            now = now,
            override = snapshot.settings.manualActiveWindowOverride,
        )
        val completedTaskIds = snapshot.completions
            .filter { it.localDate == routineDate && it.completed }
            .map { it.taskId }
            .toSet()
        val weekStart = routineDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val pointTotals = pointTotalsByChild(snapshot, routineDate, weekStart, weekEnd)
        val childIds = snapshot.children.map { it.id }
        val childStates = snapshot.children.sortedBy { it.sortOrder }.map { child ->
            val visibleTasks = snapshot.tasks
                .filter { it.enabled && it.childId == child.id && it.windowId == activeWindow.id && it.isActiveOn(routineDate) }
                .sortedBy { it.sortOrder }
                .map { task ->
                    val completion = snapshot.completions.firstOrNull { it.localDate == routineDate && it.taskId == task.id }
                    BoardTask(task, task.id in completedTaskIds, completion?.normalizedCount() ?: 0)
                }
            ChildBoardState(
                child = child,
                tasks = visibleTasks,
                progress = RoutineEngine.childProgress(snapshot.tasks, child.id, activeWindow.id, completedTaskIds, routineDate),
                points = pointTotals[child.id] ?: PointTotals(),
            )
        }

        return BoardState(
            routineDate = routineDate,
            activeWindow = activeWindow,
            weekStart = weekStart,
            weekEnd = weekEnd,
            children = childStates,
            rewards = snapshot.rewards.filter { it.enabled }.sortedWith(compareBy({ it.sortOrder }, { it.title }, { it.id })),
            allComplete = RoutineEngine.isRoutineComplete(snapshot.tasks, childIds, activeWindow.id, completedTaskIds, routineDate),
            settings = snapshot.settings,
        )
    }

    suspend fun toggleTask(taskId: String, now: LocalDateTime) {
        ensureWalletInitialized(now)
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val current = store.completion(routineDate, taskId)
        setTaskCompletionCount(taskId, if (current?.completed == true) 0 else 1, now, routineDate)
    }

    suspend fun setTaskCompletion(
        taskId: String,
        completed: Boolean,
        now: LocalDateTime,
        routineDate: LocalDate? = null,
    ) {
        setTaskCompletionCount(taskId, if (completed) 1 else 0, now, routineDate)
    }

    suspend fun setTaskCompletionCount(
        taskId: String,
        count: Int,
        now: LocalDateTime,
        routineDate: LocalDate? = null,
    ) {
        require(count >= 0) { "count must be zero or greater" }
        ensureWalletInitialized(now)
        val snapshot = store.snapshot()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: error("Unknown task $taskId")
        val targetDate = routineDate ?: RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val targetCount = if (task.repeatable) count else if (count > 0) 1 else 0
        val current = store.completion(targetDate, taskId)
        val next = if (targetCount > 0) {
            DailyCompletion(
                localDate = targetDate,
                taskId = taskId,
                completed = true,
                completedAt = current?.completedAt ?: now,
                clearedAt = null,
                count = targetCount,
            )
        } else {
            DailyCompletion(
                localDate = targetDate,
                taskId = taskId,
                completed = false,
                completedAt = current?.completedAt,
                clearedAt = current?.clearedAt ?: now,
                count = 0,
            )
        }
        store.upsertCompletion(next)
        updateEarningEntry(task, next, now)
    }

    suspend fun resetDay(now: LocalDateTime) {
        ensureWalletInitialized(now)
        val snapshot = store.snapshot()
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        store.resetDate(routineDate, now)
        snapshot.walletEntries
            .filter { it.kind == WalletEntryKind.Earning && it.id.startsWith("earning:$routineDate:") }
            .forEach { entry -> store.deleteWalletEntry(entry.id) }
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = null))
    }

    suspend fun setManualWindowOverride(windowId: String, now: LocalDateTime) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = ActiveWindowOverride(windowId, now)))
    }

    suspend fun clearManualWindowOverride() {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(manualActiveWindowOverride = null))
    }

    suspend fun setParentPin(pin: String) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(parentPinHash = PinHasher.hash(pin)))
    }

    suspend fun verifyParentPin(pin: String): Boolean {
        return PinHasher.verify(pin, store.snapshot().settings.parentPinHash)
    }

    suspend fun updateChild(child: ChildConfig) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                children = snapshot.children
                    .filterNot { it.id == child.id }
                    .plus(child)
                    .sortedBy { it.sortOrder },
            ),
        )
    }

    suspend fun deleteChild(childId: String) {
        val snapshot = store.snapshot()
        if (snapshot.children.size <= 1) return

        store.replaceSnapshot(
            snapshot.copy(
                children = snapshot.children.filterNot { it.id == childId }.sortedBy { it.sortOrder },
                tasks = snapshot.tasks.filterNot { it.childId == childId },
                walletEntries = snapshot.walletEntries.filterNot { it.childId == childId },
            ),
        )
    }

    suspend fun updateWindow(window: RoutineWindowConfig) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                windows = snapshot.windows
                    .filterNot { it.id == window.id }
                    .plus(window)
                    .sortedBy { it.sortOrder },
            ),
        )
    }

    suspend fun deleteWindow(windowId: String) {
        val snapshot = store.snapshot()
        if (snapshot.windows.size <= 1) return

        val nextOverride = snapshot.settings.manualActiveWindowOverride
            ?.takeUnless { it.windowId == windowId }
        store.replaceSnapshot(
            snapshot.copy(
                windows = snapshot.windows.filterNot { it.id == windowId }.sortedBy { it.sortOrder },
                tasks = snapshot.tasks.filterNot { it.windowId == windowId },
                settings = snapshot.settings.copy(manualActiveWindowOverride = nextOverride),
            ),
        )
    }

    suspend fun upsertTask(task: RoutineTask) {
        require(task.pointValue >= 0) { "pointValue must be zero or greater" }
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                tasks = snapshot.tasks
                    .filterNot { it.id == task.id }
                    .plus(task)
                    .sortedWith(compareBy({ it.childId }, { it.windowId }, { it.sortOrder })),
            ),
        )
    }

    suspend fun reorderTasks(childId: String, windowId: String, taskIds: List<String>) {
        val snapshot = store.snapshot()
        val targetTasks = snapshot.tasks
            .filter { it.childId == childId && it.windowId == windowId }
            .sortedWith(compareBy({ it.sortOrder }, { it.title }, { it.id }))
        val targetTaskIds = targetTasks.map { it.id }.toSet()
        val requestedTaskIds = taskIds.distinct().filter { it in targetTaskIds }
        if (requestedTaskIds.isEmpty()) return

        val remainingTaskIds = targetTasks
            .map { it.id }
            .filterNot { it in requestedTaskIds }
        val orderById = (requestedTaskIds + remainingTaskIds)
            .withIndex()
            .associate { (index, taskId) -> taskId to index }

        store.replaceSnapshot(
            snapshot.copy(
                tasks = snapshot.tasks
                    .map { task ->
                        val nextOrder = orderById[task.id]
                        if (nextOrder != null && task.childId == childId && task.windowId == windowId) {
                            task.copy(sortOrder = nextOrder)
                        } else {
                            task
                        }
                    }
                    .sortedWith(compareBy({ it.childId }, { it.windowId }, { it.sortOrder })),
            ),
        )
    }

    suspend fun deleteTask(taskId: String) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(snapshot.copy(tasks = snapshot.tasks.filterNot { it.id == taskId }))
    }

    suspend fun updateDailyResetTime(time: LocalTime) {
        val snapshot = store.snapshot()
        store.updateSettings(snapshot.settings.copy(dailyResetTime = time))
    }

    suspend fun updateReward(reward: RewardConfig) {
        require(reward.pointCost >= 0) { "pointCost must be zero or greater" }
        val snapshot = store.snapshot()
        store.replaceSnapshot(
            snapshot.copy(
                rewards = snapshot.rewards
                    .filterNot { it.id == reward.id }
                    .plus(reward)
                    .sortedWith(compareBy({ it.sortOrder }, { it.title }, { it.id })),
            ),
        )
    }

    suspend fun deleteReward(rewardId: String) {
        val snapshot = store.snapshot()
        store.replaceSnapshot(snapshot.copy(rewards = snapshot.rewards.filterNot { it.id == rewardId }))
    }

    suspend fun redeemReward(
        childId: String,
        rewardId: String,
        now: LocalDateTime,
        operationId: String = UUID.randomUUID().toString(),
    ): Boolean {
        ensureWalletInitialized(now)
        if (store.walletEntry(operationId) != null) return false
        val snapshot = store.snapshot()
        val child = snapshot.children.firstOrNull { it.id == childId } ?: return false
        val reward = snapshot.rewards.firstOrNull { it.id == rewardId && it.enabled } ?: return false
        if (walletBalance(snapshot, child.id) < reward.pointCost) return false

        store.upsertWalletEntry(
            WalletEntry(
                id = operationId,
                childId = child.id,
                amount = -reward.pointCost,
                kind = WalletEntryKind.RewardRedemption,
                reason = reward.title,
                createdAt = now,
                sourceId = reward.id,
            ),
        )
        return true
    }

    suspend fun deductPoints(
        childId: String,
        amount: Int,
        reason: String,
        now: LocalDateTime,
        operationId: String = UUID.randomUUID().toString(),
    ): Int {
        require(amount >= 0) { "amount must be zero or greater" }
        ensureWalletInitialized(now)
        if (amount == 0) return 0
        val snapshot = store.snapshot()
        if (snapshot.children.none { it.id == childId }) return 0
        val applied = min(amount, walletBalance(snapshot, childId))
        if (applied <= 0) return 0
        store.upsertWalletEntry(
            WalletEntry(
                id = operationId,
                childId = childId,
                amount = -applied,
                kind = WalletEntryKind.Deduction,
                reason = reason.ifBlank { "Parent deduction" },
                createdAt = now,
                sourceId = null,
            ),
        )
        return applied
    }

    suspend fun addPoints(
        childId: String,
        amount: Int,
        reason: String,
        now: LocalDateTime,
        operationId: String = UUID.randomUUID().toString(),
    ): Int {
        require(amount >= 0) { "amount must be zero or greater" }
        ensureWalletInitialized(now)
        if (amount == 0) return 0
        if (store.walletEntry(operationId) != null) return 0
        val snapshot = store.snapshot()
        if (snapshot.children.none { it.id == childId }) return 0
        store.upsertWalletEntry(
            WalletEntry(
                id = operationId,
                childId = childId,
                amount = amount,
                kind = WalletEntryKind.ManualGrant,
                reason = reason.ifBlank { "Parent point grant" },
                createdAt = now,
                sourceId = null,
            ),
        )
        return amount
    }

    suspend fun refundRewardRedemption(
        redemptionEntryId: String,
        now: LocalDateTime,
        operationId: String = UUID.randomUUID().toString(),
    ): Int {
        ensureWalletInitialized(now)
        if (store.walletEntry(operationId) != null) return 0
        val snapshot = store.snapshot()
        val redemption = snapshot.walletEntries.firstOrNull { entry ->
            entry.id == redemptionEntryId &&
                entry.kind == WalletEntryKind.RewardRedemption &&
                entry.amount < 0
        } ?: return 0
        val alreadyRefunded = snapshot.walletEntries.any { entry ->
            entry.kind == WalletEntryKind.RewardRefund && entry.sourceId == redemption.id
        }
        if (alreadyRefunded) return 0
        val refundAmount = -redemption.amount
        store.upsertWalletEntry(
            WalletEntry(
                id = operationId,
                childId = redemption.childId,
                amount = refundAmount,
                kind = WalletEntryKind.RewardRefund,
                reason = "Refund: ${redemption.reason}",
                createdAt = now,
                sourceId = redemption.id,
            ),
        )
        return refundAmount
    }

    private fun defaultChildren(): List<ChildConfig> = listOf(
        ChildConfig("child-a", "Kid A", "#1F8A70", 0),
        ChildConfig("child-b", "Kid B", "#D95F59", 1),
    )

    private fun defaultWindows(): List<RoutineWindowConfig> = listOf(
        RoutineWindowConfig("morning", "Morning", LocalTime.of(6, 30), 0),
        RoutineWindowConfig("after-school", "After School", LocalTime.of(15, 0), 1),
        RoutineWindowConfig("evening", "Evening", LocalTime.of(18, 30), 2),
    )

    private fun defaultTasks(): List<RoutineTask> = listOf(
        RoutineTask("a-morning-brush", "child-a", "morning", "Brush teeth", true, 0, visualCue = "😁"),
        RoutineTask("a-morning-dress", "child-a", "morning", "Get dressed", true, 1, visualCue = "👕"),
        RoutineTask("a-morning-bag", "child-a", "morning", "Pack backpack", true, 2, visualCue = "🎒"),
        RoutineTask("b-morning-brush", "child-b", "morning", "Brush teeth", true, 0, visualCue = "😁"),
        RoutineTask("b-morning-dress", "child-b", "morning", "Get dressed", true, 1, visualCue = "👕"),
        RoutineTask("b-morning-bag", "child-b", "morning", "Pack backpack", true, 2, visualCue = "🎒"),
        RoutineTask("a-after-school-shoes", "child-a", "after-school", "Put shoes away", true, 0, visualCue = "👟"),
        RoutineTask("a-after-school-snack", "child-a", "after-school", "Snack and water", true, 1, visualCue = "🍎"),
        RoutineTask("a-after-school-homework", "child-a", "after-school", "Homework check", true, 2, visualCue = "✏️"),
        RoutineTask("b-after-school-shoes", "child-b", "after-school", "Put shoes away", true, 0, visualCue = "👟"),
        RoutineTask("b-after-school-snack", "child-b", "after-school", "Snack and water", true, 1, visualCue = "🍎"),
        RoutineTask("b-after-school-homework", "child-b", "after-school", "Homework check", true, 2, visualCue = "✏️"),
        RoutineTask("a-evening-pajamas", "child-a", "evening", "Pajamas", true, 0, visualCue = "🛏️"),
        RoutineTask("a-evening-teeth", "child-a", "evening", "Brush teeth", true, 1, visualCue = "😁"),
        RoutineTask("a-evening-books", "child-a", "evening", "Choose books", true, 2, visualCue = "📚"),
        RoutineTask("b-evening-pajamas", "child-b", "evening", "Pajamas", true, 0, visualCue = "🛏️"),
        RoutineTask("b-evening-teeth", "child-b", "evening", "Brush teeth", true, 1, visualCue = "😁"),
        RoutineTask("b-evening-books", "child-b", "evening", "Choose books", true, 2, visualCue = "📚"),
    )

    private fun needsVisualCueBackfill(task: RoutineTask): Boolean {
        return task.visualCue.isBlank() || task.visualCue == "🪥"
    }

    private fun pointTotalsByChild(
        snapshot: StoreSnapshot,
        routineDate: LocalDate,
        weekStart: LocalDate,
        weekEnd: LocalDate,
    ): Map<String, PointTotals> {
        val childIds = snapshot.children.map { it.id }.toSet()
        val daily = mutableMapOf<String, Int>()
        val weekly = mutableMapOf<String, Int>()
        val wallet = snapshot.walletEntries
            .filter { it.childId in childIds }
            .groupBy { it.childId }
            .mapValues { (_, entries) -> entries.sumOf { it.amount }.coerceAtLeast(0) }

        snapshot.walletEntries
            .filter { it.childId in childIds && it.kind == WalletEntryKind.Earning }
            .forEach { entry ->
                val earningDate = entry.earningDate() ?: return@forEach
                if (earningDate == routineDate) {
                    daily[entry.childId] = daily.getOrDefault(entry.childId, 0) + entry.amount
                }
                if (!earningDate.isBefore(weekStart) && !earningDate.isAfter(weekEnd)) {
                    weekly[entry.childId] = weekly.getOrDefault(entry.childId, 0) + entry.amount
                }
            }

        return childIds.associateWith { childId ->
            PointTotals(
                daily = daily.getOrDefault(childId, 0),
                weekly = weekly.getOrDefault(childId, 0),
                wallet = wallet.getOrDefault(childId, 0),
            )
        }
    }

    private suspend fun ensureWalletInitialized(now: LocalDateTime) {
        val snapshot = store.snapshot()
        if (snapshot.settings.walletInitializedAt != null) return
        val routineDate = RoutineEngine.effectiveRoutineDate(now, snapshot.settings.dailyResetTime)
        val weekStart = routineDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val tasksById = snapshot.tasks.associateBy { it.id }
        val existingEntryIds = snapshot.walletEntries.map { it.id }.toSet()
        val seedEntries = snapshot.completions
            .filter { completion ->
                completion.completed &&
                    !completion.localDate.isBefore(weekStart) &&
                    !completion.localDate.isAfter(weekEnd)
            }
            .mapNotNull { completion ->
                val task = tasksById[completion.taskId] ?: return@mapNotNull null
                val id = earningEntryId(completion.localDate, task.childId, completion.taskId)
                val legacyId = legacyEarningEntryId(completion.localDate, completion.taskId)
                if (id in existingEntryIds) return@mapNotNull null
                if (snapshot.walletEntries.any { it.id == legacyId && it.childId == task.childId }) return@mapNotNull null
                val amount = task.pointsFor(completion)
                if (amount <= 0) return@mapNotNull null
                WalletEntry(
                    id = id,
                    childId = task.childId,
                    amount = amount,
                    kind = WalletEntryKind.Earning,
                    reason = task.title,
                    createdAt = completion.completedAt ?: now,
                    sourceId = id,
                )
            }
        store.replaceSnapshot(
            snapshot.copy(
                walletEntries = (snapshot.walletEntries + seedEntries)
                    .sortedWith(compareBy({ it.createdAt }, { it.id })),
                settings = snapshot.settings.copy(walletInitializedAt = now),
            ),
        )
    }

    private suspend fun updateEarningEntry(task: RoutineTask, completion: DailyCompletion, now: LocalDateTime) {
        val id = earningEntryId(completion.localDate, task.childId, completion.taskId)
        val legacyId = legacyEarningEntryId(completion.localDate, completion.taskId)
        val sameChildLegacyEntry = store.walletEntry(legacyId)?.takeIf { it.childId == task.childId }
        val amount = task.pointsFor(completion)
        if (!completion.completed || amount <= 0) {
            store.deleteWalletEntry(id)
            if (sameChildLegacyEntry != null) {
                store.deleteWalletEntry(legacyId)
            }
            return
        }
        if (sameChildLegacyEntry != null) {
            store.deleteWalletEntry(legacyId)
        }
        store.upsertWalletEntry(
            WalletEntry(
                id = id,
                childId = task.childId,
                amount = amount,
                kind = WalletEntryKind.Earning,
                reason = task.title,
                createdAt = completion.completedAt ?: now,
                sourceId = id,
            ),
        )
    }

    private fun earningEntryId(localDate: LocalDate, childId: String, taskId: String): String = "earning:$localDate:$childId:$taskId"

    private fun legacyEarningEntryId(localDate: LocalDate, taskId: String): String = "earning:$localDate:$taskId"

    private fun DailyCompletion.normalizedCount(): Int {
        return if (!completed) 0 else count.coerceAtLeast(1)
    }

    private fun RoutineTask.pointsFor(completion: DailyCompletion): Int {
        return pointValue.coerceAtLeast(0) * completion.normalizedCount()
    }

    private fun walletBalance(snapshot: StoreSnapshot, childId: String): Int {
        return snapshot.walletEntries
            .filter { it.childId == childId }
            .sumOf { it.amount }
            .coerceAtLeast(0)
    }

    private fun WalletEntry.earningDate(): LocalDate? {
        val value = sourceId ?: id
        if (!value.startsWith("earning:")) return null
        return runCatching { LocalDate.parse(value.removePrefix("earning:").substringBefore(":")) }.getOrNull()
    }

    private fun visualCueFor(taskId: String, title: String): String {
        val normalized = "$taskId $title".lowercase()
        return when {
            "brush" in normalized || "teeth" in normalized -> "😁"
            "dress" in normalized -> "👕"
            "bag" in normalized || "backpack" in normalized -> "🎒"
            "shoe" in normalized -> "👟"
            "snack" in normalized || "water" in normalized -> "🍎"
            "homework" in normalized -> "✏️"
            "pajama" in normalized -> "🛏️"
            "book" in normalized -> "📚"
            else -> "⭐"
        }
    }
}
