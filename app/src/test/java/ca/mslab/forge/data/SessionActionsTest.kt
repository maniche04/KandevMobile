package ca.mslab.forge.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionActionsTest {
    @Test fun `actions follow the session state and cancellation disables repeated stop`() {
        for (state in listOf("RUNNING","STARTING")) assertEquals(listOf(SessionAction.Interrupt,SessionAction.End),sessionActions(AgentSession("s",state=state)))
        assertEquals(listOf(SessionAction.End),sessionActions(AgentSession("s",state="WAITING_FOR_INPUT")))
        assertEquals(listOf(SessionAction.Start),sessionActions(AgentSession("s",state="CREATED")))
        assertEquals(listOf(SessionAction.Resume),sessionActions(AgentSession("s",state="COMPLETED")))
        for (state in listOf("FAILED","CANCELLED")) assertEquals(listOf(SessionAction.Resume,SessionAction.FreshStart),sessionActions(AgentSession("s",state=state)))
        assertTrue(sessionActions(AgentSession("s",state="RUNNING",cancellationPending=true)).isEmpty())
        assertTrue(sessionActions(AgentSession("s",state="IDLE")).isEmpty())
    }
    @Test fun `stop interrupts the turn and keeps the agent, ending is a separate request`() = runBlocking {
        val selected=AgentSession("s",state="RUNNING");val calls=mutableListOf<Pair<String,JsonObject>>()
        val model=SessionActions(this,{SessionActionContext("t",selected,true)},{selected},{action,payload->calls+=action to payload;buildJsonObject{put("success",true)}},{})
        model.perform(SessionAction.Interrupt);idle(model)
        assertEquals("agent.cancel",calls.single().first);assertEquals(setOf("session_id"),calls.single().second.keys)
        model.refreshState();idle(model);model.perform(SessionAction.End);idle(model)
        assertEquals("session.stop",calls.last().first);assertEquals("s",calls.last().second["session_id"]?.jsonPrimitive?.content)
    }
    @Test fun `start uses the same-session launch intent without sending a draft`() = runBlocking {
        var selected=AgentSession("s",state="CREATED");var calls=0
        val model=SessionActions(this,{SessionActionContext("t",selected,true)},{selected},{action,payload->
            calls++;assertEquals("session.launch",action);assertEquals("start_created",payload["intent"]?.jsonPrimitive?.content)
            assertEquals("user_action",payload["activation_source"]?.jsonPrimitive?.content);assertFalse(payload.containsKey("prompt"))
            buildJsonObject{put("success",true);put("task_id","t");put("session_id","s");put("state","STARTING")}
        },{selected=it})
        model.perform(SessionAction.Start);idle(model);assertEquals(1,calls);assertFalse(model.enabled)
    }
    @Test fun `ended sessions recover through session recover with the chosen action`() = runBlocking {
        for ((operation,action) in listOf(SessionAction.Resume to "resume",SessionAction.FreshStart to "fresh_start")) {
            val selected=AgentSession("s",state="CANCELLED");var seen:JsonObject?=null
            val model=SessionActions(this,{SessionActionContext("t",selected,true)},{selected},{name,payload->
                assertEquals("session.recover",name);seen=payload
                buildJsonObject{put("success",true);put("task_id","t");put("session_id","s")}
            },{})
            model.perform(operation);idle(model)
            assertEquals(action,seen?.get("action")?.jsonPrimitive?.content);assertEquals("t",seen?.get("task_id")?.jsonPrimitive?.content)
        }
    }
    @Test fun `a lost branch offers continuing on a new branch`() = runBlocking {
        val selected=AgentSession("s",state="FAILED");val actions=mutableListOf<String>()
        val model=SessionActions(this,{SessionActionContext("t",selected,true)},{selected},{_,payload->
            val action=payload.getValue("action").jsonPrimitive.content;actions+=action
            if (action=="resume") throw KandevApiException("Kandev rejected the request.","branch_unrecoverable")
            buildJsonObject{put("success",true);put("session_id","s")}
        },{})
        model.perform(SessionAction.Resume);idle(model)
        assertTrue(model.branchLost);assertTrue(model.offers(SessionAction.NewBranch));assertTrue(model.enabled)
        model.perform(SessionAction.NewBranch);idle(model)
        assertEquals(listOf("resume","resume_new_branch"),actions);assertFalse(model.branchLost)
    }
    @Test fun `changed state during preflight prevents a lifecycle mutation`() = runBlocking {
        var writes=0;var selected=AgentSession("s",state="CREATED")
        val model=SessionActions(this,{SessionActionContext("t",selected,true)},{selected.copy(state="RUNNING")},{_,_->writes++;buildJsonObject{}},{selected=it})
        model.perform(SessionAction.Start);idle(model);assertEquals(0,writes);assertTrue(model.offers(SessionAction.Interrupt))
    }
    @Test fun `uncertain stop is not replayed by reconnect or repeated clicks`() = runBlocking {
        var live=true;val selected=AgentSession("s",state="RUNNING");var writes=0
        val model=SessionActions(this,{SessionActionContext("t",selected,live)},{selected},{_,_->writes++;throw IOException("lost acknowledgement")},{})
        model.perform(SessionAction.Interrupt);model.perform(SessionAction.Interrupt);idle(model);live=false;assertFalse(model.enabled)
        live=true;model.perform(SessionAction.Interrupt);delay(50)
        assertEquals(1,writes);assertFalse(model.enabled)
        model.refreshState();idle(model);assertTrue(model.enabled);assertEquals(1,writes)
    }
    @Test fun `queued launch is reported without another launch attempt`() = runBlocking {
        val session=AgentSession("s",state="CANCELLED");var calls=0
        val model=SessionActions(this,{SessionActionContext("t",session,true)},{session},{_,_->calls++;buildJsonObject{
            put("success",true);put("task_id","t");put("session_id","s");put("activation_disposition","queued")
        }},{})
        model.perform(SessionAction.Resume);idle(model);assertEquals("Launch queued by Kandev.",model.notice);assertFalse(model.enabled)
        model.perform(SessionAction.Resume);assertEquals(1,calls)
    }
    @Test fun `ending refreshes to the terminal state and offers resume`() = runBlocking {
        var selected=AgentSession("s",state="WAITING_FOR_INPUT");var stopped=false
        val model=SessionActions(this,{SessionActionContext("t",selected,true)},{if(stopped)selected.copy(state="CANCELLED") else selected},
            {action,_->assertEquals("session.stop",action);stopped=true;buildJsonObject{put("success",true)}},{selected=it})
        model.perform(SessionAction.End);idle(model);assertTrue(model.offers(SessionAction.Resume));assertTrue(model.enabled)
    }
    private suspend fun idle(model:SessionActions) {withTimeout(5000){while(model.busy)delay(5)}}
}
