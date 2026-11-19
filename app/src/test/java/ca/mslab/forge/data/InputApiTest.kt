package ca.mslab.forge.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InputApiTest {
    private lateinit var server: MockWebServer
    @Before fun start(){server=MockWebServer();server.start()}
    @After fun stop(){server.shutdown()}
    private val body=buildJsonObject {put("rejected",true);put("reject_reason","skip")}
    @Test fun `question POST consumes the winning envelope and bearer header without query credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"claimed":false,"status":"answered","response":{"pending_id":"p/1","answers":[{"question_id":"q","selected_options":["b"],"custom_text":""}],"rejected":false,"reject_reason":""}}"""))
        val result=KandevApi(server.url("/").toString(),"test-token").answerQuestions("p/1",body)
        assertFalse(result.claimed);assertEquals(listOf("b"),result.response.answers.single().selected)
        val request=server.takeRequest(1,TimeUnit.SECONDS)!!
        assertEquals("POST",request.method);assertEquals("/api/v1/clarification/p%2F1/respond",request.path)
        assertEquals("Bearer test-token",request.getHeader("Authorization"));assertEquals(body.toString(),request.body.readUtf8())
    }
    @Test fun `known stale conflict is distinct from arbitrary proxy errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"not_active","error":"private diagnostic"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"unknown","error":"private diagnostic"}"""))
        val api=KandevApi(server.url("/").toString())
        val stale=runCatching{api.answerQuestions("p",body)}.exceptionOrNull() as KandevApiException
        assertEquals("not_active",stale.code);assertFalse(stale.message!!.contains("private"))
        val unknown=runCatching{api.answerQuestions("p",body)}.exceptionOrNull() as KandevApiException
        assertNull(unknown.code)
    }
    @Test fun `malformed success and mismatched winning identity never acknowledge answers`() = runBlocking {
        for (response in listOf("""{"success":true}""", """{"success":true,"claimed":true,"status":"answered","response":{"pending_id":"wrong","answers":[],"rejected":false}}""",
            """{"success":true,"claimed":false,"status":"answered","response":{"pending_id":"p","answers":[],"rejected":true}}""")) {
            server.enqueue(MockResponse().setBody(response))
            assertTrue(runCatching{KandevApi(server.url("/").toString()).answerQuestions("p",body)}.isFailure)
        }
    }
    @Test fun `lost question acknowledgement never automatically replays the HTTP POST`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val result=runCatching{KandevApi(server.url("/").toString()).answerQuestions("p",body)}
        assertTrue(result.isFailure);assertEquals(1,server.requestCount)
    }
    @Test fun `free-text questions accept an empty Go slice encoded as null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"m","session_id":"s","author_type":"agent","content":"Question","type":"clarification_request","created_at":"2026-10-30T00:00:00Z","metadata":{"pending_id":"p","question":{"id":"q","prompt":"Your thoughts?","options":null,"allow_custom_text":true}}}],"cursor":"m","has_more":false}"""))
        val question=KandevApi(server.url("/").toString()).listMessages("s").messages.single().metadata!!.question!!
        assertTrue(question.options.isEmpty());assertTrue(validAnswer(question,QuestionAnswer("q",text="An answer")))
    }
    @Test fun `durable turns decode nullable completion and lifecycle metadata`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"turns":[{"id":"turn","session_id":"s","started_at":"2026-10-30T00:00:00Z","created_at":"2026-10-30T00:00:00Z","completed_at":null,"metadata":{"lifecycle_only":"false"}}],"total":1}"""))
        val turns=KandevApi(server.url("/").toString()).listTurns("s")
        assertNull(turns.single().completedAt);assertEquals("/api/v1/task-sessions/s/turns",server.takeRequest().path)
    }
}
