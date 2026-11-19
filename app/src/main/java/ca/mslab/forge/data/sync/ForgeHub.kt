package ca.mslab.forge.data.sync

import android.app.Application
import android.content.Context
import ca.mslab.forge.data.*
import ca.mslab.forge.data.db.ForgeDatabase
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Where a notification tap should land: the task's chat, or straight into its pending request. */
internal data class DeepLink(val taskId: String, val review: Boolean = false, val sessionId: String? = null)

/** Everything that must outlive a screen: profile, database, the live link and background sync. */
internal class ForgeHub(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val vault = ConnectionVault(File(app.filesDir, "server-profile.bin"))
    val link = LiveLink(scope)
    private val prefs = app.getSharedPreferences("forge", Context.MODE_PRIVATE)

    class Profile(val routes: ca.mslab.forge.data.routing.ServerRoutes, val identity: String, val api: KandevApi,
        val store: ForgeStore, val snapshots: SnapshotStore, internal val database: ForgeDatabase, internal val name: String)

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile
    val workspaceId = MutableStateFlow(prefs.getString("workspace", null))
    /** The task on screen while the app is visible; its notifications are suppressed. */
    val visibleTask = MutableStateFlow<String?>(null)
    /** Task to open from a notification tap. */
    val deepLink = MutableStateFlow<DeepLink?>(null)

    private var foreground = false
    private var monitoring = false
    private var jobs: Job? = null

    init {
        app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = networkAvailable()
            })
    }

    /** Opens the saved profile without touching the network. */
    suspend fun restore(): Profile? {
        _profile.value?.let { return it }
        val saved = runCatching { vault.read() }.getOrNull() ?: return null
        return runCatching { attach(saved) }.getOrNull()
    }

    @Synchronized
    fun attach(saved: SavedConnection): Profile {
        val canonical = KandevApi.validateServerUrl(saved.url).toString()
        val identity = saved.profileIdentity()
        _profile.value?.takeIf { it.identity == identity }?.let { it.routes.configure(saved); return it }
        _profile.value?.let { close(it) }
        val routes = ca.mslab.forge.data.routing.ServerRoutes(saved)
        val api = KandevApi(canonical, "", routes.client(canonical, KandevApi.defaultClient)).apply { this.routes = routes }
        val name = "forge-" + digest("v1:$identity").take(24) + ".db"
        val database = ForgeDatabase.open(app, name)
        val profile = Profile(routes, identity, api, ForgeStore(scope, database, api),
            SnapshotStore(File(app.filesDir, "snapshots"), identity), database, name)
        _profile.value = profile
        wire(profile)
        updateLink()
        return profile
    }

    fun selectWorkspace(id: String) {
        if (workspaceId.value == id) return
        workspaceId.value = id
        prefs.edit().putString("workspace", id).apply()
    }

    fun setForeground(value: Boolean) {
        val returning = value && !foreground
        foreground = value; updateLink()
        // Back in the app with the link still up: events kept the database current, so only revalidate
        // after a longer absence (not after a file picker or a quick app switch).
        if (returning && link.state.value == LinkState.Live && System.currentTimeMillis() - lastRevalidate > 60_000) revalidate()
    }

    @Volatile private var lastRevalidate = 0L
    @Volatile private var lastTrim = 0L

    /** Full refresh of the selected workspace plus prefetch. */
    fun revalidate() {
        val profile = _profile.value ?: return
        val workspace = workspaceId.value ?: return
        lastRevalidate = System.currentTimeMillis()
        scope.launch {
            runCatching { profile.store.refreshWorkspace(workspace) }
            runCatching { profile.store.prefetch(workspace) }
        }
    }
    fun setMonitoring(value: Boolean) { monitoring = value; updateLink() }
    fun routesChanged() {
        link.stop(); updateLink()
        scope.launch { runCatching { _profile.value?.routes?.select() }; link.wakeUp() }
    }

    fun networkAvailable() {
        _profile.value?.routes?.let { routes ->
            routes.networkChanged()
            scope.launch { runCatching { routes.select() }; link.wakeUp() }
        }
    }

    private var linger: Job? = null

    /**
     * Brief trips away (file picker, share sheet, a quick app switch) keep the socket: it is only
     * closed after two minutes in the background with no agent to monitor.
     */
    @Synchronized
    private fun updateLink() {
        val profile = _profile.value
        linger?.cancel(); linger = null
        when {
            profile == null -> link.stop()
            foreground || monitoring -> link.start(profile.api)
            else -> linger = scope.launch { delay(120_000); if (!foreground && !monitoring) link.stop() }
        }
    }

    private fun wire(profile: Profile) {
        jobs?.cancel()
        jobs = scope.launch {
            launch {
                var previous: ca.mslab.forge.data.routing.ServerRoute? = null
                profile.routes.active.collect { route ->
                    if (route != null) {
                        if (previous != null && previous != route) { link.stop(); updateLink() }
                        previous = route
                    } else if (previous != null) {
                        // Find an alternative for future requests; never repeat the failed request.
                        scope.launch { runCatching { profile.routes.select() } }
                    }
                }
            }
            launch { link.events.collect { runCatching { profile.store.apply(it) } } }
            // Events are only missed while the link is down (a dropped socket always reconnects), so every
            // reconnect revalidates. A brief drop costs one task-list read; a longer absence refreshes all.
            launch {
                link.connected.collect { downFor ->
                    val workspace = workspaceId.value ?: return@collect
                    val now = System.currentTimeMillis()
                    val brief = downFor < 30_000 && now - lastRevalidate < 10 * 60_000
                    if (brief) { runCatching { profile.store.refreshWorkspace(workspace, structure = false) }; return@collect }
                    lastRevalidate = now
                    runCatching { profile.store.refreshWorkspace(workspace) }
                    runCatching { profile.store.prefetch(workspace) }
                    if (now - lastTrim > 6 * 3600_000L) { lastTrim = now; runCatching { profile.store.trim() } }
                }
            }
            // Stream live activity for running sessions only.
            launch {
                profile.database.dao().activeTasks().map { rows ->
                    rows.filter { it.running }.mapNotNull { it.toTask()?.primarySessionId }.toSet()
                }.distinctUntilChanged().collect(link::watchSessions)
            }
        }
    }

    private fun close(profile: Profile) {
        jobs?.cancel(); jobs = null
        linger?.cancel(); linger = null
        link.stop()
        profile.database.close()
    }

    /** Disconnect: forget the profile and every byte cached for it. */
    suspend fun forget() {
        val profile = _profile.value
        _profile.value = null
        ca.mslab.forge.notify.AgentMonitorService.stop(app)
        app.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        profile?.let { close(it); it.snapshots.clear(); app.deleteDatabase(it.name) }
        workspaceId.value = null
        prefs.edit().clear().apply()
        vault.clear()
    }

    companion object {
        @Volatile private var instance: ForgeHub? = null
        fun get(context: Context): ForgeHub = instance ?: synchronized(this) {
            instance ?: ForgeHub(context.applicationContext as Application).also { instance = it }
        }
    }
}
