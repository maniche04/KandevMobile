package ca.mslab.forge.data.routing

import ca.mslab.forge.data.*
import ca.mslab.forge.security.AccessCredential
import ca.mslab.forge.security.accessClient
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.*

@Serializable
internal enum class RouteMode { Automatic, Local, Tailscale, Public }

@Serializable
internal data class DirectRoute(val url: String, val token: String = "")

internal data class ServerRoute(val kind: RouteMode, val url: HttpUrl, val token: String, val access: AccessCredential? = null, val primary: Boolean = false) {
    val label get() = when (kind) { RouteMode.Local -> "Local"; RouteMode.Tailscale -> "Tailscale"; else -> "Cloudflare" }
}

internal fun primaryKind(url: String, access: AccessCredential?): RouteMode {
    if (access != null) return RouteMode.Public
    val host = KandevApi.validateServerUrl(url).host
    return if (host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:") ||
        (host.split('.').let { it.size == 4 && it[0] == "100" && (it[1].toIntOrNull() ?: 0) in 64..127 })) RouteMode.Tailscale else RouteMode.Local
}

internal fun SavedConnection.serverRoutes(): List<ServerRoute> {
    val result = buildList {
        local?.let { add(ServerRoute(RouteMode.Local, KandevApi.validateServerUrl(it.url), it.token)) }
        tailscale?.let { add(ServerRoute(RouteMode.Tailscale, KandevApi.validateServerUrl(it.url), it.token)) }
        add(ServerRoute(primaryKind(url, access), KandevApi.validateServerUrl(url), token, access, primary = true))
    }
    require(result.map { it.url }.distinct().size == result.size) { "Each route must have a different address." }
    result.forEach {
        require(it.token.none { c -> c == '\r' || c == '\n' }) { "Invalid route token." }
        it.access?.validate()
        require(it.access == null || it.url.isHttps) { "Cloudflare Access requires HTTPS." }
    }
    return result.filter { mode == RouteMode.Automatic || it.kind == mode }
}

/** One logical server; route changes never change its database or replay an application request. */
internal class ServerRoutes(
    initial: SavedConnection,
    private val probe: suspend (ServerRoute, String?) -> Boolean = ::probeServerRoute,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<SavedConnection> = _settings
    val config: SavedConnection get() = _settings.value
    private val lock = Mutex()
    init { require(initial.serverRoutes().isNotEmpty()) { "Select a configured connection route." } }
    private var revision = 0L
    private var probing: Deferred<ServerRoute>? = null
    private var recentFailure: Pair<Long, IOException>? = null
    private val failedUntil = mutableMapOf<String, Long>()
    private val _active = MutableStateFlow<ServerRoute?>(null)
    val active: StateFlow<ServerRoute?> = _active

    @Synchronized fun configure(value: SavedConnection) {
        require(value.serverRoutes().isNotEmpty()) { "Select a configured connection route." }
        if (value == config) return
        _settings.value = value; revision++; probing?.cancel(); recentFailure = null; failedUntil.clear(); _active.value = null
    }
    @Synchronized fun networkChanged() { revision++; probing?.cancel(); recentFailure = null; failedUntil.clear(); _active.value = null }
    @Synchronized fun failed(route: ServerRoute?) {
        if (route != null && _active.value == route) {
            failedUntil[route.url.toString()] = clock() + 15_000
            revision++; recentFailure = null; _active.value = null
        }
    }

    suspend fun select(): ServerRoute = lock.withLock {
        while (true) {
            _active.value?.let { return@withLock it }
            synchronized(this) { recentFailure?.takeIf { clock() - it.first < 1_000 }?.let { throw it.second } }
            val (version, saved, candidates) = synchronized(this) {
                val all = config.serverRoutes()
                Triple(revision, config, all.filter { (failedUntil[it.url.toString()] ?: 0) <= clock() }.ifEmpty { all })
            }
            if (candidates.isEmpty()) throw IOException("No connection route is available. Check the network or route settings.")
            // A legacy single-address profile needs no new discovery round trip.
            val result = try {
                if (saved.local == null && saved.tailscale == null) candidates.first()
                else supervisorScope {
                    val attempt = async(start = CoroutineStart.LAZY) { race(candidates, saved.workspaceAnchor) }
                    synchronized(this@ServerRoutes) {
                        if (version != revision) attempt.cancel() else probing = attempt
                    }
                    try { attempt.await() }
                    finally { synchronized(this@ServerRoutes) { if (probing === attempt) probing = null } }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (synchronized(this) { version == revision }) throw e
                continue
            } catch (e: IOException) {
                synchronized(this) { if (version == revision) recentFailure = clock() to e }
                throw e
            }
            synchronized(this) {
                if (version == revision) { _active.value = result; return@withLock result }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun race(routes: List<ServerRoute>, anchor: String?): ServerRoute = coroutineScope {
        val results = Channel<ServerRoute?>(routes.size)
        val jobs = routes.map { route -> launch {
            val works = try { withTimeout(6_000) { probe(route, anchor) } }
                catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else false }
                catch (_: Exception) { false }
            results.send(route.takeIf { works })
        } }
        try {
            repeat(routes.size) { results.receive()?.let { return@coroutineScope it } }
            throw IOException("No saved server route is reachable. Showing saved content; push notifications remain enabled.")
        } finally { jobs.forEach { it.cancel() }; results.close() }
    }

    /** Origin rewriting happens once. Failed requests are never repeated on another route. */
    fun client(logicalUrl: String, base: OkHttpClient): OkHttpClient {
        val logical = KandevApi.validateServerUrl(logicalUrl)
        return base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.scheme == logical.scheme && request.url.host == logical.host && request.url.port == logical.port) {
                    "A server request cannot leave its configured origin."
                }
                val route = runBlocking { select() }
                val target = request.url.newBuilder().scheme(route.url.scheme).host(route.url.host).port(route.url.port).build()
                val routed = request.newBuilder().url(target).removeHeader("Authorization")
                    .removeHeader("CF-Access-Client-Id").removeHeader("CF-Access-Client-Secret").removeHeader("Cookie")
                if (route.token.isNotBlank()) routed.header("Authorization", "Bearer ${route.token.trim()}")
                // Also prevent OkHttp's HTTP-status follow-ups from resending a mutation body.
                if (request.method !in setOf("GET", "HEAD")) request.body?.let { body ->
                    routed.method(request.method, object : RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isDuplex() = body.isDuplex()
                        override fun isOneShot() = true
                        override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
                    })
                }
                route.access?.let {
                    check(target.isHttps)
                    routed.header("CF-Access-Client-Id", it.clientId).header("CF-Access-Client-Secret", it.clientSecret)
                }
                // Optional plugin endpoints may be missing or refused where Kandev itself is healthy.
                val advisory = request.tag(Advisory::class.java) != null
                try {
                    val response = chain.proceed(routed.build())
                    if (!advisory && (response.code in setOf(401, 403, 502, 503, 504) || response.code in 300..399)) failed(route)
                    response
                } catch (e: IOException) { if (!advisory && !chain.call().isCanceled()) failed(route); throw e }
            }.build()
    }
}

internal suspend fun probeServerRoute(route: ServerRoute, anchor: String?): Boolean {
    // A direct alternate is not eligible until the paired server's workspace is known.
    if (anchor == null && !route.primary) return false
    val client = accessClient(route.url.toString(), route.access, KandevApi.defaultClient.newBuilder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build())
    val api = KandevApi(route.url.toString(), route.token, client)
    api.checkVersion()
    return anchor == null || api.listWorkspaces().any { it.id == anchor }
}
