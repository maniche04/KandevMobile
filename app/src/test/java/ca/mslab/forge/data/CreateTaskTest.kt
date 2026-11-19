package ca.mslab.forge.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CreateTaskTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun `create posts the prompt once and decodes the new task`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"t1","title":"Fix login","state":"IN_PROGRESS","workspace_id":"w1","session_id":"s1"}"""))
        val api = KandevApi(server.url("/").toString(), "secret")
        val task = api.createTask(buildJsonObject {
            put("workspace_id", "w1"); put("workflow_id", "f1"); put("auto_title", true); put("description", "Fix the login bug")
            put("start_agent", true)
        })
        assertEquals("t1", task.id); assertEquals("w1", task.workspaceId)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/v1/tasks", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Fix the login bug", body["description"]?.jsonPrimitive?.content)
        assertEquals(true, body["auto_title"]?.jsonPrimitive?.boolean)
        assertEquals(1, server.requestCount)
    }

    @Test fun `server errors are shown, not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"repository is dirty"}"""))
        val api = KandevApi(server.url("/").toString())
        val error = runCatching { api.createTask(buildJsonObject { put("workspace_id", "w1") }) }.exceptionOrNull()
        assertEquals("Kandev couldn't create the task: repository is dirty", error?.message)
        assertEquals(1, server.requestCount)
    }
}
