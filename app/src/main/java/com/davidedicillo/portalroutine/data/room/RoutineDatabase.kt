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
        PendingCompletionEntity::class,
        SettingsEntity::class,
    ],
    version = 4,
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
    }
}
