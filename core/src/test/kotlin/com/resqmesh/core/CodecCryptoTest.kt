package com.resqmesh.core

import org.junit.Assert
import org.junit.Test

class CodecCryptoTest {
    private val id = Identity.generate()

    private fun pkt(payload: ByteArray = Inner.text("hi"), ttl: Int = 3600, maxHop: Int = 5, type: Int = PacketType.BROADCAST): Packet {
        val p = Packet(1, type, CryptoUtil.randomId(), id.deviceId, null, null, ttl, 1_700_000_000_000L, 0, maxHop, id.publicKeyBytes, payload, ByteArray(0))
        return p.copy(signature = id.sign(PacketCodec.signingBytes(p)))
    }

    @Test fun uniqueIds() { Assert.assertEquals(5000, (1..5000).map { CryptoUtil.randomId() }.toSet().size) }

    @Test fun roundTripAndVerify() {
        val p = pkt(); val q = PacketCodec.decode(PacketCodec.encode(p))
        Assert.assertEquals(p.id, q.id); Assert.assertTrue(PacketCodec.verify(q))
        Assert.assertArrayEquals(p.payload, q.payload)
    }

    @Test fun hopChangeKeepsSignatureValid() {
        val q = PacketCodec.decode(PacketCodec.encode(pkt().copy(hop = 3)))
        Assert.assertEquals(3, q.hop); Assert.assertTrue(PacketCodec.verify(q))
    }

    @Test fun tamperedPayloadOrMaxHopFailsVerification() {
        val p = pkt()
        Assert.assertFalse(PacketCodec.verify(p.copy(payload = Inner.text("evil"))))
        Assert.assertFalse(PacketCodec.verify(p.copy(maxHop = 10)))
        Assert.assertFalse(PacketCodec.verify(p.copy(ttlSec = 999999)))
    }

    @Test fun senderIdMustMatchKey() {
        val other = Identity.generate()
        val p = pkt().copy(senderId = other.deviceId)
        Assert.assertFalse(PacketCodec.verify(p))
    }

    private fun rejects(b: ByteArray) { try { PacketCodec.decode(b); Assert.fail("accepted bad packet") } catch (e: PacketException) {} }

    @Test fun malformedRejected() {
        val good = PacketCodec.encode(pkt())
        rejects(ByteArray(0)); rejects(ByteArray(100) { 1 })
        rejects(good.copyOf(good.size - 5))                 // truncated
        rejects(good + byteArrayOf(0))                      // trailing
        rejects(good.copyOf().also { it[0] = 0x00 })        // magic
        rejects(good.copyOf().also { it[1] = 9 })           // version
        rejects(good.copyOf().also { it[2] = 77 })          // type
        rejects(ByteArray(Limits.MAX_PACKET_BYTES + 1))     // oversized
        rejects(good.copyOf().also { it[3] = 8 })           // unknown flag bits
    }

    @Test fun oversizedPayloadCannotBeEncoded() {
        try { PacketCodec.encode(pkt(payload = ByteArray(Limits.MAX_PAYLOAD_BYTES + 1))); Assert.fail() } catch (e: PacketException) {}
    }

    @Test fun hopAboveMaxRejected() { rejects(PacketCodec.encode(pkt(maxHop = 2).copy(hop = 5))) }

    @Test fun locationValidation() {
        Assert.assertNotNull(Inner.parse(Inner.location(LocationData(12.97, 77.59, 12f, 1L, "x"))))
        Assert.assertNull(Inner.parse(Inner.location(LocationData(91.0, 0.0, 1f, 1L))))
        Assert.assertNull(Inner.parse(Inner.location(LocationData(Double.NaN, 0.0, 1f, 1L))))
        Assert.assertNull(Inner.parse(Inner.location(LocationData(0.0, 181.0, 1f, 1L))))
        Assert.assertNull(Inner.parse(Inner.location(LocationData(0.0, 0.0, -1f, 1L))))
        Assert.assertNull(Inner.parse(byteArrayOf(1, 2, 3)))
        Assert.assertNull(Inner.parse(byteArrayOf(9)))
    }

    @Test fun directEncryptionOnlyRecipientCanRead() {
        val bob = Identity.generate(); val eve = Identity.generate()
        val blob = CryptoUtil.encryptFor(bob.publicKeyBytes, "aad".toByteArray(), "secret".toByteArray())
        Assert.assertEquals("secret", String(CryptoUtil.decryptFrom(bob, "aad".toByteArray(), blob)!!))
        Assert.assertNull(CryptoUtil.decryptFrom(eve, "aad".toByteArray(), blob))
        Assert.assertNull(CryptoUtil.decryptFrom(bob, "other".toByteArray(), blob)) // AAD binding
        Assert.assertNull(CryptoUtil.decryptFrom(bob, "aad".toByteArray(), blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
    }

    @Test fun groupEncryption() {
        val k = CryptoUtil.groupKey("pass", "g1", 1000); val k2 = CryptoUtil.groupKey("wrong", "g1", 1000)
        val blob = CryptoUtil.encryptGroup(k, "a".toByteArray(), "hello".toByteArray())
        Assert.assertEquals("hello", String(CryptoUtil.decryptGroup(k, "a".toByteArray(), blob)!!))
        Assert.assertNull(CryptoUtil.decryptGroup(k2, "a".toByteArray(), blob))
    }

    @Test fun identityRestore() {
        val r = Identity.restore(id.publicKeyBytes, id.privateKeyBytes())
        Assert.assertEquals(id.deviceId, r.deviceId)
        Assert.assertTrue(CryptoUtil.verify(id.publicKeyBytes, "x".toByteArray(), r.sign("x".toByteArray())))
    }
}
