package ca.mslab.forge.data

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PendingInputTest {
    private val time = "2026-10-30T01:00:00Z"
    private fun turn(id: String = "turn", started: String = time, completed: String? = null, flag: JsonElement? = null) =
        SessionTurn(id, "s", started, started, completed, flag?.let { buildJsonObject { put("lifecycle_only", it) } })
    private val question = InputQuestion("q1", "Choice", "Which path?", listOf(QuestionOption("a", "Alpha"), QuestionOption("b", "Beta")))
    private val allow = PermissionOption("once", "Allow once", "allow_once")
    private fun message(id: String = "m", type: String = "clarification_request", turn: String = "turn", status: String = "pending",
        total: Int = 1, q: InputQuestion = question) = ChatMessage(id, "s", "agent", "Input needed", type, time,
            requestsInput = true, turnId = turn, metadata = MessageMetadata(status = status, pendingId = "bundle", requestId = "request",
                options = listOf(allow, PermissionOption("deny", "Deny", "reject_once")), question = if (type == "clarification_request") q else null, questionTotal = total))
    private fun context(messages: List<ChatMessage> = listOf(message()), turns: List<SessionTurn>? = listOf(turn()), live: Boolean = true,
        state: String = "WAITING_FOR_INPUT") = InputContext("task", AgentSession("s", state = state), messages, turns, live)
    private fun controller(scope: CoroutineScope, snapshot: () -> InputContext, refresh: suspend () -> InputContext = { snapshot() },
        permission: suspend (JsonObject) -> Unit = {}, questions: suspend (String, JsonObject) -> ClarificationResolution = { id, _ ->
            ClarificationResolution(true, true, "answered", ClarificationResponse(id, listOf(QuestionAnswer("q1", listOf("a"))), false))
        }, store: () -> SnapshotStore? = { null }, resolved: (PendingInput, String, List<QuestionAnswer>) -> Unit = { _, _, _ -> }) =
        InputController(scope, snapshot, refresh, permission, questions, store, resolved)
    private suspend fun idle(model: InputController) { withTimeout(5000) { while(model.busy) delay(5) } }

    @Test fun `durable turns outrank timestamps and ignore exact lifecycle encodings only`() {
        val old = turn(completed = time)
        val open = turn("open", "2026-10-29T00:00:00Z")
        for (flag in listOf(JsonPrimitive(true), JsonPrimitive(1), JsonPrimitive("true"), JsonPrimitive("1"))) {
            val ctx = context(listOf(message(turn = "open")), listOf(old, open, turn("shadow", "2026-10-31T00:00:00Z", flag = flag)))
            assertNotNull(ctx.pending())
        }
        for (flag in listOf(JsonPrimitive(false), JsonPrimitive("false"), JsonPrimitive("yes"), JsonArray(listOf(JsonPrimitive(1))))) {
            assertNull(context(listOf(message()), listOf(turn(), turn("new", "2026-10-31T00:00:00Z", flag = flag))).pending())
        }
    }
    @Test fun `completed failed unknown-turn and replaced approval requests cannot be actioned`() {
        assertNull(context(turns = null).pending())
        assertNull(context(turns = listOf(turn(completed = time))).pending())
        assertNull(context(state = "FAILED").pending())
        assertNull(context(listOf(message(type="permission_request"), message("new",type="permission_request",status="approved"))).pending())
        assertNull(context(listOf(message(type="permission_request").copy(metadata=MessageMetadata(pendingId="p")))).pending())
    }
    @Test fun `question bundle remains incomplete until every sibling arrives`() {
        val one = message(total=2)
        assertFalse((context(listOf(one)).pending() as PendingInput.Questions).complete)
        val two = message("m2",total=2,q=question.copy(id="q2"))
        assertTrue((context(listOf(one,two)).pending() as PendingInput.Questions).complete)
        assertFalse((context(listOf(one,one.copy(id="duplicate"))).pending() as PendingInput.Questions).complete)
    }
    @Test fun `choice-only questions reject text and answer length counts Unicode code points`() {
        assertFalse(validAnswer(question.copy(allowCustom=false), QuestionAnswer("q1",text="forbidden")))
        assertFalse(validAnswer(question,QuestionAnswer("q1",listOf("invented"))))
        assertTrue(validAnswer(question,QuestionAnswer("q1",text="😀".repeat(2000))))
        assertFalse(validAnswer(question,QuestionAnswer("q1",text="😀".repeat(2001))))
    }
    @Test fun `permission decisions preserve offered identity reject and cancel semantics`() {
        val ctx = context(listOf(message(type="permission_request")))
        val pending = ctx.pending() as PendingInput.Permission
        val payload = permissionPayload(ctx,pending,allow)
        assertEquals("request",payload["request_id"]?.jsonPrimitive?.content)
        assertEquals("once",payload["option_id"]?.jsonPrimitive?.content)
        assertFalse(payload.getValue("rejected").jsonPrimitive.boolean)
        val deny = pending.message.metadata!!.options.last()
        assertTrue(permissionPayload(ctx,pending,deny).getValue("rejected").jsonPrimitive.boolean)
        assertTrue(runCatching { permissionPayload(ctx,pending,allow.copy(id="not-offered")) }.isFailure)
        val cancel = allow.copy(metadata=buildJsonObject { put("codex_decision","cancel");put("codex_app_server",true) })
        val cancelPending=PendingInput.Permission(pending.message.copy(metadata=pending.message.metadata.copy(options=listOf(cancel))))
        val cancelled=permissionPayload(ctx,cancelPending,cancel)
        assertTrue(cancelled.getValue("cancelled").jsonPrimitive.boolean);assertFalse(cancelled.containsKey("option_id"))
    }
    @Test fun `preflight resolution elsewhere prevents permission mutation`() = runBlocking {
        val ctx=context(listOf(message(type="permission_request")))
        var writes=0
        val model=controller(this,{ctx},refresh={context(listOf(message(type="permission_request",status="approved")))},permission={writes++})
        model.open(requireNotNull(model.pending));idle(model);model.permission(allow);idle(model)
        assertEquals(0,writes);assertNull(model.pending);assertNotNull(model.notice)
    }
    @Test fun `changed offered action cannot be approved from an old sheet`() = runBlocking {
        val msg=message(type="permission_request")
        val ctx=context(listOf(msg));var writes=0
        val model=controller(this,{ctx},refresh={context(listOf(msg.copy(content="A different command")))},permission={writes++})
        model.open(requireNotNull(model.pending));idle(model);model.permission(allow);idle(model)
        assertEquals(0,writes)
    }
    @Test fun `deny without an offered reject cancels without inventing an option`() = runBlocking {
        val msg=message(type="permission_request").let { it.copy(metadata=it.metadata!!.copy(options=listOf(allow))) }
        val ctx=context(listOf(msg));var sent:JsonObject?=null
        val model=controller(this,{ctx},permission={sent=it})
        model.open(requireNotNull(model.pending));idle(model);model.cancelPermission();idle(model)
        assertTrue(sent!!.getValue("cancelled").jsonPrimitive.boolean);assertFalse(sent!!.containsKey("option_id"))
    }
    @Test fun `expired server response disables stale permission controls`() = runBlocking {
        val ctx=context(listOf(message(type="permission_request")));var writes=0
        val model=controller(this,{ctx},permission={writes++;throw KandevApiException("stale","permission_stale")})
        model.open(requireNotNull(model.pending));idle(model);model.permission(allow);idle(model)
        assertNull(model.pending);model.permission(allow);assertEquals(1,writes)
    }
    @Test fun `failed response retains answers and reconnect never submits them`() = runBlocking {
        var ctx=context();var writes=0
        val model=controller(this,{ctx},questions={_,_->writes++;throw IOException("disconnected")})
        model.open(requireNotNull(model.pending));idle(model);model.answer(question,selected="a");model.submitAnswers();idle(model)
        assertEquals(1,writes);assertNotNull(model.error);assertEquals(listOf("a"),model.answers["q1"]?.selected)
        ctx=ctx.copy(live=false);assertFalse(model.enabled);ctx=ctx.copy(live=true);delay(300)
        assertEquals(1,writes)
    }
    @Test fun `concurrent clicks submit once and apply the other client's saved answer`() = runBlocking {
        val ctx=context();var writes=0;var saved:List<QuestionAnswer> = emptyList()
        val gate=CompletableDeferred<Unit>()
        val model=controller(this,{ctx},questions={id,_->writes++;gate.await();ClarificationResolution(true,false,"answered",
            ClarificationResponse(id,listOf(QuestionAnswer("q1",listOf("b"))),false))},resolved={_,_,answers->saved=answers})
        model.open(requireNotNull(model.pending));idle(model);model.answer(question,selected="a");model.submitAnswers();model.submitAnswers()
        delay(20);assertEquals(1,writes);gate.complete(Unit);idle(model)
        assertEquals(listOf("b"),saved.single().selected);assertFalse(model.resolution!!.claimed);assertNull(model.pending)
        model.submitAnswers();assertEquals(1,writes)
    }
    @Test fun `skip sends no answers and rejection is applied from the acknowledgement`() = runBlocking {
        val ctx=context();var body:JsonObject?=null
        val model=controller(this,{ctx},questions={id,payload->body=payload;ClarificationResolution(true,true,"rejected",ClarificationResponse(id,emptyList(),true,"No thanks"))})
        model.open(requireNotNull(model.pending));idle(model);model.rejectReason("No thanks");model.rejectQuestions();idle(model)
        assertFalse(body!!.containsKey("answers"));assertTrue(body!!.getValue("rejected").jsonPrimitive.boolean)
        assertEquals("rejected",model.resolution?.status)
    }
    @Test fun `late acknowledgement cannot update a different selected session`() = runBlocking {
        val child=CoroutineScope(coroutineContext+SupervisorJob());var ctx=context();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var applied=0
        val model=controller(child,{ctx},questions={id,_->
            withContext(NonCancellable) { entered.complete(Unit);release.await() }
            ClarificationResolution(true,true,"answered",ClarificationResponse(id,listOf(QuestionAnswer("q1",listOf("a"))),false))
        },resolved={_,_,_->applied++})
        try {
            model.open(requireNotNull(model.pending));idle(model);model.answer(question,selected="a");model.submitAnswers();entered.await()
            model.reset();ctx=ctx.copy(session=AgentSession("another-session",state="WAITING_FOR_INPUT"));release.complete(Unit);delay(100)
            assertEquals(0,applied);assertFalse(model.busy);assertNull(model.opened)
        } finally {child.cancel()}
    }
    @Test fun `question answers survive controller recreation scoped to pending identity`() = runBlocking {
        val root=Files.createTempDirectory("forge-answer-draft").toFile()
        val child=CoroutineScope(coroutineContext+SupervisorJob());val storage=SnapshotStore(root,"profile");val ctx=context()
        try {
            val first=controller(child,{ctx},store={storage});first.open(requireNotNull(first.pending));idle(first)
            first.answer(question,text="Kept offline");delay(400)
            val next=controller(child,{ctx.copy(live=false)},store={storage});next.open(requireNotNull(next.pending));idle(next)
            assertEquals("Kept offline",next.answers["q1"]?.text);assertFalse(next.enabled)
        } finally {child.cancel();root.deleteRecursively()}
    }
}
