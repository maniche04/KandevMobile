package ca.mslab.forge.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * The local source of truth. Every screen renders from here first; the network only revalidates.
 * Rows keep the server JSON verbatim plus the few columns that are queried or sorted on.
 */
@Entity(tableName = "tasks", indices = [Index("workspaceId"), Index("parentId")])
data class TaskRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val workflowId: String?,
    val stepId: String?,
    val parentId: String?,
    val state: String,
    val sessionState: String?,
    val pendingAction: String?,
    val running: Boolean,
    val lastActivity: Long,
    val json: String,
)

@Entity(tableName = "steps", indices = [Index("workspaceId")])
data class StepRow(@PrimaryKey val id: String, val workspaceId: String, val workflowId: String, val name: String,
    val position: Int, val color: String, val completesTask: Boolean = false)

@Entity(tableName = "workflows", indices = [Index("workspaceId")])
data class WorkflowRow(@PrimaryKey val id: String, val workspaceId: String, val name: String, val sortOrder: Int)

@Entity(tableName = "repositories", indices = [Index("workspaceId")])
data class RepositoryRow(@PrimaryKey val id: String, val workspaceId: String, val name: String, val json: String)

@Entity(tableName = "sessions", indices = [Index("taskId")])
data class SessionRow(@PrimaryKey val id: String, val taskId: String, val state: String, val json: String)

@Entity(tableName = "messages", primaryKeys = ["sessionId", "id"], indices = [Index(value = ["sessionId", "createdAt"])])
data class MessageRow(val sessionId: String, val id: String, val createdAt: Long, val json: String)

/** Paging state for the cached tail of a conversation. */
@Entity(tableName = "conversations")
data class ConversationRow(@PrimaryKey val sessionId: String, val cursor: String?, val hasOlder: Boolean,
    val depth: Int, val syncedAt: Long)

/** Live progress that is not part of the task list: todos, context window and current activity. */
@Entity(tableName = "runtime")
data class RuntimeRow(@PrimaryKey val sessionId: String, val taskId: String, val todosJson: String?,
    val contextUsed: Long, val contextSize: Long, val activity: String?, val updatedAt: Long)

@Dao
interface ForgeDao {
    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId")
    fun tasks(workspaceId: String): Flow<List<TaskRow>>

    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId")
    suspend fun tasksNow(workspaceId: String): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskRow?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun taskFlow(id: String): Flow<TaskRow?>

    /** Tasks with a running agent or an open request, across workspaces; drives live notifications. */
    @Query("SELECT * FROM tasks WHERE running = 1 OR pendingAction IS NOT NULL ORDER BY lastActivity DESC")
    fun activeTasks(): Flow<List<TaskRow>>

    @Upsert suspend fun upsertTasks(rows: List<TaskRow>)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM tasks WHERE workspaceId = :workspaceId AND id NOT IN (:keep)")
    suspend fun pruneTasks(workspaceId: String, keep: List<String>)

    @Transaction
    suspend fun replaceTasks(workspaceId: String, rows: List<TaskRow>) {
        upsertTasks(rows)
        pruneTasks(workspaceId, rows.map { it.id })
    }

    @Query("SELECT * FROM steps WHERE workspaceId = :workspaceId ORDER BY position")
    fun steps(workspaceId: String): Flow<List<StepRow>>
    @Query("SELECT * FROM workflows WHERE workspaceId = :workspaceId ORDER BY sortOrder")
    fun workflows(workspaceId: String): Flow<List<WorkflowRow>>
    @Query("SELECT * FROM repositories WHERE workspaceId = :workspaceId")
    fun repositories(workspaceId: String): Flow<List<RepositoryRow>>

    @Query("DELETE FROM steps WHERE workspaceId = :workspaceId") suspend fun clearSteps(workspaceId: String)
    @Query("DELETE FROM workflows WHERE workspaceId = :workspaceId") suspend fun clearWorkflows(workspaceId: String)
    @Query("DELETE FROM repositories WHERE workspaceId = :workspaceId") suspend fun clearRepositories(workspaceId: String)
    @Upsert suspend fun upsertSteps(rows: List<StepRow>)
    @Upsert suspend fun upsertWorkflows(rows: List<WorkflowRow>)
    @Upsert suspend fun upsertRepositories(rows: List<RepositoryRow>)

    @Transaction
    suspend fun replaceStructure(workspaceId: String, workflows: List<WorkflowRow>, steps: List<StepRow>, repositories: List<RepositoryRow>?) {
        clearWorkflows(workspaceId); upsertWorkflows(workflows)
        clearSteps(workspaceId); upsertSteps(steps)
        if (repositories != null) { clearRepositories(workspaceId); upsertRepositories(repositories) }
    }

    @Query("SELECT * FROM sessions WHERE taskId = :taskId")
    suspend fun sessions(taskId: String): List<SessionRow>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionRow?
    @Upsert suspend fun upsertSessions(rows: List<SessionRow>)
    @Query("DELETE FROM sessions WHERE taskId = :taskId AND id NOT IN (:keep)")
    suspend fun pruneSessions(taskId: String, keep: List<String>)
    @Transaction
    suspend fun replaceSessions(taskId: String, rows: List<SessionRow>) { upsertSessions(rows); pruneSessions(taskId, rows.map { it.id }) }

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun messages(sessionId: String): List<MessageRow>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMessage(sessionId: String): MessageRow?
    @Upsert suspend fun upsertMessages(rows: List<MessageRow>)
    @Query("DELETE FROM messages WHERE sessionId = :sessionId") suspend fun clearMessages(sessionId: String)
    @Transaction
    suspend fun replaceConversation(meta: ConversationRow, rows: List<MessageRow>) {
        clearMessages(meta.sessionId); upsertMessages(rows); upsertConversation(meta)
    }
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId") suspend fun conversation(sessionId: String): ConversationRow?
    @Upsert suspend fun upsertConversation(row: ConversationRow)

    @Query("SELECT * FROM runtime WHERE sessionId = :sessionId") fun runtime(sessionId: String): Flow<RuntimeRow?>
    @Query("SELECT * FROM runtime WHERE sessionId = :sessionId") suspend fun runtimeNow(sessionId: String): RuntimeRow?
    @Query("SELECT * FROM runtime") fun runtimes(): Flow<List<RuntimeRow>>
    @Upsert suspend fun upsertRuntime(row: RuntimeRow)

    /** Keeps the database bounded: conversations of sessions not opened or updated recently are dropped. */
    @Query("DELETE FROM messages WHERE sessionId IN (SELECT sessionId FROM conversations WHERE syncedAt < :before)")
    suspend fun evictMessages(before: Long)
    @Query("DELETE FROM conversations WHERE syncedAt < :before") suspend fun evictConversations(before: Long)
}

@Database(
    entities = [TaskRow::class, StepRow::class, WorkflowRow::class, RepositoryRow::class, SessionRow::class,
        MessageRow::class, ConversationRow::class, RuntimeRow::class],
    // The database is a cache: schema changes rebuild it from the server.
    version = 2, exportSchema = false,
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun dao(): ForgeDao

    companion object {
        /** One database per server profile, so a different server or token never sees cached rows. */
        fun open(context: Context, name: String): ForgeDatabase =
            Room.databaseBuilder(context.applicationContext, ForgeDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
