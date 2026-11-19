package ca.mslab.forge.data

import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*

const val TARGET_KANDEV_VERSION = "0.98.0"

@Serializable
data class SystemInfo(val version: String, val commit: String)

@Serializable
data class Workspace(val id: String, val name: String, val description: String? = null,
    @kotlinx.serialization.SerialName("task_prefix") val taskPrefix: String = "",
    @kotlinx.serialization.SerialName("default_agent_profile_id") val defaultAgentProfileId: String? = null,
    @kotlinx.serialization.SerialName("default_executor_id") val defaultExecutorId: String? = null,
    @kotlinx.serialization.SerialName("default_executor_profile_id") val defaultExecutorProfileId: String? = null)

@Serializable
internal data class WorkspaceList(val workspaces: List<Workspace>, val total: Int)

@Serializable
data class ConnectionSnapshot(val server: SystemInfo, val workspaces: List<Workspace>)

open class KandevApiException(message: String, val code: String? = null) : IOException(message)

/** Marks a request whose failure says nothing about the route (optional plugin telemetry). */
internal object Advisory

internal class UsageUnavailable(val status: Int) : KandevApiException("Provider usage returned HTTP $status")

/** Only the v0.98.0 HTTP contract. Credentials never enter URLs. */
class KandevApi(
    serverUrl: String,
    private val token: String = "",
    private val client: OkHttpClient = defaultClient,
) {
    internal var routes: ca.mslab.forge.data.routing.ServerRoutes? = null
    private val baseUrl = validateServerUrl(serverUrl)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    init {
        require(token.none { it == '\r' || it == '\n' }) { "The API token contains a line break." }
    }

    suspend fun connect(): ConnectionSnapshot = ConnectionSnapshot(checkVersion(), listWorkspaces())

    suspend fun checkVersion(): SystemInfo {
        val info = decode<SystemInfo>("/api/v1/system/info")
        if (info.version.removePrefix("v") != TARGET_KANDEV_VERSION) {
            throw KandevApiException(
                "This build needs Kandev v$TARGET_KANDEV_VERSION. Update the server before connecting.",
            )
        }
        return info
    }

    suspend fun listWorkspaces(): List<Workspace> =
        decode<WorkspaceList>("/api/v1/workspaces").workspaces

    suspend fun listTasks(workspaceId: String, page: Int = 1, pageSize: Int = 100): TaskPage =
        decode("/api/v1/workspaces/${segment(workspaceId)}/tasks", mapOf("page" to "$page", "page_size" to "$pageSize"))

    suspend fun getTask(taskId: String): TaskSummary = decode("/api/v1/tasks/${segment(taskId)}")

    suspend fun listWorkflows(workspaceId: String): List<Workflow> =
        decode<WorkflowList>("/api/v1/workspaces/${segment(workspaceId)}/workflows").workflows

    suspend fun listWorkflowSteps(workspaceId: String): List<WorkflowStep> =
        decode<WorkflowStepList>("/api/v1/workspaces/${segment(workspaceId)}/workflow-steps").steps

    suspend fun launchOptions(): LaunchOptions = kotlinx.coroutines.coroutineScope {
        val agents = async { decode<AgentList>("/api/v1/agents").agents }
        val executors = async { decode<ExecutorList>("/api/v1/executors").executors }
        LaunchOptions(agents.await(), executors.await())
    }

    /**
     * Creates a task (POST /api/v1/tasks). One explicit request with no automatic retry: an
     * uncertain outcome is surfaced to the user rather than risking a duplicate task and agent.
     */
    internal suspend fun createTask(body: JsonObject): TaskSummary {
        val request = authenticated("/api/v1/tasks").header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val response = exchange(request, client.newBuilder().retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS).build())
        if (response.code !in 200..299) throw KandevApiException(
            runCatching { json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                ?.let { "Kandev couldn't create the task: $it" } ?: httpError(response.code))
        return withContext(Dispatchers.Default) { json.decodeFromString<TaskSummary>(response.body) }
    }

    /** Read-only: what moving the task would do (agent prompt, session, model). */
    suspend fun movePreview(taskId: String, workflowId: String, stepId: String): MovePreview =
        postJson("/api/v1/tasks/${segment(taskId)}/move-preview", buildJsonObject {
            put("workflow_id", workflowId); put("workflow_step_id", stepId)
        }).let { json.decodeFromJsonElement(MovePreview.serializer(), it) }

    /** Moves a task to another workflow step. Explicit user action; never retried automatically. */
    suspend fun moveTask(taskId: String, workflowId: String, stepId: String) {
        postJson("/api/v1/tasks/${segment(taskId)}/move", buildJsonObject {
            put("workflow_id", workflowId); put("workflow_step_id", stepId); put("position", 0)
        })
    }

    /** Archives a task (it leaves the board; Kandev stops its sessions). Explicit user action; never retried. */
    suspend fun archiveTask(taskId: String) {
        postJson("/api/v1/tasks/${segment(taskId)}/archive", buildJsonObject { })
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonElement {
        val request = authenticated(path).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val response = exchange(request, client.newBuilder().retryOnConnectionFailure(false).build())
        if (response.code !in 200..299) throw KandevApiException(
            runCatching { json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                ?: httpError(response.code))
        return if (response.body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(response.body)
    }

    suspend fun listRepositories(workspaceId: String): List<Repository> =
        decode<RepositoryList>("/api/v1/workspaces/${segment(workspaceId)}/repositories").repositories

    suspend fun listSessions(taskId: String): List<AgentSession> =
        decode<SessionList>("/api/v1/tasks/${segment(taskId)}/sessions").sessions

    suspend fun listMessages(sessionId: String, before: String? = null): MessagePage =
        decode("/api/v1/task-sessions/${segment(sessionId)}/messages",
            mapOf("limit" to "100", "sort" to "desc") + (before?.let { mapOf("before" to it) } ?: emptyMap()))

    suspend fun shellOutput(sessionId: String, messageId: String): ShellOutputSnapshot =
        decode("/api/v1/task-sessions/${segment(sessionId)}/messages/${segment(messageId)}/shell-output")

    internal suspend fun uploadAttachment(workspaceId: String, local: LocalAttachment, file: File): UploadedAttachment {
        require(file.isFile && file.length() == local.size && local.size <= MAX_ATTACHMENT_BYTES)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("workspace_id", workspaceId).addFormDataPart("kind", local.kind)
            .addFormDataPart("delivery_mode", local.deliveryMode).addFormDataPart("mime_type", local.mimeType)
            .addFormDataPart("file", local.name, file.asRequestBody(local.mimeType.toMediaTypeOrNull())).build()
        val result = exchange(authenticated("/api/v1/attachments").post(multipart).build(), attachmentClient(false))
        if (result.code !in 200..299) throw KandevApiException(httpError(result.code))
        val uploaded = withContext(Dispatchers.Default) { json.decodeFromString<UploadedAttachment>(result.body) }
        check(uploaded.id.isNotBlank() && uploaded.size == local.size && uploaded.kind == local.kind &&
            uploaded.deliveryMode == local.deliveryMode && uploaded.state == "staged")
        return uploaded
    }

    internal suspend fun downloadAttachment(id: String, destination: File): File = suspendCancellableCoroutine { continuation ->
        val call = attachmentClient(true).newCall(authenticated("/api/v1/attachments/${segment(id)}/content").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                var temp: File? = null
                try {
                    response.use {
                        if (!it.isSuccessful) throw KandevApiException(httpError(it.code))
                        val body = it.body ?: throw IOException("Empty attachment")
                        require(body.contentLength() <= MAX_ATTACHMENT_BYTES)
                        destination.parentFile?.mkdirs()
                        val scratch = File.createTempFile("attachment-", ".tmp", destination.parentFile); temp = scratch
                        var size = 0L
                        body.byteStream().use { source -> scratch.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                if (call.isCanceled()) throw IOException("Download cancelled")
                                val count = source.read(buffer); if (count < 0) break
                                size += count; require(size <= MAX_ATTACHMENT_BYTES)
                                output.write(buffer, 0, count)
                            }
                        } }
                        if (body.contentLength() >= 0) check(size == body.contentLength())
                        if (!continuation.isActive) return@use
                        Files.move(scratch.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        continuation.resume(destination)
                    }
                } catch (e: Exception) {
                    temp?.delete(); temp = null
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { temp?.delete() }
            }
        })
    }

    private fun authenticated(path: String) = Request.Builder().url(baseUrl.newBuilder().encodedPath(path).build())
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
    private fun attachmentClient(retry: Boolean) = client.newBuilder().retryOnConnectionFailure(retry)
        .followRedirects(false).followSslRedirects(false).callTimeout(120, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    internal suspend fun listTurns(sessionId: String): List<SessionTurn> =
        decode<TurnList>("/api/v1/task-sessions/${segment(sessionId)}/turns").turns

    internal suspend fun answerQuestions(pendingId: String, body: JsonObject): ClarificationResolution {
        val request = Request.Builder().url(baseUrl.newBuilder()
            .encodedPath("/api/v1/clarification/${segment(pendingId)}/respond").build())
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        // POST delivery can be uncertain: only an explicit operator action may retry.
        val response = exchange(request, client.newBuilder().retryOnConnectionFailure(false).build())
        if (response.code == 404) throw KandevApiException("Request no longer available.", "not_active")
        if (response.code == 409) {
            val code = runCatching { json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
            if (code == "not_active") throw KandevApiException("Request no longer active.", code)
        }
        if (response.code !in 200..299) throw KandevApiException(httpError(response.code))
        val result = withContext(Dispatchers.Default) { json.decodeFromString<ClarificationResolution>(response.body) }
        require(result.success && result.status in setOf("answered", "rejected") && result.response.pendingId == pendingId)
        require(result.response.rejected == (result.status == "rejected"))
        return result
    }

    internal suspend fun listTerminals(taskId: String, environmentId: String): TerminalList =
        decode("/api/v1/tasks/${segment(taskId)}/terminals", mapOf("task_environment_id" to environmentId))

    internal fun terminalRequest(environmentId: String, terminalId: String): Request = Request.Builder()
        .url(baseUrl.newBuilder().encodedPath("/terminal/environment/${segment(environmentId)}")
            .addQueryParameter("terminalId", terminalId).build())
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }.build()

    internal fun terminalClient(): OkHttpClient = client.newBuilder().pingInterval(15, TimeUnit.SECONDS).build()

    /** 30 s pings keep idle proxies (Cloudflare closes after 100 s) open with fewer radio wake-ups in the background. */
    internal fun sessionSocket(): SessionSocket = SessionSocket(
        baseUrl.newBuilder().encodedPath("/ws").build(), token,
        client.newBuilder().pingInterval(30, TimeUnit.SECONDS).build(),
    )

    /** A provider logo as SVG text; null when Kandev has none for this agent. */
    internal suspend fun agentLogo(agent: String, variant: String): String? = try {
        get("/api/v1/agents/${segment(agent)}/logo", mapOf("variant" to variant)).takeIf { it.contains("<svg") }
    } catch (e: KandevApiException) { null }

    /**
     * Provider quota telemetry from the Provider Usage plugin, as raw JSON. Advisory: its status never
     * demotes the connection route, and [refresh] asks the plugin to re-read providers instead of its cache.
     */
    internal suspend fun providerUsage(refresh: Boolean): String {
        val url = baseUrl.newBuilder().encodedPath("/api/plugins/kandev-provider-usage/webhooks/providers")
            .apply { if (refresh) addQueryParameter("refresh", "1") }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").tag(Advisory::class.java, Advisory)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }.build()
        val result = exchange(request)
        if (result.code !in 200..299) throw UsageUnavailable(result.code)
        return result.body
    }

    private fun segment(value: String): String = HttpUrl.Builder().scheme("https").host("placeholder.invalid")
        .addPathSegment(value).build().encodedPath.removePrefix("/")

    private suspend inline fun <reified T> decode(path: String, query: Map<String, String> = emptyMap()): T {
        val body = get(path, query)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
    }

    private data class HttpResult(val code: Int, val body: String)

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): String {
        val url = baseUrl.newBuilder().encodedPath(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }.build()
        val result = exchange(request)
        if (result.code !in 200..299) throw KandevApiException(httpError(result.code))
        return result.body
    }

    private suspend fun exchange(request: Request, transport: OkHttpClient = client): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val source = it.body?.source() ?: throw KandevApiException("The server returned an empty response.")
                        val limit = 16L * 1024 * 1024
                        source.request(limit + 1)
                        if (source.buffer.size > limit) throw KandevApiException("This response is too large to load on the device.")
                        if (continuation.isActive) continuation.resume(HttpResult(it.code, source.readUtf8()))
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    companion object {
        // Never forward a bearer credential through a redirect to a different origin.
        internal val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun validateServerUrl(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull()
            require(url != null && url.scheme in setOf("http", "https")) {
                "Enter a full server address, such as https://forge.example.com."
            }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "Use the API token field instead of credentials in the address."
            }
            require(url.query == null && url.fragment == null && url.encodedPath == "/") {
                "Enter the server address without a path, query, or fragment."
            }
            require(url.scheme == "https" || isPrivateHost(url.host)) {
                "Use HTTPS for public addresses. Plain HTTP is allowed only on Tailscale or your local network."
            }
            return url
        }

        /**
         * Hosts where plain HTTP is acceptable: loopback, RFC 1918, Tailscale (100.64.0.0/10, *.ts.net,
         * fd7a:115c:a1e0::/48), link-local, mDNS and single-label LAN names. Tailscale already encrypts.
         */
        fun isPrivateHost(host: String): Boolean {
            val name = host.lowercase().trim('[', ']')
            if (name == "localhost" || name.endsWith(".ts.net") || name.endsWith(".local") || name.endsWith(".lan") ||
                name.endsWith(".home.arpa") || name.endsWith(".internal")) return true
            val v4 = name.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
            if (v4 != null) {
                val (a, b) = v4
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127) || (a == 169 && b == 254)
            }
            if (name.contains(':')) return name == "::1" || name.startsWith("fd7a:115c:a1e0") || name.startsWith("fc") ||
                name.startsWith("fd") || name.startsWith("fe80")
            return !name.contains('.')
        }

        private fun httpError(code: Int): String = when (code) {
            401 -> "Authentication required. Enter a valid Kandev API token."
            403 -> "Access denied. Check the token's account permissions."
            404 -> "Kandev API not found. Check the server address."
            in 300..399 -> "The server redirected this request. Enter its final Kandev address."
            else -> "The server returned HTTP $code. Try again."
        }
    }
}
