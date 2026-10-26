package edu.uestc.nightsign.data.wechat.nativeprotocol

import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64

internal object MmtlsBusiness {
    private val random = SecureRandom()
    private val serverPoint = "04ef87876d6478b15f1796eab12068610541173b7176b67f1dcc86683e901acd44d18b4ac36938251d0812dd0cf842aa2d6cbb8115712d1c0087dcefc14a44cd58"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    class Meta(val ticket: ByteArray, val device: ByteArray, val host: ByteArray)
    class Pending(val key: PrivateKey, val okm: ByteArray, val compressed: ByteArray)
    class Session(val send: ByteArray, val receive: ByteArray, val f9: ByteArray, val uin: Long,
                  val device: ByteArray, val host: ByteArray)
    private fun bytes(fields: Map<Int, Any>, number: Int) = fields[number] as? ByteArray ?: error("BUSINESS_FIELD_MISSING")
    private fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun header(uin: Long, device: ByteArray, f9: ByteArray, login: Boolean): ByteArray {
        val ints = linkedMapOf(1 to 1L, 2 to uin, 3 to 0L, 4 to 0L, 5 to 524545L, 6 to 11L, 7 to 0L,
            8 to 0L, 9 to 0L, 10 to 1L, 11 to 0L, 12 to 0L, 13 to 0L, 17 to 0L, 18 to 1L, 20 to 1504L,
            21 to 0L, 22 to uin, 23 to 0L, 25 to if (login) 17L else 16L, 26 to 4L, 28 to 1L, 29 to 1L, 30 to 0L)
        return MmtlsCodec.wpkgHead(ints, mapOf(14 to byteArrayOf(), 24 to device, 27 to f9))
    }
    fun meta(buffer: String): Meta {
        require(buffer.length <= 1024 * 1024) { "LOGIN_BUFFER_TOO_LARGE" }
        val fields = MmtlsCodec.parseProto(Base64.getDecoder().decode(buffer))
        val ticket = bytes(fields, 1)
        val device = bytes(fields, 2)
        require(ticket.isNotEmpty() && device.isNotEmpty()) { "LOGIN_BUFFER_INVALID" }
        return Meta(ticket, device, fields[3] as? ByteArray ?: byteArrayOf())
    }
    fun login(meta: Meta): Pair<ByteArray, Pending> {
        val appBase = MmtlsCodec.pbBytes(1, random(32)) + MmtlsCodec.pbVar(2, 1901)
        val request = MmtlsCodec.pbBytes(1, appBase) + MmtlsCodec.pbBytes(3, MmtlsCodec.pbBytes(1, meta.ticket)) +
            MmtlsCodec.pbVar(4, 4) + MmtlsCodec.pbBytes(6, byteArrayOf()) + MmtlsCodec.pbVar(7, 0) + MmtlsCodec.pbVar(8, 6)
        val pair = MmtlsCrypto.generateEcdh()
        val public = MmtlsCrypto.publicPoint(pair)
        val secret = MmtlsCrypto.mmtlsSecret(pair.private, serverPoint)
        val hash1 = MmtlsCrypto.sha256("1415".toByteArray() + public)
        val contentKey = random(32)
        val encryptedKey = MmtlsCrypto.encryptLayout(secret.copyOf(24), hash1, contentKey)
        val okm = MmtlsCrypto.hkdfExpand(MmtlsCrypto.hkdfExtract("security hdkf expand".toByteArray(), contentKey), hash1, 56)
        val hash2 = MmtlsCrypto.sha256("1415".toByteArray() + public + encryptedKey)
        val compressed = MmtlsCodec.lz4Literal(request)
        val encrypted = MmtlsCrypto.encryptLayout(okm.copyOf(24), hash2, compressed)
        val hybrid = MmtlsCodec.pbVar(1, 1) + MmtlsCodec.pbBytes(2, MmtlsCodec.pbVar(1, 415) + MmtlsCodec.pbBytes(2, public)) +
            MmtlsCodec.pbBytes(3, encryptedKey) + MmtlsCodec.pbBytes(4, byteArrayOf()) + MmtlsCodec.pbBytes(5, encrypted)
        return (header(0, meta.device, byteArrayOf(), true) + hybrid) to Pending(pair.private, okm, compressed)
    }
    fun headerLength(body: ByteArray): Int {
        val r = MmtlsCodec.Reader(body)
        require(r.varint() == 1L) { "WPKG_VERSION_INVALID" }
        while (true) {
            val field = r.varint()
            if (field == 0L) break
            require(field in 1..0x1fffffffL)
            r.varint()
        }
        while (true) {
            val field = r.varint()
            if (field == 0L) break
            require(field in 1..0x1fffffffL)
            val length = r.varint()
            require(length in 0..r.remaining.toLong())
            r.bytes(length.toInt())
        }
        val trailer = r.offset
        require(r.varint() == trailer.toLong()) { "WPKG_TRAILER_INVALID" }
        return r.offset
    }
    fun parseLogin(body: ByteArray, pending: Pending, meta: Meta): Session {
        var offset = runCatching { headerLength(body) }.getOrDefault(-1)
        if (offset !in body.indices || body[offset] != 10.toByte()) {
            val marker = byteArrayOf(8, 0x9f.toByte(), 3, 18, 65, 4)
            offset = (2..body.size - marker.size).firstOrNull { i ->
                marker.indices.all { body[i + it] == marker[it] }
            }?.minus(2) ?: error("MANUAL_AUTH_RESPONSE_INVALID")
        }
        val fields = MmtlsCodec.parseProto(body.copyOfRange(offset, body.size))
        val point = bytes(MmtlsCodec.parseProto(bytes(fields, 1)), 2)
        val kind = fields[2] as? Long ?: 1L
        val secret = MmtlsCrypto.mmtlsSecret(pending.key, point)
        val aad = MmtlsCrypto.sha256(pending.okm.copyOfRange(24, 56) + pending.compressed + "415".toByteArray() + point + kind.toString().toByteArray())
        val decoded = MmtlsCodec.lz4Decompress(MmtlsCrypto.decryptLayout(secret.copyOf(24), aad, bytes(fields, 3)))
        val outer = MmtlsCodec.parseProto(decoded)
        val fields3 = MmtlsCodec.parseProto(bytes(outer, 3))
        val session = MmtlsCodec.parseProto(bytes(fields3, 2))
        val identity = MmtlsCodec.parseProto(bytes(fields3, 3))
        val send = bytes(session, 1)
        val receive = bytes(session, 2)
        val uin = identity[1] as? Long ?: 0L
        require(send.size in setOf(16, 24, 32) && receive.size in setOf(16, 24, 32) && uin > 0) { "SESSION_KEYS_INVALID" }
        return Session(send, receive, session[9] as? ByteArray ?: byteArrayOf(), uin, meta.device, meta.host)
    }
    fun transfer(session: Session): ByteArray {
        val host = if (session.host.isEmpty()) "wxd44977328b36e647".toByteArray() else session.host
        val plain = MmtlsCodec.jsLoginPlaintext(session.uin, hostAppId = host)
        val encrypted = MmtlsCrypto.encryptLayout(session.send, byteArrayOf(), MmtlsCodec.lz4Literal(plain))
        val packet = MmtlsCodec.shortlink(0x0b41, 0, header(session.uin, session.device, session.f9, false) + encrypted)
        val url = "/ilink/ilinkapp/mp/wxaruntime_transfer".toByteArray()
        val server = "shortcloud.weixin.com".toByteArray()
        val envelope = MmtlsCodec.u16(url.size) + url + MmtlsCodec.u16(server.size) + server + MmtlsCodec.u32(packet.size.toLong()) + packet
        return MmtlsCodec.u32(envelope.size.toLong()) + envelope
    }
    fun decryptResponse(body: ByteArray, key: ByteArray): ByteArray {
        val candidates = listOf(runCatching { headerLength(body) }.getOrDefault(-1)) + (0 until minOf(body.size, 220))
        for (offset in candidates.distinct()) {
            if (offset < 0 || body.size - offset < 29) continue
            val decoded = runCatching {
                MmtlsCodec.lz4Decompress(MmtlsCrypto.decryptLayout(key, byteArrayOf(), body.copyOfRange(offset, body.size)))
            }.getOrNull()
            if (decoded != null) return decoded
        }
        error("BUSINESS_AUTHENTICATION_FAILED")
    }
    fun code(response: ByteArray): String {
        val fields = MmtlsCodec.parseProto(response)
        val base = fields[1]
        val status = if (base is ByteArray) MmtlsCodec.parseProto(base)[1] as? Long else base as? Long
        require(status == null || status == 0L) { "WECHAT_CODE_REJECTED" }
        val code = bytes(MmtlsCodec.parseProto(bytes(fields, 2)), 3).toString(Charsets.UTF_8)
        require(Regex("[A-Za-z0-9_-]{24,128}").matches(code)) { "WX_CODE_INVALID" }
        return code
    }
}
