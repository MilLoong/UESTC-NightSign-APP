package edu.uestc.nightsign.data.wechat.nativeprotocol

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class NativeMmtls(private val cancelled: AtomicBoolean = AtomicBoolean(false)) {
    private val network = NativeNetwork(cancelled)
    private val random = SecureRandom()
    internal class AccessPsk(val key: ByteArray, val ticket: ByteArray, val expires: Long)
    private class Login(val session: MmtlsBusiness.Session, val psk: AccessPsk)
    private fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun hash(data: ByteArray) = MmtlsCrypto.sha256(data)
    private fun epoch() = System.currentTimeMillis() / 1000
    internal fun verifyHandshake(socket: Socket, deadline: Long) { Connection(socket, deadline).establish() }
    private fun message(payload: ByteArray): Pair<Int, ByteArray> {
        val reader = MmtlsCodec.Reader(payload)
        val length = reader.u32()
        require(length in 1..reader.remaining.toLong()) { "HANDSHAKE_LENGTH_INVALID" }
        val type = reader.u8()
        return type to reader.bytes(length.toInt() - 1)
    }
    private fun packet(plain: ByteArray): Pair<Long, ByteArray> {
        val r = MmtlsCodec.Reader(plain)
        require(r.u32() == plain.size.toLong() && r.u16() == 0x1110) { "SHORTLINK_HEADER_INVALID" }
        r.u16()
        val command = r.u32()
        r.u32()
        return command to r.bytes(r.remaining)
    }

    private inner class Connection(private val socket: Socket, private val deadline: Long) {
        private val transcript = ByteArrayOutputStream()
        private var tx = 0L
        private var rx = 0L
        private lateinit var secret: ByteArray
        private lateinit var handshake: MmtlsCrypto.TrafficKeys
        private lateinit var app: MmtlsCrypto.TrafficKeys
        private lateinit var finalHash: ByteArray
        private var certHash: ByteArray? = null
        private val tickets = mutableListOf<Triple<Int, Long, ByteArray>>()
        private fun send(type: Int, data: ByteArray, key: ByteArray? = null, iv: ByteArray? = null) {
            network.remaining(deadline)
            val payload = if (key == null) data else MmtlsCrypto.encryptRecord(key, iv!!, tx, type, data)
            socket.getOutputStream().write(MmtlsCodec.record(type, payload))
            tx++
        }
        private fun receive(key: ByteArray? = null, iv: ByteArray? = null): Pair<Int, ByteArray> {
            val r = MmtlsCodec.Reader(network.read(socket, 5, deadline))
            val type = r.u8()
            require(type in setOf(0x14, 0x15, 0x16, 0x17, 0x19) && r.u16() == 0xf103) { "RECORD_HEADER_INVALID" }
            val body = network.read(socket, r.u16(), deadline)
            val payload = if (key == null) body else MmtlsCrypto.decryptRecord(key, iv!!, rx, type, body)
            rx++
            return type to payload
        }
        private fun feed(data: ByteArray) {
            require(transcript.size() + data.size <= 2 * 1024 * 1024) { "HANDSHAKE_TOO_LARGE" }
            transcript.write(data)
        }
        private fun transcriptHash() = hash(transcript.toByteArray())

        fun establish() {
            val key = MmtlsCrypto.generateEcdh()
            val second = MmtlsCrypto.generateEcdh()
            val hello = MmtlsCodec.clientHello(random(32), epoch(), MmtlsCrypto.publicPoint(key), MmtlsCrypto.publicPoint(second))
            feed(hello)
            send(0x16, hello)
            val server = receive()
            require(server.first == 0x16) { "SERVER_HELLO_MISSING" }
            val (type, body) = message(server.second)
            require(type == 2 && body.size >= 40) { "SERVER_HELLO_INVALID" }
            feed(server.second)
            val reader = MmtlsCodec.Reader(body)
            reader.u16()
            require(reader.u16() == 0xc02b) { "CIPHER_UNSUPPORTED" }
            reader.bytes(32)
            val length = reader.u32()
            require(length <= reader.remaining)
            val extension = reader.bytes(length.toInt())
            val er = MmtlsCodec.Reader(extension)
            require(er.u8() == 1) { "KEY_SHARE_MISSING" }
            val declared = er.u32()
            require(declared <= er.remaining)
            er.bytes(2)
            require(er.u32() == 1L) { "ECDH_GROUP_UNSUPPORTED" }
            val point = er.bytes(er.u16())
            secret = MmtlsCrypto.mmtlsSecret(key.private, point)
            handshake = MmtlsCrypto.handshakeKeys(secret, transcriptHash())
            repeat(64) {
                val next = receive(handshake.serverKey, handshake.serverIv)
                require(next.first == 0x16) { "HANDSHAKE_REJECTED" }
                val (kind, content) = message(next.second)
                if (kind == 0x14) {
                    val expectedHash = transcriptHash()
                    val fr = MmtlsCodec.Reader(content)
                    val proof = fr.bytes(fr.u16())
                    require(fr.remaining == 0 && MessageDigest.isEqual(proof, MmtlsCrypto.finished(secret, false, expectedHash))) {
                        "SERVER_FINISHED_INVALID"
                    }
                    finalHash = expectedHash
                    app = MmtlsCrypto.applicationKeys(MmtlsCrypto.deriveMaster(secret, finalHash), finalHash)
                    feed(next.second)
                    val verify = MmtlsCrypto.finished(secret, true, finalHash)
                    send(0x16, MmtlsCodec.handshake(0x14, MmtlsCodec.u16(verify.size) + verify), handshake.clientKey, handshake.clientIv)
                    return
                }
                feed(next.second)
                if (kind == 15) certHash = transcriptHash()
                if (kind == 4) {
                    val tr = MmtlsCodec.Reader(content)
                    repeat(tr.u8()) {
                        val size = tr.u32()
                        require(size in 5..tr.remaining.toLong())
                        val entry = tr.bytes(size.toInt())
                        val entryReader = MmtlsCodec.Reader(entry)
                        tickets += Triple(entryReader.u8(), entryReader.u32(), entry)
                    }
                    require(tr.remaining == 0)
                }
            }
            error("HANDSHAKE_LIMIT_EXCEEDED")
        }
        fun login(meta: MmtlsBusiness.Meta): Login {
            val (body, pending) = MmtlsBusiness.login(meta)
            send(0x17, MmtlsCodec.shortlink(0x0d7d, 0, body), app.clientKey, app.clientIv)
            val response = receive(app.serverKey, app.serverIv)
            require(response.first == 0x17)
            val (command, content) = packet(response.second)
            require(command == 0x0d7dL) { "MANUAL_AUTH_REJECTED" }
            val session = MmtlsBusiness.parseLogin(content, pending, meta)
            val cert = certHash ?: error("CERT_TRANSCRIPT_MISSING")
            val ticket = tickets.firstOrNull { it.first == 1 && it.second > 0 } ?: error("ACCESS_PSK_MISSING")
            return Login(session, AccessPsk(MmtlsCrypto.derivePsk(secret, cert, 1), ticket.third, epoch() + ticket.second))
        }
    }

    internal fun earlyRequest(psk: AccessPsk, envelope: ByteArray, timestamp: Long, random: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(psk.expires > timestamp) { "ACCESS_PSK_EXPIRED" }
        val hello = MmtlsCodec.pskClientHello(random, timestamp, psk.ticket)
        val block = MmtlsCrypto.expandLabel(psk.key, "early data key expansion", hash(hello), 28)
        val key = block.copyOf(16)
        val iv = block.copyOfRange(16, 28)
        val type8 = byteArrayOf(0, 0, 0, 16, 8, 0, 0, 0, 11, 1, 0, 0, 0, 6, 0, 18) + MmtlsCodec.u32(timestamp)
        val body = MmtlsCodec.record(0x19, hello) +
            MmtlsCodec.record(0x19, MmtlsCrypto.encryptRecord(key, iv, 1, 0x19, type8)) +
            MmtlsCodec.record(0x17, MmtlsCrypto.encryptRecord(key, iv, 2, 0x17, envelope)) +
            MmtlsCodec.record(0x15, MmtlsCrypto.encryptRecord(key, iv, 3, 0x15, byteArrayOf(0, 0, 0, 3, 0, 1, 1)))
        return Triple(body, hello, type8)
    }
    internal fun parseEarlyResponse(response: ByteArray, psk: ByteArray, hello: ByteArray, type8: ByteArray, receiveKey: ByteArray): String {
        val (records, consumed) = MmtlsCodec.parseRecords(response)
        require(consumed == response.size) { "EARLY_RESPONSE_TRUNCATED" }
        val server = records.firstOrNull { it.type == 0x16 }?.payload ?: error("EARLY_HELLO_MISSING")
        require(message(server).first == 2) { "EARLY_HELLO_INVALID" }
        val ciphertext = records.firstOrNull { it.type == 0x17 }?.payload ?: error("EARLY_DATA_MISSING")
        for (transcript in listOf(hello + server, hello + type8 + server, hello + server + type8)) {
            val block = MmtlsCrypto.expandLabel(psk, "handshake key expansion", hash(transcript), 28)
            for (seq in listOf(2L, 1L, 3L)) {
                val plain = runCatching { MmtlsCrypto.decryptRecord(block.copyOf(16), block.copyOfRange(16, 28), seq, 0x17, ciphertext) }.getOrNull() ?: continue
                val framed = runCatching { packet(plain).second }.getOrNull()
                for (candidate in listOfNotNull(framed, plain)) {
                    val business = runCatching { MmtlsBusiness.decryptResponse(candidate, receiveKey) }.getOrNull() ?: continue
                    return MmtlsBusiness.code(business)
                }
            }
        }
        error("EARLY_RESPONSE_AUTHENTICATION_FAILED")
    }
    fun getCode(buffer: String, progress: (String) -> Unit = {}): String {
        val deadline = NativeNetwork.now() + 70000
        val meta = MmtlsBusiness.meta(buffer)
        progress("正在连接微信…")
        val (long, short) = network.targets(NativeNetwork.now() + 5000)
        progress("正在验证微信授权…")
        val loginDeadline = NativeNetwork.now() + (deadline - NativeNetwork.now()) * 65 / 100
        var login: Login? = null
        var connectionError: Exception? = null
        for ((index, target) in long.withIndex()) {
            val attempt = minOf(loginDeadline, NativeNetwork.now() + network.remaining(loginDeadline) / (long.size - index))
            var manualStarted = false
            try {
                login = network.socket(target, attempt) { socket -> Connection(socket, attempt).run {
                    establish()
                    manualStarted = true
                    this.login(meta)
                } }
                break
            } catch (e: Exception) {
                connectionError = e
                if (cancelled.get()) error("AUTH_CANCELLED")
                if (e.message in setOf("MANUAL_AUTH_REJECTED", "MANUAL_AUTH_RESPONSE_INVALID", "SERVER_FINISHED_INVALID")) throw e
                // login_buffer can be one-use; never replay it after sending ManualAuth.
                if (manualStarted) throw IllegalStateException("MANUAL_AUTH_FAILED")
            }
        }
        val ready = login ?: throw IllegalStateException("MMTLS_CONNECTION_FAILED", connectionError)
        val envelope = MmtlsBusiness.transfer(ready.session)
        progress("正在获取授权…")
        for ((index, target) in short.withIndex()) {
            val attempt = NativeNetwork.now() + network.remaining(deadline) / (short.size - index)
            val (body, hello, type8) = earlyRequest(ready.psk, envelope, epoch(), random(32))
            val response = try {
                network.http(target, "shortcloud.weixin.com", "/mmtls/${epoch().toString(16).padStart(8, '0')}", body, attempt)
            } catch (_: Exception) { if (cancelled.get()) error("AUTH_CANCELLED"); continue }
            return parseEarlyResponse(response, ready.psk.key, hello, type8, ready.session.receive)
        }
        error("MMTLS_SHORTLINK_FAILED")
    }
}
