package ca.mslab.forge.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdatesTest {
    @Test fun directoryResolvesManifestInsideIt() {
        assertEquals("http://192.0.2.10:8897/ca.mslab.forge.json",
            AppUpdates.directory("http://192.0.2.10:8897").resolve("ca.mslab.forge.json").toString())
        assertEquals("https://downloads.example.com/forge/ca.mslab.forge.json",
            AppUpdates.directory(" https://downloads.example.com/forge ").resolve("ca.mslab.forge.json").toString())
        assertEquals("https://downloads.example.com/forge/x.apk",
            AppUpdates.directory("https://downloads.example.com/forge/").resolve("x.apk").toString())
        assertEquals("https://github.com/OWNER/REPO/releases/latest/download/ca.mslab.forge.json",
            AppUpdates.directory("https://github.com/OWNER/REPO/releases/latest/download").resolve("ca.mslab.forge.json").toString())
    }

    @Test fun directoryRejectsBlankAndOddAddresses() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdates.directory("") }
        assertThrows(IllegalArgumentException::class.java) { AppUpdates.directory("ftp://example.com/") }
        assertThrows(IllegalArgumentException::class.java) { AppUpdates.directory("https://example.com/?a=1") }
    }

    @Test fun parsesPublishedManifest() {
        val r = AppUpdates.parse("""{"version_code": 3, "version_name": "0.3.0", "apk": "forge-0.3.0-20261115-143000.apk",
            "sha256": "${"a".repeat(64)}", "size": 1234, "notes": "Theme switcher", "extra": true}""")
        assertEquals(3L, r.versionCode)
        assertEquals("0.3.0", r.versionName)
        assertEquals("Theme switcher", r.notes)
    }

    @Test fun refusesManifestPointingOutsideTheDirectory() {
        for (apk in listOf("../evil.apk", "https://example.com/x.apk", "sub/x.apk", "x.zip")) {
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdates.parse("""{"version_code": 3, "version_name": "0.3.0", "apk": "$apk", "sha256": "${"a".repeat(64)}"}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdates.parse("""{"version_code": 3, "version_name": "0.3.0", "apk": "x.apk", "sha256": "nope"}""")
        }
    }

    @Test fun copyVerifiedChecksHashAndReportsProgress() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        AppUpdates.copyVerified(ByteArrayInputStream(bytes), out, sha.uppercase(), bytes.size.toLong()) { progress += it }
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(1f, progress.last())

        assertThrows(IOException::class.java) {
            AppUpdates.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), "0".repeat(64), bytes.size.toLong()) { }
        }
    }
}
