package ca.mslab.forge.notify

import ca.mslab.forge.data.ChatMessage

/** Model identity takes precedence over the runner (for example Gemini inside Antigravity). */
internal enum class AgentBrand { OpenAI, Claude, Gemini, OpenCode, Pi, Antigravity, Unknown }

internal fun agentBrand(model: String?, agentType: String?): AgentBrand {
    val value = model.orEmpty().lowercase()
    return when {
        value.contains("gpt-") || value.startsWith("openai/") || Regex("(^|/)(o1|o3|o4)(-|$)").containsMatchIn(value) -> AgentBrand.OpenAI
        listOf("claude", "sonnet", "opus", "haiku").any(value::contains) -> AgentBrand.Claude
        value.contains("gemini") -> AgentBrand.Gemini
        value.startsWith("opencode/") -> AgentBrand.OpenCode
        else -> when {
            agentType.orEmpty().startsWith("codex") -> AgentBrand.OpenAI
            agentType.orEmpty().startsWith("claude") -> AgentBrand.Claude
            agentType.orEmpty().startsWith("gemini") -> AgentBrand.Gemini
            agentType.orEmpty().startsWith("opencode") -> AgentBrand.OpenCode
            agentType.orEmpty().startsWith("pi-") -> AgentBrand.Pi
            agentType.orEmpty().startsWith("antigravity") -> AgentBrand.Antigravity
            else -> AgentBrand.Unknown
        }
    }
}

/** What exactly is waiting: the command or path for approvals, the question text for questions. */
internal fun requestText(pending: String?, message: ChatMessage?): String {
    val details = message?.metadata?.actionDetails
    return when {
        message?.type == "clarification_request" -> message.metadata?.question?.prompt?.let { "Question: $it" } ?: "A question is waiting for you"
        details?.command != null -> "Approve running:\n${details.command}"
        details?.path != null -> "Approve access to:\n${details.path}"
        pending == "permission" -> "An action needs your approval"
        else -> "A question is waiting for you"
    }
}

