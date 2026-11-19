package ca.mslab.forge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.viewModelScope
import ca.mslab.forge.data.*
import ca.mslab.forge.data.db.StepRow
import ca.mslab.forge.data.db.RuntimeRow
import ca.mslab.forge.data.sync.toTask
import ca.mslab.forge.data.db.WorkflowRow
import ca.mslab.forge.data.sync.ForgeHub
import ca.mslab.forge.data.sync.ForgeStore
import ca.mslab.forge.data.sync.Focus
import ca.mslab.forge.data.sync.LinkState
import ca.mslab.forge.data.routing.serverRoutes
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    var serverUrl by mutableStateOf(""); private set
    var token by mutableStateOf(""); private set
    var routeLabel by mutableStateOf("Connecting"); private set
    var routeAddress by mutableStateOf(""); private set
    private var savedConfig: SavedConnection? = null
    private var routeJob: Job? = null
    var snapshot by mutableStateOf<ConnectionSnapshot?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var workspace by mutableStateOf<Workspace?>(null); private set
    var tasks by mutableStateOf<List<TaskSummary>>(emptyList()); private set
    var taskTotal by mutableStateOf(0); private set
    var repositories by mutableStateOf<Map<String, Repository>>(emptyMap()); private set
    var task by mutableStateOf<TaskSummary?>(null); private set
    var sessions by mutableStateOf<List<AgentSession>>(emptyList()); private set
    var session by mutableStateOf<AgentSession?>(null); private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    var hasOlder by mutableStateOf(false); private set
    var loadingOlder by mutableStateOf(false); private set
    var syncingHistory by mutableStateOf(false); private set
    var liveStatus by mutableStateOf("Connecting…"); private set
    var live by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var draft by mutableStateOf(""); private set
    var sendError by mutableStateOf<String?>(null); private set
    var toolOutputs by mutableStateOf<Map<String, ShellOutputSnapshot>>(emptyMap()); private set
    var loadingOutputs by mutableStateOf<Set<String>>(emptySet()); private set
    var outputErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var restoring by mutableStateOf(true); private set
    var cacheNotice by mutableStateOf<String?>(null); private set
    internal val hub = ForgeHub.get(application)
    private val vault get() = hub.vault
    private var store: ForgeStore? = null
    private var workspaceJob: Job? = null
    var steps by mutableStateOf<List<StepRow>>(emptyList()); private set
    var workflows by mutableStateOf<List<WorkflowRow>>(emptyList()); private set
    val linkState get() = hub.link.state
    /** Live todos, activity and context usage per session, streamed into the database. */
    var runtimes by mutableStateOf<Map<String, RuntimeRow>>(emptyMap()); private set
    internal val queue = QueueController(viewModelScope, { QueueContext(task?.id.orEmpty(), session, live) },
        { action, payload, timeout -> link.request(action, payload, timeout) })
    /** Your sent messages until their copy arrives in the conversation stream. */
    internal var outgoing by mutableStateOf<List<OutgoingMessage>>(emptyList()); private set
    /**
     * Bubbles still waiting for Kandev's copy. Kandev may add context to the stored text, so a new message of
     * yours that contains it counts; once delivery is confirmed, any new message of yours does (sends are serial).
     */
    internal val pendingOutgoing get() = outgoing.filter { sent -> sent.sessionId == session?.id && messages.none {
        it.author == "user" && it.id !in sent.known && (sent.acknowledged || (sent.content.isNotEmpty() && sent.content in it.content))
    } }
    /** How the composer delivers a draft for the open session right now. */
    internal val inputMode get() = inputMode(session, queue.count)
    private val uiPrefs = application.getSharedPreferences("forge-ui", android.content.Context.MODE_PRIVATE)
    /** How home lays out tasks: by workflow step, by repository, or as a Kanban board. */
    var homeView by mutableStateOf(uiPrefs.getString("home_view", null)?.let { runCatching { HomeView.valueOf(it) }.getOrNull() }
        ?: if (uiPrefs.getBoolean("group_repo", false)) HomeView.Repository else HomeView.Steps); private set
    fun chooseHomeView(value: HomeView) { homeView = value; uiPrefs.edit().putString("home_view", value.name).apply() }
    val groupByRepository get() = homeView == HomeView.Repository
    /** Home sections you expanded or collapsed against their default. */
    var homeToggled by mutableStateOf(emptySet<String>())
    private var cache: SnapshotStore? = null
    private var attachmentStore: AttachmentStore? = null
    private var previewDirectory = File(application.cacheDir, "attachment-previews")
    private var logoDirectory: File? = null
    internal val logos = AgentLogos(viewModelScope, { logoDirectory }, { api })
    /** Codex/Claude quota telemetry from the server's Provider Usage plugin; foreground-only. */
    internal val usage = UsageController(viewModelScope, { api }, { cache })
    /** Agent profile id → agent (`claude-acp`, `codex-acp`…), from the cached launch options. */
    private val profileAgents by androidx.compose.runtime.derivedStateOf {
        launchOptions?.agents.orEmpty().flatMap { agent -> agent.profiles.orEmpty().map { it.id to agent.name } }.toMap()
    }
    /** The provider logo for an agent profile, or null while unknown (callers fall back to a generic mark). */
    fun agentIcon(profileId: String?, dark: Boolean): androidx.compose.ui.graphics.vector.ImageVector? =
        profileId?.let { profileAgents[it] }?.let { logos.icon(it, dark) }
    private var pickerJob: Job? = null
    internal val attachments = AttachmentController(viewModelScope, { cache }, { attachmentStore }, { api },
        { workspace?.id }, { previewDirectory }, { live })
    internal val sessionActions = SessionActions(viewModelScope, {
        SessionActionContext(task?.id.orEmpty(), session, live)
    }, {
        val id = requireNotNull(session).id; val taskId = requireNotNull(task).id; val current = conversationGeneration
        val fresh = requireNotNull(api).listSessions(taskId).first { it.id == id }
        check(current == conversationGeneration && session?.id == id)
        fresh
    }, { action, payload -> link.request(action, payload, if (action in setOf("session.launch", "session.recover")) 60_000 else 30_000) },
        ::updateSelectedSession)

    private fun updateSelectedSession(value: AgentSession) {
        if (session?.id != value.id) return
        session = value; sessions = sessions.map { if (it.id == value.id) value else it }
        tools.updateMetadata(value)
        task?.id?.let { id -> val items = sessions; store?.let { s -> viewModelScope.launch { runCatching { s.saveSessions(id, items) } } } }
    }

    fun addDocuments(uris: List<Uri>) {
        if (sending) return
        val current = conversationGeneration
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val sources = withContext(Dispatchers.IO) { uris.map { uri ->
                val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } }.getOrNull() ?: "attachment"
                val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
                AttachmentSource(name, mime) { requireNotNull(resolver.openInputStream(uri)) }
            } }
            if (current == conversationGeneration) attachments.add(sources)
        }
    }
    private var cleanup: Job? = null
    private var conversationRestore: Job? = null
    private var conversationGeneration = 0
    private var historyWrite: Job? = null
    private var draftWrite: Job? = null
    private val writes = mutableSetOf<Job>()
    private val networkWake = Channel<Unit>(Channel.CONFLATED)
    private var api: KandevApi? = null
    internal val tools = WorkspaceController(viewModelScope, { action, payload ->
        if (!live) throw IOException("Not connected")
        link.request(action, payload)
    }, { cache }, { api })
    private var inputTurns by mutableStateOf<List<SessionTurn>?>(null)
    /** When the agent's current turn began, for the "working for 2m" timer. */
    val turnStartedAt: String? get() = inputTurns?.filter { it.completedAt == null }?.maxByOrNull { it.startedAt }?.startedAt
    internal val inputs = InputController(viewModelScope, ::inputContext, {
        val selected = requireNotNull(session)
        val selectedTask = requireNotNull(task)
        val current = conversationGeneration
        val connection = requireNotNull(api)
        val freshSession = connection.listSessions(selectedTask.id).first { it.id == selected.id }
        val freshTurns = connection.listTurns(selected.id)
        val page = connection.listMessages(selected.id)
        check(current == conversationGeneration && session?.id == selected.id && live)
        session = freshSession; sessions = sessions.map { if (it.id == selected.id) freshSession else it }
        inputTurns = freshTurns
        messages = orderedMessages(messages.filterNot { old -> page.messages.any { it.id == old.id } } + page.messages)
        cacheConversation()
        InputContext(selectedTask.id, freshSession, orderedMessages(page.messages), freshTurns, live)
    }, { payload -> link.request("permission.respond", payload); Unit },
        { id, body -> requireNotNull(api).answerQuestions(id, body) }, { cache }, ::resolveInput)

    private fun inputContext() = InputContext(task?.id.orEmpty(), session, messages, inputTurns, live)

    private fun resolveInput(input: PendingInput, status: String, answers: List<QuestionAnswer>) {
        messages = messages.map { message ->
            val matches = when (input) {
                is PendingInput.Permission -> message.id == input.message.id && message.metadata?.requestId == input.message.metadata?.requestId
                is PendingInput.Questions -> message.type == "clarification_request" && message.metadata?.pendingId == input.pendingId
            }
            if (matches && message.metadata != null) message.copy(requestsInput = false, metadata = message.metadata.copy(
                status = status, response = answers.firstOrNull { it.questionId == message.metadata.question?.id })) else message
        }
        cacheConversation()
    }

    private var request: Job? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null
    private var olderJob: Job? = null
    private val link get() = hub.link
    private var foreground = false
    private var generation = 0
    private var taskPage = 1
    private var olderCursor: String? = null
    private var historyDepth = 1
    private val drafts = mutableMapOf<String, String>()
    private val sendIdentities = mutableMapOf<String, Pair<String, String>>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            try {
                // Paint from the database before any network round trip.
                vault.read()?.let { saved ->
                    savedConfig = saved; serverUrl = saved.url; token = saved.token; accessCredential = saved.access
                    hub.restore()?.let { profile -> restoreFromDatabase(profile) }
                    restoring = false
                    connect(); request?.join()
                }
            } catch (e: Exception) { error = "Saved connection could not be opened. Enter the server details again." }
            finally { restoring = false }
        }
    }

    private suspend fun restoreFromDatabase(profile: ForgeHub.Profile) {
        bind(profile)
        cached("connection", ConnectionSnapshot.serializer())?.let { saved ->
            snapshot = saved; cacheNotice = "Saved content · refreshing…"
            val id = hub.workspaceId.value
            (saved.workspaces.firstOrNull { it.id == id } ?: saved.workspaces.firstOrNull())?.let(::applyWorkspace)
        }
    }

    private fun bind(profile: ForgeHub.Profile) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            var previous: ca.mslab.forge.data.routing.ServerRoute? = null
            profile.routes.active.collect { route ->
                if (route != null) { routeLabel = route.label; routeAddress = route.url.toString() }
                else if (previous == null) routeLabel = "Connecting"
                if (route != null) {
                    if (previous != null && route != previous) tools.terminal.routeChanged()
                    previous = route
                }
            }
        }
        api = profile.api; cache = profile.snapshots; store = profile.store
        attachmentStore = AttachmentStore(File(getApplication<Application>().filesDir, "attachments"), profile.identity)
        previewDirectory = File(getApplication<Application>().cacheDir, "attachment-previews/${digest(profile.identity)}")
        logoDirectory = File(getApplication<Application>().filesDir, "agent-logos/${digest(profile.identity).take(16)}")
        // Profiles map tasks to their provider logo; cached first, refreshed in the background.
        loadLaunchOptions()
        usage.bind(profile.identity)
    }

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>): T? =
        runCatching { cache?.read(key, serializer)?.value }.getOrNull()

    private fun <T> persist(key: String, serializer: KSerializer<T>, value: T) {
        val storage = cache ?: return
        val job = viewModelScope.launch { runCatching { storage.write(key, serializer, value) } }
        writes.add(job)
        job.invokeOnCompletion { writes.remove(job) }
    }

    private fun conversationSnapshot(): CachedConversation {
        val recent = messages.takeLast(100)
        return CachedConversation(recent, if (messages.size > 100) recent.firstOrNull()?.id else olderCursor,
            hasOlder || messages.size > 100, 1)
    }

    private fun persistConversation(id: String, value: CachedConversation) {
        val data = store ?: return
        val job = hub.scope.launch { runCatching { data.saveConversation(id, value) } }
        writes.add(job)
        job.invokeOnCompletion { writes.remove(job) }
    }

    private fun cacheConversation() {
        if (historyWrite?.isActive == true) return
        val id = session?.id ?: return
        historyWrite = viewModelScope.launch {
            delay(750)
            if (session?.id == id) persistConversation(id, conversationSnapshot())
        }
    }

    fun networkAvailable() {
        networkWake.trySend(Unit); tools.terminal.networkAvailable()
        // Showing saved content: revalidate once the network returns. Reads only; never replays input.
        viewModelScope.launch { if (cacheNotice != null && !busy && api != null && task == null) refresh() }
    }
    fun networkLost() {
        viewModelScope.launch {
            cacheNotice = "Offline · saved content"
        }
    }

    private var accessCredential: ca.mslab.forge.security.AccessCredential? = null

    internal fun connectRemote(enrollment: ca.mslab.forge.security.RemoteEnrollment) {
        load {
            val previous = hub.profile.value
            val old = previous?.routes?.config
            val fresh = KandevApi(enrollment.server, enrollment.token,
                ca.mslab.forge.security.accessClient(enrollment.server, enrollment.access, KandevApi.defaultClient)).connect()
            val anchor = old?.workspaceAnchor ?: snapshot?.workspaces?.firstOrNull()?.id ?: enrollment.workspaceAnchor
            require(anchor == null || fresh.workspaces.any { it.id == anchor }) { "This connection belongs to another server." }
            val direct = old?.takeIf { it.access == null }?.let { ca.mslab.forge.data.routing.DirectRoute(it.url, it.token) }
            val saved = SavedConnection(enrollment.server, enrollment.token, enrollment.access,
                local = enrollment.local ?: old?.local ?: direct?.takeUnless { ca.mslab.forge.data.routing.primaryKind(it.url, null) == ca.mslab.forge.data.routing.RouteMode.Tailscale },
                tailscale = enrollment.tailscale ?: old?.tailscale ?: direct?.takeIf { ca.mslab.forge.data.routing.primaryKind(it.url, null) == ca.mslab.forge.data.routing.RouteMode.Tailscale },
                workspaceAnchor = anchor ?: enrollment.workspaceAnchor ?: fresh.workspaces.firstOrNull()?.id,
                cacheIdentity = previous?.identity)
            serverUrl = saved.url; token = saved.token; accessCredential = saved.access
            connectSaved(saved)
        }
    }

    internal fun saveRoutes(local: String, localToken: String, tailscale: String, tailscaleToken: String,
        mode: ca.mslab.forge.data.routing.RouteMode) {
        load {
            val profile = requireNotNull(hub.profile.value)
            val old = profile.routes.config
            fun direct(url: String, credential: String): ca.mslab.forge.data.routing.DirectRoute? {
                if (url.isBlank()) return null
                val canonical = KandevApi.validateServerUrl(url).toString()
                require(canonical != KandevApi.validateServerUrl(old.url).toString()) { "That address is already the primary route." }
                require(credential.none { it == '\r' || it == '\n' }) { "Invalid direct-route token." }
                return ca.mslab.forge.data.routing.DirectRoute(canonical, credential)
            }
            val saved = old.copy(local = direct(local, localToken), tailscale = direct(tailscale, tailscaleToken), mode = mode,
                workspaceAnchor = old.workspaceAnchor ?: snapshot?.workspaces?.firstOrNull()?.id, cacheIdentity = profile.identity)
            require(saved.workspaceAnchor != null || (saved.local == null && saved.tailscale == null)) { "Connect to the primary server before adding routes." }
            require(saved.serverRoutes().isNotEmpty()) { "Add an address for the selected route first." }
            vault.save(saved); savedConfig = saved
            profile.routes.configure(saved)
            tools.terminal.routeChanged(); hub.routesChanged()
        }
    }

    fun editServer(value: String) { if (value != serverUrl) accessCredential = null; serverUrl = value; error = null }
    fun editToken(value: String) { token = value; error = null }
    fun editDraft(value: String) {
        draft = value
        session?.id?.let { drafts[it] = value }
        sendError = null
        draftWrite?.cancel()
        val id = session?.id ?: return
        draftWrite = viewModelScope.launch {
            delay(250)
            val pending = sendIdentities[id]
            persist("draft:$id", DraftRecord.serializer(), DraftRecord(value, pending?.second, pending?.first))
        }
    }

    private fun load(action: suspend () -> Unit) {
        request?.cancel()
        val current = ++generation
        error = null
        busy = true
        request = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == current) {
                error = connectionError(e)
                if (snapshot != null) cacheNotice = "Saved content · refresh unavailable"
            } }
            finally { if (generation == current) busy = false }
        }
    }

    fun connect() {
        val canonical = runCatching { KandevApi.validateServerUrl(serverUrl).toString() }.getOrDefault(serverUrl)
        val previous = savedConfig?.takeIf { KandevApi.validateServerUrl(it.url).toString() == canonical }
        val saved = previous?.copy(token = token.ifBlank { previous.token }, access = accessCredential)
            ?: SavedConnection(canonical, token, accessCredential)
        load { connectSaved(saved) }
    }

    private suspend fun connectSaved(saved: SavedConnection) {
        cleanup?.join()
        val profile = hub.attach(saved)
        val connection = profile.api
        bind(profile)
        fun pick(list: List<Workspace>) = list.firstOrNull { it.id == (workspace?.id ?: hub.workspaceId.value) } ?: list.firstOrNull()
        if (snapshot == null) cached("connection", ConnectionSnapshot.serializer())?.let {
            snapshot = it; cacheNotice = "Saved content · refreshing…"
            pick(it.workspaces)?.let(::applyWorkspace)
        }
        val fresh = connection.connect()
        snapshot = fresh; cacheNotice = null
        persist("connection", ConnectionSnapshot.serializer(), fresh)
        val anchored = saved.copy(workspaceAnchor = saved.workspaceAnchor ?: fresh.workspaces.firstOrNull()?.id,
            cacheIdentity = profile.identity)
        savedConfig = anchored; profile.routes.configure(anchored)
        try { vault.save(anchored) }
        catch (e: Exception) { currentCoroutineContext().ensureActive(); error = "Connected, but the profile could not be saved on this device." }
        token = ""
        val selected = pick(fresh.workspaces) ?: return
        if (workspace?.id != selected.id) applyWorkspace(selected) else workspace = selected
        fetchTasks(selected)
    }

    /** Reloads the workspace list without leaving the current workspace. */
    fun refreshWorkspaces() {
        val connection = api ?: return
        load {
            snapshot = snapshot?.copy(workspaces = connection.listWorkspaces())
            cacheNotice = null
            snapshot?.let { persist("connection", ConnectionSnapshot.serializer(), it) }
        }
    }

    fun refresh() {
        val connection = api ?: return
        val selected = workspace
        usage.refreshIfDue()
        if (selected == null) refreshWorkspaces()
        else load {
            snapshot = snapshot?.copy(workspaces = connection.listWorkspaces())
            snapshot?.let { persist("connection", ConnectionSnapshot.serializer(), it) }
            fetchTasks(selected)
        }
    }

    fun openWorkspace(selected: Workspace) {
        applyWorkspace(selected)
        load { fetchTasks(selected) }
    }

    /** Switches the observed workspace; the database answers immediately, the network revalidates. */
    private fun applyWorkspace(selected: Workspace) {
        val changed = workspace?.id != selected.id
        stopConversation()
        workspace = selected
        task = null
        sessions = emptyList()
        hub.selectWorkspace(selected.id)
        if (!changed && workspaceJob?.isActive == true) return
        val data = store ?: return
        if (changed) { tasks = emptyList(); taskTotal = 0; repositories = emptyMap(); steps = emptyList(); workflows = emptyList() }
        workspaceJob?.cancel()
        workspaceJob = viewModelScope.launch {
            launch {
                data.tasks(selected.id).collect { rows ->
                    tasks = rows; taskTotal = rows.size
                    // Keep the open task current (agent name, state, diff stats) as its row updates.
                    task?.let { open -> rows.firstOrNull { it.id == open.id }?.let { if (it != open) task = it } }
                }
            }
            launch { data.repositories(selected.id).collect { repositories = it } }
            launch { data.steps(selected.id).collect { steps = it } }
            launch { data.workflows(selected.id).collect { workflows = it } }
            launch { data.db.dao().runtimes().collect { rows -> runtimes = rows.associateBy { it.sessionId } } }
        }
    }

    private suspend fun fetchTasks(selected: Workspace) {
        val data = requireNotNull(store)
        data.refreshWorkspace(selected.id)
        cacheNotice = null
        hub.scope.launch { runCatching { data.prefetch(selected.id) } }
    }

    fun openTask(selected: TaskSummary) {
        stopConversation()
        task = selected
        // While the link is live, events keep the row current; otherwise read it fresh.
        if (link.state.value != LinkState.Live) store?.refetchTask(selected.id, 0)
        sessions = emptyList()
        load {
            fun show(items: List<AgentSession>) {
                sessions = items
                val current = items.firstOrNull { it.id == session?.id }
                if (current != null) { session = current; tools.updateMetadata(current) }
                else (items.firstOrNull { it.id == selected.primarySessionId } ?: items.firstOrNull())?.let(::openSession)
            }
            store?.cachedSessions(selected.id)?.takeIf { it.isNotEmpty() }?.let(::show)
            val fresh = requireNotNull(api).listSessions(selected.id)
            show(fresh)
            store?.saveSessions(selected.id, fresh)
        }
    }

    fun openSession(selected: AgentSession) {
        stopConversation()
        session = selected
        messages = emptyList()
        hasOlder = false
        olderCursor = null
        historyDepth = 1
        draft = drafts[selected.id].orEmpty()
        sendError = null
        toolOutputs = emptyMap(); outputErrors = emptyMap(); loadingOutputs = emptySet()
        tools.selectSession(task?.id.orEmpty(), selected)
        inputTurns = null
        queue.selectSession(selected.id)
        attachments.selectSession(selected.id)
        val current = conversationGeneration
        conversationRestore = viewModelScope.launch {
            try {
                attachments.restore(selected.id)
                cached("turns:${selected.id}", TurnList.serializer())?.let { inputTurns = it.turns }
                (store?.cachedConversation(selected.id) ?: cached("conversation:${selected.id}", CachedConversation.serializer()))?.let {
                    messages = it.messages; hasOlder = it.hasOlder; olderCursor = it.cursor; historyDepth = it.depth
                }
                if (draft.isEmpty()) cached("draft:${selected.id}", DraftRecord.serializer())?.let {
                    draft = it.content; drafts[selected.id] = it.content
                    if (it.submissionId != null && it.submittedContent != null)
                        sendIdentities[selected.id] = it.submittedContent to it.submissionId
                }
            } finally {
                if (conversationGeneration == current) { conversationRestore = null; startConversation() }
            }
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        // The shared link decides liveness; backgrounding never tears the conversation down by itself.
        if (active) { startConversation(); usage.refreshIfDue() }
    }

    fun retryConversation() { pauseConversation(); hub.networkAvailable(); startConversation() }

    /**
     * Follows the open session over the app-wide link. Opening a task is one subscription round trip on a
     * socket that is already up; cached messages stay on screen meanwhile. Each (re)connect resubscribes.
     */
    private fun startConversation() {
        val selected = session ?: return
        val taskId = task?.id ?: return
        if (api == null || conversationRestore?.isActive == true || streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            link.state.collectLatest { state ->
                if (state != LinkState.Live) {
                    live = false
                    liveStatus = if (state == LinkState.Connecting) "Connecting…" else "Offline"
                    tools.onDisconnected()
                    return@collectLatest
                }
                var backoff = 1_000L
                while (true) {
                    try { followConversation(selected, taskId); return@collectLatest }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        live = false
                        liveStatus = "Connection interrupted · retrying"
                        tools.onDisconnected()
                    }
                    withTimeoutOrNull(backoff) { networkWake.receive() }
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    private suspend fun followConversation(selected: AgentSession, taskId: String) = coroutineScope {
        val connection = requireNotNull(api)
        // Listen before subscribing so nothing between the ack and the history read is lost.
        val inbox = Channel<WireMessage>(Channel.UNLIMITED)
        val pump = launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect { inbox.trySend(it) } }
        val scopeId = "core:android:${UUID.randomUUID()}"
        var subscribed = false
        try {
            link.focus(Focus(taskId, selected.id))
            var projection = subscribeConversation(selected.id, scopeId)
            subscribed = true
            live = true
            liveStatus = "Live"
            cacheNotice = null
            tools.onConnected()
            launch { queue.refresh() }
            refreshHistory(connection, selected.id, reuseOlder = true)
            for (event in inbox) {
                val payload = event.payload
                tools.onEvent(event)
                if (payload["session_id"]?.jsonPrimitive?.content != selected.id) continue
                when (event.action) {
                    "session.conversation.changed" -> {
                        if (payload["scope_id"]?.jsonPrimitive?.content != scopeId) continue
                        if (payload["terminal"]?.jsonPrimitive?.booleanOrNull == true) {
                            liveStatus = "Session unavailable"
                            awaitCancellation()
                        }
                        val change = runCatching {
                            projection.apply(json.decodeFromJsonElement(ConversationChange.serializer(), payload), messages)
                        }
                        if (change.isSuccess) {
                            messages = change.getOrThrow()
                            val delta = json.decodeFromJsonElement(ConversationChange.serializer(), payload)
                            for (operation in delta.operations.filter { it.entity == "turn" }) {
                                inputTurns = if (operation.kind == "remove") inputTurns?.filterNot { it.id == operation.id }
                                else operation.turn?.takeIf { it.id == operation.id && it.sessionId == selected.id }?.let { turn ->
                                    inputTurns?.filterNot { it.id == turn.id }?.plus(turn)
                                }
                            }
                            cacheConversation()
                        }
                        else {
                            // v0.98.0 also emits reset-only invalidations with empty revisions.
                            // Coalesce bursts, rebase the subscription, then read authoritative history.
                            delay(400)
                            while (true) {
                                val buffered = inbox.tryReceive().getOrNull() ?: break
                                updateSessionState(buffered, selected.id)
                                tools.onEvent(buffered)
                            }
                            projection = subscribeConversation(selected.id, scopeId)
                            refreshHistory(connection, selected.id)
                        }
                    }
                    "session.state_changed", "session.activity_changed" -> updateSessionState(event, selected.id)
                    "message.queue.status_changed" -> queue.onStatus(payload)
                }
            }
        } finally {
            pump.cancel()
            // Release this screen's conversation scope; the socket itself stays up for everything else.
            if (subscribed) hub.scope.launch {
                runCatching { link.request("session.conversation.unsubscribe", buildJsonObject {
                    put("session_id", selected.id); put("scope_id", scopeId)
                }, 10_000) }
            }
        }
    }

    private suspend fun subscribeConversation(sessionId: String, scopeId: String): ConversationProjection {
        val ack = link.request("session.conversation.subscribe", buildJsonObject {
            put("session_id", sessionId); put("scope_id", scopeId); put("consumer_kind", "core")
        })
        require(ack["success"]?.jsonPrimitive?.boolean == true && ack["protocol_version"]?.jsonPrimitive?.int == 2)
        require(ack["scope_id"]?.jsonPrimitive?.content == scopeId && ack["session_id"]?.jsonPrimitive?.content == sessionId)
        return ConversationProjection(sessionId, scopeId, ack.getValue("epoch").jsonPrimitive.content,
            ack.getValue("revision").jsonPrimitive.content)
    }

    private fun updateSessionState(event: WireMessage, sessionId: String) {
        val payload = event.payload
        if (payload["session_id"]?.jsonPrimitive?.content != sessionId) return
        // Both events carry the live busy substate and whether a send would steer the running turn.
        fun live(value: AgentSession) = value.copy(
            activity = if (payload.containsKey("foreground_activity")) payload["foreground_activity"]?.jsonPrimitive?.contentOrNull else value.activity,
            supportsSteering = payload["supports_steering"]?.jsonPrimitive?.booleanOrNull ?: value.supportsSteering)
        when (event.action) {
            "session.state_changed" -> payload["new_state"]?.jsonPrimitive?.content?.let { state ->
                val cancelling = payload["cancellation_pending"]?.jsonPrimitive?.booleanOrNull
                    ?: if (state in ENDED_STATES) false else session?.cancellationPending ?: false
                val error = payload["error_message"]?.jsonPrimitive?.contentOrNull
                fun next(value: AgentSession) = live(value).copy(state = state, cancellationPending = cancelling,
                    supportsSteering = if (state == "RUNNING") live(value).supportsSteering else false,
                    errorMessage = error ?: if (state == "FAILED") value.errorMessage else "")
                session = session?.let(::next)
                sessions = sessions.map { if (it.id == sessionId) next(it) else it }
            }
            "session.activity_changed" -> {
                session = session?.let(::live)
                sessions = sessions.map { if (it.id == sessionId) live(it) else it }
            }
        }
    }

    /**
     * Reads authoritative history. With [reuseOlder] (open and reconnect, not server resets), a newest page
     * that overlaps the messages on screen keeps the older pages already loaded instead of re-reading them.
     */
    private suspend fun refreshHistory(connection: KandevApi, sessionId: String, reuseOlder: Boolean = false) {
        olderJob?.cancel()
        loadingOlder = false
        syncingHistory = true
        try {
            val (turns, first) = coroutineScope {
                val turns = async { try { connection.listTurns(sessionId) } catch (e: Exception) { currentCoroutineContext().ensureActive(); null } }
                val first = async { connection.listMessages(sessionId) }
                turns.await() to first.await()
            }
            inputTurns = turns
            turns?.let { persist("turns:$sessionId", TurnList.serializer(), TurnList(it)) }
            val newest = orderedMessages(first.messages)
            val overlap = newest.firstOrNull()?.let { oldest -> messages.indexOfFirst { it.id == oldest.id } } ?: -1
            if (reuseOlder && historyDepth > 1 && first.hasMore && overlap > 0) {
                messages = orderedMessages(messages.subList(0, overlap) + newest)
                cacheConversation()
                return
            }
            val rows = mutableListOf<ChatMessage>()
            var page = first
            rows += page.messages
            repeat(historyDepth - 1) {
                if (page.hasMore && page.cursor.isNotBlank()) {
                    page = connection.listMessages(sessionId, page.cursor)
                    rows += page.messages
                }
            }
            messages = orderedMessages(rows)
            hasOlder = page.hasMore
            olderCursor = page.cursor.takeIf { it.isNotBlank() }
            cacheConversation()
        } finally { syncingHistory = false }
    }

    fun loadOlder() {
        val selected = session ?: return
        val cursor = olderCursor ?: return
        if (loadingOlder || syncingHistory || !hasOlder) return
        loadingOlder = true
        olderJob = viewModelScope.launch {
            try {
                var shown = false
                fun show(page: MessagePage) {
                    messages = orderedMessages(messages + page.messages)
                    olderCursor = page.cursor.takeIf { it.isNotBlank() }
                    hasOlder = page.hasMore
                    if (!shown) { historyDepth++; shown = true }
                    cacheConversation()
                }
                cached("history:${selected.id}:$cursor", MessagePage.serializer())?.let(::show)
                if (live) {
                    val page = requireNotNull(api).listMessages(selected.id, cursor)
                    show(page)
                    persist("history:${selected.id}:$cursor", MessagePage.serializer(), page)
                } else if (!shown) error = "Earlier messages are not cached. Connect to load them."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = connectionError(e) }
            finally { loadingOlder = false }
        }
    }

    fun loadToolOutput(message: ChatMessage) {
        val selected = session ?: return
        if (loadingOutputs.contains(message.id)) return
        val connection = api ?: return
        val storage = cache
        loadingOutputs = loadingOutputs + message.id
        viewModelScope.launch {
            try {
                storage?.read("output:${selected.id}:${message.id}", ShellOutputSnapshot.serializer())?.let {
                    if (session?.id == selected.id) toolOutputs = toolOutputs + (message.id to it.value)
                }
                if (live) {
                    val result = connection.shellOutput(selected.id, message.id)
                    if (session?.id == selected.id) {
                        toolOutputs = (toolOutputs + (message.id to result)).entries.toList().takeLast(40).associate { it.toPair() }
                        outputErrors = outputErrors - message.id
                    }
                    storage?.write("output:${selected.id}:${message.id}", ShellOutputSnapshot.serializer(), result)
                } else if (!toolOutputs.containsKey(message.id)) {
                    outputErrors = outputErrors + (message.id to "Output is not cached. Connect to load it.")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (session?.id == selected.id) outputErrors = outputErrors + (message.id to "Output is unavailable. The server may have removed it.")
            } finally { if (session?.id == selected.id) loadingOutputs = loadingOutputs - message.id }
        }
    }

    // New work --------------------------------------------------------------------------------------

    var launchOptions by mutableStateOf<LaunchOptions?>(null); private set
    var creating by mutableStateOf(false); private set
    var createError by mutableStateOf<String?>(null); private set

    /** Agent profiles and executors for the new-task and new-session sheets; cached for offline. */
    fun loadLaunchOptions() {
        val connection = api ?: return
        viewModelScope.launch {
            if (launchOptions == null) cached("launch-options", LaunchOptions.serializer())?.let {
                launchOptions = it; ca.mslab.forge.notify.NotificationBranding.remember(getApplication(), it)
            }
            runCatching { connection.launchOptions() }.onSuccess {
                launchOptions = it; persist("launch-options", LaunchOptions.serializer(), it)
                ca.mslab.forge.notify.NotificationBranding.remember(getApplication(), it)
            }
        }
    }

    fun clearCreateError() { createError = null }

    private fun taskDraftKey() = "new-task:${workspace?.id.orEmpty()}"

    /** The new-task sheet has its own attachment draft, kept on the device like a chat draft. */
    fun beginTaskDraft() {
        if (session != null) return
        val key = taskDraftKey()
        if (attachments.owner == key) return
        attachments.selectSession(key)
        viewModelScope.launch { attachments.restore(key) }
    }

    fun endTaskDraft() { if (attachments.owner == taskDraftKey()) attachments.selectSession("") }

    /**
     * Creates a task and (optionally) starts its agent. The prompt is the task description; Kandev
     * generates the title when none is given. Opens the new task as soon as the server confirms it.
     */
    fun createTask(prompt: String, title: String, repository: Repository?, branch: String, agentProfileId: String?,
        executorId: String?, planMode: Boolean, startAgent: Boolean) {
        val connection = api ?: return
        val data = store ?: return
        val selected = workspace ?: return
        if (creating) return
        val workflowId = tasks.firstOrNull { !it.isDone() }?.workflowId ?: workflows.firstOrNull()?.id
        if (workflowId == null) { createError = "This workspace has no workflow yet. Create one in Kandev."; return }
        creating = true; createError = null
        viewModelScope.launch {
            var uploaded = false
            try {
                val body = buildJsonObject {
                    put("workspace_id", selected.id); put("workflow_id", workflowId)
                    if (title.isBlank()) put("auto_title", true) else put("title", title.trim())
                    put("description", prompt.trim())
                    if (repository != null) put("repositories", buildJsonArray {
                        add(buildJsonObject {
                            put("repository_id", repository.id)
                            branch.trim().ifBlank { repository.defaultBranch }.takeIf { it.isNotBlank() }?.let { put("base_branch", it) }
                        })
                    })
                    if (startAgent) { put("state", "IN_PROGRESS"); put("start_agent", true) }
                    else { put("state", "CREATED"); put("prepare_session", true) }
                    agentProfileId?.let { put("agent_profile_id", it) }
                    executorId?.let { put("executor_id", it) }
                    if (planMode) put("plan_mode", true)
                    put("priority", "medium")
                }
                // Files go up first (staged), then the task claims them; the same path chat messages use.
                val sent = if (attachments.owner == taskDraftKey() && attachments.files.isNotEmpty()) attachments.prepareForSend() else emptyList()
                uploaded = true
                val created = connection.createTask(if (sent.isEmpty()) body else JsonObject(body + ("attachments" to json.encodeToJsonElement(sent))))
                if (sent.isNotEmpty()) runCatching { attachments.acknowledged(sent) }
                data.insertTask(created, selected.id)
                data.refetchTask(created.id, 1_500)
                openTask(created)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                createError = (e as? KandevApiException)?.message
                    ?: if (!uploaded) "Files could not be uploaded, so nothing was created. Try again."
                    else "The task may not have been created. Check the task list before trying again."
            } finally { creating = false }
        }
    }

    /** Starts a fresh agent session on the open task with a first prompt (`session.launch`, intent start). */
    fun startNewSession(prompt: String, agentProfileId: String, executorId: String?) {
        val selectedTask = task ?: return
        if (!live) { createError = "Connect to start a session."; return }
        if (creating || prompt.isBlank()) return
        creating = true; createError = null
        viewModelScope.launch {
            try {
                val response = link.request("session.launch", buildJsonObject {
                    put("task_id", selectedTask.id); put("intent", "start"); put("agent_profile_id", agentProfileId)
                    put("profile_explicit", true); put("prompt", prompt.trim()); put("activation_source", "user_action")
                    executorId?.let { put("executor_id", it) }
                }, 60_000)
                val newId = response["session_id"]?.jsonPrimitive?.contentOrNull
                val fresh = requireNotNull(api).listSessions(selectedTask.id)
                store?.saveSessions(selectedTask.id, fresh)
                sessions = fresh
                (fresh.firstOrNull { it.id == newId } ?: fresh.lastOrNull())?.let(::openSession)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                createError = "The session may not have started. Check the session list before trying again."
            } finally { creating = false }
        }
    }

    // Moving tasks between workflow steps ----------------------------------------------------------

    var movePreview by mutableStateOf<MovePreview?>(null); private set
    var moveBusy by mutableStateOf(false); private set
    var moveError by mutableStateOf<String?>(null); private set

    /** Read-only preview: does entering the step start the agent, reuse the session, reset context? */
    fun previewMove(target: TaskSummary, step: StepRow) {
        val connection = api ?: return
        movePreview = null; moveError = null; moveBusy = true
        viewModelScope.launch {
            try { movePreview = connection.movePreview(target.id, step.workflowId, step.id) }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); moveError = "Couldn't preview this move. Connect to Kandev and try again." }
            finally { moveBusy = false }
        }
    }

    fun clearMove() { movePreview = null; moveError = null }

    /** Explicit, single request; the row updates from the server's task event and a direct refetch. */
    fun moveTask(target: TaskSummary, step: StepRow, onDone: () -> Unit) {
        val connection = api ?: return
        if (moveBusy) return
        moveBusy = true; moveError = null
        viewModelScope.launch {
            try {
                connection.moveTask(target.id, step.workflowId, step.id)
                store?.refetchTask(target.id, 0)
                movePreview = null
                onDone()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                moveError = (e as? KandevApiException)?.message?.let { "Not moved: $it" } ?: "The move may not have happened. Check the task before retrying."
            } finally { moveBusy = false }
        }
    }

    // Housekeeping ----------------------------------------------------------------------------------

    var housekeepingBusy by mutableStateOf(false); private set
    var housekeepingError by mutableStateOf<String?>(null); private set
    fun clearHousekeepingError() { housekeepingError = null }

    /** Archives a task, as the web's card menu does. Leaves the task if it is open. */
    fun archiveTask(target: TaskSummary, onDone: () -> Unit = {}) {
        val connection = api ?: return
        if (housekeepingBusy) return
        housekeepingBusy = true; housekeepingError = null
        viewModelScope.launch {
            try {
                connection.archiveTask(target.id)
                if (task?.id == target.id) back()
                store?.removeTask(target.id)
                onDone()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                housekeepingError = (e as? KandevApiException)?.message?.let { "Not archived: $it" }
                    ?: "The task may not have been archived. Refresh before retrying."
            } finally { housekeepingBusy = false }
        }
    }

    /** Deletes a session that isn't running (Kandev refuses running ones); opens another if it was selected. */
    fun deleteSession(target: AgentSession) {
        val selectedTask = task ?: return
        if (!live || housekeepingBusy || target.state in setOf("RUNNING", "STARTING")) return
        housekeepingBusy = true; housekeepingError = null
        viewModelScope.launch {
            try {
                link.request("session.delete", buildJsonObject { put("session_id", target.id) })
                val fresh = requireNotNull(api).listSessions(selectedTask.id)
                store?.saveSessions(selectedTask.id, fresh)
                if (task?.id != selectedTask.id) return@launch
                sessions = fresh
                if (session?.id == target.id) (fresh.firstOrNull { it.primary } ?: fresh.firstOrNull())?.let(::openSession)
                    ?: run { stopConversation() }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                housekeepingError = "The session may not have been deleted. Reload sessions before retrying."
            } finally { housekeepingBusy = false }
        }
    }

    /** Opens a task by id (notification tap), switching workspace if needed. Cache-first. */
    fun openTaskById(id: String, review: Boolean = false, sessionId: String? = null) {
        val data = store ?: return
        viewModelScope.launch {
            val row = data.db.dao().task(id) ?: runCatching { requireNotNull(api).getTask(id) }.getOrNull()?.let { fresh ->
                data.insertTask(fresh, fresh.workspaceId.ifBlank { workspace?.id.orEmpty() }); data.db.dao().task(id)
            } ?: return@launch
            val target = row.toTask() ?: return@launch
            if (workspace?.id != row.workspaceId) snapshot?.workspaces?.firstOrNull { it.id == row.workspaceId }?.let(::applyWorkspace)
            if (task?.id != target.id) openTask(target)
            // Land on the conversation; for approvals and questions open the request itself once it loads.
            tools.selectTab(WorkspaceTab.Chat)
            sessionId?.let { wanted -> if (session?.id != wanted) sessions.firstOrNull { it.id == wanted }?.let(::openSession) }
            if (review) {
                pendingReview = true
                withTimeoutOrNull(15_000) { while (inputs.pending == null) delay(250) }
                inputs.pending?.let { inputs.open(it) }
                pendingReview = false
            }
        }
    }

    private var pendingReview = false

    /**
     * Thumbnails of images the agent read, by message: a screenshot path the agent overwrites later
     * doesn't change what an earlier step showed once loaded. Bounded to a few MB.
     */
    private val chatImages = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    internal fun cachedChatImage(messageId: String) = chatImages.get(messageId)

    internal suspend fun chatImage(message: ChatMessage, path: String): androidx.compose.ui.graphics.ImageBitmap? {
        chatImages.get(message.id)?.let { return it }
        val selected = session ?: return null
        val file = try { tools.previewFile(workspaceRelative(path, selected.workspacePath)) }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); null } ?: return null
        val bitmap = withContext(Dispatchers.Default) { decodeImage(fileBytes(file), 640) } ?: return null
        chatImages.put(message.id, bitmap)
        return bitmap
    }

    /** From a file path in the conversation: open it in Files (images render, changed files offer their diff). */
    fun openFileFromChat(path: String) {
        val selected = session ?: return
        val relative = workspaceRelative(path, selected.workspacePath)
        // Agents often name just the file (`Foo.kt`); find the full path among files this session touched.
        val resolved = if ('/' in relative || !isOpenableFilePath(relative)) relative else (messages.asReversed().mapNotNull { it.toolFilePath }
            .map { workspaceRelative(it, selected.workspacePath) } + tools.changes.cumulative.keys +
            tools.changes.statuses.values.flatMap { it.files.keys }).firstOrNull { it.endsWith("/$relative") } ?: relative
        tools.openFromChat(resolved)
        tools.refreshDiffQuietly()
    }

    /**
     * While the agent works, a follow-up goes into Kandev's session queue and runs when the turn ends.
     * One explicit request with a stable client id; never replayed on reconnect.
     */
    fun queueMessage() {
        val selected = session ?: return
        val content = draft.trim()
        if (!live || sending || content.isEmpty() || selected.queueIncarnationId.isBlank() || queue.full) return
        sending = true; sendError = null
        sendJob = viewModelScope.launch {
            try { if (queue.add(content) && draft.trim() == content) editDraft("") }
            finally { sending = false }
        }
    }

    /** Sends, steers or queues the draft, whichever the session takes right now. */
    fun submitDraft() = when (inputMode) {
        InputMode.Queue -> queueMessage()
        InputMode.Direct, InputMode.Steer -> sendMessage()
        InputMode.Unavailable -> Unit
    }

    fun sendMessage() {
        val selected = session ?: return
        val selectedTask = task ?: return
        val content = draft.trim()
        if (!live || inputs.pending != null || sending || attachments.importing || sessionActions.busy || (content.isEmpty() && attachments.files.isEmpty()) ||
            inputMode !in setOf(InputMode.Direct, InputMode.Steer)) return
        sending = true
        sendError = null
        // Your message stays on screen from the tap: a "Sending…" bubble until Kandev's copy arrives in the stream.
        val pendingBubble = OutgoingMessage(UUID.randomUUID().toString(), selected.id, content, attachments.files.size,
            java.time.Instant.now().toString(), messages.mapTo(HashSet()) { it.id }, after = messages.lastOrNull()?.createdAt)
        outgoing = outgoing + pendingBubble
        sendJob = viewModelScope.launch {
            var acknowledged = false
            try {
                require(content.toByteArray().size <= 512 * 1024) { "Message too large" }
                val sentAttachments = attachments.prepareForSend()
                val fingerprint = messageFingerprint(content, sentAttachments)
                val identity = sendIdentities[selected.id]?.takeIf { it.first == fingerprint }
                    ?: (fingerprint to UUID.randomUUID().toString()).also { sendIdentities[selected.id] = it }
                draftWrite?.cancel()
                cache?.write("draft:${selected.id}", DraftRecord.serializer(), DraftRecord(draft, identity.second, fingerprint))
                // The text is safe on disk with its identity; the composer can clear while the bubble shows it.
                if (session?.id == selected.id && draft.trim() == content) { draft = ""; drafts[selected.id] = "" }
                link.request("message.add", buildJsonObject {
                    put("task_id", selectedTask.id); put("session_id", selected.id)
                    put("content", content); put("message_id", identity.second); put("client_message_id", identity.second)
                    if (sentAttachments.isNotEmpty()) put("attachments", json.encodeToJsonElement(sentAttachments))
                })
                acknowledged = true
                outgoing = outgoing.map { if (it.id == pendingBubble.id) it.copy(acknowledged = true) else it }
                viewModelScope.launch { reconcileSent(selected.id, pendingBubble.id) }
                // Only a correlated acknowledgement clears the saved draft. Never replay on reconnect.
                if (draft.trim() == content) editDraft("")
                sendIdentities.remove(selected.id)
                attachments.acknowledged(sentAttachments)
                cache?.write("draft:${selected.id}", DraftRecord.serializer(), DraftRecord(draft))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!acknowledged) {
                    // Not confirmed: the bubble goes and the text returns to the composer for an explicit retry.
                    outgoing = outgoing.filterNot { it.id == pendingBubble.id }
                    if (session?.id == selected.id && draft.isBlank()) { draft = content; drafts[selected.id] = content }
                }
                sendError = if (acknowledged) "Message sent, but the local draft could not be fully cleared."
                    else "Delivery was not confirmed. Check the conversation before retrying. Your draft and files are kept."
            } finally {
                sending = false
                // A cancelled send (you left the session) keeps its text in the draft, never a stale bubble.
                if (!acknowledged) outgoing = outgoing.filterNot { it.id == pendingBubble.id }
            }
        }
    }

    /**
     * Kandev stores your message before acknowledging it. If its copy hasn't streamed in shortly after,
     * read the newest page (read-only) and take just the missing prompts of yours, so the bubble becomes
     * the real message instead of waiting for a reopen.
     */
    private suspend fun reconcileSent(sessionId: String, bubbleId: String) {
        val current = conversationGeneration
        for (wait in listOf(4_000L, 12_000L)) {
            delay(wait)
            if (current != conversationGeneration || session?.id != sessionId || pendingOutgoing.none { it.id == bubbleId }) return
            if (!live) continue
            val page = try { requireNotNull(api).listMessages(sessionId) } catch (e: Exception) { currentCoroutineContext().ensureActive(); continue }
            if (current != conversationGeneration || session?.id != sessionId) return
            val missing = page.messages.filter { sent -> sent.author == "user" && messages.none { it.id == sent.id } }
            if (missing.isNotEmpty()) { messages = orderedMessages(messages + missing); cacheConversation() }
        }
    }

    /** Leaves the open task. The task list is the home screen, so there is nothing further back. */
    fun back() {
        if (task == null) return
        request?.cancel(); generation++; busy = false
        stopConversation(); task = null; sessions = emptyList(); error = null
    }

    private fun pauseConversation() {
        streamJob?.cancel(); streamJob = null
        olderJob?.cancel(); loadingOlder = false
        sendJob?.cancel(); sending = false
        live = false
        syncingHistory = false
        liveStatus = "Paused"
        tools.onDisconnected()
    }

    private fun stopConversation() {
        inputs.reset(); sessionActions.reset(); queue.selectSession(null); inputTurns = null
        pickerJob?.cancel(); attachments.selectSession("")
        val id = session?.id
        if (id != null) {
            persistConversation(id, conversationSnapshot())
            val identity = sendIdentities[id]
            // Mid-send the composer is empty but the text is still in flight; keep it as the draft.
            val unsent = outgoing.lastOrNull { it.sessionId == id && !it.acknowledged }?.content
            val text = draft.ifBlank { unsent.orEmpty() }
            drafts[id] = text
            persist("draft:$id", DraftRecord.serializer(), DraftRecord(text, identity?.second, identity?.first))
        }
        outgoing = emptyList()
        conversationGeneration++; conversationRestore?.cancel(); conversationRestore = null
        historyWrite?.cancel(); draftWrite?.cancel()
        pauseConversation(); session = null
        link.setFocus(null)
        tools.selectSession("", null)
    }

    fun disconnect() {
        request?.cancel(); generation++; busy = false
        stopConversation()
        api = null; snapshot = null; workspace = null; task = null
        tasks = emptyList(); sessions = emptyList(); messages = emptyList(); repositories = emptyMap()
        token = ""; accessCredential = null; savedConfig = null; routeJob?.cancel(); routeLabel = "Offline"; draft = ""; error = null; sendError = null
        toolOutputs = emptyMap(); outputErrors = emptyMap(); loadingOutputs = emptySet()
        drafts.clear(); sendIdentities.clear(); chatImages.evictAll(); cacheNotice = null; logos.clear(); launchOptions = null; usage.clear()
        workspaceJob?.cancel(); workspaceJob = null; store = null; steps = emptyList(); workflows = emptyList()
        cache = null
        val previousAttachments = attachmentStore; attachmentStore = null
        val previousPreviews = previewDirectory
        val pendingWrites = writes.toList(); pendingWrites.forEach { it.cancel() }
        cleanup = viewModelScope.launch { pendingWrites.joinAll(); hub.forget(); previousAttachments?.clear(); withContext(Dispatchers.IO) { previousPreviews.deleteRecursively() } }
    }

    override fun onCleared() { link.setFocus(null); tools.terminal.stop(); super.onCleared() }
}

private fun connectionError(error: Exception): String = when (error) {
    is KandevApiException -> error.message ?: "Could not connect. Try again."
    is SerializationException -> "Unexpected server response. Check that this is a Kandev v0.98.0 or newer server."
    is IllegalArgumentException -> "Check the server address, route settings, and session details."
    is IOException -> "Could not reach Kandev. Check the address, network, and HTTPS certificate."
    else -> "Could not load this view. Try again."
}

/**
 * A message you sent, shown at once while Kandev confirms and streams back its own copy. [after] is the
 * server time of the newest message on screen at the tap: the bubble stays there as the agent replies below it.
 */
internal data class OutgoingMessage(val id: String, val sessionId: String, val content: String, val files: Int,
    val createdAt: String, val known: Set<String>, val acknowledged: Boolean = false, val after: String? = null)
