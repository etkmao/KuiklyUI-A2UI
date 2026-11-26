package com.tencent.kuikly.a2ui.agent.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AgentApiClient(val connection: AgentConnection) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS).build()

    suspend fun createSession(): JSONObject = request("sessions", body = JSONObject().put("client", JSONObject()
        .put("platform", "android").put("sdkVersion", "agent-demo-1.0")
        .put("profileId", "basic-text-v1").put("a2uiRendererCapabilities", JSONObject()
            .put("1.0", JSONObject().put("supportedCatalogIds", org.json.JSONArray()
                .put("https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"))))))

    suspend fun snapshot(sessionId: String): JSONObject = request("snapshot", parameters = arrayOf("sessionId" to sessionId))
    suspend fun submit(sessionId: String, body: JSONObject, key: String): JSONObject = request(
        "runs", body, key, arrayOf("sessionId" to sessionId)
    )
    suspend fun cancel(runId: String): JSONObject = request("cancel", JSONObject(), parameters = arrayOf("runId" to runId))
    suspend fun messages(sessionId: String, before: Int): JSONObject = request(
        "messages", parameters = arrayOf("sessionId" to sessionId), query = "?limit=30&beforeOrdinal=$before"
    )

    private fun builder(key: String, parameters: Array<out Pair<String, String>>, query: String = ""): Request.Builder =
        Request.Builder().url(connection.url(key, *parameters) + query).apply {
            if (connection.headerName.isNotEmpty() && connection.headerValue.isNotEmpty()) {
                header(connection.headerName, connection.headerValue)
            }
        }

    private suspend fun request(
        route: String, body: JSONObject? = null, idempotencyKey: String? = null,
        parameters: Array<out Pair<String, String>> = emptyArray(), query: String = ""
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = builder(route, parameters, query).header("Accept", "application/json").apply {
            body?.let { post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            idempotencyKey?.let { header("Idempotency-Key", it) }
        }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AgentFailure("NETWORK_ERROR", true))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val json = JSONObject(response.body?.string().orEmpty())
                        if (!response.isSuccessful) throw parseFailure(response.code, json)
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is AgentFailure) e else AgentFailure("INVALID_RESPONSE", false, response.code)
                        )
                    }
                }
            }
        })
    }

    fun events(sessionId: String, cursor: Long, onOpen: () -> Unit): Flow<JSONObject> = callbackFlow {
        val request = builder("events", arrayOf("sessionId" to sessionId))
            .header("Accept", "text/event-stream").header("Last-Event-ID", cursor.toString()).build()
        val source = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) { onOpen() }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val event = JSONObject(data)
                    if (id != event.getLong("eventId").toString() || type != event.getString("type")) {
                        throw SyncRequired()
                    }
                    if (trySend(event).isFailure) eventSource.cancel()
                } catch (e: Exception) { close(e); eventSource.cancel() }
            }
            override fun onClosed(eventSource: EventSource) { close(AgentFailure("STREAM_CLOSED", true)) }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val failure = response?.use {
                    try { parseFailure(it.code, JSONObject(it.body?.string().orEmpty())) }
                    catch (_: Exception) { AgentFailure("STREAM_ERROR", it.code >= 500, it.code) }
                } ?: AgentFailure("NETWORK_ERROR", true)
                close(failure)
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    fun close() {
        client.dispatcher.cancelAll()
        streamClient.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun parseFailure(status: Int, json: JSONObject): AgentFailure {
        val error = json.optJSONObject("error")
        return AgentFailure(error?.optString("code") ?: "HTTP_ERROR", error?.optBoolean("retryable") ?: (status >= 500), status)
    }
}
