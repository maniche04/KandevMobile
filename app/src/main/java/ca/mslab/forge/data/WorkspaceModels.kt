package ca.mslab.forge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TaskPlan(val id: String, val title: String, val content: String,
    @SerialName("updated_at") val updatedAt: String = "")
@Serializable
internal data class PlanSnapshot(val plan: TaskPlan? = null)
@Serializable
internal data class FileNode(val name: String, val path: String,
    @SerialName("is_dir") val directory: Boolean, val size: Long = 0, val children: List<FileNode>? = null)
@Serializable
internal data class FileTree(val root: FileNode, val error: String? = null)
@Serializable
internal data class WorkspaceFile(val path: String, val content: String, val size: Long = 0,
    @SerialName("is_binary") val binary: Boolean = false, val error: String? = null)
@Serializable
internal data class FileFacet(val status: String = "", val additions: Int = 0, val deletions: Int = 0,
    val diff: String? = null, @SerialName("diff_state") val diffState: String? = null)
@Serializable
internal data class ChangedFile(val path: String, val status: String = "modified", val staged: Boolean = false,
    val additions: Int = 0, val deletions: Int = 0, val diff: String? = null,
    @SerialName("repository_name") val repository: String = "",
    @SerialName("diff_skip_reason") val skipReason: String? = null,
    @SerialName("staged_change") val stagedChange: FileFacet? = null,
    @SerialName("unstaged_change") val unstagedChange: FileFacet? = null)
@Serializable
internal data class GitStatus(
    @SerialName("status_state") val statusState: String = "loading",
    @SerialName("error_code") val errorCode: String? = null,
    val branch: String = "", val ahead: Int = 0, val behind: Int = 0,
    @SerialName("repository_name") val repository: String = "", val files: Map<String, ChangedFile> = emptyMap(),
    val modified: List<String> = emptyList(), val added: List<String> = emptyList(),
    val deleted: List<String> = emptyList(), val untracked: List<String> = emptyList(), val renamed: List<String> = emptyList())
@Serializable
internal data class ChangesSnapshot(val statuses: Map<String, GitStatus> = emptyMap(),
    val cumulative: Map<String, ChangedFile> = emptyMap())
@Serializable
internal data class TerminalInfo(val id: String, val kind: String = "ordinary",
    @SerialName("display_name") val name: String = "Terminal", val state: String = "open",
    @SerialName("pty_status") val ptyStatus: String = "stopped")
@Serializable
internal data class TerminalList(val terminals: List<TerminalInfo> = emptyList())
@Serializable
internal data class CachedConversation(val messages: List<ChatMessage>, val cursor: String?, val hasOlder: Boolean, val depth: Int = 1)
@Serializable
internal data class DraftRecord(val content: String, val submissionId: String? = null, val submittedContent: String? = null)

internal enum class WorkspaceTab(val title: String) { Chat("Chat"), Plan("Plan"), Changes("Changes"), Files("Files"), Terminal("Terminal") }

@Serializable
internal data class FileEditSnapshot(val original: WorkspaceFile, val content: String)

/** Preserve raw binary bytes; text content in the workspace protocol is plain UTF-8. */
internal fun fileBytes(file: WorkspaceFile): ByteArray =
    if (file.binary) java.util.Base64.getMimeDecoder().decode(file.content) else file.content.toByteArray(Charsets.UTF_8)
