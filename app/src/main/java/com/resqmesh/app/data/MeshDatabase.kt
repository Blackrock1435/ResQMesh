package com.resqmesh.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.resqmesh.core.MeshStore
import com.resqmesh.core.PeerRecord
import com.resqmesh.core.ReceiptRecord
import com.resqmesh.core.StoredPacket

/**
 * Schema v1. For future versions add explicit Migration objects (never fallbackToDestructiveMigration:
 * users could lose queued emergency messages).
 */
@Database(
    entities = [PacketEntity::class, ReceiptEntity::class, PeerEntity::class, GroupEntity::class, DiagEntity::class],
    version = 1, exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun packets(): PacketDao
    abstract fun receipts(): ReceiptDao
    abstract fun peers(): PeerDao
    abstract fun groups(): GroupDao
    abstract fun diag(): DiagDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, MeshDatabase::class.java, "resqmesh.db").build()
    }
}

/** Room-backed MeshStore. Must be called from a background thread (MeshManager's engine thread). */
class RoomMeshStore(private val db: MeshDatabase) : MeshStore {
    private val dao = db.packets()
    override fun insertIfNew(p: StoredPacket) = dao.insert(PacketEntity.from(p)) != -1L
    override fun get(id: String) = dao.get(id)?.toCore()
    override fun update(p: StoredPacket) = dao.replace(PacketEntity.from(p))
    override fun inventory(now: Long, limit: Int) = dao.inventory(now, limit)
    override fun forwardCandidates(now: Long, ignoreBackoff: Boolean, limit: Int) =
        dao.candidates(now, if (ignoreBackoff) 1 else 0, limit).map { it.toCore() }
    override fun expire(now: Long): List<String> = db.runInTransaction<List<String>> {
        val ids = dao.expiredIds(now); if (ids.isNotEmpty()) dao.markExpired(now); ids
    }
    override fun purgeOlderThan(cutoffMs: Long) = dao.purgeTombstones(cutoffMs)
    fun purgeHistory(cutoffMs: Long) = dao.purgeHistory(cutoffMs)
    override fun count() = dao.count()
    override fun enforceBound(max: Int): Int {
        val over = dao.count() - max
        if (over <= 0) return 0
        val v = dao.victims(over); dao.deleteIds(v); return v.size
    }
    override fun addReceipt(r: ReceiptRecord) = db.receipts().insert(ReceiptEntity(r.messageId, r.peerId, r.kind, r.atMs)) != -1L
    override fun receipts(messageId: String) = db.receipts().forMessage(messageId).map { it.toCore() }
    override fun upsertPeer(p: PeerRecord) = db.peers().upsert(PeerEntity(p.deviceId, p.name, p.pubKey, p.lastSeenMs))
    override fun peer(deviceId: String) = db.peers().get(deviceId)?.toCore()
}
