package ca.mslab.forge.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Light/dark choice, kept on this device; disconnecting from a server doesn't reset it. */
internal class Appearance private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("forge-ui", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System)
    val mode: StateFlow<ThemeMode> = _mode

    fun choose(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit().putString(KEY, mode.name).apply()
    }

    companion object {
        private const val KEY = "theme"
        @Volatile private var instance: Appearance? = null
        fun get(context: Context): Appearance = instance ?: synchronized(this) {
            instance ?: Appearance(context.applicationContext).also { instance = it }
        }
    }
}
