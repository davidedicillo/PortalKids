package com.davidedicillo.portalroutine.sync

import com.davidedicillo.portalroutine.data.CompletionMutation
import com.davidedicillo.portalroutine.data.StoreJson
import com.davidedicillo.portalroutine.data.StoreSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PortalHubClient(
    private val hubUrl: String,
    private val deviceId: String,
) {
    suspend fun fetchSnapshot(): StoreSnapshot = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/state")
        StoreJson.snapshotFromState(json)
    }

    suspend fun setCompletion(mutation: CompletionMutation) {
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                path = "/api/completions/set",
                form = mapOf(
                    "operationId" to mutation.operationId,
                    "taskId" to mutation.taskId,
                    "routineDate" to mutation.routineDate.toString(),
                    "completed" to mutation.completed.toString(),
                    "changedAt" to mutation.changedAt.toString(),
                    "deviceId" to deviceId,
                ),
            )
        }
    }

    suspend fun login(pin: String): String = withContext(Dispatchers.IO) {
        request("POST", "/api/login", form = mapOf("pin" to pin)).getString("token")
    }

    suspend fun resetDay(token: String) {
        authenticatedPost("/api/reset", token, emptyMap())
    }

    suspend fun setManualWindowOverride(token: String, windowId: String) {
        authenticatedPost("/api/windows/override", token, mapOf("windowId" to windowId))
    }

    suspend fun clearManualWindowOverride(token: String) {
        authenticatedPost("/api/windows/override/clear", token, emptyMap())
    }

    suspend fun replaceState(token: String, snapshot: StoreSnapshot) {
        authenticatedPost(
            path = "/api/state/replace",
            token = token,
            form = mapOf("snapshot" to StoreJson.snapshotJson(snapshot).toString()),
        )
    }

    private suspend fun authenticatedPost(path: String, token: String, form: Map<String, String>) {
        withContext(Dispatchers.IO) {
            request("POST", "$path?token=${token.urlEncode()}", form)
        }
    }

    private fun request(
        method: String,
        path: String,
        form: Map<String, String>? = null,
    ): JSONObject {
        val connection = (URL("$hubUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_500
            readTimeout = 5_000
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        if (form != null) {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(form.entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" })
            }
        }

        val code: Int
        val responseText = try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(responseText.ifBlank { "{}" })
        if (code !in 200..299) {
            error(json.optString("error", "Hub request failed with HTTP $code"))
        }
        return json
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
