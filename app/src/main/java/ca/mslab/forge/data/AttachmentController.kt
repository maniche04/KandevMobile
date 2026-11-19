package ca.mslab.forge.data

import androidx.compose.runtime.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import java.util.Base64

internal data class AttachmentSource(val name: String, val mime: String, val open: () -> InputStream)

/** Per-session selected files. Uploads happen only inside an explicit Send. */
internal class AttachmentController(
    private val scope: CoroutineScope,
    private val snapshots: () -> SnapshotStore?,
    private val storage: () -> AttachmentStore?,
    private val api: () -> KandevApi?,
    private val workspaceId: () -> String?,
    private val previewRoot: () -> File,
    private val online: () -> Boolean,
) {
    var files by mutableStateOf<List<LocalAttachment>>(emptyList()); private set
    var importing by mutableStateOf(false); private set
    var uploading by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<MessageAttachment?>(null); private set
    var previewFile by mutableStateOf<File?>(null); private set
    var previewBusy by mutableStateOf(false); private set
    var previewError by mutableStateOf<String?>(null); private set
    private var selectedSession = ""
    /** The draft the selected files belong to: a session id, or a new-task draft key. */
    val owner: String get() = selectedSession
    private var generation = 0
    private var importJob: Job? = null
    private var previewJob: Job? = null
    private val manifestLock = kotlinx.coroutines.sync.Mutex()

    suspend fun restore(sessionId: String) {
        val current = generation
        snapshots()?.read("attachments:$sessionId", AttachmentDraft.serializer())?.let {
            if (current == generation && selectedSession == sessionId) files = it.value.files
        }
    }
    fun selectSession(id: String) {
        generation++; importJob?.cancel(); previewJob?.cancel()
        selectedSession = id; files = emptyList(); importing = false; uploading = null; error = null
        preview = null; previewFile = null; previewBusy = false; previewError = null
    }
    private suspend fun persist(session: String = selectedSession, values: List<LocalAttachment> = files, target: SnapshotStore? = snapshots()) {
        manifestLock.lock()
        try { target?.write("attachments:$session", AttachmentDraft.serializer(), AttachmentDraft(values)) }
        finally { manifestLock.unlock() }
    }
    private fun persistAsync() {
        val session = selectedSession; val values = files; val target = snapshots()
        scope.launch { persist(session, values, target) }
    }
    fun add(sources: List<AttachmentSource>) {
        if (selectedSession.isBlank() || importing || uploading != null || sources.isEmpty()) return
        val target = storage() ?: return
        val current = generation
        importing = true; error = null
        importJob = scope.launch {
            try {
                for (source in sources) {
                    require(files.size < MAX_ATTACHMENTS) { "A message can have at most 10 attachments." }
                    val staged = target.stage(source.name, normalizeMime(source.mime), source.open)
                    currentCoroutineContext().ensureActive()
                    if (current != generation) { target.remove(staged.id); return@launch }
                    if ((files.sumOf { it.size } + staged.size) > MAX_ATTACHMENT_BYTES) {
                        target.remove(staged.id); throw IllegalArgumentException("Attachments exceed 100 MiB per message.")
                    }
                    files = files + staged; persist()
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                error = if (e is IllegalArgumentException) e.message else "Could not copy the selected file. Try selecting it again."
            } finally { if (current == generation) importing = false }
        }
    }
    fun remove(id: String) {
        if (importing || uploading != null) return
        val removed = files.firstOrNull { it.id == id } ?: return
        val target = storage(); files = files.filterNot { it.id == id }
        // Staged remote content expires server-side. Never delete a possibly claimed file after an uncertain Send.
        persistAsync(); scope.launch { target?.remove(removed.id) }
    }
    suspend fun prepareForSend(): List<MessageAttachment> {
        check(!importing && uploading == null)
        val target = requireNotNull(storage()); val connection = requireNotNull(api()); val workspace = requireNotNull(workspaceId())
        val current = generation
        try {
            for (local in files.toList()) {
                currentCoroutineContext().ensureActive(); check(current == generation)
                if (local.uploaded != null) continue
                uploading = local.id
                val uploaded = connection.uploadAttachment(workspace, local, target.file(local.id))
                currentCoroutineContext().ensureActive(); check(current == generation)
                files = files.map { if (it.id == local.id) it.copy(uploaded = uploaded) else it }
                persist()
            }
            return files.map { requireNotNull(it.uploaded).message() }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); error = "Upload was not confirmed. Files are kept on this device. Tap Send to retry."
            throw e
        } finally { if (current == generation) uploading = null }
    }
    suspend fun acknowledged(sent: List<MessageAttachment>) {
        val ids = sent.mapNotNull { it.attachmentId }.toSet()
        val removed = files.filter { it.uploaded?.id in ids }
        files = files.filterNot { it.uploaded?.id in ids }
        val target = storage()
        try { persist() } finally { withContext(NonCancellable) { removed.forEach { target?.remove(it.id) } } }
    }
    fun changeMode(id: String, mode: String) {
        if (importing || uploading != null || mode !in setOf("prompt", "path")) return
        val local = files.firstOrNull { it.id == id } ?: return
        if (local.uploaded != null || local.kind != "image") return
        files = files.map { if (it.id == id) it.copy(deliveryMode = mode) else it }
        persistAsync()
    }
    fun previewLocal(local: LocalAttachment) {
        previewJob?.cancel(); preview = local.uploaded?.message() ?: MessageAttachment(local.kind, mimeType = local.mimeType, name = local.name, size = local.size, deliveryMode = local.deliveryMode)
        previewFile = storage()?.file(local.id)?.takeIf { it.isFile }; previewError = if (previewFile == null) "The local file is no longer available." else null
        previewBusy = false
    }
    fun openPreview(attachment: MessageAttachment) {
        previewJob?.cancel(); preview = attachment; previewFile = null; previewError = null; previewBusy = true
        val directory = previewRoot(); val current = generation
        previewJob = scope.launch {
            try {
                val key = attachment.attachmentId ?: digest(attachment.data.orEmpty())
                val target = File(directory, digest(key) + "-" + safeAttachmentName(attachment.name))
                if (!target.isFile) {
                    if (attachment.attachmentId != null) {
                        check(online()); requireNotNull(api()).downloadAttachment(attachment.attachmentId, target)
                    } else withContext(Dispatchers.IO) {
                        val data = requireNotNull(attachment.data)
                        require(data.length.toLong() <= MAX_ATTACHMENT_BYTES * 4 / 3 + 4)
                        target.parentFile?.mkdirs()
                        val scratch = File.createTempFile("attachment-", ".tmp", target.parentFile)
                        try {
                            Base64.getDecoder().wrap(data.byteInputStream()).use { input -> scratch.outputStream().use { output ->
                                val copied = input.copyTo(output); require(copied <= MAX_ATTACHMENT_BYTES)
                            } }
                            currentCoroutineContext().ensureActive()
                            Files.move(scratch.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { scratch.delete() }
                    }
                }
                if (current == generation && preview == attachment) previewFile = target
                withContext(Dispatchers.IO) {
                    var bytes = directory.listFiles().orEmpty().sumOf { it.length() }
                    directory.listFiles().orEmpty().filter { it != target }.sortedBy { it.lastModified() }.forEach {
                        if (bytes > MAX_ATTACHMENT_BYTES) { val size = it.length(); if (it.delete()) bytes -= size }
                    }
                    target.setLastModified(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive(); previewError = "This attachment is not available locally. Connect and try opening it again."
            } finally { if (current == generation) previewBusy = false }
        }
    }
    fun closePreview() { previewJob?.cancel(); preview = null; previewFile = null; previewBusy = false; previewError = null }
}
internal fun normalizeMime(value: String): String = value.substringBefore(';').trim().lowercase()
    .takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) } ?: "application/octet-stream"
