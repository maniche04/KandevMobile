package ca.mslab.forge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import ca.mslab.forge.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updates from a download directory: `scripts/publish-apk.sh` puts the signed APK and `<applicationId>.json` there,
 * either on the operator's own server or as assets of a GitHub release (read through `…/releases/latest/download/`,
 * which redirects to the newest release). The phone reads the manifest, streams the APK, checks its SHA-256 and hands
 * it to Android's installer, which also refuses an APK signed by anyone else. One manifest per application id, so a
 * debug build never offers the release APK. The address comes from the build (`FORGE_UPDATE_URL`,
 * `~/.forge/update.properties`) or Settings.
 */
internal class AppUpdates(private val context: Context, private val client: OkHttpClient = updateClient) {
    @Serializable
    data class Release(
        @SerialName("version_code") val versionCode: Long,
        @SerialName("version_name") val versionName: String,
        val apk: String,
        val sha256: String,
        val size: Long = 0,
        val notes: String = "",
    )

    private val prefs = context.getSharedPreferences("forge-ui", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString("update_url", null) ?: BuildConfig.UPDATE_URL
        set(value) { prefs.edit().putString("update_url", value.trim()).apply() }

    val installedCode: Long
        get() = runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }
            .getOrDefault(0)

    /** The newer release in the download directory, or null when this phone is current. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val manifest = directory(address).resolve("${context.packageName}.json")!!
        val body = client.newCall(Request.Builder().url(manifest).build()).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("The update server answered HTTP ${response.code}")
            response.body!!.string()
        }
        parse(body).takeIf { it.versionCode > installedCode }
    }

    /** Streams the APK into the app's cache and checks it; a mismatch deletes it and fails. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, release.apk)
        val url = directory(address).resolve(release.apk)!!
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The update server answered HTTP ${response.code}")
                val body = response.body!!
                val total = release.size.takeIf { it > 0 } ?: body.contentLength()
                file.outputStream().use { out -> copyVerified(body.byteStream(), out, release.sha256, total, onProgress) }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        file
    }

    /** Android asks once whether Forge may install apps; until then this opens that switch. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the verified APK to the system installer (it shows its own confirmation; same signature required). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".attachments", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")
        private val json = Json { ignoreUnknownKeys = true }

        private val updateClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** The download directory as a URL that relative names resolve inside. */
        fun directory(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull()
            require(url != null && url.scheme in setOf("http", "https")) { "Set an update address, such as https://downloads.example.com/forge/" }
            require(url.query == null && url.fragment == null) { "Use the download directory without a query or fragment" }
            return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        }

        /** A manifest naming anything but a plain APK file in the same directory is refused. */
        fun parse(body: String): Release {
            val release = json.decodeFromString(Release.serializer(), body)
            require(SAFE_NAME.matches(release.apk)) { "The update manifest names an unexpected file" }
            require(release.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "The update manifest has no valid checksum" }
            return release
        }

        /** Copies [input] while hashing it; throws unless the SHA-256 matches [sha256]. */
        fun copyVerified(input: InputStream, out: OutputStream, sha256: String, total: Long, onProgress: (Float) -> Unit) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            var shown = -1
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                read += n
                if (total > 0) {
                    val percent = (read * 100 / total).toInt().coerceAtMost(100)
                    if (percent != shown) { shown = percent; onProgress(percent / 100f) }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) throw IOException("The download didn't match its checksum, so it wasn't installed")
        }
    }
}
