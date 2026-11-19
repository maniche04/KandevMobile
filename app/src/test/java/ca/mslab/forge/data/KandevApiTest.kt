package ca.mslab.forge.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KandevApiTest {
    private lateinit var server: MockWebServer
    @Before fun startServer() { server = MockWebServer(); server.start() }
    @After fun stopServer() { server.shutdown() }

    @Test fun `connect loads release info and workspace envelope with bearer header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":"v0.98.0","commit":"2d93502c","os":"linux"}"""))
        server.enqueue(MockResponse().setBody("""{"workspaces":[{"id":"ws-1","name":"Personal","description":null,"owner_id":"u-1"}],"total":1}"""))
        val result = KandevApi(server.url("/").toString(), "test-token").connect()
        assertEquals("v0.98.0", result.server.version)
        assertEquals(listOf(Workspace("ws-1", "Personal")), result.workspaces)
        for (path in listOf("/api/v1/system/info", "/api/v1/workspaces")) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(path, request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test fun `old server reports update requirement without falling back`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":"v0.96.0","commit":"old"}"""))
        val error = runCatching { KandevApi(server.url("/").toString()).connect() }.exceptionOrNull()
        assertTrue(error is KandevApiException)
        assertTrue(error!!.message!!.contains("Update the server"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `newer kandev releases are accepted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":"v0.101.0","commit":"3c2f7e98"}"""))
        server.enqueue(MockResponse().setBody("""{"workspaces":[],"total":0}"""))
        assertEquals("v0.101.0", KandevApi(server.url("/").toString()).connect().server.version)
    }

    @Test fun `kandev version gate compares release numbers`() {
        listOf("v0.98.0", "0.98.0", "v0.98.1", "v0.99.0", "v0.100.0", "v0.101.0", "v1.0.0", "v0.101.0-rc.1", " v0.98.0 ")
            .forEach { assertTrue(it, isSupportedKandev(it)) }
        listOf("v0.97.9", "v0.9.100", "v0.96.0", "dev", "", "v0.98", "v99999999999.0.0", "0.98.0.1")
            .forEach { assertFalse(it, isSupportedKandev(it)) }
    }

    @Test fun `unreleased build names the reported version`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":"dev","commit":"local"}"""))
        val error = runCatching { KandevApi(server.url("/").toString()).connect() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("\"dev\""))
        assertEquals(1, server.requestCount)
    }

    @Test fun `unauthorized response does not expose raw server body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret server diagnostic"))
        val error = runCatching { KandevApi(server.url("/").toString()).connect() }.exceptionOrNull()
        assertEquals("Authentication required. Enter a valid Kandev API token.", error?.message)
    }

    @Test fun `redirect does not forward a credential or follow browser login`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))
        val error = runCatching { KandevApi(server.url("/").toString(), "test-token").connect() }.exceptionOrNull()
        assertTrue(error is KandevApiException)
        assertEquals(1, server.requestCount)
    }

    @Test fun `empty workspace list is valid and does not require authentication`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"workspaces":[],"total":0}"""))
        assertTrue(KandevApi(server.url("/").toString()).listWorkspaces().isEmpty())
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun `addresses reject embedded secrets and non-root paths`() {
        for (url in listOf("no server", "file:///tmp/test", "https://user:secret@example.com", "https://example.com/api", "https://example.com?token=secret")) {
            assertTrue(url, runCatching { KandevApi.validateServerUrl(url) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("https://example.com/", KandevApi.validateServerUrl(" https://example.com ").toString())
    }
    @Test fun `task session and history routes preserve paging and encoded identifiers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tasks":[{"id":"t","title":"Task","state":"IN_PROGRESS"}],"total":51}"""))
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s","name":"","state":"IDLE"}],"total":1}"""))
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"m","session_id":"s","author_type":"agent","content":"Hello","type":"content","created_at":"2026-10-30T00:00:00Z"}],"total":1,"has_more":true,"cursor":"m"}"""))
        val api = KandevApi(server.url("/").toString())
        assertEquals(51, api.listTasks("w/1", 2).total)
        assertEquals("s", api.listSessions("t").single().id)
        assertEquals("Hello", api.listMessages("s", "before+id").messages.single().content)
        assertEquals("/api/v1/workspaces/w%2F1/tasks?page=2&page_size=100", server.takeRequest().path)
        assertEquals("/api/v1/tasks/t/sessions", server.takeRequest().path)
        assertEquals("/api/v1/task-sessions/s/messages?limit=100&sort=desc&before=before%2Bid", server.takeRequest().path)
    }

}
