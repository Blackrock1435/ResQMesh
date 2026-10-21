package com.resqmesh.core

/** Wire packet types. Values are part of the protocol; never renumber. */
object PacketType {
    const val HELLO = 1          // signed identity announcement (flooded, short-lived)
    const val DIRECT = 2         // end-to-end encrypted 1:1 message
    const val GROUP = 3          // group-key encrypted message
    const val BROADCAST = 4      // plaintext, signed, to everyone reachable
    const val SOS = 6            // plaintext, signed emergency alert
    const val SOS_RESOLVE = 7    // sender cancels/resolves own SOS
    const val RECEIPT = 8        // destination receipt (DELIVERED)
    const val INVENTORY = 9      // link-local: list of packet ids held
    const val REQUEST = 10       // link-local: ask neighbour for missing ids

    private val all = setOf(HELLO, DIRECT, GROUP, BROADCAST, SOS, SOS_RESOLVE, RECEIPT, INVENTORY, REQUEST)
    fun isValid(t: Int) = t in all
    fun isLinkLocal(t: Int) = t == INVENTORY || t == REQUEST
    /** Lower number = sent first. */
    fun priority(t: Int) = when (t) {
        SOS, SOS_RESOLVE -> 0
        RECEIPT -> 1
        DIRECT, GROUP -> 2
        BROADCAST -> 3
        HELLO -> 4
        else -> 5
    }
}

object Limits {
    const val VERSION = 1
    const val MAX_PACKET_BYTES = 16 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024
    const val MAX_TEXT_CHARS = 1000
    const val MAX_NAME_CHARS = 32
    const val MAX_HOPS = 10
    const val MAX_TTL_SEC = 7 * 24 * 3600
    const val MAX_GROUP_ID_BYTES = 32
}

object Status {
    const val PENDING = 0    // saved, not yet handed to any neighbour
    const val SENT = 1       // handed to >=1 neighbour; delivery NOT confirmed
    const val DELIVERED = 2  // signed receipt from the intended destination received
    const val FAILED = 3     // retry budget exhausted
    const val EXPIRED = 4
    const val RECEIVED = 5   // incoming (or relayed foreign) packet
    const val RESOLVED = 6   // SOS resolved by its sender
    const val CANCELLED = 7
    fun label(s: Int) = when (s) {
        PENDING -> "Saved. Waiting for a connection to a nearby device."
        SENT -> "Passed to a nearby device. Final delivery is not confirmed."
        DELIVERED -> "Delivered to the recipient."
        FAILED -> "Failed. No route found after several attempts."
        EXPIRED -> "Expired before delivery was confirmed."
        RECEIVED -> "Received."
        RESOLVED -> "Resolved."
        CANCELLED -> "Cancelled."
        else -> "Unknown"
    }
}

object ReceiptKind { const val DELIVERED = 1 }

data class Packet(
    val version: Int,
    val type: Int,
    val id: String,              // 32 hex chars (16 random bytes)
    val senderId: String,        // 32 hex chars = first 16 bytes of SHA-256(senderPubKey)
    val destId: String?,
    val groupId: String?,
    val ttlSec: Int,
    val createdAtMs: Long,       // informational only; expiry is computed from LOCAL receive time
    val hop: Int,                // NOT covered by the signature (relays increment it)
    val maxHop: Int,             // covered by the signature
    val senderPubKey: ByteArray, // X.509 encoded P-256 public key
    val payload: ByteArray,
    val signature: ByteArray,
)

data class LocationData(
    val lat: Double, val lon: Double, val accuracyM: Float, val fixTimeMs: Long, val note: String = "",
)

data class StoredPacket(
    val id: String,
    val type: Int,
    val senderId: String,
    val destId: String?,
    val groupId: String?,
    val createdAtMs: Long,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
    val hop: Int,
    val maxHop: Int,
    val priority: Int,
    val own: Boolean,
    val relay: Boolean,            // should this node keep forwarding it?
    val raw: ByteArray?,           // signed wire bytes (hop as received); cleared on expiry
    val text: String? = null,      // decrypted, user-visible text (own or addressed to me)
    val lat: Double? = null,
    val lon: Double? = null,
    val accM: Float? = null,
    val fixTimeMs: Long? = null,
    val status: Int,
    val retryCount: Int = 0,
    val nextAttemptMs: Long = 0,
    val sentTo: String = "",       // comma separated neighbour ids already served
)

data class PeerRecord(val deviceId: String, val name: String, val pubKey: ByteArray, val lastSeenMs: Long)
data class ReceiptRecord(val messageId: String, val peerId: String, val kind: Int, val atMs: Long)

interface MeshStore {
    /** Atomic: returns true only for the first caller that inserts this id. */
    fun insertIfNew(p: StoredPacket): Boolean
    fun get(id: String): StoredPacket?
    fun update(p: StoredPacket)
    /** Live packets this node could share with a neighbour: emergency priority first, then newest. */
    fun inventory(now: Long, limit: Int): List<String>
    /** relay=true, raw!=null, not expired, nextAttempt<=now (or ignoreBackoff). Ordered by priority, then age. */
    fun forwardCandidates(now: Long, ignoreBackoff: Boolean, limit: Int): List<StoredPacket>
    /** Marks unfinished packets past expiry as EXPIRED, clears raw, returns the affected ids. */
    fun expire(now: Long): List<String>
    /** Deletes expired, non-user-visible tombstones (no text/location) older than the cutoff. Chat history is kept (see retention). */
    fun purgeOlderThan(cutoffMs: Long): Int
    fun count(): Int
    /** Evicts lowest-priority/oldest foreign packets until count<=max. Never evicts own unresolved SOS. */
    fun enforceBound(max: Int): Int
    fun addReceipt(r: ReceiptRecord): Boolean
    fun receipts(messageId: String): List<ReceiptRecord>
    fun upsertPeer(p: PeerRecord)
    fun peer(deviceId: String): PeerRecord?
}

interface Transport {
    /** Returns true if the bytes were handed to the radio layer. This is NOT delivery. */
    fun send(linkId: String, data: ByteArray): Boolean
}

interface GroupKeys { fun key(groupId: String): ByteArray? }

interface EngineListener {
    fun onMessage(rec: StoredPacket) {}
    fun onSos(rec: StoredPacket) {}
    fun onStatus(id: String) {}
    fun onPeers() {}
    fun log(msg: String) {}
}

class Stats {
    @Volatile var received = 0L
    @Volatile var duplicates = 0L
    @Volatile var malformed = 0L
    @Volatile var invalid = 0L
    @Volatile var expired = 0L
    @Volatile var relayed = 0L
    @Volatile var rateLimited = 0L
    @Volatile var lastTransferMs = 0L
}

class EngineConfig(
    val maxStored: Int = 500,
    val maxRetries: Int = 30,
    val maxSkewMs: Long = 12 * 3600_000L,
    val defaultTtlSec: Int = 24 * 3600,
    val sosTtlSec: Int = 72 * 3600,
    val helloTtlSec: Int = 3600,
    val receiptTtlSec: Int = 24 * 3600,
    val defaultMaxHop: Int = 8,
    val inventoryMax: Int = 150,
    val requestMax: Int = 50,
    val rateBurst: Double = 60.0,
    val ratePerSec: Double = 20.0,
    val tombstoneMs: Long = 24 * 3600_000L,
    val syncMs: Long = 30_000L,
)
