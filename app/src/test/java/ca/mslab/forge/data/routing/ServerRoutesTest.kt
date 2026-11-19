package ca.mslab.forge.data.routing

import ca.mslab.forge.data.*
import ca.mslab.forge.security.AccessCredential
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRoutesTest {
    private val access = AccessCredential("phone.access", "s".repeat(64))
    private val saved = SavedConnection("https://forge.example", "p".repeat(43), access,
        local = DirectRoute("http://192.168.1.10:38429"),
        tailscale = DirectRoute("http://100.90.80.70:38429", "private-tail-token"), workspaceAnchor = "same-workspace")

    @Test fun `stalled local route never delays a healthy public route and losers are cancelled`() = runTest {
        var cancelled = 0
        val routes = ServerRoutes(saved, { route, _ ->
            if (route.kind == RouteMode.Public) { delay(25); true }
            else try { awaitCancellation() } finally { cancelled++ }
        })
        assertEquals(RouteMode.Public, routes.select().kind)
        assertEquals(25L, currentTime)
        assertEquals(2, cancelled)
    }
    @Test fun `direct route wins when it is fastest and selection is reused`() = runTest {
        var probes = 0
        val routes = ServerRoutes(saved, { route, anchor ->
            probes++; assertEquals("same-workspace", anchor)
            delay(if (route.kind == RouteMode.Local) 5 else 50); true
        })
        repeat(10) { assertEquals(RouteMode.Local, routes.select().kind) }
        assertEquals(3, probes)
        assertEquals(5L, currentTime)
    }
    @Test fun `simultaneous reads and sockets share a single route race`() = runTest {
        var probes = 0
        val routes = ServerRoutes(saved, { _, _ -> probes++; delay(20); true })
        val selected = (1..8).map { async { routes.select() } }.awaitAll()
        assertEquals(3, probes)
        assertEquals(1, selected.distinct().size)
    }
    @Test fun `simultaneous offline requests share a failure instead of serial timeout batches`() = runTest {
        var probes = 0
        val routes = ServerRoutes(saved, { _, _ -> probes++; delay(20); false }, { currentTime })
        val failures = (1..8).map { async { runCatching { routes.select() }.exceptionOrNull() } }.awaitAll()
        assertTrue(failures.all { it is IOException })
        assertEquals(3, probes)
        assertEquals(20L, currentTime)
    }
    @Test fun `network change cancels obsolete checks and starts fresh immediately`() = runTest {
        var online = false
        val routes = ServerRoutes(saved, { route, _ ->
            if (!online) awaitCancellation()
            delay(10); route.kind == RouteMode.Public
        })
        val pending = async { routes.select() }
        runCurrent()
        online = true; routes.networkChanged()
        assertEquals(RouteMode.Public, pending.await().kind)
        assertEquals(10L, currentTime)
    }
    @Test fun `failed route is skipped for next selection and network changes reset its cooldown`() = runTest {
        val routes = ServerRoutes(saved, { route, _ -> delay(if (route.kind == RouteMode.Local) 1 else 2); true }, { currentTime })
        val local = routes.select(); routes.failed(local)
        assertNotEquals(RouteMode.Local, routes.select().kind)
        routes.networkChanged()
        assertEquals(RouteMode.Local, routes.select().kind)
    }
    @Test fun `all unreachable routes return offline without an endless probe loop`() = runTest {
        val routes = ServerRoutes(saved, { _, _ -> delay(10); false })
        assertTrue(runCatching { routes.select() }.exceptionOrNull() is IOException)
        assertNull(routes.active.value)
        assertEquals(10L, currentTime)
    }
    @Test fun `manual mode probes only its selected route`() = runTest {
        val seen = mutableListOf<RouteMode>()
        val routes = ServerRoutes(saved.copy(mode = RouteMode.Tailscale), { route, _ -> seen += route.kind; true })
        assertEquals(RouteMode.Tailscale, routes.select().kind)
        assertEquals(listOf(RouteMode.Tailscale), seen)
    }
    @Test fun `legacy profile is compatible and route settings never change cache identity`() = runTest {
        val old = Json.decodeFromString<SavedConnection>("""{"url":"http://192.168.1.10:38429","token":""}""")
        val routes = ServerRoutes(old, { _, _ -> error("Legacy profiles need no discovery") })
        assertEquals(RouteMode.Local, routes.select().kind)
        assertEquals(saved.profileIdentity(), saved.copy(local = null, tailscale = null, mode = RouteMode.Public).profileIdentity())
        assertEquals(old.profileIdentity(), saved.copy(cacheIdentity = old.profileIdentity()).profileIdentity())
        val roundTrip = Json.decodeFromString<SavedConnection>(Json.encodeToString(SavedConnection.serializer(), saved))
        assertEquals(saved, roundTrip)
    }
    @Test fun `settings updates publish even when the selected address stays the same`() = runTest {
        val routes = ServerRoutes(saved, { route, _ -> route.kind == RouteMode.Local })
        val local = routes.select()
        routes.configure(saved.copy(mode = RouteMode.Local))
        assertEquals(RouteMode.Local, routes.settings.value.mode)
        assertEquals(local, routes.select())
        routes.configure(saved)
        assertEquals(RouteMode.Automatic, routes.settings.value.mode)
        assertEquals(local, routes.select())
    }
    @Test fun `missing manual routes and duplicate origins are rejected`() {
        assertTrue(runCatching { ServerRoutes(saved.copy(tailscale = null, mode = RouteMode.Tailscale)) }.isFailure)
        assertTrue(runCatching { saved.copy(local = DirectRoute(saved.url)).serverRoutes() }.isFailure)
        assertTrue(runCatching { ServerRoutes(saved.copy(token = "bad\r\nHeader")) }.isFailure)
    }
    @Test fun `direct probe rejects a different workspace even with the correct Kandev version`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"v0.98.0","commit":"same-build"}"""))
            server.enqueue(MockResponse().setBody("""{"workspaces":[{"id":"other-server","name":"Other"}],"total":1}"""))
            server.start()
            assertFalse(probeServerRoute(ServerRoute(RouteMode.Local, server.url("/"), ""), "paired-workspace"))
        }
    }
    @Test fun `private route receives its token and never receives public credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("ok"))
            val config = saved.copy(local = DirectRoute(server.url("/").toString(), "local-only-token"), mode = RouteMode.Local)
            val routes = ServerRoutes(config, { _, _ -> true })
            val client = routes.client(saved.url, OkHttpClient())
            client.newCall(Request.Builder().url(saved.url + "/api/v1/files?q=value")
                .header("Authorization", "Bearer ${saved.token}").header("CF-Access-Client-Secret", access.clientSecret).build()).execute().close()
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/files?q=value", request.path)
            assertEquals("Bearer local-only-token", request.getHeader("Authorization"))
            assertNull(request.getHeader("CF-Access-Client-Secret"))
            assertNull(request.getHeader("CF-Access-Client-Id"))
        }
    }
    @Test fun `a failed write is never replayed on the fallback route`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            val config = saved.copy(local = DirectRoute(server.url("/").toString()), mode = RouteMode.Local)
            val routes = ServerRoutes(config, { _, _ -> true })
            val client = routes.client(saved.url, OkHttpClient())
            client.newCall(Request.Builder().url(saved.url + "/api/v1/tasks").post("write-once".toRequestBody()).build()).execute().use {
                assertEquals(503, it.code)
            }
            assertEquals(1, server.requestCount)
            assertFalse(client.retryOnConnectionFailure)
            assertNull(routes.active.value)
        }
    }
    @Test fun `a refused or missing plugin read never demotes a healthy route`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            listOf(401, 403, 404, 502).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
            val config = saved.copy(local = DirectRoute(server.url("/").toString()), mode = RouteMode.Local)
            val routes = ServerRoutes(config, { _, _ -> true })
            val api = KandevApi(saved.url, "", routes.client(saved.url, OkHttpClient()))
            repeat(4) { assertThrows(UsageUnavailable::class.java) { runBlocking { api.providerUsage(refresh = false) } } }
            assertEquals(RouteMode.Local, routes.active.value?.kind)
            assertEquals("/api/plugins/kandev-provider-usage/webhooks/providers", server.takeRequest().path)
        }
    }
    @Test fun `cancelling a screen read does not blacklist a healthy route`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val config = saved.copy(local = DirectRoute(server.url("/").toString()), mode = RouteMode.Local)
            val routes = ServerRoutes(config, { _, _ -> true })
            val client = routes.client(saved.url, OkHttpClient())
            val finished = CompletableDeferred<Unit>()
            val call = client.newCall(Request.Builder().url(saved.url + "/api/v1/tasks").build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { finished.complete(Unit) }
                override fun onResponse(call: Call, response: Response) { response.close(); finished.complete(Unit) }
            })
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            call.cancel(); withTimeout(3000) { finished.await() }
            assertEquals(RouteMode.Local, routes.active.value?.kind)
        }
    }
    @Test fun `event and terminal websocket handshakes share the route and credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})) }
            val config = saved.copy(local = DirectRoute(server.url("/").toString()), mode = RouteMode.Local)
            val routes = ServerRoutes(config, { _, _ -> true })
            val client = routes.client(saved.url, OkHttpClient())
            for (path in listOf("/ws", "/terminal/environment/env?terminalId=term")) {
                val ready = CompletableDeferred<Unit>()
                val ws = client.newWebSocket(Request.Builder().url(saved.url + path).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ready.completeExceptionally(t) }
                })
                withTimeout(3000) { ready.await() }; ws.cancel()
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(path, request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("CF-Access-Client-Secret"))
            }
        }
    }
}
