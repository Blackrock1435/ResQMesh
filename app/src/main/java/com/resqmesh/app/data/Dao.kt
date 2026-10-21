package com.resqmesh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(e: PacketEntity): Long   // -1 when id exists => atomic dedup
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun replace(e: PacketEntity)
    @Query("SELECT * FROM packets WHERE id=:id") fun get(id: String): PacketEntity?
    @Query("SELECT id FROM packets WHERE raw IS NOT NULL AND relay=1 AND expiresAtMs>:now ORDER BY priority ASC, receivedAtMs DESC LIMIT :limit")
    fun inventory(now: Long, limit: Int): List<String>
    @Query("SELECT * FROM packets WHERE relay=1 AND raw IS NOT NULL AND expiresAtMs>:now AND (:ignore=1 OR nextAttemptMs<=:now) ORDER BY priority ASC, receivedAtMs ASC LIMIT :limit")
    fun candidates(now: Long, ignore: Int, limit: Int): List<PacketEntity>
    @Query("SELECT id FROM packets WHERE raw IS NOT NULL AND expiresAtMs<=:now") fun expiredIds(now: Long): List<String>
    @Query("UPDATE packets SET raw=NULL, relay=0, status=CASE WHEN own=1 AND status NOT IN (2,6,7) THEN 4 ELSE status END WHERE raw IS NOT NULL AND expiresAtMs<=:now")
    fun markExpired(now: Long)
    @Query("DELETE FROM packets WHERE raw IS NULL AND text IS NULL AND lat IS NULL AND expiresAtMs<:cutoff") fun purgeTombstones(cutoff: Long): Int
    @Query("DELETE FROM packets WHERE receivedAtMs<:cutoff AND (raw IS NULL OR relay=0)") fun purgeHistory(cutoff: Long): Int
    @Query("SELECT COUNT(*) FROM packets") fun count(): Int
    @Query("SELECT id FROM packets WHERE NOT (own=1 AND type=6 AND status!=6) ORDER BY priority DESC, receivedAtMs ASC LIMIT :n") fun victims(n: Int): List<String>
    @Query("DELETE FROM packets WHERE id IN (:ids)") fun deleteIds(ids: List<String>)

    @Query("SELECT * FROM packets WHERE type=2 AND ((senderId=:me AND destId=:peer) OR (senderId=:peer AND destId=:me)) AND (own=1 OR text IS NOT NULL OR lat IS NOT NULL) ORDER BY createdAtMs")
    fun direct(me: String, peer: String): Flow<List<PacketEntity>>
    @Query("SELECT * FROM packets WHERE type=3 AND groupId=:g AND (own=1 OR text IS NOT NULL OR lat IS NOT NULL) ORDER BY createdAtMs")
    fun group(g: String): Flow<List<PacketEntity>>
    @Query("SELECT * FROM packets WHERE type=4 AND (own=1 OR text IS NOT NULL OR lat IS NOT NULL) ORDER BY createdAtMs")
    fun broadcast(): Flow<List<PacketEntity>>
    @Query("SELECT * FROM packets WHERE type=6 ORDER BY createdAtMs DESC") fun sos(): Flow<List<PacketEntity>>
    @Query("SELECT COUNT(*) FROM packets WHERE own=1 AND status IN (0,1) AND type IN (2,3,4,6)") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM packets WHERE type=6 AND status IN (0,1,5) AND expiresAtMs>:now") fun activeSosCount(now: Long): Flow<Int>
}

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(e: ReceiptEntity): Long
    @Query("SELECT * FROM receipts WHERE messageId=:id") fun forMessage(id: String): List<ReceiptEntity>
    @Query("SELECT * FROM receipts") fun all(): Flow<List<ReceiptEntity>>
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(e: PeerEntity)
    @Query("SELECT * FROM peers WHERE deviceId=:id") fun get(id: String): PeerEntity?
    @Query("SELECT * FROM peers ORDER BY name") fun all(): Flow<List<PeerEntity>>
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(e: GroupEntity)
    @Query("SELECT * FROM mesh_groups ORDER BY name") fun all(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM mesh_groups") fun allNow(): List<GroupEntity>
}

@Dao
interface DiagDao {
    @Insert fun insert(e: DiagEntity)
    @Query("SELECT * FROM diag ORDER BY atMs DESC LIMIT :n") fun recent(n: Int): Flow<List<DiagEntity>>
    @Query("SELECT * FROM diag ORDER BY atMs DESC LIMIT :n") fun recentNow(n: Int): List<DiagEntity>
    @Query("DELETE FROM diag WHERE atMs<:cutoff") fun prune(cutoff: Long)
}
