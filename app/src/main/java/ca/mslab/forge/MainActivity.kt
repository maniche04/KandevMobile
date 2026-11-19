package ca.mslab.forge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.mslab.forge.data.sync.DeepLink
import ca.mslab.forge.data.sync.ForgeHub
import ca.mslab.forge.notify.AgentNotifications
import ca.mslab.forge.ui.ForgeApp
import ca.mslab.forge.ui.theme.Appearance
import ca.mslab.forge.ui.theme.ForgeTheme
import ca.mslab.forge.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) route(intent)
        val appearance = Appearance.get(this)
        setContent {
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
            // The default edge-to-edge style follows the system, so an app-level override needs its own bar icons.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x80, 0x1b, 0x19, 0x17))
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            ForgeTheme(darkTheme = dark) { ForgeApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** Notification taps carry the task to open. */
    private fun route(intent: Intent?) {
        val task = intent?.getStringExtra(AgentNotifications.EXTRA_TASK) ?: return
        ForgeHub.get(this).deepLink.value = DeepLink(task, intent.getBooleanExtra(AgentNotifications.EXTRA_REVIEW, false),
            intent.getStringExtra(AgentNotifications.EXTRA_SESSION))
        // Consume it so a configuration change doesn't reopen the task.
        intent.removeExtra(AgentNotifications.EXTRA_TASK)
    }
}
