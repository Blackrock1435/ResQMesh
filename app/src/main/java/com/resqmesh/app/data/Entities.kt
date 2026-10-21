package com.resqmesh.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.resqmesh.core.PeerRecord
import com.resqmesh.core.ReceiptRecord
import com.resqmesh.core.StoredPacket

@Entity(
    tableName = "packets",
    indices = [Index("type"), Index("status"), Index("expiresAtMs"), Index("senderId"), Index("groupId"), Index("relay", "nextAttemptMs")],
)
class PacketEntity(
    @PrimaryKey val id: String,
    val type: Int, val senderId: String, val destId: String?, val groupId: String?,
    val createdAtMs: Long, val receivedAtMs: Long, val expiresAtMs: Long,
    val hop: Int, val maxHop: Int, val priority: Int, val own: Boolean, val relay: Boolean,
    val raw: ByteArray?, val text: String?, val lat: Double?, val lon: Double?, val accM: Float?, val fixTimeMs: Long?,
    val status: Int, val retryCount: Int, val nextAttemptMs: Long, val sentTo: String,
) {
    fun toCore() = StoredPacket(id, type, senderId, destId, groupId, createdAtMs, receivedAtMs, expiresAtMs, hop, maxHop, priority,
        own, relay, raw, text, lat, lon, accM, fixTimeMs, status, retryCount, nextAttemptMs, sentTo)

    companion object {
        fun from(p: StoredPacket) = PacketEntity(p.id, p.type, p.senderId, p.destId, p.groupId, p.createdAtMs, p.receivedAtMs,
            p.expiresAtMs, p.hop, p.maxHop, p.priority, p.own, p.relay, p.raw, p.text, p.lat, p.lon, p.accM, p.fixTimeMs,
            p.status, p.retryCount, p.nextAttemptMs, p.sentTo)
    }
}

@Entity(tableName = "receipts", primaryKeys = ["messageId", "peerId", "kind"], indices = [Index("messageId")])
class ReceiptEntity(val messageId: String, val peerId: String, val kind: Int, val atMs: Long) {
    fun toCore() = ReceiptRecord(messageId, peerId, kind, atMs)
}

@Entity(tableName = "peers")
class PeerEntity(@PrimaryKey val deviceId: String, val name: String, val pubKey: ByteArray, val lastSeenMs: Long) {
    fun toCore() = PeerRecord(deviceId, name, pubKey, lastSeenMs)
}

/** Group membership: the AES key is derived from the shared passphrase and stored wrapped by the Android Keystore. */
@Entity(tableName = "mesh_groups")
class GroupEntity(@PrimaryKey val groupId: String, val name: String, val wrappedKey: String, val createdAtMs: Long)

@Entity(tableName = "diag", indices = [Index("atMs")])
class DiagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val atMs: Long, val msg: String)
