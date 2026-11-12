package dev.pi.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The LAN PIN is the key to a desktop Pi instance that can run code, so it is
 * stored as AES-GCM ciphertext under an Android Keystore key that never leaves
 * the device. Plaintext exists only in memory while a connection opens, and the
 * session cookie is never persisted at all.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("pi_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }.getOrNull()

    fun putPin(profileId: String, pin: String): Boolean = runCatching {
        val secretKey = key() ?: return false
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(payload, 0)
        ciphertext.copyInto(payload, iv.size)
        prefs.edit()
            .putString(keyFor(profileId), Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    fun getPin(profileId: String): String? = runCatching {
        val encoded = prefs.getString(keyFor(profileId), null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= IV_SIZE) return null
        val iv = payload.copyOfRange(0, IV_SIZE)
        val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
        val secretKey = key() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    fun hasPin(profileId: String): Boolean = prefs.contains(keyFor(profileId))

    fun removePin(profileId: String) {
        prefs.edit().remove(keyFor(profileId)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(profileId: String) = "pin_$profileId"

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pi_mobile_pin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
