package com.alphapi.codexremote

import java.time.Instant
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class BridgeHttpException(
    val statusCode: Int,
    val serverError: String,
) : IOException(
    "Bridge $statusCode: ${serverError.ifBlank { "request-failed" }}",
)

class BridgeApi(
    baseUrl: String,
    private val token: String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    private val root = BridgeEndpoint.normalize(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val taskCreationClient = client.newBuilder()
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    suspend fun pair(code: String, name: String): PairResponse {
        val body = json.encodeToString(
            buildJsonObject {
                put("code", code)
                put("name", name)
                put("kind", "android")
            },
        )
        return execute("POST", "/v1/pair", body, authenticated = false) { response ->
            json.decodeFromString<PairResponse>(response.body!!.string())
        }
    }

    suspend fun tasks(): List<TaskDto> =
        execute("GET", "/v1/tasks") { response ->
            json.decodeFromString<TasksResponse>(response.body!!.string()).tasks
        }

    suspend fun health(): HealthDto =
        execute("GET", "/v1/health", authenticated = false) { response ->
            json.decodeFromString<HealthDto>(response.body!!.string())
        }

    suspend fun follow(threadId: String) {
        execute<Unit>("POST", "/v1/tasks/$threadId/follow", "{}") { }
    }

    suspend fun activateTask(threadId: String) {
        val body = json.encodeToString(
            buildJsonObject { put("idempotencyKey", UUID.randomUUID().toString()) },
        )
        execute<Unit>("POST", "/v1/tasks/$threadId/activate", body) { }
    }

    suspend fun taskDetail(threadId: String, historyCursor: String? = null): TaskDetailDto {
        val path = "/v1/tasks/$threadId?historyLimit=50" +
            historyCursor?.let { "&historyCursor=${encodeQuery(it)}" }.orEmpty()
        return execute("GET", path) { response ->
            json.decodeFromString<TaskDetailResponse>(response.body!!.string()).task
        }
    }

    suspend fun taskMedia(threadId: String, mediaId: String, destination: File) {
        val path = "/v1/tasks/${encodePathSegment(threadId)}/media/${encodePathSegment(mediaId)}"
        execute("GET", path) { response ->
            val body = requireNotNull(response.body)
            val maxBytes = 50L * 1_024 * 1_024
            check(body.contentLength() <= maxBytes) { "image-download-limit-exceeded" }
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        check(total <= maxBytes) { "image-download-limit-exceeded" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    suspend fun taskResource(threadId: String, resourceId: String): ByteArray {
        val path = "/v1/tasks/${encodePathSegment(threadId)}/resources/${encodePathSegment(resourceId)}"
        return execute("GET", path) { response -> response.body!!.bytes() }
    }

    suspend fun approvals(): List<ApprovalDto> =
        execute("GET", "/v1/approvals") { response ->
            json.decodeFromString<ApprovalsResponse>(response.body!!.string()).approvals
        }

    suspend fun capabilities(): RemoteCapabilitiesDto =
        execute("GET", "/v1/capabilities") { response ->
            val raw = response.body!!.string()
            val element = json.parseToJsonElement(raw)
            val wrapped = (element as? JsonObject)?.get("capabilities")
            if (wrapped != null) json.decodeFromJsonElement(wrapped)
            else json.decodeFromString(raw)
        }

    suspend fun models(refresh: Boolean = false): List<ModelOptionDto> =
        execute("GET", if (refresh) "/v1/models?refresh=true" else "/v1/models") { response ->
            json.decodeFromString<ModelsResponse>(response.body!!.string()).values()
        }

    suspend fun queue(threadId: String): QueueResponse =
        execute("GET", "/v1/tasks/$threadId/queue") { response ->
            json.decodeFromString(response.body!!.string())
        }

    suspend fun cancelQueuedMessage(threadId: String, messageId: String, expectedQueueHash: String) {
        val idempotencyKey = UUID.randomUUID().toString()
        val path = queueDeletePath(threadId, messageId, expectedQueueHash, idempotencyKey)
        execute<Unit>("DELETE", path, "{}") { }
    }

    suspend fun diff(threadId: String): TaskDiffDto =
        execute("GET", "/v1/tasks/$threadId/diff") { response ->
            json.decodeFromString<DiffResponse>(response.body!!.string()).diff
        }

    suspend fun revokeSelf() {
        execute<Unit>("DELETE", "/v1/devices/self", "{}") { }
    }

    suspend fun sendMessage(
        threadId: String,
        text: String,
        delivery: DeliveryMode? = null,
        expectedTurnId: String? = null,
        expectedQueueHash: String? = null,
        attachmentIds: List<String> = emptyList(),
    ) {
        val body = json.encodeToString(buildJsonObject {
            put("text", text)
            delivery?.let { put("delivery", it.wireValue) }
            expectedTurnId?.let { put("expectedTurnId", it) }
            expectedQueueHash?.let { put("expectedQueueHash", it) }
            put("idempotencyKey", UUID.randomUUID().toString())
            if (attachmentIds.isNotEmpty()) {
                put("attachmentIds", buildJsonArray {
                    attachmentIds.forEach { add(JsonPrimitive(it)) }
                })
            }
        })
        execute<Unit>("POST", "/v1/tasks/$threadId/messages", body) { }
    }

    suspend fun updateSettings(
        threadId: String,
        model: String,
        reasoningEffort: String,
    ) {
        val body = json.encodeToString(buildJsonObject {
            put("model", model)
            put("effort", reasoningEffort)
        })
        execute<Unit>("PATCH", "/v1/tasks/$threadId/settings", body) { }
    }

    suspend fun createTask(draft: CreateTaskDraft): CreateTaskResponse {
        val body = json.encodeToString(buildJsonObject {
            put("mode", draft.mode)
            draft.cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            put("prompt", draft.prompt)
            draft.model?.let { put("model", it) }
            draft.reasoningEffort?.let { put("reasoningEffort", it) }
            put("idempotencyKey", UUID.randomUUID().toString())
        })
        return execute(
            method = "POST",
            path = "/v1/tasks",
            body = body,
            httpClient = taskCreationClient,
        ) { response ->
            json.decodeFromString(response.body!!.string())
        }
    }

    suspend fun uploadAttachment(
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): UploadedAttachmentDto {
        val idempotencyKey = UUID.randomUUID().toString()
        val path = attachmentUploadPath(name, mimeType, idempotencyKey)
        return executeBytes(
            method = "POST",
            path = path,
            body = bytes,
            mediaType = mimeType.lowercase(),
        ) { response ->
            json.decodeFromString<UploadAttachmentResponse>(response.body!!.string()).attachment
        }
    }

    suspend fun deleteAttachment(attachmentId: String) {
        execute<Unit>("DELETE", "/v1/attachments/$attachmentId", "{}") { }
    }

    suspend fun workspaceFiles(threadId: String, query: String = ""): List<WorkspaceFileDto> {
        val path = "/v1/tasks/${encodePathSegment(threadId)}/workspace-files" +
            if (query.isBlank()) "" else "?query=${encodeQuery(query)}"
        return execute("GET", path) { response ->
            json.decodeFromString<WorkspaceFilesResponse>(response.body!!.string()).files
        }
    }

    suspend fun importWorkspaceAttachment(
        threadId: String,
        relativePath: String,
    ): UploadedAttachmentDto {
        val body = json.encodeToString(buildJsonObject {
            put("relativePath", relativePath)
            put("idempotencyKey", UUID.randomUUID().toString())
        })
        return execute("POST", "/v1/tasks/${encodePathSegment(threadId)}/workspace-attachments", body) {
            response -> json.decodeFromString<UploadAttachmentResponse>(response.body!!.string()).attachment
        }
    }

    suspend fun interrupt(threadId: String) {
        execute<Unit>("POST", "/v1/tasks/$threadId/interrupt", "{}") { }
    }

    suspend fun requestPush(threadId: String) {
        execute<Unit>("POST", "/v1/tasks/$threadId/request-push", "{\"confirmed\":true}") { }
    }

    suspend fun respondApproval(requestId: String, decision: String) {
        val body = json.encodeToString(buildJsonObject { put("decision", decision) })
        execute<Unit>("POST", "/v1/approvals/$requestId", body) { }
    }

    suspend fun respondUserInput(requestId: String, answers: Map<String, List<String>>) {
        val response = buildJsonObject {
            put("answers", buildJsonObject {
                answers.forEach { (questionId, values) ->
                    put(questionId, buildJsonObject {
                        put("answers", buildJsonArray {
                            values.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
        }
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("response", response) },
        )
        execute<Unit>("POST", "/v1/user-input/$requestId", body) { }
    }

    fun stream(
        onEvent: (BridgeEvent) -> Unit,
        onConnected: (Boolean) -> Unit,
        onConnectionFailure: (Throwable) -> Unit = {},
    ): WebSocket {
        val path = "/v1/stream"
        val request = signedBuilder("GET", path, ByteArray(0))
            .url(root.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + path)
            .build()
        return client.newWebSocket(request, object : WebSocketListener() {
            private val disconnected = AtomicBoolean(false)

            private fun reportDisconnected() {
                if (disconnected.compareAndSet(false, true)) onConnected(false)
            }

            override fun onOpen(webSocket: WebSocket, response: Response) = onConnected(true)
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<BridgeEvent>(text) }.onSuccess(onEvent)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                reportDisconnected()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reportDisconnected()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onConnectionFailure(
                    response?.let {
                        BridgeHttpException(it.code, it.body?.string().orEmpty())
                    } ?: t,
                )
                reportDisconnected()
            }
        })
    }

    private suspend fun <T> execute(
        method: String,
        path: String,
        body: String = "",
        authenticated: Boolean = true,
        httpClient: OkHttpClient = client,
        read: (Response) -> T,
    ): T = executeBytes(
        method = method,
        path = path,
        body = body.toByteArray(Charsets.UTF_8),
        mediaType = "application/json",
        authenticated = authenticated,
        httpClient = httpClient,
        read = read,
    )

    private suspend fun <T> executeBytes(
        method: String,
        path: String,
        body: ByteArray,
        mediaType: String,
        authenticated: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        httpClient: OkHttpClient = client,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val bytes = body
        val builder = if (authenticated) signedBuilder(method, path, bytes) else Request.Builder()
        builder.url(root + path)
        headers.forEach(builder::header)
        if (method != "GET") {
            builder.method(method, bytes.toRequestBody(mediaType.toMediaType()))
        }
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) {
                                throw BridgeHttpException(it.code, it.body?.string().orEmpty())
                            }
                            read(it)
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    private fun signedBuilder(method: String, path: String, body: ByteArray): Request.Builder {
        val secret = requireNotNull(token) { "Device is not paired" }
        val headers = RequestSigner.sign(
            secret,
            method,
            path,
            body,
            UUID.randomUUID().toString(),
            Instant.now().epochSecond,
        )
        return Request.Builder()
            .header("Authorization", "Bearer $secret")
            .header("X-Request-Id", headers.requestId)
            .header("X-Timestamp", headers.timestamp)
            .header("X-Signature", headers.signature)
    }

}

internal fun attachmentUploadPath(name: String, mimeType: String, idempotencyKey: String): String =
    "/v1/attachments?name=${encodeQuery(name)}" +
        "&mimeType=${encodeQuery(mimeType.lowercase())}&idempotencyKey=${encodeQuery(idempotencyKey)}"

internal fun queueDeletePath(
    threadId: String,
    messageId: String,
    expectedQueueHash: String,
    idempotencyKey: String,
): String = "/v1/tasks/${encodePathSegment(threadId)}/queue/${encodePathSegment(messageId)}" +
    "?expectedQueueHash=${encodeQuery(expectedQueueHash)}&idempotencyKey=${encodeQuery(idempotencyKey)}"

private fun encodeQuery(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun encodePathSegment(value: String): String = encodeQuery(value)
