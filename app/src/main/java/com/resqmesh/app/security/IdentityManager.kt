package com.resqmesh.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.resqmesh.core.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the device identity. The P-256 private key is wrapped with an AES-256-GCM key that lives in the
 * Android Keystore (never exported), so the key bytes at rest in SharedPreferences are not usable on their own.
 * The same wrapping is used for group keys. No secret is hardcoded.
 */
class IdentityManager(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val alias = "resqmesh_wrap_v1"

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val ct = c.doFinal(plain)
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun unwrap(s: String): ByteArray {
        val (iv, ct) = s.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv)) }
        return c.doFinal(ct)
    }

    /** Creates the identity on first call (random persistent device id derived from the new public key). */
    @Synchronized
    fun identity(): Identity {
        val pub = prefs.getString("pub", null)
        val priv = prefs.getString("priv", null)
        if (pub != null && priv != null) {
            val restored = runCatching { Identity.restore(Base64.decode(pub, Base64.NO_WRAP), unwrap(priv)) }.getOrNull()
            if (restored != null) return restored
        }
        val id = Identity.generate()
        prefs.edit().putString("pub", Base64.encodeToString(id.publicKeyBytes, Base64.NO_WRAP))
            .putString("priv", wrap(id.privateKeyBytes())).putLong("createdAt", System.currentTimeMillis()).apply()
        return id
    }

    var displayName: String?
        get() = prefs.getString("name", null)
        set(v) { prefs.edit().putString("name", v?.filter { it != '|' && !it.isISOControl() }?.trim()?.take(32)).apply() }
    val createdAt: Long get() = prefs.getLong("createdAt", 0)

    var autoJoin: Boolean
        get() = prefs.getBoolean("autoJoin", false)
        set(v) { prefs.edit().putBoolean("autoJoin", v).apply() }
    var retentionDays: Int
        get() = prefs.getInt("retentionDays", 14).coerceIn(8, 90) // must exceed max TTL (7 d) + clock skew
        set(v) { prefs.edit().putInt("retentionDays", v).apply() }
    var networkConsent: Boolean
        get() = prefs.getBoolean("networkConsent", false)
        set(v) { prefs.edit().putBoolean("networkConsent", v).apply() }
}
