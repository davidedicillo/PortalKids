package com.davidedicillo.portalroutine.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChildEntity::class,
        RoutineWindowEntity::class,
        RoutineTaskEntity::class,
        DailyCompletionEntity::class,
        RewardEntity::class,
        WalletEntryEntity::class,
        PendingCompletionEntity::class,
        PendingWalletMutationEntity::class,
        SettingsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class RoutineDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao

    companion object {
        @Volatile private var instance: RoutineDatabase? = null

        fun get(context: Context): RoutineDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RoutineDatabase::class.java,
                    "portal-routine.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_tasks ADD COLUMN visualCue TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_completions (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        routineDate TEXT NOT NULL,
                        completed INTEGER NOT NULL,
                        changedAt TEXT NOT NULL,
                        deviceId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE routine_tasks
                    ADD COLUMN activeDays TEXT NOT NULL DEFAULT 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY'
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_tasks ADD COLUMN pointValue INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE routine_tasks ADD COLUMN repeatable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_completions ADD COLUMN count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE daily_completions SET count = 1 WHERE completed = 1")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE pending_completions SET count = 1 WHERE completed = 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN walletInitializedAt TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rewards (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        pointCost INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        note TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wallet_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        childId TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        sourceId TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_wallet_mutations (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        childId TEXT NOT NULL,
                        rewardId TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        deviceId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
