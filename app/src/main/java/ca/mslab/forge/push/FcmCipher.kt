package ca.mslab.forge.push

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM envelope shared with relay/fcm.mjs; ciphertext includes the 16-byte tag. */
internal object FcmCipher {
    fun decrypt(key: String, data: Map<String, String>): String {
        require(data["version"] == "1") { "Unsupported push version" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(requireNotNull(data["iv"]))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decoder.decode(key), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(decoder.decode(requireNotNull(data["ciphertext"]))).toString(Charsets.UTF_8)
    }
}
