package com.davidedicillo.portalroutine.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RoutineDao {
    @Query("SELECT * FROM children ORDER BY sortOrder")
    suspend fun children(): List<ChildEntity>

    @Query("SELECT * FROM routine_windows ORDER BY sortOrder")
    suspend fun windows(): List<RoutineWindowEntity>

    @Query("SELECT * FROM routine_tasks ORDER BY childId, windowId, sortOrder")
    suspend fun tasks(): List<RoutineTaskEntity>

    @Query("SELECT * FROM daily_completions ORDER BY localDate DESC, taskId")
    suspend fun completions(): List<DailyCompletionEntity>

    @Query("SELECT * FROM rewards ORDER BY sortOrder, title, id")
    suspend fun rewards(): List<RewardEntity>

    @Query("SELECT * FROM wallet_entries ORDER BY createdAt, id")
    suspend fun walletEntries(): List<WalletEntryEntity>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun settings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChildren(children: List<ChildEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWindows(windows: List<RoutineWindowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<RoutineTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompletion(completion: DailyCompletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompletions(completions: List<DailyCompletionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRewards(rewards: List<RewardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWalletEntries(entries: List<WalletEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWalletEntry(entry: WalletEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingCompletion(completion: PendingCompletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingWalletMutation(mutation: PendingWalletMutationEntity)

    @Query("DELETE FROM children")
    suspend fun deleteChildren()

    @Query("DELETE FROM routine_windows")
    suspend fun deleteWindows()

    @Query("DELETE FROM routine_tasks")
    suspend fun deleteTasks()

    @Query("DELETE FROM daily_completions")
    suspend fun deleteCompletions()

    @Query("DELETE FROM rewards")
    suspend fun deleteRewards()

    @Query("DELETE FROM wallet_entries")
    suspend fun deleteWalletEntries()

    @Query("DELETE FROM wallet_entries WHERE id = :id")
    suspend fun deleteWalletEntry(id: String)

    @Query("SELECT * FROM wallet_entries WHERE id = :id")
    suspend fun walletEntry(id: String): WalletEntryEntity?

    @Query("SELECT * FROM pending_completions ORDER BY changedAt, operationId")
    suspend fun pendingCompletions(): List<PendingCompletionEntity>

    @Query("DELETE FROM pending_completions WHERE operationId = :operationId")
    suspend fun deletePendingCompletion(operationId: String)

    @Query("SELECT COUNT(*) FROM pending_completions")
    suspend fun pendingCompletionCount(): Int

    @Query("SELECT * FROM pending_wallet_mutations ORDER BY createdAt, operationId")
    suspend fun pendingWalletMutations(): List<PendingWalletMutationEntity>

    @Query("DELETE FROM pending_wallet_mutations WHERE operationId = :operationId")
    suspend fun deletePendingWalletMutation(operationId: String)

    @Query("SELECT COUNT(*) FROM pending_wallet_mutations")
    suspend fun pendingWalletMutationCount(): Int

    @Query("SELECT * FROM daily_completions WHERE localDate = :localDate AND taskId = :taskId")
    suspend fun completion(localDate: String, taskId: String): DailyCompletionEntity?

    @Query("UPDATE daily_completions SET completed = 0, count = 0, clearedAt = :clearedAt WHERE localDate = :localDate AND completed = 1")
    suspend fun resetDate(localDate: String, clearedAt: String)

    @Transaction
    suspend fun replaceConfig(
        children: List<ChildEntity>,
        windows: List<RoutineWindowEntity>,
        tasks: List<RoutineTaskEntity>,
        completions: List<DailyCompletionEntity>,
        rewards: List<RewardEntity>,
        walletEntries: List<WalletEntryEntity>,
        settings: SettingsEntity,
    ) {
        deleteChildren()
        deleteWindows()
        deleteTasks()
        deleteCompletions()
        deleteRewards()
        deleteWalletEntries()
        upsertChildren(children)
        upsertWindows(windows)
        upsertTasks(tasks)
        upsertCompletions(completions)
        upsertRewards(rewards)
        upsertWalletEntries(walletEntries)
        upsertSettings(settings)
    }
}
