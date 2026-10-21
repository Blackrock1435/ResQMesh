package com.resqmesh.core

import org.junit.Assert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Test

class RelayEngineTest {
    private fun five(net: SimNet = SimNet()): SimNet {
        listOf("A", "B", "C", "D", "E").forEach { net.node(it) }
        net.chain("A", "B", "C", "D", "E"); return net
    }
    private fun SimNet.id(n: String) = nodes[n]!!.identity.deviceId
    private fun SimNet.status(n: String, id: String) = nodes[n]!!.store.get(id)!!.status

    @Test fun fivePhoneChainDeliversAndConfirms() {
        val net = five()
        Assert.assertFalse(net.connected("A", "E"))
        val res = net.nodes["A"]!!.engine.sendDirect(net.id("E"), "hello E") as SendResult.Ok
        net.pump()
        val e = net.nodes["E"]!!.received.single { it.type == PacketType.DIRECT }
        Assert.assertEquals("hello E", e.text)
        Assert.assertEquals(4 - 1, e.hop)                       // B,C,D each incremented: 3 relay hops
        // intermediates never see plaintext
        listOf("B", "C", "D").forEach { Assert.assertTrue(net.nodes[it]!!.received.none { r -> r.type == PacketType.DIRECT }) }
        Assert.assertEquals(Status.DELIVERED, net.status("A", res.id))   // receipt from E travelled back
        // path check from the radio log: no direct A<->E transfer ever happened
        Assert.assertTrue(net.sentLog.none { (f, t, _) -> setOf(f, t) == setOf("A", "E") })
    }

    @Test fun relayAcknowledgmentIsNotDelivery() {
        val net = five()
        net.nodes["A"]!!.dropIncomingType = null
        net.nodes["E"]!!.dropIncomingType = null
        // E's receipts are lost: block RECEIPT type packets arriving at D (E->D direction effectively)
        net.nodes["D"]!!.dropIncomingType = PacketType.RECEIPT
        val id = (net.nodes["A"]!!.engine.sendDirect(net.id("E"), "x") as SendResult.Ok).id
        net.pump()
        Assert.assertEquals("E got it", 1, net.nodes["E"]!!.received.count { it.type == PacketType.DIRECT })
        Assert.assertEquals(Status.SENT, net.status("A", id))    // never falsely DELIVERED
    }

    @Test fun hopLimitStopsPropagation() {
        val net = SimNet(); listOf("A", "B", "C", "D", "E").forEach { net.node(it) }
        net.chain("A", "B", "C", "D", "E")
        // craft a 2-hop broadcast by using a tiny-hop engine config: use A's engine config default (8) -> instead originate via SOS? use custom node
        val net2 = SimNet(); val cfg = EngineConfig(defaultMaxHop = 2)
        net2.node("A", cfg); listOf("B", "C", "D", "E").forEach { net2.node(it) }
        net2.chain("A", "B", "C", "D", "E")
        net2.nodes["A"]!!.engine.sendBroadcast("short range"); net2.pump()
        Assert.assertTrue(net2.nodes["C"]!!.received.any { it.text == "short range" })
        Assert.assertTrue(net2.nodes["D"]!!.received.any { it.text == "short range" })   // hop 2 = last allowed copy
        Assert.assertTrue(net2.nodes["E"]!!.received.none { it.text == "short range" })  // would be hop 3
        Assert.assertNotNull(net)
    }

    @Test fun duplicateSuppression() {
        val net = SimNet(); net.node("A"); net.node("B"); net.chain("A", "B")
        net.nodes["A"]!!.engine.sendBroadcast("once"); net.pump()
        val raw = net.sentLog.first { (f, _, d) -> f == "A" && PacketCodec.decode(d).type == PacketType.BROADCAST }.third
        repeat(5) { net.nodes["B"]!!.engine.onBytes("A", raw) }
        Assert.assertEquals(1, net.nodes["B"]!!.received.count { it.text == "once" })
        Assert.assertTrue(net.nodes["B"]!!.engine.stats.duplicates >= 5)
    }

    @Test fun concurrentDuplicateDeliveryProcessedOnce() {
        val net = SimNet(); net.node("A"); net.node("B"); net.chain("A", "B")
        net.nodes["A"]!!.engine.sendBroadcast("race"); net.pump()
        val raw = net.sentLog.first { (f, _, d) -> f == "A" && PacketCodec.decode(d).type == PacketType.BROADCAST }.third
        val fresh = net.node("C"); net.connect("A", "C"); net.pump() // C may already have it via inventory; use unseen engine instead:
        val x = SimNet().node("X")
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        repeat(8) { pool.submit { go.await(); x.engine.onBytes("A", raw) } }
        go.countDown(); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        Assert.assertEquals(1, x.received.count { it.text == "race" })
        Assert.assertEquals(7L, x.engine.stats.duplicates)
        Assert.assertNotNull(fresh)
    }

    @Test fun expiredPacketsRejectedAndPurged() {
        val net = SimNet(); net.node("A"); net.node("B"); net.chain("A", "B")
        net.nodes["A"]!!.engine.sendBroadcast("old"); net.pump()
        val raw = net.sentLog.first { (_, _, d) -> PacketCodec.decode(d).type == PacketType.BROADCAST }.third
        val late = SimNet(net.now + 3 * 24 * 3600_000L).node("Z")
        late.engine.onBytes("A", raw)
        Assert.assertTrue(late.received.isEmpty()); Assert.assertEquals(1L, late.engine.stats.expired)
        // local expiry of stored message
        net.advance(25 * 3600_000L); net.tickAll()
        Assert.assertEquals(null, net.nodes["B"]!!.store.get(PacketCodec.decode(raw).id)!!.raw)
    }

    @Test fun storeAndForwardAcrossLaterConnection() {
        val net = SimNet(); listOf("A", "B", "C").forEach { net.node(it) }
        net.connect("A", "B"); net.pump()
        val idC = net.nodes["C"]!!.identity.deviceId
        // A does not yet know C's key
        Assert.assertTrue(net.nodes["A"]!!.engine.sendDirect(idC, "x") is SendResult.Err)
        net.connect("B", "C"); net.pump()                      // key spreads A <- B <- C
        val id = (net.nodes["A"]!!.engine.sendDirect(idC, "later") as SendResult.Ok).id
        net.disconnect("B", "C"); net.pump()                    // B holds it while C is away
        net.pump()
        Assert.assertTrue(net.nodes["C"]!!.received.none { it.text == "later" })
        Assert.assertEquals(Status.SENT, net.status("A", id))
        net.connect("B", "C"); net.pump()
        Assert.assertEquals("later", net.nodes["C"]!!.received.single { it.type == PacketType.DIRECT }.text)
        Assert.assertEquals(Status.DELIVERED, net.status("A", id))
    }

    @Test fun pendingWithNoLinksThenRecoversAfterReconnect() {
        val net = SimNet(); net.node("A"); net.node("B"); net.connect("A", "B"); net.pump(); net.disconnect("A", "B")
        val idB = net.id("B")
        val id = (net.nodes["A"]!!.engine.sendDirect(idB, "queued") as SendResult.Ok).id
        Assert.assertEquals(Status.PENDING, net.status("A", id))
        net.connect("A", "B"); net.pump()
        Assert.assertEquals(Status.DELIVERED, net.status("A", id))
    }

    @Test fun peerDisappearsDuringTransferIsRetried() {
        val net = SimNet(); net.node("A"); net.node("B"); net.chain("A", "B")
        val idB = net.id("B")
        val id = (net.nodes["A"]!!.engine.sendDirect(idB, "mid") as SendResult.Ok).id
        net.disconnect("A", "B")                                // in-flight bytes dropped
        net.pump(); Assert.assertTrue(net.nodes["B"]!!.received.none { it.text == "mid" })
        net.connect("A", "B"); net.pump()
        Assert.assertEquals("mid", net.nodes["B"]!!.received.single { it.type == PacketType.DIRECT }.text)
        Assert.assertEquals(Status.DELIVERED, net.status("A", id))
    }

    @Test fun retryLimitThenManualRetry() {
        val cfg = EngineConfig(maxRetries = 4)
        val net = SimNet(); net.node("A", cfg); net.node("B"); net.connect("A", "B"); net.pump(); net.disconnect("A", "B")
        val id = (net.nodes["A"]!!.engine.sendDirect(net.id("B"), "never") as SendResult.Ok).id
        repeat(10) { net.advance(70_000); net.nodes["A"]!!.engine.tick() }
        Assert.assertEquals(Status.FAILED, net.status("A", id))
        Assert.assertTrue(net.nodes["A"]!!.engine.retry(id))
        Assert.assertEquals(Status.PENDING, net.status("A", id))
        net.connect("A", "B"); net.pump()
        Assert.assertEquals(Status.DELIVERED, net.status("A", id))
    }

    @Test fun unavailableDestinationExpiresNotDelivered() {
        val net = five(); val ghost = Identity.generate()
        net.nodes["A"]!!.store.upsertPeer(PeerRecord(ghost.deviceId, "ghost", ghost.publicKeyBytes, net.now))
        val id = (net.nodes["A"]!!.engine.sendDirect(ghost.deviceId, "hello?") as SendResult.Ok).id
        net.pump(); Assert.assertEquals(Status.SENT, net.status("A", id))
        net.advance(25 * 3600_000L); net.tickAll()
        Assert.assertEquals(Status.EXPIRED, net.status("A", id))
    }

    @Test fun boundedQueueEvictsLowPriorityKeepsOwnSos() {
        val cfg = EngineConfig(maxStored = 10)
        val net = SimNet(); net.node("A", cfg)
        val sos = (net.nodes["A"]!!.engine.sendSos(null, "help") as SendResult.Ok).id
        repeat(30) { net.nodes["A"]!!.engine.sendBroadcast("m$it") }
        net.nodes["A"]!!.engine.tick()
        Assert.assertTrue(net.nodes["A"]!!.store.count() <= 10)
        Assert.assertNotNull(net.nodes["A"]!!.store.get(sos))
    }

    @Test fun restartKeepsPendingWork() {
        val net = SimNet(); net.node("A"); net.node("B"); net.connect("A", "B"); net.pump(); net.disconnect("A", "B")
        val id = (net.nodes["A"]!!.engine.sendDirect(net.id("B"), "persist") as SendResult.Ok).id
        net.nodes["A"]!!.restart()                              // new engine, same store (== app restart)
        net.connect("A", "B"); net.pump()
        Assert.assertEquals(Status.DELIVERED, net.status("A", id))
    }

    @Test fun invalidAndMalformedPacketsDropped() {
        val net = SimNet(); net.node("A"); val b = net.node("B"); net.chain("A", "B")
        net.nodes["A"]!!.engine.sendBroadcast("ok"); net.pump()
        val raw = net.sentLog.first { (_, _, d) -> PacketCodec.decode(d).type == PacketType.BROADCAST }.third
        val x = SimNet().node("X")
        x.engine.onBytes("l", raw.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })   // bad signature
        val p = PacketCodec.decode(raw)
        val spoof = PacketCodec.encode(p.copy(senderId = Identity.generate().deviceId))
        x.engine.onBytes("l", spoof)
        x.engine.onBytes("l", ByteArray(50) { 7 })
        x.engine.onBytes("l", ByteArray(Limits.MAX_PACKET_BYTES + 10))
        Assert.assertTrue(x.received.isEmpty())
        Assert.assertTrue(x.engine.stats.invalid + x.engine.stats.malformed >= 4)
        Assert.assertNotNull(b)
    }

    @Test fun sosPriorityAndAcknowledgements() {
        val net = SimNet(); listOf("A", "B", "C").forEach { net.node(it) }
        net.connect("A", "B"); net.pump(); net.disconnect("A", "B")
        val a = net.nodes["A"]!!
        a.engine.sendBroadcast("chat 1"); a.engine.sendBroadcast("chat 2")
        val sos = (a.engine.sendSos(LocationData(12.9716, 77.5946, 8f, net.now, "need help"), "need help") as SendResult.Ok).id
        net.sentLog.clear()
        net.connect("A", "B"); net.pump()
        val types = net.sentLog.filter { it.first == "A" && it.second == "B" }.map { PacketCodec.decode(it.third).type }
        val firstSos = types.indexOf(PacketType.SOS); val firstBc = types.indexOf(PacketType.BROADCAST)
        Assert.assertTrue("SOS must precede chat: $types", firstSos in 0 until firstBc)
        // relay to C, both ack
        net.connect("B", "C"); net.pump()
        val rec = net.nodes["C"]!!.received.single { it.type == PacketType.SOS }
        Assert.assertEquals(12.9716, rec.lat!!, 1e-9)
        val rs = a.store.receipts(sos).map { it.peerId }.toSet()
        Assert.assertEquals(setOf(net.id("B"), net.id("C")), rs)
        // duplicate suppression: exactly one SOS record per node
        Assert.assertEquals(1, net.nodes["C"]!!.received.count { it.type == PacketType.SOS })
        // resolve propagates
        a.engine.resolveSos(sos); net.pump()
        Assert.assertEquals(Status.RESOLVED, net.status("C", sos))
    }

    @Test fun groupMessagesAreEncryptedForNonMembersAndReceiptsAreProven() {
        val net = SimNet(); listOf("A", "B", "C").forEach { net.node(it) }; net.chain("A", "B", "C")
        val k = CryptoUtil.groupKey("team", "alpha", 1000)
        net.nodes["A"]!!.keys.put("alpha", k); net.nodes["C"]!!.keys.put("alpha", k)   // B is a non-member relay
        val id = (net.nodes["A"]!!.engine.sendGroup("alpha", "rendezvous 5pm") as SendResult.Ok).id
        net.pump()
        Assert.assertEquals("rendezvous 5pm", net.nodes["C"]!!.received.single { it.type == PacketType.GROUP }.text)
        Assert.assertTrue(net.nodes["B"]!!.received.none { it.type == PacketType.GROUP })
        Assert.assertEquals(setOf(net.id("C")), net.nodes["A"]!!.store.receipts(id).map { it.peerId }.toSet())
        // forged receipt from non-member B (no valid proof) must be ignored
        val forged = signedReceipt(net.nodes["B"]!!.identity, id, net.id("A"), ByteArray(32))
        net.nodes["A"]!!.engine.onBytes("B", forged)
        Assert.assertFalse(net.nodes["A"]!!.store.receipts(id).any { it.peerId == net.id("B") })
    }

    private fun signedReceipt(who: Identity, msgId: String, dest: String, proof: ByteArray): ByteArray {
        val payload = PacketCodec.unhex(msgId) + byteArrayOf(ReceiptKind.DELIVERED.toByte(), proof.size.toByte()) + proof
        val p = Packet(1, PacketType.RECEIPT, CryptoUtil.randomId(), who.deviceId, dest, null, 3600, 1_700_000_000_000L, 0, 5, who.publicKeyBytes, payload, ByteArray(0))
        return PacketCodec.encode(p.copy(signature = who.sign(PacketCodec.signingBytes(p))))
    }

    @Test fun spoofedDirectReceiptFromNonDestinationIgnored() {
        val net = five()
        val id = (net.nodes["A"]!!.engine.sendDirect(net.id("E"), "x") as SendResult.Ok).id
        net.nodes["D"]!!.dropIncomingType = PacketType.RECEIPT; net.pump()   // genuine receipt lost
        val fake = signedReceipt(net.nodes["C"]!!.identity, id, net.id("A"), ByteArray(0))
        net.nodes["A"]!!.engine.onBytes("B", fake)
        Assert.assertEquals(Status.SENT, net.status("A", id))
    }

    @Test fun inventorySyncFillsGapsWithoutResendingEverything() {
        val net = SimNet(); listOf("A", "B", "C").forEach { net.node(it) }
        net.connect("A", "B"); net.pump()
        repeat(3) { net.nodes["A"]!!.engine.sendBroadcast("n$it") }; net.pump()
        net.sentLog.clear(); net.advance(5_000)
        net.connect("B", "C"); net.pump()                        // C joins late
        Assert.assertEquals(3, net.nodes["C"]!!.received.count { it.type == PacketType.BROADCAST })
        // B -> C must not have transmitted the same packet id twice
        val ids = net.sentLog.filter { it.first == "B" && it.second == "C" }.map { PacketCodec.decode(it.third) }
            .filter { it.type == PacketType.BROADCAST }.map { it.id }
        Assert.assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun rateLimitDropsFloods() {
        val cfg = EngineConfig(rateBurst = 5.0, ratePerSec = 0.0)
        val net = SimNet(); val x = net.node("X", cfg); val y = net.node("Y"); net.connect("X", "Y"); net.pump()
        repeat(40) { y.engine.sendBroadcast("f$it") }; net.pump()
        Assert.assertTrue(x.engine.stats.rateLimited > 0)
    }

    @Test fun locationInChatDirectEncrypted() {
        val net = five()
        net.nodes["A"]!!.engine.sendDirect(net.id("E"), loc = LocationData(12.0, 77.0, 5f, net.now)); net.pump()
        val r = net.nodes["E"]!!.received.single { it.type == PacketType.DIRECT }
        Assert.assertEquals(12.0, r.lat!!, 1e-9)
        Assert.assertTrue(net.nodes["A"]!!.engine.sendDirect(net.id("E"), loc = LocationData(99.0, 0.0, 5f, 1L)) is SendResult.Err)
    }
}

class RelayExtraTest {
    @Test fun periodicInventoryRecoversLostTransfer() {
        val net = SimNet(); net.node("A"); net.node("B"); net.connect("A", "B"); net.pump()
        net.nodes["A"]!!.engine.sendBroadcast("lost?")
        net.nodes["A"]!!.store.packets.values.first { it.text == "lost?" } // exists
        net.disconnect("A", "B"); net.pump()                       // in-flight copy lost
        net.connect("A", "B"); net.pump()
        Assert.assertEquals(1, net.nodes["B"]!!.received.count { it.text == "lost?" })
    }
}
