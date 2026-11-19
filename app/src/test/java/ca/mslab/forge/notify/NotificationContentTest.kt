package ca.mslab.forge.notify

import ca.mslab.forge.data.ChatMessage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationContentTest {
    @Test fun modelProviderWinsOverRunner() {
        assertEquals(AgentBrand.OpenAI, agentBrand("gpt-6-luna", "opencode-acp"))
        assertEquals(AgentBrand.Gemini, agentBrand("gemini-3.8-flash-high", "antigravity-acp"))
        assertEquals(AgentBrand.Claude, agentBrand("opus[1m]", "claude-acp"))
        assertEquals(AgentBrand.OpenAI, agentBrand("openai/o3", "pi-acp"))
    }

    @Test fun unknownModelsUseRunnerOrNeutralAvatar() {
        assertEquals(AgentBrand.OpenAI, agentBrand(null, "codex-acp"))
        assertEquals(AgentBrand.Pi, agentBrand("commandcode/deepseek/v4", "pi-acp"))
        assertEquals(AgentBrand.Unknown, agentBrand(null, null))
    }

    @Test fun liveAndRelayRequestPreviewsMatchSharedFixtures() {
        val json = Json { ignoreUnknownKeys = true }
        val fixtures = json.parseToJsonElement(javaClass.getResource("/notification-previews.json")!!.readText()).jsonArray
        for (fixture in fixtures) {
            val row = fixture.jsonObject
            val message = row["message"]?.takeIf { it != JsonNull }?.jsonObject?.let {
                val full = JsonObject(it + mapOf("id" to JsonPrimitive("test"), "session_id" to JsonPrimitive("test"),
                    "author_type" to JsonPrimitive("agent"), "content" to JsonPrimitive(""), "created_at" to JsonPrimitive("2026-10-31T00:00:00Z")))
                json.decodeFromJsonElement(ChatMessage.serializer(), full)
            }
            assertEquals(row.getValue("expected").jsonPrimitive.content,
                requestText(row.getValue("pending").jsonPrimitive.content, message))
        }
    }
}
