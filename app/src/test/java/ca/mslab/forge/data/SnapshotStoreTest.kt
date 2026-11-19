package ca.mslab.forge.data

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SnapshotStoreTest {
    private val root = Files.createTempDirectory("forge-cache-test").toFile()
    @After fun clean() { root.deleteRecursively() }

    @Test fun `snapshots survive a new store and remain isolated by credential`() = runBlocking {
        val first = SnapshotStore(root, "https://server|token-a")
        first.write("plan", String.serializer(), "saved content")
        val reopened = SnapshotStore(root, "https://server|token-a")
        assertEquals("saved content", reopened.read("plan", String.serializer())?.value)
        assertNull(SnapshotStore(root, "https://server|token-b").read("plan", String.serializer()))
        assertNull(SnapshotStore(root, "https://other|token-a").read("plan", String.serializer()))
    }

    @Test fun `corruption is discarded without poisoning other snapshots`() = runBlocking {
        val store = SnapshotStore(root, "profile")
        store.write("broken", String.serializer(), "old")
        store.write("healthy", String.serializer(), "kept")
        root.walkTopDown().first { it.name == digest("broken") + ".json" }.writeText("{incomplete")
        assertNull(store.read("broken", String.serializer()))
        assertEquals("kept", store.read("healthy", String.serializer())?.value)
    }

    @Test fun `cache is bounded and oldest snapshots are evicted`() = runBlocking {
        val store = SnapshotStore(root, "profile", 800)
        store.write("old", String.serializer(), "x".repeat(250))
        root.walkTopDown().first { it.name == digest("old") + ".json" }.setLastModified(1)
        store.write("new", String.serializer(), "y".repeat(250))
        store.write("newest", String.serializer(), "z".repeat(250))
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 800)
        assertNull(store.read("old", String.serializer()))
        assertNotNull(store.read("newest", String.serializer()))
    }

    @Test fun `parallel writes are atomic and leave no temporary files`() = runBlocking {
        val store = SnapshotStore(root, "profile")
        (1..30).map { n -> async(Dispatchers.Default) { store.write("$n", String.serializer(), "value-$n") } }.awaitAll()
        (1..30).forEach { n -> assertEquals("value-$n", store.read("$n", String.serializer())?.value) }
        assertFalse(root.walkTopDown().any { it.extension == "tmp" })
    }

    @Test fun `forgetting one profile never removes another profile`() = runBlocking {
        val one = SnapshotStore(root, "one"); val two = SnapshotStore(root, "two")
        one.write("key", String.serializer(), "one"); two.write("key", String.serializer(), "two")
        one.clear()
        one.write("late", String.serializer(), "must not return")
        assertNull(SnapshotStore(root, "one").read("late", String.serializer()))
        assertNull(one.read("key", String.serializer()))
        assertEquals("two", two.read("key", String.serializer())?.value)
    }

    @Test fun `unacknowledged draft identity survives process recreation`() = runBlocking {
        val record = DraftRecord("draft", "stable-submission", "draft")
        SnapshotStore(root, "profile").write("draft:s", DraftRecord.serializer(), record)
        assertEquals(record, SnapshotStore(root, "profile").read("draft:s", DraftRecord.serializer())?.value)
    }
}
