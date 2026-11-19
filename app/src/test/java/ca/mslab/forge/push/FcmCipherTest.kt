package ca.mslab.forge.push

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FcmCipherTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/fcm-envelope.json")!!.readText()).jsonObject
    private val key = fixture.getValue("key").jsonPrimitive.content
    private val envelope = fixture.getValue("envelope").jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test fun decryptsNodeEnvelope() {
        val alert = Json.parseToJsonElement(FcmCipher.decrypt(key, envelope)).jsonObject
        assertEquals(fixture.getValue("alert").jsonObject.getValue("body"), alert.getValue("body"))
        assertEquals("test", alert.getValue("kind").jsonPrimitive.content)
    }

    @Test fun rejectsTamperedCiphertext() {
        val changed = envelope.toMutableMap()
        changed["ciphertext"] = "AAAA" + changed.getValue("ciphertext").drop(4)
        assertThrows(Exception::class.java) { FcmCipher.decrypt(key, changed) }
    }

    @Test fun rejectsWrongKeyAndVersion() {
        assertThrows(Exception::class.java) { FcmCipher.decrypt(java.util.Base64.getEncoder().encodeToString(ByteArray(32)), envelope) }
        assertThrows(IllegalArgumentException::class.java) { FcmCipher.decrypt(key, envelope + ("version" to "2")) }
    }
}
