package ca.mslab.forge.data.sync

import ca.mslab.forge.data.KandevApi
import ca.mslab.forge.data.SessionSocket
import ca.mslab.forge.data.WireMessage
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class LinkState { Offline, Connecting, Live }

/** The task and session open on screen; subscribed on the shared socket next to the running ones. */
internal data class Focus(val taskId: String, val sessionId: String)

/**
 * The one app-wide socket. It subscribes to the user's workspace feed (`user.subscribe`), to the
 * sessions that are currently running and to the task open on screen, and fans events out to the
 * database, notifications and the open conversation. Screens never open sockets of their own:
 * moving between the list and a task costs one subscription round trip, not a new connection.
 */
internal class LiveLink(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(LinkState.Offline)
    val state: StateFlow<LinkState> = _state
    private val _events = MutableSharedFlow<WireMessage>(extraBufferCapacity = 1024)
    val events: SharedFlow<WireMessage> = _events
    /**
     * What the UI should show: drops are announced only once they last a few seconds, recovery at once.
     * Starts optimistic so a cold start over cached data doesn't flash "offline".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val settled: StateFlow<LinkState> = _state.transformLatest { if (it != LinkState.Live) delay(2_500); emit(it) }
        .stateIn(scope, SharingStarted.Eagerly, LinkState.Live)
    /**
     * Emits once per successful (re)subscription so consumers can revalidate anything missed. The value is how
     * long the link was down in milliseconds ([Long.MAX_VALUE] on the first connection).
     */
    private val _connected = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val connected: SharedFlow<Long> = _connected
    @Volatile private var lostAt = 0L

    private var job: Job? = null
    @Volatile private var socket: SessionSocket? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val watched = MutableStateFlow<Set<String>>(emptySet())
    private val focus = MutableStateFlow<Focus?>(null)
    private var subscribed = emptySet<String>()
    private var subscribedTask: String? = null
    private val subscriptions = kotlinx.coroutines.sync.Mutex()

    fun start(api: KandevApi) {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                _state.value = LinkState.Connecting
                var transport: SessionSocket? = null
                var route: ca.mslab.forge.data.routing.ServerRoute? = null
                try {
                    route = api.routes?.select()
                    transport = api.sessionSocket()
                    transport.request("user.subscribe", JsonObject(emptyMap()), 15_000)
                    subscriptions.withLock { subscribed = emptySet(); subscribedTask = null }
                    sync(transport)
                    socket = transport
                    _state.value = LinkState.Live
                    _connected.tryEmit(if (lostAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lostAt)
                    backoff = 1_000L
                    coroutineScope {
                        val watcher = launch { combine(watched, focus) { _, _ -> }.collect { sync(transport) } }
                        try { for (event in transport.events) _events.emit(event) }
                        finally { watcher.cancel() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { api.routes?.failed(route) }
                finally {
                    transport?.close()
                    if (socket === transport) socket = null
                    if (_state.value == LinkState.Live) lostAt = System.currentTimeMillis()
                    _state.value = LinkState.Offline
                }
                withTimeoutOrNull(backoff + (0..backoff / 4).random()) { wake.receive() }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        if (_state.value == LinkState.Live) lostAt = System.currentTimeMillis()
        job?.cancel(); job = null; socket?.close(); socket = null; _state.value = LinkState.Offline
    }

    /** Network came back: retry immediately instead of waiting for the backoff. */
    fun wakeUp() { wake.trySend(Unit) }

    /** Sessions whose live activity (tool calls, todos) should stream; usually the running ones. */
    fun watchSessions(ids: Set<String>) { watched.value = ids }

    /** Sets the task and session on screen (or clears it); the subscription follows in the background. */
    fun setFocus(value: Focus?) { focus.value = value }

    /** Like [setFocus], but returns only once the server confirmed the subscriptions. */
    suspend fun focus(value: Focus) {
        focus.value = value
        socket?.let { runCatching { sync(it) } }
    }

    /** A correlated request on the shared socket; fails fast when the link is down. */
    suspend fun request(action: String, payload: JsonObject, timeoutMs: Long = 30_000): JsonObject {
        val transport = socket?.takeIf { _state.value == LinkState.Live } ?: throw IOException("Not connected")
        return transport.request(action, payload, timeoutMs)
    }

    private suspend fun sync(transport: SessionSocket) = subscriptions.withLock {
        val current = focus.value
        val wanted = watched.value + listOfNotNull(current?.sessionId)
        for (id in wanted - subscribed) runCatching {
            transport.request("session.subscribe", buildJsonObject { put("session_id", id) }, 15_000)
        }.onSuccess { subscribed = subscribed + id }
        for (id in subscribed - wanted) runCatching {
            transport.request("session.unsubscribe", buildJsonObject { put("session_id", id) }, 15_000)
        }
        subscribed = subscribed intersect wanted
        val task = current?.taskId
        if (task != subscribedTask) {
            subscribedTask?.let { old -> runCatching { transport.request("task.unsubscribe", buildJsonObject { put("task_id", old) }, 15_000) } }
            subscribedTask = null
            if (task != null) runCatching {
                transport.request("task.subscribe", buildJsonObject { put("task_id", task) }, 15_000)
            }.onSuccess { subscribedTask = task }
        }
    }
}
