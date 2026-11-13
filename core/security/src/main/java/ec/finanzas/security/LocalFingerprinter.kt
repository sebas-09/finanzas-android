package ec.finanzas.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Clave no exportable independiente de la BD; componentes prefijados por longitud evitan colisiones de unión. */
class LocalFingerprinter {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
    }
    fun hash(vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
    private companion object { const val ALIAS = "ec.finanzas.locales.fingerprint.v1" }
}
