package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.core.RoutineEngine
import com.davidedicillo.portalroutine.core.RoutineTask
import com.davidedicillo.portalroutine.core.RoutineWindowConfig
import com.davidedicillo.portalroutine.data.ChildConfig
import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.RewardConfig
import com.davidedicillo.portalroutine.data.RoutineRepository
import com.davidedicillo.portalroutine.data.StoreJson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class HubServer(
    private val repository: RoutineRepository,
    private val completionSetService: CompletionSetService,
    private val publicUrl: String,
    port: Int,
) : NanoHTTPD("0.0.0.0", port) {
    private val sessions = mutableSetOf<String>()

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").withCors()
        }
        return try {
            when {
                session.uri == "/" -> htmlResponse()
                session.uri == "/health" -> jsonResponse(JSONObject().put("ok", true).put("hubUrl", publicUrl))
                session.uri == "/api/login" && session.method == Method.POST -> login(session)
                session.uri == "/api/state" -> state()
                session.uri == "/api/state/replace" && session.method == Method.POST -> authenticated(session) { replaceState(session) }
                session.uri == "/api/completions/set" && session.method == Method.POST -> setCompletion(session)
                session.uri == "/api/rewards/redeem" && session.method == Method.POST -> redeemReward(session)
                session.uri == "/api/children" && session.method == Method.POST -> authenticated(session) { updateChild(session) }
                session.uri == "/api/children/delete" && session.method == Method.POST -> authenticated(session) { deleteChild(session) }
                session.uri == "/api/windows" && session.method == Method.POST -> authenticated(session) { updateWindow(session) }
                session.uri == "/api/windows/delete" && session.method == Method.POST -> authenticated(session) { deleteWindow(session) }
                session.uri == "/api/windows/override" && session.method == Method.POST -> authenticated(session) { setWindowOverride(session) }
                session.uri == "/api/windows/override/clear" && session.method == Method.POST -> authenticated(session) { clearWindowOverride() }
                session.uri == "/api/tasks" && session.method == Method.POST -> authenticated(session) { upsertTask(session) }
                session.uri == "/api/tasks/reorder" && session.method == Method.POST -> authenticated(session) { reorderTasks(session) }
                session.uri == "/api/tasks/delete" && session.method == Method.POST -> authenticated(session) { deleteTask(session) }
                session.uri == "/api/rewards" && session.method == Method.POST -> authenticated(session) { upsertReward(session) }
                session.uri == "/api/rewards/delete" && session.method == Method.POST -> authenticated(session) { deleteReward(session) }
                session.uri == "/api/wallet/deduct" && session.method == Method.POST -> authenticated(session) { deductPoints(session) }
                session.uri == "/api/reset" && session.method == Method.POST -> authenticated(session) { resetDay() }
                session.uri == "/api/settings/reset-time" && session.method == Method.POST -> authenticated(session) { updateResetTime(session) }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found").withCors()
            }
        } catch (error: Exception) {
            jsonResponse(JSONObject().put("error", error.message ?: "Server error"), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun htmlResponse(): Response {
        val html = requireNotNull(javaClass.classLoader.getResource("admin/index.html")) {
            "Missing admin/index.html resource"
        }.readText()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html).withCors()
    }

    private fun login(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        val pin = params["pin"].orEmpty()
        if (pin.length < 4) {
            return@runBlocking jsonResponse(
                JSONObject().put("ok", false).put("error", "Enter a PIN with at least 4 digits"),
                Response.Status.UNAUTHORIZED,
            )
        }

        val ok = if (!repository.hasParentPin()) {
            repository.setParentPin(pin)
            true
        } else {
            repository.verifyParentPin(pin)
        }
        if (!ok) {
            return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Invalid PIN"), Response.Status.UNAUTHORIZED)
        }
        val token = UUID.randomUUID().toString()
        synchronized(sessions) { sessions.add(token) }
        jsonResponse(JSONObject().put("ok", true).put("token", token))
    }

    private fun authenticated(session: IHTTPSession, action: () -> Response): Response {
        val hasPin = runBlocking { repository.hasParentPin() }
        if (!hasPin) {
            return jsonResponse(JSONObject().put("error", "Parent PIN is not set yet"), Response.Status.FORBIDDEN)
        }
        val token = queryParam(session, "token").orEmpty()
        val valid = synchronized(sessions) { token in sessions }
        return if (valid) action() else {
            jsonResponse(JSONObject().put("error", "PIN login required"), Response.Status.UNAUTHORIZED)
        }
    }

    private fun state(): Response = runBlocking {
        jsonResponse(stateJson())
    }

    private suspend fun stateJson(): JSONObject {
        val board = repository.boardState(LocalDateTime.now())
        val snapshot = repository.snapshot()
        return StoreJson.stateJson(snapshot, board, publicUrl)
    }

    private fun setCompletion(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        val changedAt = params["changedAt"]?.takeIf { it.isNotBlank() }?.let(LocalDateTime::parse) ?: LocalDateTime.now()
        val snapshot = repository.snapshot()
        val routineDate = params["routineDate"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            ?: RoutineEngine.effectiveRoutineDate(changedAt, snapshot.settings.dailyResetTime)
        val count = params["count"]?.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 }
            ?: if (required(params, "completed").toBooleanStrictOrNull() == true) 1 else 0
        val mutation = CompletionMutation(
            operationId = required(params, "operationId"),
            taskId = required(params, "taskId"),
            routineDate = routineDate,
            completed = count > 0,
            changedAt = changedAt,
            deviceId = params["deviceId"].orEmpty().ifBlank { "unknown" },
            count = count,
        )
        val applied = completionSetService.setCompletion(mutation)
        jsonResponse(stateJson().put("applied", applied))
    }

    private fun redeemReward(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        val createdAt = params["createdAt"]?.takeIf { it.isNotBlank() }?.let(LocalDateTime::parse) ?: LocalDateTime.now()
        val applied = repository.redeemReward(
            childId = required(params, "childId"),
            rewardId = required(params, "rewardId"),
            now = createdAt,
            operationId = required(params, "operationId"),
        )
        jsonResponse(stateJson().put("applied", applied))
    }

    private fun replaceState(session: IHTTPSession): Response = runBlocking {
        val current = repository.snapshot()
        val incoming = StoreJson.snapshotFromState(JSONObject(required(formParams(session), "snapshot")))
        repository.storeSnapshot(
            incoming.copy(
                settings = incoming.settings.copy(parentPinHash = current.settings.parentPinHash),
            ),
        )
        state()
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

    private fun setWindowOverride(session: IHTTPSession): Response = runBlocking {
        repository.setManualWindowOverride(required(formParams(session), "windowId"), LocalDateTime.now())
        state()
    }

    private fun clearWindowOverride(): Response = runBlocking {
        repository.clearManualWindowOverride()
        state()
    }

    private fun upsertTask(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        val taskId = params["id"].orEmpty()
        val childIds = if (taskId.isBlank()) childIds(session) else listOf(required(params, "childId"))
        childIds.forEach { childId ->
            repository.upsertTask(
                RoutineTask(
                    id = taskId.ifBlank { UUID.randomUUID().toString() },
                    childId = childId,
                    windowId = required(params, "windowId"),
                    title = required(params, "title"),
                    enabled = params["enabled"] != "false",
                    sortOrder = params["sortOrder"]?.toIntOrNull() ?: 0,
                    note = params["note"]?.ifBlank { null },
                    visualCue = params["visualCue"].orEmpty().ifBlank { "⭐" },
                    activeDays = activeDays(session),
                    pointValue = params["pointValue"]?.toIntOrNull()?.takeIf { it >= 0 } ?: 1,
                    repeatable = params["repeatable"] == "true",
                ),
            )
        }
        state()
    }

    private fun reorderTasks(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        repository.reorderTasks(
            childId = required(params, "childId"),
            windowId = required(params, "windowId"),
            taskIds = required(params, "taskIds")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() },
        )
        state()
    }

    private fun deleteTask(session: IHTTPSession): Response = runBlocking {
        repository.deleteTask(required(formParams(session), "id"))
        state()
    }

    private fun upsertReward(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        repository.updateReward(
            RewardConfig(
                id = params["id"].orEmpty().ifBlank { UUID.randomUUID().toString() },
                title = required(params, "title"),
                pointCost = params["pointCost"]?.toIntOrNull()?.takeIf { it >= 0 } ?: error("pointCost must be zero or greater"),
                enabled = params["enabled"] != "false",
                sortOrder = params["sortOrder"]?.toIntOrNull() ?: 0,
                note = params["note"]?.ifBlank { null },
            ),
        )
        state()
    }

    private fun deleteReward(session: IHTTPSession): Response = runBlocking {
        repository.deleteReward(required(formParams(session), "id"))
        state()
    }

    private fun deductPoints(session: IHTTPSession): Response = runBlocking {
        val params = formParams(session)
        val applied = repository.deductPoints(
            childId = required(params, "childId"),
            amount = params["amount"]?.toIntOrNull()?.takeIf { it >= 0 } ?: error("amount must be zero or greater"),
            reason = params["reason"].orEmpty().ifBlank { "Parent deduction" },
            now = LocalDateTime.now(),
        )
        stateJson().put("applied", applied).let(::jsonResponse)
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

    private fun childIds(session: IHTTPSession): List<String> {
        val ids = session.parameters["childId"].orEmpty()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return ids.ifEmpty { error("Missing childId") }
    }

    private fun activeDays(session: IHTTPSession): Set<DayOfWeek> {
        val days = session.parameters["activeDays"].orEmpty()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { value -> runCatching { DayOfWeek.valueOf(value.uppercase()) }.getOrNull() }
            .toSet()
        return days.ifEmpty { DayOfWeek.entries.toSet() }
    }

    private fun required(params: Map<String, String>, name: String): String {
        return params[name]?.takeIf { it.isNotBlank() } ?: error("Missing $name")
    }

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json", json.toString()).withCors()
    }

    private fun Response.withCors(): Response = apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
    }
}
