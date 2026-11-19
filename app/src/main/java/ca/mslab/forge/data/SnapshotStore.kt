package ca.mslab.forge.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal data class CachedValue<T>(val value: T, val savedAt: Long)

/** Private, bounded snapshots, isolated by server AND credential. No mutation queue. */
internal class SnapshotStore(root: File, identity: String, private val maxBytes: Long = 64L * 1024 * 1024) {
    private val directory = File(root, digest("v0.98.0:$identity"))
    private val lock = Mutex()
    private var retired = false
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Entry(val savedAt: Long, val payload: String)

    suspend fun <T> read(key: String, serializer: KSerializer<T>): CachedValue<T>? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (retired) return@withLock null
            val file = File(directory, digest(key) + ".json")
            if (!file.isFile) return@withLock null
            try {
                val entry = json.decodeFromString<Entry>(file.readText())
                val value = json.decodeFromString(serializer, entry.payload)
                file.setLastModified(System.currentTimeMillis())
                CachedValue(value, entry.savedAt)
            } catch (_: Exception) { file.delete(); null }
        }
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (retired) return@withLock
            val body = json.encodeToString(Entry.serializer(), Entry(System.currentTimeMillis(), json.encodeToString(serializer, value)))
            if (body.toByteArray().size > minOf(maxBytes, 4L * 1024 * 1024)) return@withLock
            directory.mkdirs()
            atomicWrite(File(directory, digest(key) + ".json"), body.toByteArray())
            val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
            var bytes = files.sumOf { it.length() }
            for (file in files) {
                if (bytes <= maxBytes) break
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (!retired) File(directory, digest(key) + ".json").delete(); Unit
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { retired = true; directory.deleteRecursively(); Unit } }
}

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File.createTempFile("snapshot-", ".tmp", file.parentFile)
    try {
        temp.outputStream().use { it.write(bytes) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally { temp.delete() }
}
