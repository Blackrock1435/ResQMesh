package com.resqmesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class PacketException(msg: String) : Exception(msg)

/** Strict, versioned binary codec. Every field is bounds-checked; trailing bytes are rejected. */
object PacketCodec {
    private const val MAGIC = 0x52
    private const val F_DEST = 1
    private const val F_GROUP = 2

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        if (s.length != 32 || !s.all { it in '0'..'9' || it in 'a'..'f' }) throw PacketException("bad id")
        return ByteArray(16) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun isId(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun writeBody(o: DataOutputStream, p: Packet) {
        o.writeByte(MAGIC); o.writeByte(p.version); o.writeByte(p.type)
        var flags = 0
        if (p.destId != null) flags = flags or F_DEST
        if (p.groupId != null) flags = flags or F_GROUP
        o.writeByte(flags)
        o.write(unhex(p.id)); o.write(unhex(p.senderId))
        p.destId?.let { o.write(unhex(it)) }
        p.groupId?.let { val b = it.toByteArray(Charsets.UTF_8); o.writeByte(b.size); o.write(b) }
        o.writeInt(p.ttlSec); o.writeLong(p.createdAtMs)
        o.writeByte(p.maxHop)
        o.writeShort(p.senderPubKey.size); o.write(p.senderPubKey)
        o.writeShort(p.payload.size); o.write(p.payload)
    }

    /** Bytes covered by the signature: everything except hop and the signature itself. */
    fun signingBytes(p: Packet): ByteArray {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        o.write("ResQMesh-sig-v1".toByteArray())
        writeBody(o, p)
        return bo.toByteArray()
    }

    fun encode(p: Packet): ByteArray {
        if (p.payload.size > Limits.MAX_PAYLOAD_BYTES) throw PacketException("payload too large")
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        // wire layout: body with hop inserted right after flags-free header; simplest: body, hop, signature
        writeBody(o, p)
        o.writeByte(p.hop)
        o.writeShort(p.signature.size); o.write(p.signature)
        val out = bo.toByteArray()
        if (out.size > Limits.MAX_PACKET_BYTES) throw PacketException("packet too large")
        return out
    }

    fun decode(data: ByteArray): Packet {
        if (data.size > Limits.MAX_PACKET_BYTES) throw PacketException("oversized")
        if (data.size < 40) throw PacketException("too short")
        val inp = DataInputStream(ByteArrayInputStream(data))
        try {
            if (inp.readUnsignedByte() != MAGIC) throw PacketException("bad magic")
            val version = inp.readUnsignedByte()
            if (version != Limits.VERSION) throw PacketException("unsupported version $version")
            val type = inp.readUnsignedByte()
            if (!PacketType.isValid(type)) throw PacketException("bad type")
            val flags = inp.readUnsignedByte()
            if (flags and (F_DEST or F_GROUP).inv() != 0) throw PacketException("bad flags")
            val id = hex(ByteArray(16).also { inp.readFully(it) })
            val sender = hex(ByteArray(16).also { inp.readFully(it) })
            val dest = if (flags and F_DEST != 0) hex(ByteArray(16).also { inp.readFully(it) }) else null
            val group = if (flags and F_GROUP != 0) {
                val n = inp.readUnsignedByte()
                if (n == 0 || n > Limits.MAX_GROUP_ID_BYTES) throw PacketException("bad group id")
                val s = String(ByteArray(n).also { inp.readFully(it) }, Charsets.UTF_8)
                if (!s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) throw PacketException("bad group chars")
                s
            } else null
            val ttl = inp.readInt()
            if (ttl <= 0 || ttl > Limits.MAX_TTL_SEC) throw PacketException("bad ttl")
            val created = inp.readLong()
            val maxHop = inp.readUnsignedByte()
            if (maxHop > Limits.MAX_HOPS) throw PacketException("bad maxHop")
            val pkLen = inp.readUnsignedShort()
            if (pkLen < 60 || pkLen > 200) throw PacketException("bad key length")
            val pk = ByteArray(pkLen).also { inp.readFully(it) }
            val plLen = inp.readUnsignedShort()
            if (plLen > Limits.MAX_PAYLOAD_BYTES) throw PacketException("payload too large")
            val payload = ByteArray(plLen).also { inp.readFully(it) }
            val hop = inp.readUnsignedByte()
            if (hop > maxHop) throw PacketException("hop exceeds maxHop")
            val sigLen = inp.readUnsignedShort()
            if (sigLen < 8 || sigLen > 100) throw PacketException("bad signature length")
            val sig = ByteArray(sigLen).also { inp.readFully(it) }
            if (inp.available() != 0) throw PacketException("trailing bytes")
            return Packet(version, type, id, sender, dest, group, ttl, created, hop, maxHop, pk, payload, sig)
        } catch (e: EOFException) {
            throw PacketException("truncated")
        }
    }

    /** Self-certifying identity check + signature check. */
    fun verify(p: Packet): Boolean =
        CryptoUtil.deviceIdFor(p.senderPubKey) == p.senderId &&
            CryptoUtil.verify(p.senderPubKey, signingBytes(p), p.signature)
}

/** Plaintext inside DIRECT/GROUP/BROADCAST/SOS payloads: [kind][body]. kind 0 = text, 1 = location. */
object Inner {
    data class Parsed(val text: String?, val loc: LocationData?)

    fun text(s: String): ByteArray = byteArrayOf(0) + s.take(Limits.MAX_TEXT_CHARS).toByteArray(Charsets.UTF_8)

    fun location(l: LocationData): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeByte(1); o.writeDouble(l.lat); o.writeDouble(l.lon); o.writeFloat(l.accuracyM); o.writeLong(l.fixTimeMs)
        val n = l.note.take(Limits.MAX_TEXT_CHARS).toByteArray(Charsets.UTF_8)
        o.writeShort(n.size); o.write(n)
        return bo.toByteArray()
    }

    fun parse(b: ByteArray): Parsed? {
        if (b.isEmpty()) return null
        return try {
            when (b[0].toInt()) {
                0 -> {
                    val s = String(b, 1, b.size - 1, Charsets.UTF_8)
                    if (s.length > Limits.MAX_TEXT_CHARS) null else Parsed(s, null)
                }
                1 -> {
                    val i = DataInputStream(ByteArrayInputStream(b, 1, b.size - 1))
                    val lat = i.readDouble(); val lon = i.readDouble(); val acc = i.readFloat(); val t = i.readLong()
                    val n = i.readUnsignedShort()
                    if (n > Limits.MAX_TEXT_CHARS * 4 || i.available() != n) return null
                    val note = String(ByteArray(n).also { i.readFully(it) }, Charsets.UTF_8)
                    if (!validLocation(lat, lon, acc, t)) null
                    else Parsed(note.takeIf { it.isNotEmpty() }, LocationData(lat, lon, acc, t, note))
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun validLocation(lat: Double, lon: Double, acc: Float, t: Long) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            acc.isFinite() && acc >= 0f && acc < 1_000_000f && t > 0
}
