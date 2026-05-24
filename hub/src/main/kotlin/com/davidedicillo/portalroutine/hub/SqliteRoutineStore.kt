package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.DailyCompletion
import com.davidedicillo.portalroutine.data.RewardConfig
import com.davidedicillo.portalroutine.data.RoutineSettings
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.StoreSnapshot
import com.davidedicillo.portalroutine.data.WalletEntry
import com.davidedicillo.portalroutine.data.WalletEntryKind
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SqliteRoutineStore(private val databaseFile: File) : RoutineStore {
    private val lock = Any()
    private val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    init {
        Class.forName("org.sqlite.JDBC")
        databaseFile.parentFile?.mkdirs()
        initialize()
    }

    override suspend fun snapshot(): StoreSnapshot = synchronized(lock) {
        connect { connection ->
            StoreSnapshot(
                children = connection.query("SELECT id, displayName, color, sortOrder FROM children ORDER BY sortOrder, id") {
                    ChildConfig(
                        id = getString("id"),
                        displayName = getString("displayName"),
                        color = getString("color"),
                        sortOrder = getInt("sortOrder"),
                    )
                },
                windows = connection.query("SELECT id, name, startTime, sortOrder FROM routine_windows ORDER BY sortOrder, startTime") {
                    RoutineWindowConfig(
                        id = getString("id"),
                        name = getString("name"),
                        startTime = LocalTime.parse(getString("startTime")),
                        sortOrder = getInt("sortOrder"),
                    )
                },
                tasks = connection.query("SELECT id, childId, windowId, title, visualCue, note, enabled, sortOrder, activeDays, pointValue, repeatable FROM routine_tasks ORDER BY childId, windowId, sortOrder") {
                    RoutineTask(
                        id = getString("id"),
                        childId = getString("childId"),
                        windowId = getString("windowId"),
                        title = getString("title"),
                        visualCue = getString("visualCue"),
                        note = getString("note"),
                        enabled = getInt("enabled") == 1,
                        sortOrder = getInt("sortOrder"),
                        activeDays = getString("activeDays").toActiveDays(),
                        pointValue = getInt("pointValue"),
                        repeatable = getInt("repeatable") == 1,
                    )
                },
                completions = connection.query("SELECT localDate, taskId, completed, completedAt, clearedAt, count FROM daily_completions ORDER BY localDate DESC, taskId") {
                    DailyCompletion(
                        localDate = LocalDate.parse(getString("localDate")),
                        taskId = getString("taskId"),
                        completed = getInt("completed") == 1,
                        completedAt = getString("completedAt")?.let(LocalDateTime::parse),
                        clearedAt = getString("clearedAt")?.let(LocalDateTime::parse),
                        count = getInt("count"),
                    )
                },
                rewards = connection.query("SELECT id, title, pointCost, enabled, sortOrder, note FROM rewards ORDER BY sortOrder, title, id") {
                    RewardConfig(
                        id = getString("id"),
                        title = getString("title"),
                        pointCost = getInt("pointCost"),
                        enabled = getInt("enabled") == 1,
                        sortOrder = getInt("sortOrder"),
                        note = getString("note"),
                    )
                },
                walletEntries = connection.query("SELECT id, childId, amount, kind, reason, createdAt, sourceId FROM wallet_entries ORDER BY createdAt, id") {
                    WalletEntry(
                        id = getString("id"),
                        childId = getString("childId"),
                        amount = getInt("amount"),
                        kind = getString("kind").toWalletEntryKind(),
                        reason = getString("reason"),
                        createdAt = LocalDateTime.parse(getString("createdAt")),
                        sourceId = getString("sourceId"),
                    )
                },
                settings = connection.queryOne("SELECT parentPinHash, dailyResetTime, adminServerEnabled, overrideWindowId, overrideSetAt, walletInitializedAt FROM settings WHERE id = 0") {
                    RoutineSettings(
                        parentPinHash = getString("parentPinHash"),
                        dailyResetTime = LocalTime.parse(getString("dailyResetTime")),
                        adminServerEnabled = getInt("adminServerEnabled") == 1,
                        manualActiveWindowOverride = if (getString("overrideWindowId") != null && getString("overrideSetAt") != null) {
                            ActiveWindowOverride(getString("overrideWindowId"), LocalDateTime.parse(getString("overrideSetAt")))
                        } else {
                            null
                        },
                        walletInitializedAt = getString("walletInitializedAt")?.let(LocalDateTime::parse),
                    )
                } ?: RoutineSettings.Default,
            )
        }
    }

    override suspend fun replaceSnapshot(snapshot: StoreSnapshot) {
        synchronized(lock) {
            connect { connection ->
                connection.transaction {
                    createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM children")
                        statement.executeUpdate("DELETE FROM routine_windows")
                        statement.executeUpdate("DELETE FROM routine_tasks")
                        statement.executeUpdate("DELETE FROM daily_completions")
                        statement.executeUpdate("DELETE FROM rewards")
                        statement.executeUpdate("DELETE FROM wallet_entries")
                    }
                    upsertChildren(snapshot.children)
                    upsertWindows(snapshot.windows)
                    upsertTasks(snapshot.tasks)
                    upsertCompletions(snapshot.completions)
                    upsertRewards(snapshot.rewards)
                    upsertWalletEntries(snapshot.walletEntries)
                    upsertSettings(snapshot.settings)
                }
            }
        }
    }

    override suspend fun updateSettings(settings: RoutineSettings) {
        synchronized(lock) {
            connect { connection -> connection.upsertSettings(settings) }
        }
    }

    override suspend fun upsertCompletion(completion: DailyCompletion) {
        synchronized(lock) {
            connect { connection -> connection.upsertCompletions(listOf(completion)) }
        }
    }

    override suspend fun completion(localDate: LocalDate, taskId: String): DailyCompletion? = synchronized(lock) {
        connect { connection ->
            connection.prepareStatement(
                    "SELECT localDate, taskId, completed, completedAt, clearedAt, count FROM daily_completions WHERE localDate = ? AND taskId = ?",
            ).use { statement ->
                statement.setString(1, localDate.toString())
                statement.setString(2, taskId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@connect null
                    DailyCompletion(
                        localDate = LocalDate.parse(result.getString("localDate")),
                        taskId = result.getString("taskId"),
                        completed = result.getInt("completed") == 1,
                        completedAt = result.getString("completedAt")?.let(LocalDateTime::parse),
                        clearedAt = result.getString("clearedAt")?.let(LocalDateTime::parse),
                        count = result.getInt("count"),
                    )
                }
            }
        }
    }

    override suspend fun resetDate(localDate: LocalDate, clearedAt: LocalDateTime) {
        synchronized(lock) {
            connect { connection ->
                connection.prepareStatement(
                    "UPDATE daily_completions SET completed = 0, count = 0, clearedAt = ? WHERE localDate = ? AND completed = 1",
                ).use { statement ->
                    statement.setString(1, clearedAt.toString())
                    statement.setString(2, localDate.toString())
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun upsertWalletEntry(entry: WalletEntry) {
        synchronized(lock) {
            connect { connection -> connection.upsertWalletEntries(listOf(entry)) }
        }
    }

    override suspend fun deleteWalletEntry(id: String) {
        synchronized(lock) {
            connect { connection ->
                connection.prepareStatement("DELETE FROM wallet_entries WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun walletEntry(id: String): WalletEntry? = synchronized(lock) {
        connect { connection ->
            connection.prepareStatement(
                "SELECT id, childId, amount, kind, reason, createdAt, sourceId FROM wallet_entries WHERE id = ?",
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@connect null
                    WalletEntry(
                        id = result.getString("id"),
                        childId = result.getString("childId"),
                        amount = result.getInt("amount"),
                        kind = result.getString("kind").toWalletEntryKind(),
                        reason = result.getString("reason"),
                        createdAt = LocalDateTime.parse(result.getString("createdAt")),
                        sourceId = result.getString("sourceId"),
                    )
                }
            }
        }
    }

    fun completionOperationProcessed(operationId: String): Boolean = synchronized(lock) {
        connect { connection ->
            connection.prepareStatement("SELECT 1 FROM processed_completion_operations WHERE operationId = ?").use { statement ->
                statement.setString(1, operationId)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    fun recordCompletionOperation(mutation: CompletionMutation, processedAt: LocalDateTime) {
        synchronized(lock) {
            connect { connection ->
                connection.prepareStatement(
                    """
                    INSERT OR REPLACE INTO processed_completion_operations
                    (operationId, taskId, routineDate, completed, count, changedAt, deviceId, processedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, mutation.operationId)
                    statement.setString(2, mutation.taskId)
                    statement.setString(3, mutation.routineDate.toString())
                    statement.setInt(4, if (mutation.completed) 1 else 0)
                    statement.setInt(5, mutation.count)
                    statement.setString(6, mutation.changedAt.toString())
                    statement.setString(7, mutation.deviceId)
                    statement.setString(8, processedAt.toString())
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun initialize() {
        synchronized(lock) {
            connect { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS children (
                            id TEXT PRIMARY KEY,
                            displayName TEXT NOT NULL,
                            color TEXT NOT NULL,
                            sortOrder INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS routine_windows (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            startTime TEXT NOT NULL,
                            sortOrder INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS routine_tasks (
                            id TEXT PRIMARY KEY,
                            childId TEXT NOT NULL,
                            windowId TEXT NOT NULL,
                            title TEXT NOT NULL,
                            visualCue TEXT NOT NULL,
                            note TEXT,
                            enabled INTEGER NOT NULL,
                            sortOrder INTEGER NOT NULL,
                            activeDays TEXT NOT NULL DEFAULT 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY',
                            pointValue INTEGER NOT NULL DEFAULT 1,
                            repeatable INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS daily_completions (
                            localDate TEXT NOT NULL,
                            taskId TEXT NOT NULL,
                            completed INTEGER NOT NULL,
                            completedAt TEXT,
                            clearedAt TEXT,
                            count INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (localDate, taskId)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS rewards (
                            id TEXT PRIMARY KEY,
                            title TEXT NOT NULL,
                            pointCost INTEGER NOT NULL,
                            enabled INTEGER NOT NULL,
                            sortOrder INTEGER NOT NULL,
                            note TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS wallet_entries (
                            id TEXT PRIMARY KEY,
                            childId TEXT NOT NULL,
                            amount INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            createdAt TEXT NOT NULL,
                            sourceId TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS settings (
                            id INTEGER PRIMARY KEY CHECK (id = 0),
                            parentPinHash TEXT,
                            dailyResetTime TEXT NOT NULL,
                            adminServerEnabled INTEGER NOT NULL,
                            overrideWindowId TEXT,
                            overrideSetAt TEXT,
                            walletInitializedAt TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS processed_completion_operations (
                            operationId TEXT PRIMARY KEY,
                            taskId TEXT NOT NULL,
                            routineDate TEXT NOT NULL,
                            completed INTEGER NOT NULL,
                            count INTEGER NOT NULL DEFAULT 0,
                            changedAt TEXT NOT NULL,
                            deviceId TEXT NOT NULL,
                            processedAt TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.ensureRoutineTaskActiveDaysColumn()
                connection.ensureColumn(
                    table = "routine_tasks",
                    column = "pointValue",
                    addSql = "ALTER TABLE routine_tasks ADD COLUMN pointValue INTEGER NOT NULL DEFAULT 1",
                )
                connection.ensureColumn(
                    table = "routine_tasks",
                    column = "repeatable",
                    addSql = "ALTER TABLE routine_tasks ADD COLUMN repeatable INTEGER NOT NULL DEFAULT 0",
                )
                val addedCount = connection.ensureColumn(
                    table = "daily_completions",
                    column = "count",
                    addSql = "ALTER TABLE daily_completions ADD COLUMN count INTEGER NOT NULL DEFAULT 0",
                )
                if (addedCount) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("UPDATE daily_completions SET count = 1 WHERE completed = 1")
                    }
                }
                connection.ensureColumn(
                    table = "settings",
                    column = "walletInitializedAt",
                    addSql = "ALTER TABLE settings ADD COLUMN walletInitializedAt TEXT",
                )
                connection.ensureColumn(
                    table = "processed_completion_operations",
                    column = "count",
                    addSql = "ALTER TABLE processed_completion_operations ADD COLUMN count INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }

    private fun Connection.upsertChildren(children: List<ChildConfig>) {
        prepareStatement(
            "INSERT OR REPLACE INTO children (id, displayName, color, sortOrder) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            children.forEach { child ->
                statement.setString(1, child.id)
                statement.setString(2, child.displayName)
                statement.setString(3, child.color)
                statement.setInt(4, child.sortOrder)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertWindows(windows: List<RoutineWindowConfig>) {
        prepareStatement(
            "INSERT OR REPLACE INTO routine_windows (id, name, startTime, sortOrder) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            windows.forEach { window ->
                statement.setString(1, window.id)
                statement.setString(2, window.name)
                statement.setString(3, window.startTime.toString())
                statement.setInt(4, window.sortOrder)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertTasks(tasks: List<RoutineTask>) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO routine_tasks
            (id, childId, windowId, title, visualCue, note, enabled, sortOrder, activeDays, pointValue, repeatable)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            tasks.forEach { task ->
                statement.setString(1, task.id)
                statement.setString(2, task.childId)
                statement.setString(3, task.windowId)
                statement.setString(4, task.title)
                statement.setString(5, task.visualCue)
                statement.setString(6, task.note)
                statement.setInt(7, if (task.enabled) 1 else 0)
                statement.setInt(8, task.sortOrder)
                statement.setString(9, task.activeDays.toStorageValue())
                statement.setInt(10, task.pointValue)
                statement.setInt(11, if (task.repeatable) 1 else 0)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertCompletions(completions: List<DailyCompletion>) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO daily_completions
            (localDate, taskId, completed, completedAt, clearedAt, count)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            completions.forEach { completion ->
                statement.setString(1, completion.localDate.toString())
                statement.setString(2, completion.taskId)
                statement.setInt(3, if (completion.completed) 1 else 0)
                statement.setString(4, completion.completedAt?.toString())
                statement.setString(5, completion.clearedAt?.toString())
                statement.setInt(6, completion.count)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertRewards(rewards: List<RewardConfig>) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO rewards
            (id, title, pointCost, enabled, sortOrder, note)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            rewards.forEach { reward ->
                statement.setString(1, reward.id)
                statement.setString(2, reward.title)
                statement.setInt(3, reward.pointCost)
                statement.setInt(4, if (reward.enabled) 1 else 0)
                statement.setInt(5, reward.sortOrder)
                statement.setString(6, reward.note)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertWalletEntries(entries: List<WalletEntry>) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO wallet_entries
            (id, childId, amount, kind, reason, createdAt, sourceId)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            entries.forEach { entry ->
                statement.setString(1, entry.id)
                statement.setString(2, entry.childId)
                statement.setInt(3, entry.amount)
                statement.setString(4, entry.kind.name)
                statement.setString(5, entry.reason)
                statement.setString(6, entry.createdAt.toString())
                statement.setString(7, entry.sourceId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertSettings(settings: RoutineSettings) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO settings
            (id, parentPinHash, dailyResetTime, adminServerEnabled, overrideWindowId, overrideSetAt, walletInitializedAt)
            VALUES (0, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, settings.parentPinHash)
            statement.setString(2, settings.dailyResetTime.toString())
            statement.setInt(3, if (settings.adminServerEnabled) 1 else 0)
            statement.setString(4, settings.manualActiveWindowOverride?.windowId)
            statement.setString(5, settings.manualActiveWindowOverride?.setAt?.toString())
            statement.setString(6, settings.walletInitializedAt?.toString())
            statement.executeUpdate()
        }
    }

    private fun <T> connect(block: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl).use { connection ->
            block(connection)
        }
    }

    private fun <T> Connection.transaction(block: Connection.() -> T): T {
        autoCommit = false
        return try {
            val result = block()
            commit()
            result
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = true
        }
    }

    private fun <T> Connection.query(sql: String, row: ResultSet.() -> T): List<T> {
        return createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val rows = mutableListOf<T>()
                while (result.next()) {
                    rows += result.row()
                }
                rows
            }
        }
    }

    private fun <T> Connection.queryOne(sql: String, row: ResultSet.() -> T): T? {
        return createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                if (result.next()) result.row() else null
            }
        }
    }

    private fun Connection.ensureRoutineTaskActiveDaysColumn() {
        ensureColumn(
            table = "routine_tasks",
            column = "activeDays",
            addSql = """
                ALTER TABLE routine_tasks
                ADD COLUMN activeDays TEXT NOT NULL DEFAULT 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY'
            """.trimIndent(),
        )
    }

    private fun Connection.ensureColumn(table: String, column: String, addSql: String): Boolean {
        val hasColumn = createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                var found = false
                while (result.next()) {
                    if (result.getString("name") == column) {
                        found = true
                    }
                }
                found
            }
        }
        if (!hasColumn) {
            createStatement().use { statement ->
                statement.executeUpdate(addSql)
            }
            return true
        }
        return false
    }

    private fun String.toActiveDays(): Set<DayOfWeek> {
        val days = split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { value -> runCatching { DayOfWeek.valueOf(value.uppercase()) }.getOrNull() }
            .toSet()
        return days.ifEmpty { DayOfWeek.entries.toSet() }
    }

    private fun Set<DayOfWeek>.toStorageValue(): String {
        return if (isEmpty()) {
            DayOfWeek.entries.joinToString(",") { it.name }
        } else {
            sortedBy { it.value }.joinToString(",") { it.name }
        }
    }

    private fun String.toWalletEntryKind(): WalletEntryKind {
        return WalletEntryKind.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: WalletEntryKind.Earning
    }
}
