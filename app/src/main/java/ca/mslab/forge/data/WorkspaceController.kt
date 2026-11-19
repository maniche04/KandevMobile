package ca.mslab.forge.data

import androidx.compose.runtime.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/** Panel requests are lazy, scoped to the selected session, and cache-first. */
internal class WorkspaceController(
    private val scope: CoroutineScope,
    private val request: suspend (String, JsonObject) -> JsonObject,
    private val store: () -> SnapshotStore?,
    private val api: () -> KandevApi?,
) {
    var tab by mutableStateOf(WorkspaceTab.Chat); private set
    var plan by mutableStateOf<PlanSnapshot?>(null); private set
    var tree by mutableStateOf<FileTree?>(null); private set
    var directory by mutableStateOf(""); private set
    var file by mutableStateOf<WorkspaceFile?>(null); private set
    var changes by mutableStateOf(ChangesSnapshot()); private set
    var selectedDiff by mutableStateOf<ChangedFile?>(null); private set
    var terminals by mutableStateOf<List<TerminalInfo>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var savedAt by mutableStateOf<Long?>(null); private set
    var connected by mutableStateOf(false); private set
    var fileDraft by mutableStateOf<String?>(null); private set
    // Retain the selected bytes while Android's save picker recreates the activity.
    var pendingDownload: WorkspaceFile? = null
    var fileSaving by mutableStateOf(false); private set
    /** The open file was tapped in the conversation: closing it goes back to the chat, not the file tree. */
    var returnsToChat by mutableStateOf(false); private set
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private var taskId = ""
    private var session: AgentSession? = null
    private var job: Job? = null
    private var invalidation: Job? = null
    private var editorWrite: Job? = null
    private var changesWrite: Job? = null
    private var generation = 0
    private val loaded = mutableSetOf<String>()
    private val trees = mutableMapOf<String, FileTree>()
    val terminal = TerminalController(scope, api, store)

    fun updateMetadata(value: AgentSession) { if (session?.id == value.id) session = value }

    fun selectSession(task: String, selected: AgentSession?) {
        saveEditorDraft(); editorWrite?.cancel(); changesWrite?.cancel()
        job?.cancel(); invalidation?.cancel(); generation++
        taskId = task; session = selected; tab = WorkspaceTab.Chat; returnsToChat = false
        connected = false; busy = false; error = null; savedAt = null
        pendingDownload = null
        plan = null; tree = null; file = null; directory = ""; fileDraft = null
        changes = ChangesSnapshot(); selectedDiff = null; terminals = emptyList(); loaded.clear(); trees.clear()
        planDraft = null; planBase = null; gitNotice = null
        terminal.stop()
        if (selected != null) loadPanel(false)
    }

    fun selectTab(selected: WorkspaceTab) {
        if (tab != selected) { job?.cancel(); generation++; busy = false; error = null }
        returnsToChat = false
        tab = selected
        if (selected != WorkspaceTab.Terminal) terminal.pause()
        loadPanel(false)
        if (selected == WorkspaceTab.Terminal && connected) terminal.resume()
    }

    fun onConnected() { connected = true; loaded.clear(); loadPanel(true); if (tab == WorkspaceTab.Terminal) terminal.resume() }
    fun onDisconnected() { connected = false; terminal.pause() }
    fun refresh() { val opened = file; if (tab == WorkspaceTab.Files && opened != null) openFile(opened.path) else loadPanel(true) }

    private fun <T> read(key: String, serializer: KSerializer<T>, fetch: suspend () -> T, assign: (T) -> Unit) {
        job?.cancel()
        val current = ++generation
        busy = true; error = null
        job = scope.launch {
            try {
                store()?.read(key, serializer)?.let {
                    currentCoroutineContext().ensureActive()
                    assign(it.value); savedAt = it.savedAt
                }
                if (connected) {
                    val data = fetch()
                    currentCoroutineContext().ensureActive()
                    assign(data); savedAt = null; loaded.add(key)
                    runCatching { store()?.write(key, serializer, data) }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = "Could not refresh this panel. Saved content is still available; retry when connected."
            } finally { if (generation == current) busy = false }
        }
    }

    private fun loadPanel(force: Boolean) {
        val selected = session ?: return
        val key = when (tab) {
            WorkspaceTab.Plan -> "plan:$taskId"
            WorkspaceTab.Changes -> "changes:${selected.id}"
            WorkspaceTab.Files -> "tree:${selected.id}:$directory"
            WorkspaceTab.Terminal -> "terminals:$taskId"
            else -> "chat"
        }
        if (!force && loaded.contains(key)) return
        when (tab) {
            WorkspaceTab.Chat -> { job?.cancel(); generation++; busy = false; error = null }
            WorkspaceTab.Plan -> read("plan:$taskId", PlanSnapshot.serializer(), {
                val response = request("task.plan.get", buildJsonObject { put("task_id", taskId) })
                PlanSnapshot(if (response.isEmpty()) null else json.decodeFromJsonElement(TaskPlan.serializer(), response))
            }) { plan = it }
            WorkspaceTab.Changes -> read("changes:${selected.id}", ChangesSnapshot.serializer(), {
                request("session.focus", buildJsonObject { put("session_id", selected.id) })
                // A refresh can be unavailable while cached branch data is still readable.
                try { request("session.git.refresh", buildJsonObject { put("session_id", selected.id); put("mode", "fresh") }) }
                catch (_: KandevApiException) { currentCoroutineContext().ensureActive() }
                val response = request("session.cumulative_diff", buildJsonObject { put("session_id", selected.id) })
                val files = response["cumulative_diff"]?.takeUnless { it == JsonNull }?.jsonObject?.get("files")
                if (response["ready"]?.jsonPrimitive?.booleanOrNull == false) changes else changes.copy(cumulative = files?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), ChangedFile.serializer()), it) }.orEmpty())
            }) { changes = it }
            WorkspaceTab.Files -> {
                if (file != null) return
                val path = directory
                read("tree:${selected.id}:$path", FileTree.serializer(), {
                    json.decodeFromJsonElement(FileTree.serializer(), request("workspace.tree.get", buildJsonObject {
                        put("session_id", selected.id); put("path", path); put("depth", 1)
                    })).also { check(it.error.isNullOrEmpty()) }
                }) { tree = it; trees[path] = it }
            }
            WorkspaceTab.Terminal -> read("terminals:$taskId", TerminalList.serializer(), {
                val environment = selected.environmentId ?: throw IOException("No task environment")
                requireNotNull(api()).listTerminals(taskId, environment)
            }) { terminals = it.terminals }
        }
    }

    fun enterDirectory(path: String) {
        saveEditorDraft()
        job?.cancel(); generation++; busy = false; error = null; savedAt = null
        directory = path.trimEnd('/'); tree = trees[directory]; file = null; fileDraft = null
        if (tree == null) loaded.remove("tree:${session?.id}:$directory")
        loadPanel(false)
    }
    fun closeFile() {
        saveEditorDraft(); job?.cancel(); generation++; busy = false; error = null
        file = null; fileDraft = null
        if (returnsToChat) selectTab(WorkspaceTab.Chat) else loadPanel(false)
    }

    /** Resolve a linked workspace path using tree metadata, including extensionless files and dotted folders. */
    fun openFromChat(path: String) {
        val selected = session ?: return
        saveEditorDraft(); terminal.pause()
        job?.cancel(); val current = ++generation
        tab = WorkspaceTab.Files; returnsToChat = true
        file = null; fileDraft = null; tree = null; busy = true; error = null
        val target = path.trimEnd('/')
        if (target.isEmpty()) { returnsToChat = false; enterDirectory(""); return }
        // Outside the workspace (/tmp/shot.png, ~/notes.md): Kandev reads these files read-only but
        // won't list their folders, so open the file directly.
        if (target.startsWith('/') || target.startsWith("~/")) { busy = false; openFile(target); return }
        job = scope.launch {
            try {
                val parent = target.substringBeforeLast('/', "")
                val key = "tree:${selected.id}:$parent"
                var listing = trees[parent] ?: store()?.read(key, FileTree.serializer())?.value
                if (connected) {
                    listing = json.decodeFromJsonElement(FileTree.serializer(), request("workspace.tree.get", buildJsonObject {
                        put("session_id", selected.id); put("path", parent); put("depth", 1)
                    })).also { check(it.error.isNullOrEmpty()) }
                }
                currentCoroutineContext().ensureActive()
                listing?.let { trees[parent] = it }
                val node = listing?.root?.children?.firstOrNull { it.name == target.substringAfterLast('/') }
                if (node?.directory == true || path.endsWith('/')) {
                    returnsToChat = false
                    enterDirectory(target)
                } else {
                    directory = parent; tree = listing
                    openFile(target)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = "Could not open this workspace path. Reconnect and try again."
            } finally { if (generation == current) busy = false }
        }
    }

    private val previews = kotlinx.coroutines.sync.Semaphore(2)

    /** Read-only fetch for a chat thumbnail; leaves the open file and panel state alone. */
    suspend fun previewFile(path: String): WorkspaceFile? {
        val selected = session ?: return null
        if (!connected) return null
        return previews.withPermit {
            json.decodeFromJsonElement(WorkspaceFile.serializer(), request("workspace.file.get", buildJsonObject {
                put("session_id", selected.id); put("path", path)
            })).takeIf { it.error.isNullOrEmpty() && it.binary }
        }
    }

    /** System back inside a task: close the open file, then leave the panel for the chat. */
    fun back() {
        if (fileSaving) return
        if (tab == WorkspaceTab.Files && file != null) closeFile()
        else if (tab == WorkspaceTab.Files && directory.isNotBlank()) enterDirectory(directory.substringBeforeLast('/', ""))
        else selectTab(WorkspaceTab.Chat)
    }
    fun openFile(path: String) {
        val selected = session ?: return
        saveEditorDraft()
        selectedDiff = null; fileDraft = null; file = null
        job?.cancel(); val current = ++generation
        busy = true; error = null
        job = scope.launch {
            try {
                store()?.read("edit:${selected.id}:$path", FileEditSnapshot.serializer())?.let {
                    file = it.value.original; fileDraft = it.value.content
                }
                store()?.read("file:${selected.id}:$path", WorkspaceFile.serializer())?.let {
                    if (fileDraft == null) file = it.value
                    savedAt = it.savedAt
                }
                if (connected) {
                    val result = json.decodeFromJsonElement(WorkspaceFile.serializer(), request("workspace.file.get", buildJsonObject {
                        put("session_id", selected.id); put("path", path)
                    })).also { check(it.error.isNullOrEmpty()) }
                    currentCoroutineContext().ensureActive()
                    if (fileDraft == null) file = result
                    savedAt = null
                    store()?.write("file:${selected.id}:$path", WorkspaceFile.serializer(), result)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = when {
                    file != null -> "Could not load this file. Saved content is still available."
                    path.startsWith('/') || path.startsWith("~/") -> "Could not read $path on the server. It may have been deleted, or it is over 10 MB."
                    else -> "Could not load this file. Reconnect and try again."
                }
            } finally { if (generation == current) busy = false }
        }
    }
    /** Read independently of panel navigation so downloading does not open or close a file. */
    suspend fun fileForDownload(path: String): WorkspaceFile {
        val selected = session ?: throw IOException("No session selected")
        file?.takeIf { it.path == path }?.let { return it }
        if (!connected) return store()?.read("file:${selected.id}:$path", WorkspaceFile.serializer())?.value
            ?: throw IOException("Connect to download this file")
        return json.decodeFromJsonElement(WorkspaceFile.serializer(), request("workspace.file.get", buildJsonObject {
            put("session_id", selected.id); put("path", path)
        })).also { check(it.error.isNullOrEmpty()) }
    }

    fun selectDiff(value: ChangedFile?) { selectedDiff = value }

    /** The change for a file, from the working tree or the branch diff (paths may be repo-prefixed). */
    fun changeFor(path: String): ChangedFile? {
        val all = changes.statuses.values.flatMap { it.files.values } + changes.cumulative.values
        return all.firstOrNull { it.path == path } ?: all.firstOrNull { path.endsWith("/${it.path}") || it.path.endsWith("/$path") }
    }

    /** Loads the branch diff in the background (no spinner) so a file opened from chat can show its diff. */
    fun refreshDiffQuietly() {
        val selected = session ?: return
        if (!connected || changes.cumulative.isNotEmpty()) return
        scope.launch {
            runCatching {
                val response = request("session.cumulative_diff", buildJsonObject { put("session_id", selected.id) })
                val files = response["cumulative_diff"]?.takeUnless { it == JsonNull }?.jsonObject?.get("files") ?: return@launch
                if (session?.id == selected.id) changes = changes.copy(cumulative =
                    json.decodeFromJsonElement(MapSerializer(String.serializer(), ChangedFile.serializer()), files))
            }
        }
    }

    fun editFile() {
        val current = file ?: return
        if (!current.binary && current.content.length < 256_000) fileDraft = decodeFile(current)
    }
    fun updateFileDraft(value: String) {
        fileDraft = value; editorWrite?.cancel()
        editorWrite = scope.launch { delay(250); saveEditorDraft() }
    }
    private fun saveEditorDraft() {
        val selected = session ?: return
        val original = file ?: return
        val content = fileDraft ?: return
        val storage = store() ?: return
        scope.launch { runCatching { storage.write("edit:${selected.id}:${original.path}", FileEditSnapshot.serializer(), FileEditSnapshot(original, content)) } }
    }
    fun cancelFileEdit() {
        editorWrite?.cancel()
        val key = "edit:${session?.id}:${file?.path}"
        fileDraft = null
        scope.launch { store()?.remove(key) }
    }
    fun saveFile() {
        val selected = session ?: return
        val current = file ?: return
        val desired = fileDraft ?: return
        if (!connected || fileSaving) return
        fileSaving = true; error = null
        job = scope.launch {
            try {
                val original = decodeFile(current)
                val result = request("workspace.file.update", buildJsonObject {
                    put("session_id", selected.id); put("path", current.path)
                    put("original_hash", digest(original))
                    put("diff", replacementPatch(current.path, original, desired))
                })
                check(result["success"]?.jsonPrimitive?.booleanOrNull == true)
                val updated = current.copy(content = desired, size = desired.toByteArray().size.toLong())
                editorWrite?.cancel()
                file = updated; fileDraft = null
                runCatching {
                    store()?.remove("edit:${selected.id}:${current.path}")
                    store()?.write("file:${selected.id}:${current.path}", WorkspaceFile.serializer(), updated)
                }.onFailure { error = "Saved on the server, but the local cache could not be updated." }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = "File was not saved. It may have changed on the server. Keep your edit, or cancel and reload before retrying."
            } finally { fileSaving = false }
        }
    }

    // Plan editing -----------------------------------------------------------------------------------

    var planDraft by mutableStateOf<String?>(null); private set
    var planSaving by mutableStateOf(false); private set

    /** The plan as it was when editing began; a save is refused if the server's copy moved on since. */
    private var planBase: TaskPlan? = null

    fun editPlan() { if (connected && plan != null) { planBase = plan?.plan; planDraft = planBase?.content.orEmpty() } }
    fun updatePlanDraft(value: String) { planDraft = value }
    fun cancelPlanEdit() { planDraft = null; planBase = null }

    /**
     * Saves the plan. Kandev's update is last-write-wins, so the plan is re-read first and the save refused if
     * the agent (or someone else) changed it since you started editing; your draft is kept either way.
     */
    fun savePlan() {
        val content = planDraft ?: return
        if (!connected || planSaving) return
        val opened = planBase
        val task = taskId
        planSaving = true; error = null
        scope.launch {
            try {
                val latest = request("task.plan.get", buildJsonObject { put("task_id", task) })
                    .takeIf { it.isNotEmpty() }?.let { json.decodeFromJsonElement(TaskPlan.serializer(), it) }
                if (latest?.updatedAt != opened?.updatedAt || (latest == null) != (opened == null)) {
                    plan = PlanSnapshot(latest); planBase = latest
                    error = "The plan changed while you were editing. Your draft is kept; review the new plan before saving again."
                    return@launch
                }
                val saved = request(if (latest == null) "task.plan.create" else "task.plan.update", buildJsonObject {
                    put("task_id", task); put("title", latest?.title?.ifBlank { null } ?: "Plan"); put("content", content)
                    put("author_kind", "user")
                })
                if (task != taskId) return@launch
                val updated = PlanSnapshot(json.decodeFromJsonElement(TaskPlan.serializer(), saved))
                plan = updated; planDraft = null; planBase = null
                runCatching { store()?.write("plan:$task", PlanSnapshot.serializer(), updated) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = "The plan was not saved. Your draft is kept; retry when connected."
            } finally { planSaving = false }
        }
    }

    // Git ----------------------------------------------------------------------------------------------

    var gitBusy by mutableStateOf<String?>(null); private set
    val baseBranch get() = session?.baseBranch.orEmpty()
    var gitNotice by mutableStateOf<String?>(null); private set
    fun clearGitNotice() { gitNotice = null }

    /** The repo subpath Kandev needs on multi-repo tasks; single-repo workspaces leave it empty. */
    private fun repoParam(repository: String): String? = repository.takeIf { changes.statuses.size > 1 && it.isNotBlank() }

    fun commit(message: String, repository: String) = git("commit", "worktree.commit", buildJsonObject {
        put("message", message.trim()); put("stage_all", true); put("amend", false); repoParam(repository)?.let { put("repo", it) }
    }) { "Committed." }

    fun push(repository: String) = git("push", "worktree.push", buildJsonObject {
        put("force", false); put("set_upstream", true); repoParam(repository)?.let { put("repo", it) }
    }) { "Pushed." }

    fun createPullRequest(title: String, body: String, baseBranch: String, draft: Boolean, repository: String) =
        git("pr", "worktree.create_pr", buildJsonObject {
            put("title", title.trim()); put("body", body); put("base_branch", baseBranch); put("draft", draft)
            repoParam(repository)?.let { put("repo", it) }
        }) { result -> result["pr_url"]?.jsonPrimitive?.contentOrNull?.let { "Pull request opened: $it" } ?: "Pull request opened." }

    /** One explicit git operation in the session's workspace; never retried. Changes refresh afterwards. */
    private fun git(name: String, action: String, extra: JsonObject, done: (JsonObject) -> String) {
        val selected = session ?: return
        if (!connected || gitBusy != null) return
        gitBusy = name; gitNotice = null; error = null
        scope.launch {
            try {
                val result = request(action, JsonObject(buildJsonObject { put("session_id", selected.id) } + extra))
                if (session?.id == selected.id) gitNotice = done(result)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (session?.id == selected.id) error = when (name) {
                    "commit" -> "Commit failed. There may be nothing to commit, or a hook rejected it."
                    "push" -> "Push failed. The branch may need a pull or rebase first; check in Kandev."
                    else -> "The pull request was not created. Push the branch first, or check the provider connection in Kandev."
                }
            } finally {
                gitBusy = null
                if (session?.id == selected.id && tab == WorkspaceTab.Changes) loadPanel(true)
            }
        }
    }

    fun onEvent(event: WireMessage) {
        if (event.action.startsWith("session.") && event.payload["session_id"]?.jsonPrimitive?.content != session?.id) return
        if (event.action == "session.git.event" && event.payload["type"]?.jsonPrimitive?.content == "status_update") {
            event.payload["status"]?.let { status ->
                val update = status.jsonObject
                val repository = update["repository_name"]?.jsonPrimitive?.content.orEmpty()
                val previous = changes.statuses[repository] ?: GitStatus()
                val merged = JsonObject(json.encodeToJsonElement(GitStatus.serializer(), previous).jsonObject + update)
                runCatching { json.decodeFromJsonElement(GitStatus.serializer(), merged) }.getOrNull()?.let {
                    val membership = buildMap {
                        it.modified.forEach { path -> put(path, ChangedFile(path, "modified")) }
                        it.added.forEach { path -> put(path, ChangedFile(path, "added")) }
                        it.deleted.forEach { path -> put(path, ChangedFile(path, "deleted")) }
                        it.untracked.forEach { path -> put(path, ChangedFile(path, "untracked")) }
                        it.renamed.forEach { path -> put(path, ChangedFile(path, "renamed")) }
                    }
                    val status = it.copy(files = if (update.containsKey("files")) it.files else if (listOf("modified", "added", "deleted", "untracked", "renamed").any(update::containsKey)) membership else previous.files)
                    changes = changes.copy(statuses = changes.statuses + (it.repository to status))
                    if (changesWrite?.isActive != true) {
                        val selectedId = session?.id
                        changesWrite = scope.launch {
                            delay(750)
                            if (session?.id == selectedId) runCatching {
                                store()?.write("changes:$selectedId", ChangesSnapshot.serializer(), changes)
                            }
                        }
                    }
                }
            }
        }
        if ((event.action.startsWith("task.plan.") && event.payload["task_id"]?.jsonPrimitive?.content == taskId && tab == WorkspaceTab.Plan) ||
            (event.action == "session.workspace.file.changes" && tab in setOf(WorkspaceTab.Files, WorkspaceTab.Changes))) {
            if (invalidation?.isActive == true) return
            invalidation = scope.launch { delay(750); if (fileDraft == null && file == null) loadPanel(true) }
        }
    }

    fun connectTerminal(info: TerminalInfo) {
        val selected = session ?: return
        val environment = selected.environmentId ?: return
        terminal.connect(environment, info.id)
    }
    fun createTerminal() {
        val selected = session ?: return
        if (!connected || busy) return
        busy = true; error = null
        job = scope.launch {
            try {
                val response = request("user_shell.create", buildJsonObject {
                    put("task_id", taskId); put("task_environment_id", selected.environmentId ?: throw IOException())
                })
                val id = response.getValue("terminal_id").jsonPrimitive.content
                val info = TerminalInfo(id, name = response["display_name"]?.jsonPrimitive?.content ?: "Terminal")
                terminals = terminals + info
                connectTerminal(info)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive(); error = "Could not create a terminal. Reconnect and try again."
            } finally { busy = false }
        }
    }
}

internal fun decodeFile(file: WorkspaceFile): String = file.content

/** Whole-file unified patch; the server enforces the original SHA-256 before applying it. */
internal fun replacementPatch(path: String, original: String, desired: String): String {
    require(!path.contains('\n') && !path.contains('\r'))
    fun lines(value: String) = if (value.isEmpty()) emptyList() else value.removeSuffix("\n").split('\n')
    val old = lines(original); val new = lines(desired)
    fun range(size: Int) = if (size == 0) "0,0" else "1,$size"
    return buildString {
        append("--- $path\n+++ $path\n@@ -${range(old.size)} +${range(new.size)} @@\n")
        old.forEachIndexed { i, line -> append('-').append(line).append('\n'); if (i == old.lastIndex && !original.endsWith('\n')) append("\\ No newline at end of file\n") }
        new.forEachIndexed { i, line -> append('+').append(line).append('\n'); if (i == new.lastIndex && !desired.endsWith('\n')) append("\\ No newline at end of file\n") }
    }
}
