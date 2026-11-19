package ca.mslab.forge.security

import ca.mslab.forge.data.KandevApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Stored alongside the server token in ConnectionVault, encrypted with Android Keystore. */
@Serializable
internal data class AccessCredential(val clientId: String, val clientSecret: String) {
    fun validate() {
        require(clientId.matches(Regex("[a-zA-Z0-9._-]{10,256}")) && clientSecret.matches(Regex("[a-zA-Z0-9_-]{32,256}"))) {
            "Invalid Cloudflare Access credential."
        }
    }
}

@Serializable
internal data class RemoteEnrollment(val server: String, val token: String, val access: AccessCredential,
    val local: ca.mslab.forge.data.routing.DirectRoute? = null,
    val tailscale: ca.mslab.forge.data.routing.DirectRoute? = null,
    val workspaceAnchor: String? = null,
) {
    fun validate(): RemoteEnrollment {
        val origin = KandevApi.validateServerUrl(server)
        require(origin.isHttps) { "Remote access requires HTTPS." }
        require(token.matches(Regex("[a-zA-Z0-9_-]{43,256}"))) { "Invalid server connection token." }
        access.validate()
        listOfNotNull(local, tailscale).forEach {
            require(KandevApi.validateServerUrl(it.url) != origin) { "Each route must have a different address." }
            require(it.token.none { c -> c == '\r' || c == '\n' }) { "Invalid direct-route token." }
        }
        return copy(server = origin.toString())
    }
    companion object {
        fun parse(value: String): RemoteEnrollment {
            require(value.length <= 65536) { "Connection file is too large." }
            return Json.decodeFromString<RemoteEnrollment>(value).validate()
        }
    }
}

/** The same client secures REST, file transfer, event sockets, and terminal sockets. */
internal fun accessClient(server: String, credential: AccessCredential?, base: OkHttpClient): OkHttpClient {
    if (credential == null) return base
    credential.validate()
    val origin = KandevApi.validateServerUrl(server)
    require(origin.isHttps) { "Cloudflare Access credentials require HTTPS." }
    return base.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor { call ->
        val target = call.request().url
        check(target.isHttps && target.host == origin.host && target.port == origin.port) {
            "Access credentials cannot leave the approved server."
        }
        val response = call.proceed(call.request().newBuilder()
            .header("CF-Access-Client-Id", credential.clientId)
            .header("CF-Access-Client-Secret", credential.clientSecret).build())
        if (response.code in setOf(401, 403) || response.code in 300..399) {
            response.close()
            throw ca.mslab.forge.data.KandevApiException("Remote access was denied. Import a renewed Forge connection file in Settings.")
        }
        response
    }.build()
}
