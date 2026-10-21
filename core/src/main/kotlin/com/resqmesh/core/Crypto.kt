package com.resqmesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A device identity: an ECDSA/ECDH P-256 key pair. deviceId = first 16 bytes of SHA-256(public key). */
class Identity(val keyPair: KeyPair) {
    val publicKeyBytes: ByteArray = keyPair.public.encoded
    val deviceId: String = CryptoUtil.deviceIdFor(publicKeyBytes)
    fun privateKeyBytes(): ByteArray = keyPair.private.encoded
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(data); sign()
    }

    companion object {
        fun generate() = Identity(KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair())

        fun restore(pub: ByteArray, priv: ByteArray): Identity {
            val kf = KeyFactory.getInstance("EC")
            return Identity(KeyPair(kf.generatePublic(X509EncodedKeySpec(pub)), kf.generatePrivate(PKCS8EncodedKeySpec(priv))))
        }
    }
}

/** Only standard primitives (ECDSA, ECDH, HKDF-SHA256, AES-256-GCM, PBKDF2, HMAC-SHA256). No custom algorithms. */
object CryptoUtil {
    private val rng = SecureRandom()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun deviceIdFor(pub: ByteArray) = PacketCodec.hex(sha256(pub).copyOf(16))
    fun randomId(): String = PacketCodec.hex(ByteArray(16).also { rng.nextBytes(it) })

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(sig) }
    } catch (e: Exception) { false }

    private fun hmac(key: ByteArray, data: ByteArray) =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)) // 32 bytes = one HKDF block
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad); doFinal(input)
        }

    /** ECIES-style: ephemeral ECDH -> HKDF -> AES-GCM. Blob = [u16 ephPubLen][ephPub][iv12][ct+tag]. */
    fun encryptFor(recipientPub: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()
        val rPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(recipientPub))
        val secret = KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(rPub, true); generateSecret() }
        val ephPub = eph.public.encoded
        val key = hkdf(secret, ephPub + recipientPub, "ResQMesh-dm-v1".toByteArray())
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val ct = gcm(Cipher.ENCRYPT_MODE, key, iv, aad, plain)
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeShort(ephPub.size); o.write(ephPub); o.write(iv); o.write(ct)
        return bo.toByteArray()
    }

    fun decryptFrom(me: Identity, aad: ByteArray, blob: ByteArray): ByteArray? {
      return try {
        val i = DataInputStream(ByteArrayInputStream(blob))
        val n = i.readUnsignedShort()
        if (n < 60 || n > 200) return null
        val ephPub = ByteArray(n).also { i.readFully(it) }
        val iv = ByteArray(12).also { i.readFully(it) }
        val ct = ByteArray(i.available()).also { i.readFully(it) }
        val ePub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephPub))
        val secret = KeyAgreement.getInstance("ECDH").run { init(me.keyPair.private); doPhase(ePub, true); generateSecret() }
        val key = hkdf(secret, ephPub + me.publicKeyBytes, "ResQMesh-dm-v1".toByteArray())
        gcm(Cipher.DECRYPT_MODE, key, iv, aad, ct)
      } catch (e: Exception) { null }
    }

    /** Group key from a shared passphrase (PBKDF2-HMAC-SHA256, salt bound to the group id). */
    fun groupKey(secret: String, groupId: String, iterations: Int = 100_000): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), ("ResQMesh-group-v1:$groupId").toByteArray(), iterations, 256)).encoded

    fun encryptGroup(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        return iv + gcm(Cipher.ENCRYPT_MODE, key, iv, aad, plain)
    }

    fun decryptGroup(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray? = try {
        if (blob.size < 12 + 16) null else gcm(Cipher.DECRYPT_MODE, key, blob.copyOf(12), aad, blob.copyOfRange(12, blob.size))
    } catch (e: Exception) { null }

    /** Proof that a group member (holder of the key) received message [id]; prevents forged group receipts. */
    fun groupReceiptProof(key: ByteArray, id: String, receiverId: String): ByteArray =
        hmac(key, "rcpt:$id:$receiverId".toByteArray())

    fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
