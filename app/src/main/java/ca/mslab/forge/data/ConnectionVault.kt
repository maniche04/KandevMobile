package ca.mslab.forge.data

import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedConnection(val url: String, val token: String, val access: ca.mslab.forge.security.AccessCredential? = null,
    val local: ca.mslab.forge.data.routing.DirectRoute? = null,
    val tailscale: ca.mslab.forge.data.routing.DirectRoute? = null,
    val mode: ca.mslab.forge.data.routing.RouteMode = ca.mslab.forge.data.routing.RouteMode.Automatic,
    val workspaceAnchor: String? = null,
    val cacheIdentity: String? = null,
)

internal fun SavedConnection.profileIdentity(): String = cacheIdentity ?: (
    "${KandevApi.validateServerUrl(url)}|${digest(token)}" +
        if (access == null) "" else "|${digest(access.clientId + access.clientSecret)}")

/** Only the server profile is encrypted here; snapshots stay in private app storage. */
internal class ConnectionVault(private val file: File, private val key: () -> SecretKey = ::androidKey) {
    private val lock = Mutex()
    suspend fun read(): SavedConnection? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.isFile) return@withLock null
            val bytes = file.readBytes()
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Json.decodeFromString<SavedConnection>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        }
    }
    suspend fun save(profile: SavedConnection) = withContext(Dispatchers.IO) {
        lock.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            atomicWrite(file, cipher.iv + cipher.doFinal(Json.encodeToString(SavedConnection.serializer(), profile).toByteArray()))
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { file.delete(); Unit } }
}

private fun androidKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val alias = "forge.server-profile.v1"
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
