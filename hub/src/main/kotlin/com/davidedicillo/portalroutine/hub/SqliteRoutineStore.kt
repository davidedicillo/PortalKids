package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.core.ActiveWindowOverride
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.DailyCompletion
import com.davidedicillo.portalroutine.data.RoutineSettings
import com.davidedicillo.portalroutine.data.RoutineStore
import com.davidedicillo.portalroutine.data.StoreSnapshot
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
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
                tasks = connection.query("SELECT id, childId, windowId, title, visualCue, note, enabled, sortOrder FROM routine_tasks ORDER BY childId, windowId, sortOrder") {
                    RoutineTask(
                        id = getString("id"),
                        childId = getString("childId"),
                        windowId = getString("windowId"),
                        title = getString("title"),
                        visualCue = getString("visualCue"),
                        note = getString("note"),
                        enabled = getInt("enabled") == 1,
                        sortOrder = getInt("sortOrder"),
                    )
                },
                completions = connection.query("SELECT localDate, taskId, completed, completedAt, clearedAt FROM daily_completions ORDER BY localDate DESC, taskId") {
                    DailyCompletion(
                        localDate = LocalDate.parse(getString("localDate")),
                        taskId = getString("taskId"),
                        completed = getInt("completed") == 1,
                        completedAt = getString("completedAt")?.let(LocalDateTime::parse),
                        clearedAt = getString("clearedAt")?.let(LocalDateTime::parse),
                    )
                },
                settings = connection.queryOne("SELECT parentPinHash, dailyResetTime, adminServerEnabled, overrideWindowId, overrideSetAt FROM settings WHERE id = 0") {
                    RoutineSettings(
                        parentPinHash = getString("parentPinHash"),
                        dailyResetTime = LocalTime.parse(getString("dailyResetTime")),
                        adminServerEnabled = getInt("adminServerEnabled") == 1,
                        manualActiveWindowOverride = if (getString("overrideWindowId") != null && getString("overrideSetAt") != null) {
                            ActiveWindowOverride(getString("overrideWindowId"), LocalDateTime.parse(getString("overrideSetAt")))
                        } else {
                            null
                        },
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
                    }
                    upsertChildren(snapshot.children)
                    upsertWindows(snapshot.windows)
                    upsertTasks(snapshot.tasks)
                    upsertCompletions(snapshot.completions)
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
                "SELECT localDate, taskId, completed, completedAt, clearedAt FROM daily_completions WHERE localDate = ? AND taskId = ?",
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
                    )
                }
            }
        }
    }

    override suspend fun resetDate(localDate: LocalDate, clearedAt: LocalDateTime) {
        synchronized(lock) {
            connect { connection ->
                connection.prepareStatement(
                    "UPDATE daily_completions SET completed = 0, clearedAt = ? WHERE localDate = ? AND completed = 1",
                ).use { statement ->
                    statement.setString(1, clearedAt.toString())
                    statement.setString(2, localDate.toString())
                    statement.executeUpdate()
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
                    (operationId, taskId, routineDate, completed, changedAt, deviceId, processedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, mutation.operationId)
                    statement.setString(2, mutation.taskId)
                    statement.setString(3, mutation.routineDate.toString())
                    statement.setInt(4, if (mutation.completed) 1 else 0)
                    statement.setString(5, mutation.changedAt.toString())
                    statement.setString(6, mutation.deviceId)
                    statement.setString(7, processedAt.toString())
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
                            sortOrder INTEGER NOT NULL
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
                            PRIMARY KEY (localDate, taskId)
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
                            overrideSetAt TEXT
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
                            changedAt TEXT NOT NULL,
                            deviceId TEXT NOT NULL,
                            processedAt TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
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
            (id, childId, windowId, title, visualCue, note, enabled, sortOrder)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertCompletions(completions: List<DailyCompletion>) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO daily_completions
            (localDate, taskId, completed, completedAt, clearedAt)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            completions.forEach { completion ->
                statement.setString(1, completion.localDate.toString())
                statement.setString(2, completion.taskId)
                statement.setInt(3, if (completion.completed) 1 else 0)
                statement.setString(4, completion.completedAt?.toString())
                statement.setString(5, completion.clearedAt?.toString())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.upsertSettings(settings: RoutineSettings) {
        prepareStatement(
            """
            INSERT OR REPLACE INTO settings
            (id, parentPinHash, dailyResetTime, adminServerEnabled, overrideWindowId, overrideSetAt)
            VALUES (0, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, settings.parentPinHash)
            statement.setString(2, settings.dailyResetTime.toString())
            statement.setInt(3, if (settings.adminServerEnabled) 1 else 0)
            statement.setString(4, settings.manualActiveWindowOverride?.windowId)
            statement.setString(5, settings.manualActiveWindowOverride?.setAt?.toString())
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
}
