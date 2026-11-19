package ca.mslab.forge.security

import ca.mslab.forge.data.ConnectionVault
import ca.mslab.forge.data.KandevApi
import ca.mslab.forge.data.SavedConnection
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class RemoteAccessTest {
    private val access = AccessCredential("device.access", "a".repeat(64))
    @Test fun `old profiles still decode without access credentials`() {
        val profile = Json.decodeFromString<SavedConnection>("""{"url":"http://192.168.1.2:38429","token":""}""")
        assertNull(profile.access)
    }
    @Test fun `all credentials survive encrypted storage without plaintext secrets`() = runBlocking {
        val root = Files.createTempDirectory("forge-access").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val file = root.resolve("connection.bin")
            val profile = SavedConnection("https://forge.example", "b".repeat(43), access)
            ConnectionVault(file) { key }.save(profile)
            assertEquals(profile, ConnectionVault(file) { key }.read())
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            for (secret in listOf(access.clientId, access.clientSecret, profile.token)) assertFalse(bytes.contains(secret))
        } finally { root.deleteRecursively() }
    }
    @Test fun `remote import rejects insecure origins and injected credentials`() {
        val valid = RemoteEnrollment("https://forge.example", "b".repeat(43), access)
        assertEquals("https://forge.example/", valid.validate().server)
        assertTrue(runCatching { valid.copy(server = "http://192.168.1.2").validate() }.isFailure)
        assertTrue(runCatching { valid.copy(access = access.copy(clientSecret = "bad\r\nHeader")).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(server = "https://forge.example/path").validate() }.isFailure)
    }
    @Test fun `access client blocks cross origin and downgrade before network`() {
        val client = accessClient("https://forge.example", access, OkHttpClient())
        for (target in listOf("http://forge.example", "https://other.example", "https://forge.example:444")) {
            val result = runCatching { client.newCall(Request.Builder().url(target).build()).execute() }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
    @Test fun `access credentials are attached to approved origin requests`() {
        // End the chain before network I/O to observe the request passed by the origin guard.
        val client = accessClient("https://forge.example", access, OkHttpClient()).newBuilder().addInterceptor { call ->
            assertEquals(access.clientId, call.request().header("CF-Access-Client-Id"))
            assertEquals(access.clientSecret, call.request().header("CF-Access-Client-Secret"))
            assertEquals("Bearer app-token", call.request().header("Authorization"))
            okhttp3.Response.Builder().request(call.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, "ok")).build()
        }.build()
        client.newCall(Request.Builder().url("https://forge.example/api/v1/system/info").header("Authorization", "Bearer app-token").build()).execute().use {
            assertEquals(200, it.code)
        }
    }
}
