package ec.finanzas.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Solo el secreto envuelto sale de Keystore; el secreto SQLCipher nunca se escribe en claro. */
class DatabaseKeyVault(context: Context, private val name: String = "ledger") {
    init { require(name.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val wrappedFile = AtomicFile(File(context.noBackupFilesDir, "$name.key"))
    private val alias = "ec.finanzas.locales.db.$name.v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun getOrCreate(databaseExists: Boolean): ByteArray {
        if (wrappedFile.baseFile.exists() || java.io.File(wrappedFile.baseFile.path + ".bak").exists()) {
            val wrappingKey = keyStore.getKey(alias, null) as? SecretKey
                ?: throw IllegalStateException("Clave local no disponible; no se restablecen datos automáticamente")
            val payload = wrappedFile.readFully()
            require(payload.size == 61 && payload[0] == 1.toByte()) { "Secreto local inválido" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, payload.copyOfRange(1, 13)))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(payload.copyOfRange(13, payload.size)).also { check(it.size == 32) }
        }
        check(!databaseExists) { "Falta secreto de base existente; no se restablecen datos automáticamente" }
        val wrappingKey = (keyStore.getKey(alias, null) as? SecretKey) ?: createWrappingKey()
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(secret)
        val stream = wrappedFile.startWrite()
        try {
            stream.write(payload)
            wrappedFile.finishWrite(stream)
        } catch (failure: Exception) {
            wrappedFile.failWrite(stream)
            secret.fill(0)
            throw failure
        }
        return secret
    }

    private fun createWrappingKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
    }.generateKey()

    /** Solo después de cerrar y eliminar explícitamente la BD y sus archivos auxiliares. */
    @Synchronized
    fun deleteAfterDatabaseRemoval() {
        wrappedFile.delete()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}

