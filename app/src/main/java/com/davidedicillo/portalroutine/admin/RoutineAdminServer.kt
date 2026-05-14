package com.davidedicillo.portalroutine.admin

import android.content.Context
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.RoutineRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class RoutineAdminServer(
    private val context: Context,
    private val repository: RoutineRepository,
    port: Int = 8080,
) : NanoHTTPD("0.0.0.0", port) {
    private val sessions = mutableSetOf<String>()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/" -> htmlResponse()
                session.uri == "/health" -> jsonResponse(JSONObject().put("ok", true))
                session.uri == "/api/login" && session.method == Method.POST -> login(session)
                session.uri == "/api/state" -> authenticated(session) { state() }
                session.uri == "/api/children" && session.method == Method.POST -> authenticated(session) { updateChild(session) }
                session.uri == "/api/children/delete" && session.method == Method.POST -> authenticated(session) { deleteChild(session) }
                session.uri == "/api/windows" && session.method == Method.POST -> authenticated(session) { updateWindow(session) }
                session.uri == "/api/windows/delete" && session.method == Method.POST -> authenticated(session) { deleteWindow(session) }
                session.uri == "/api/tasks" && session.method == Method.POST -> authenticated(session) { upsertTask(session) }
                session.uri == "/api/tasks/delete" && session.method == Method.POST -> authenticated(session) { deleteTask(session) }
                session.uri == "/api/reset" && session.method == Method.POST -> authenticated(session) { resetDay() }
                session.uri == "/api/settings/reset-time" && session.method == Method.POST -> authenticated(session) { updateResetTime(session) }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (error: Exception) {
            jsonResponse(JSONObject().put("error", error.message ?: "Server error"), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun htmlResponse(): Response {
        val html = context.assets.open("admin/index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun login(session: IHTTPSession): Response {
        val params = formParams(session)
        val pin = params["pin"].orEmpty()
        val ok = runBlocking {
            if (!repository.hasParentPin()) false else repository.verifyParentPin(pin)
        }
        if (!ok) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "Invalid PIN"), Response.Status.UNAUTHORIZED)
        }
        val token = UUID.randomUUID().toString()
        synchronized(sessions) { sessions.add(token) }
        return jsonResponse(JSONObject().put("ok", true).put("token", token))
    }

    private fun authenticated(session: IHTTPSession, action: () -> Response): Response {
        val hasPin = runBlocking { repository.hasParentPin() }
        if (!hasPin) {
            return jsonResponse(JSONObject().put("error", "Parent PIN is not set on the Portal yet"), Response.Status.FORBIDDEN)
        }
        val token = queryParam(session, "token").orEmpty()
        val valid = synchronized(sessions) { token in sessions }
        return if (valid) action() else {
            jsonResponse(JSONObject().put("error", "PIN login required"), Response.Status.UNAUTHORIZED)
        }
    }

    private fun state(): Response = runBlocking {
        val snapshot = repository.snapshot()
        val board = repository.boardState(LocalDateTime.now())
        jsonResponse(
            JSONObject()
                .put("adminUrl", "http://${NetworkAddress.localIpv4Address() ?: "127.0.0.1"}:8080")
                .put("activeWindow", board.activeWindow.id)
                .put("routineDate", board.routineDate.toString())
                .put("children", JSONArray(snapshot.children.map { child ->
                    JSONObject()
                        .put("id", child.id)
                        .put("displayName", child.displayName)
                        .put("color", child.color)
                        .put("sortOrder", child.sortOrder)
                }))
                .put("windows", JSONArray(snapshot.windows.map { window ->
                    JSONObject()
                        .put("id", window.id)
                        .put("name", window.name)
                        .put("startTime", window.startTime.toString())
                        .put("sortOrder", window.sortOrder)
                }))
                .put("tasks", JSONArray(snapshot.tasks.map { task ->
                    JSONObject()
                        .put("id", task.id)
                        .put("childId", task.childId)
                        .put("windowId", task.windowId)
                        .put("title", task.title)
                        .put("visualCue", task.visualCue)
                        .put("note", task.note ?: "")
                        .put("enabled", task.enabled)
                        .put("sortOrder", task.sortOrder)
                }))
                .put("settings", JSONObject()
                    .put("dailyResetTime", snapshot.settings.dailyResetTime.toString())
                    .put("adminServerEnabled", snapshot.settings.adminServerEnabled))
                .put("history", JSONArray(snapshot.completions.take(100).map { completion ->
                    JSONObject()
                        .put("localDate", completion.localDate.toString())
                        .put("taskId", completion.taskId)
                        .put("completed", completion.completed)
                        .put("completedAt", completion.completedAt?.toString() ?: "")
                        .put("clearedAt", completion.clearedAt?.toString() ?: "")
                })),
        )
    }

    private fun updateChild(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        repository.updateChild(
            ChildConfig(
                id = required(params, "id"),
                displayName = required(params, "displayName"),
                color = params["color"].orEmpty().ifBlank { "#1F8A70" },
                sortOrder = params["sortOrder"]?.toIntOrNull() ?: 0,
            ),
        )
        state()
    }

    private fun deleteChild(session: IHTTPSession): Response = runBlocking {
        repository.deleteChild(required(formParams(session), "id"))
        state()
    }

    private fun updateWindow(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        repository.updateWindow(
            RoutineWindowConfig(
                id = required(params, "id"),
                name = required(params, "name"),
                startTime = LocalTime.parse(required(params, "startTime")),
                sortOrder = params["sortOrder"]?.toIntOrNull() ?: 0,
            ),
        )
        state()
    }

    private fun deleteWindow(session: IHTTPSession): Response = runBlocking {
        repository.deleteWindow(required(formParams(session), "id"))
        state()
    }

    private fun upsertTask(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        repository.upsertTask(
            RoutineTask(
                id = params["id"].orEmpty().ifBlank { UUID.randomUUID().toString() },
                childId = required(params, "childId"),
                windowId = required(params, "windowId"),
                title = required(params, "title"),
                enabled = params["enabled"] != "false",
                sortOrder = params["sortOrder"]?.toIntOrNull() ?: 0,
                note = params["note"]?.ifBlank { null },
                visualCue = params["visualCue"].orEmpty().ifBlank { "⭐" },
            ),
        )
        state()
    }

    private fun deleteTask(session: IHTTPSession): Response = runBlocking {
        repository.deleteTask(required(formParams(session), "id"))
        state()
    }

    private fun resetDay(): Response = runBlocking {
        repository.resetDay(LocalDateTime.now())
        state()
    }

    private fun updateResetTime(session: IHTTPSession): Response = runBlocking {
        repository.updateDailyResetTime(LocalTime.parse(required(formParams(session), "dailyResetTime")))
        state()
    }

    private fun formParams(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            session.parseBody(files)
        }
        return session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
    }

    private fun queryParam(session: IHTTPSession, name: String): String? {
        return session.parameters[name]?.firstOrNull()
    }

    private fun required(params: Map<String, String>, name: String): String {
        return params[name]?.takeIf { it.isNotBlank() } ?: error("Missing $name")
    }

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json", json.toString())
            .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }
}
