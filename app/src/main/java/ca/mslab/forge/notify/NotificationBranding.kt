package ca.mslab.forge.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import ca.mslab.forge.R
import ca.mslab.forge.data.LaunchOptions
import java.util.concurrent.ConcurrentHashMap

/** Bundled provider marks are available for cold-start push, without contacting Kandev. */
internal object NotificationBranding {
    private val icons = ConcurrentHashMap<String, Icon>()

    fun remember(context: Context, options: LaunchOptions) {
        val edit = context.getSharedPreferences("notification-agents", Context.MODE_PRIVATE).edit()
        options.agents.forEach { agent -> agent.profiles.orEmpty().forEach { profile ->
            edit.putString("${profile.id}.model", profile.model).putString("${profile.id}.agent", agent.name)
        } }
        edit.apply()
    }

    fun resolve(context: Context, profileId: String?, model: String? = null, agentType: String? = null): AgentBrand {
        val saved = context.getSharedPreferences("notification-agents", Context.MODE_PRIVATE)
        return agentBrand(model ?: profileId?.let { saved.getString("$it.model", null) },
            agentType ?: profileId?.let { saved.getString("$it.agent", null) })
    }

    fun icon(context: Context, brand: AgentBrand, name: String?): Icon = icons.getOrPut("$brand:${if (brand == AgentBrand.Unknown) name else ""}") {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 248, 243) }
        canvas.drawCircle(48f, 48f, 48f, paint)
        val resource = when (brand) {
            AgentBrand.OpenAI -> R.drawable.ic_provider_openai
            AgentBrand.Claude -> R.drawable.ic_provider_claude
            AgentBrand.Gemini -> R.drawable.ic_provider_gemini
            AgentBrand.OpenCode -> R.drawable.ic_provider_opencode
            AgentBrand.Pi -> R.drawable.ic_provider_pi
            AgentBrand.Antigravity -> R.drawable.ic_provider_antigravity
            AgentBrand.Unknown -> null
        }
        val drawable = resource?.let { runCatching { context.getDrawable(it) }.getOrNull() }
        if (drawable != null) {
            val scale = 58f / maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight)
            val width = (drawable.intrinsicWidth * scale).toInt()
            val height = (drawable.intrinsicHeight * scale).toInt()
            drawable.setBounds((96 - width) / 2, (96 - height) / 2, (96 + width) / 2, (96 + height) / 2)
            drawable.draw(canvas)
        } else {
            paint.color = Color.rgb(55, 45, 40); paint.textSize = 42f; paint.textAlign = Paint.Align.CENTER
            val letter = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            canvas.drawText(letter, 48f, 48f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        Icon.createWithBitmap(bitmap)
    }
}
