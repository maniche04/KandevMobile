package ca.mslab.forge.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QueueAndStatusTest {
    private fun task(summary: TaskStatusSummary?, session: String? = null, task: String? = null, state: String? = "RUNNING") =
        TaskSummary("t", "Task", "IN_PROGRESS", sessionState = state, summary = summary, sessionPendingAction = session, taskPendingAction = task)

    @Test fun `a status summary without a pending action clears stale task fields`() {
        // An auto-approved request: the summary was re-projected, the older task fields were not.
        assertNull(task(TaskStatusSummary(), session = "permission", task = "permission", state = "WAITING_FOR_INPUT").pendingAction)
        assertEquals("permission", task(TaskStatusSummary(pendingAction = "permission")).pendingAction)
    }

    @Test fun `without a summary the task field wins and the session fallback needs a waiting session`() {
        assertEquals("clarification", task(null, task = "clarification").pendingAction)
        assertEquals("permission", task(null, session = "permission", state = "WAITING_FOR_INPUT").pendingAction)
        assertNull(task(null, session = "permission", state = "RUNNING").pendingAction)
    }

    @Test fun `input mode matches the web composer`() {
        fun s(state: String, activity: String? = null, steer: Boolean = false) = AgentSession("s", state = state, activity = activity, supportsSteering = steer)
        assertEquals(InputMode.Direct, inputMode(s("WAITING_FOR_INPUT"), 0))
        assertEquals(InputMode.Direct, inputMode(s("CREATED"), 0))
        assertEquals(InputMode.Queue, inputMode(s("STARTING"), 0))
        assertEquals(InputMode.Queue, inputMode(s("RUNNING", "generating"), 0))
        assertEquals(InputMode.Direct, inputMode(s("RUNNING", "background"), 0))
        assertEquals(InputMode.Steer, inputMode(s("RUNNING", "generating", steer = true), 0))
        // A steer never jumps messages already waiting in the queue.
        assertEquals(InputMode.Queue, inputMode(s("RUNNING", "generating", steer = true), 2))
        for (ended in listOf("COMPLETED", "FAILED", "CANCELLED")) assertEquals(InputMode.Unavailable, inputMode(s(ended), 0))
    }

    private fun session() = AgentSession("s", state = "RUNNING", queueIncarnationId = "inc")
    private val status = buildJsonObject {
        put("session_id", "s"); put("count", 2); put("max", 10)
        put("entries", buildJsonArray {
            add(buildJsonObject { put("id", "a"); put("content", "first"); put("position", 0) })
            add(buildJsonObject { put("id", "b"); put("content", "second"); put("position", 1) })
        })
    }

    @Test fun `send now targets one entry with the session identity`() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val queue = QueueController(this, { QueueContext("t", session(), true) }) { action, payload, _ ->
            calls += action to payload; if (action == "message.queue.get") status else buildJsonObject { put("dispatched", true) }
        }
        queue.selectSession("s"); queue.refresh()
        assertEquals(listOf("a", "b"), queue.status.entries.map { it.id })
        queue.sendNow("b"); idle(queue)
        val (action, payload) = calls.first { it.first == "message.queue.send_now" }
        assertEquals("message.queue.send_now", action)
        assertEquals("entry", payload["scope"]?.jsonPrimitive?.content); assertEquals("b", payload["entry_id"]?.jsonPrimitive?.content)
        assertEquals("inc", payload["session_incarnation_id"]?.jsonPrimitive?.content); assertEquals("t", payload["task_id"]?.jsonPrimitive?.content)
        queue.sendNow(null); idle(queue)
        val all = calls.last { it.first == "message.queue.send_now" }.second
        assertEquals("all", all["scope"]?.jsonPrimitive?.content); assertFalse(all.containsKey("entry_id"))
    }

    @Test fun `edits go through a lease and always release it`() = runBlocking {
        val actions = mutableListOf<String>()
        val queue = QueueController(this, { QueueContext("t", session(), true) }) { action, payload, _ ->
            actions += action
            when (action) {
                "message.queue.edit.begin" -> buildJsonObject { put("lease_id", "L"); put("target_revision", 3) }
                "message.queue.update" -> {
                    assertEquals("L", payload["lease_id"]?.jsonPrimitive?.content)
                    assertEquals(3L, payload["expected_target_revision"]?.jsonPrimitive?.long)
                    throw KandevApiException("rejected", "edit_conflict")
                }
                "message.queue.get" -> status
                else -> buildJsonObject { }
            }
        }
        queue.selectSession("s"); queue.edit("a", "changed"); idle(queue)
        assertEquals(listOf("message.queue.edit.begin", "message.queue.update", "message.queue.edit.end", "message.queue.get"), actions)
        assertEquals("The entry changed while you were editing. Review it and try again.", queue.error)
    }

    @Test fun `a full queue is reported and nothing is retried`() = runBlocking {
        var adds = 0
        val queue = QueueController(this, { QueueContext("t", session(), true) }) { action, _, _ ->
            if (action == "message.queue.add") { adds++; throw KandevApiException("rejected", "queue_full") } else status
        }
        queue.selectSession("s")
        assertFalse(queue.add("hello")); assertEquals(1, adds)
        assertTrue(queue.error!!.startsWith("The queue is full"))
    }

    @Test fun `status events for another session are ignored and switching sessions resets the queue`() = runBlocking {
        val queue = QueueController(this, { QueueContext("t", session(), true) }) { _, _, _ -> status }
        queue.selectSession("s"); queue.onStatus(JsonObject(status + ("session_id" to JsonPrimitive("other"))))
        assertEquals(0, queue.count)
        queue.onStatus(status); assertEquals(2, queue.count)
        queue.selectSession("x"); assertEquals(0, queue.count)
    }

    private suspend fun idle(queue: QueueController) { withTimeout(5000) { while (queue.working.isNotEmpty()) delay(5) } }
}
