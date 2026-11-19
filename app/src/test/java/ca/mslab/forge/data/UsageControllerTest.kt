package ca.mslab.forge.data

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UsageControllerTest {
    private val root = Files.createTempDirectory("forge-usage-test").toFile()
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000L
    private val body = """{"generated_at":"2026-11-04T04:09:19Z","poll_minutes":10,"providers":[{"provider":"claude",
        "windows":[{"label":"5-hour","utilization_pct":2,"window_seconds":18000}]}],"unavailable":[]}"""

    @After fun clean() { scope.cancel(); server.shutdown(); root.deleteRecursively() }

    private fun controller(store: SnapshotStore = SnapshotStore(root, "profile"), api: KandevApi? = KandevApi(server.url("/").toString())) =
        UsageController(scope, { api }, { store }, { now })
    private fun UsageController.settle() = runBlocking { pending?.join() }
    private fun ok() = MockResponse().setBody(body)

    @Test fun `first bind fetches the cached snapshot, stores it, and paints it next time without the network`() {
        server.enqueue(ok())
        val first = controller().apply { bind("profile"); settle() }
        assertEquals("claude", first.report?.providers?.single()?.id)
        assertNull(first.problem)
        val request = server.takeRequest()
        assertEquals("/api/plugins/kandev-provider-usage/webhooks/providers", request.path)

        // Nothing listens on port 1: the phone is effectively offline.
        val reopened = controller(api = KandevApi("http://127.0.0.1:1/")).apply { bind("profile"); settle() }
        assertEquals("claude", reopened.report?.providers?.single()?.id)
        assertEquals(UsageProblem.Offline, reopened.problem)
    }

    @Test fun `foreground revalidation waits ten minutes between attempts`() {
        server.enqueue(ok()); server.enqueue(ok())
        val usage = controller().apply { bind("profile"); settle() }
        now += 9 * 60_000; usage.refreshIfDue(); usage.settle()
        assertEquals(1, server.requestCount)
        now += 60_000; usage.refreshIfDue(); usage.settle()
        assertEquals(2, server.requestCount)
        assertEquals("/api/plugins/kandev-provider-usage/webhooks/providers", server.takeRequest().path)
        assertEquals("/api/plugins/kandev-provider-usage/webhooks/providers", server.takeRequest().path)
    }

    @Test fun `manual refresh forces a provider re-read at most once a minute`() {
        repeat(3) { server.enqueue(ok()) }
        val usage = controller().apply { bind("profile"); settle() }
        server.takeRequest()
        usage.refreshNow(); usage.settle()
        assertEquals("/api/plugins/kandev-provider-usage/webhooks/providers?refresh=1", server.takeRequest().path)
        now += 10_000; usage.refreshNow(); usage.settle()
        assertEquals("A second tap within a minute reads the server cache", "/api/plugins/kandev-provider-usage/webhooks/providers",
            server.takeRequest().path)
    }

    @Test fun `refused and failing servers keep the last good report`() {
        server.enqueue(ok())
        val usage = controller().apply { bind("profile"); settle() }
        for ((code, problem) in listOf(401 to UsageProblem.Denied, 403 to UsageProblem.Denied, 500 to UsageProblem.Server,
            503 to UsageProblem.Server)) {
            server.enqueue(MockResponse().setResponseCode(code))
            now += 10 * 60_000; usage.refreshIfDue(); usage.settle()
            assertEquals(problem, usage.problem)
            assertNotNull(usage.report)
        }
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        now += 10 * 60_000; usage.refreshIfDue(); usage.settle()
        assertEquals(UsageProblem.Invalid, usage.problem)
        assertNotNull(usage.report)
    }

    @Test fun `a missing plugin hides the strip and forgets the saved report`() {
        server.enqueue(ok())
        val store = SnapshotStore(root, "profile")
        val usage = controller(store).apply { bind("profile"); settle() }
        server.enqueue(MockResponse().setResponseCode(404))
        now += 10 * 60_000; usage.refreshIfDue(); usage.settle()
        assertEquals(UsageProblem.NotInstalled, usage.problem)
        assertNull(usage.report)
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(controller(store).apply { bind("profile"); settle() }.report)
    }

    @Test fun `rebinding the same profile is free, another profile starts clean, disconnect clears`() {
        server.enqueue(ok())
        val usage = controller().apply { bind("profile"); settle() }
        usage.bind("profile"); usage.settle()
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        usage.bind("other"); usage.settle()
        assertEquals(2, server.requestCount)
        usage.clear()
        assertNull(usage.report); assertNull(usage.problem)
    }

    @Test fun `no connection means no request`() {
        val usage = controller(api = null).apply { bind("profile"); settle() }
        usage.refreshNow(); usage.settle()
        assertNull(usage.report)
        assertEquals(0, server.requestCount)
    }
}
