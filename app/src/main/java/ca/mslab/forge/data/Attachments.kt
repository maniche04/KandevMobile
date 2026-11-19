package ca.mslab.forge.data

import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
internal const val MAX_ATTACHMENTS = 10
internal val PROMPT_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

@Serializable
data class MessageAttachment(val type: String, @SerialName("attachment_id") val attachmentId: String? = null,
    @SerialName("mime_type") val mimeType: String, val name: String = "Attachment",
    @SerialName("size_bytes") val size: Long = 0, @SerialName("delivery_mode") val deliveryMode: String = "path", val data: String? = null)
@Serializable
internal data class UploadedAttachment(@SerialName("attachment_id") val id: String, val name: String,
    @SerialName("mime_type") val mimeType: String, val kind: String,
    @SerialName("delivery_mode") val deliveryMode: String, @SerialName("size_bytes") val size: Long,
    val state: String = "staged", @SerialName("expires_at") val expiresAt: String? = null) {
    fun message() = MessageAttachment(kind, id, mimeType, name, size, deliveryMode)
    fun expired(): Boolean = state in setOf("deleted", "expired") || expiresAt?.let {
        runCatching { !java.time.Instant.parse(it).isAfter(java.time.Instant.now()) }.getOrDefault(true)
    } == true
}
@Serializable
internal data class LocalAttachment(val id: String, val name: String, val mimeType: String, val size: Long,
    val deliveryMode: String, val uploaded: UploadedAttachment? = null) {
    val kind get() = if (mimeType in PROMPT_IMAGE_TYPES) "image" else if (mimeType.startsWith("audio/")) "audio" else "resource"
}
@Serializable internal data class AttachmentDraft(val files: List<LocalAttachment> = emptyList())

/** Owned bytes are separate from evictable JSON snapshots and bounded across this profile. */
internal class AttachmentStore(root: File, identity: String, private val maxBytes: Long = MAX_ATTACHMENT_BYTES) {
    private val directory = File(root, digest("v0.98.0:$identity"))
    private val lock = Mutex()
    private var retired = false
    suspend fun stage(name: String, mimeType: String, source: () -> InputStream): LocalAttachment {
        var ownedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                lock.withLock {
                    check(!retired)
                    directory.mkdirs()
                    val remaining = maxBytes - directory.listFiles().orEmpty().sumOf { it.length() }
                    require(remaining > 0) { "Local attachment storage is full. Remove an unused draft attachment." }
                    val id = UUID.randomUUID().toString()
                    val file = File(directory, id)
                    ownedFile = file
                    var size = 0L
                    source().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                require(size <= remaining) { "Attachments exceed 100 MiB of local storage." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    LocalAttachment(id, safeAttachmentName(name), mimeType, size,
                        if (mimeType in PROMPT_IMAGE_TYPES) "prompt" else "path")
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { ownedFile?.delete() }
            throw e
        }
    }
    fun file(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(directory, id) }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { lock.withLock { file(id).delete(); Unit } }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { retired = true; directory.deleteRecursively(); Unit } }
}
internal fun safeAttachmentName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .filter { !it.isISOControl() }.take(200).ifBlank { "attachment" }
internal fun messageFingerprint(content: String, attachments: List<MessageAttachment>): String = if (attachments.isEmpty()) content
    else "attachments:" + digest(content + "\u0000" + Json.encodeToString(attachments))
