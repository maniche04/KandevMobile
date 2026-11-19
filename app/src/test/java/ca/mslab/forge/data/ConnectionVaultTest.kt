package ca.mslab.forge.data

import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ConnectionVaultTest {
    private val root = Files.createTempDirectory("forge-vault-test").toFile()
    private val file = root.resolve("profile.bin")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @After fun clean() { root.deleteRecursively() }
    @Test fun `profile is authenticated encrypted and survives a new vault`() = runBlocking {
        val profile = SavedConnection("https://private.example", "secret-token")
        ConnectionVault(file) { key }.save(profile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("secret-token"))
        assertEquals(profile, ConnectionVault(file) { key }.read())
    }
    @Test fun `every save has a fresh nonce and tampering fails closed`() = runBlocking {
        val vault = ConnectionVault(file) { key }
        val profile = SavedConnection("https://server", "token")
        vault.save(profile); val first = file.readBytes()
        vault.save(profile); val second = file.readBytes()
        assertFalse(first.copyOfRange(0,12).contentEquals(second.copyOfRange(0,12)))
        second[second.lastIndex] = (second.last().toInt() xor 1).toByte(); file.writeBytes(second)
        assertTrue(runCatching { vault.read() }.isFailure)
    }
    @Test fun `forget removes the saved credential`() = runBlocking {
        val vault = ConnectionVault(file) { key }; vault.save(SavedConnection("https://server", "token"))
        vault.clear(); assertNull(vault.read())
    }
}
