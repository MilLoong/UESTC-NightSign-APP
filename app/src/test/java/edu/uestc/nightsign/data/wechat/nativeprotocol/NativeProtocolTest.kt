package edu.uestc.nightsign.data.wechat.nativeprotocol

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec

class NativeProtocolTest {
    @Test fun deviceSelfTestAlsoRunsOnJvm() { NativeProtocolSelfTest.run() }
    @Test fun yybRequestSignatureMatchesPython() {
        assertEquals("7ca4093eff999281379f19d1cb83ef6a", NativeYybAuth.signature("{}", "1792998000000", "1234"))
    }
    private val vectors get() = javaClass.getResourceAsStream("/native-protocol-vectors.json")!!.bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }
    private fun String.bytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun cryptoMatchesPythonReference() {
        for (item in vectors.getAsJsonArray("crypto")) {
            val row = item.asJsonObject
            fun b(name: String) = row[name].asString.bytes()
            val sequence = row["sequence"].asLong
            assertArrayEquals(b("nonce"), MmtlsCrypto.nonce(b("iv"), sequence))
            assertArrayEquals(b("aad"), MmtlsCrypto.recordAad(sequence, 23, b("plain").size + 16))
            val encrypted = MmtlsCrypto.encryptRecord(b("key"), b("iv"), sequence, 23, b("plain"))
            assertArrayEquals(b("cipher"), encrypted)
            assertArrayEquals(b("plain"), MmtlsCrypto.decryptRecord(b("key"), b("iv"), sequence, 23, encrypted))
            assertArrayEquals(b("layout"), MmtlsCrypto.encryptLayout(b("key"), b("hash"), b("plain"), b("iv")))
            assertArrayEquals(b("plain"), MmtlsCrypto.decryptLayout(b("key"), b("hash"), b("layout")))
            assertArrayEquals(b("master"), MmtlsCrypto.deriveMaster(b("secret"), b("hash")))
            val handshake = MmtlsCrypto.handshakeKeys(b("secret"), b("hash"))
            assertArrayEquals(b("handshake"), handshake.clientKey + handshake.serverKey + handshake.clientIv + handshake.serverIv)
            val app = MmtlsCrypto.applicationKeys(b("master"), b("hash"))
            assertArrayEquals(b("application"), app.clientKey + app.serverKey + app.clientIv + app.serverIv)
            assertArrayEquals(b("finished"), MmtlsCrypto.finished(b("secret"), true, b("hash")))
            assertArrayEquals(b("psk"), MmtlsCrypto.derivePsk(b("secret"), b("hash"), 1))
            assertArrayEquals(b("expand"), MmtlsCrypto.hkdfExpand(b("secret"), b("hash"), b("expand").size))
            encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
            reject { MmtlsCrypto.decryptRecord(b("key"), b("iv"), sequence, 23, encrypted) }
            reject { MmtlsCrypto.decryptRecord(b("key"), b("iv"), sequence + 1, 23, b("cipher")) }
        }
    }

    @Test fun p256MatchesPythonAndRejectsInvalidPoints() {
        val spec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        for (item in vectors.getAsJsonArray("ecdh")) {
            val row = item.asJsonObject
            val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(row["scalar"].asString), spec))
            assertArrayEquals(row["secret"].asString.bytes(), MmtlsCrypto.mmtlsSecret(key, row["peer"].asString.bytes()))
            reject { MmtlsCrypto.rawEcdh(key, byteArrayOf(4) + ByteArray(64)) }
        }
        val first = MmtlsCrypto.generateEcdh()
        val second = MmtlsCrypto.generateEcdh()
        assertArrayEquals(MmtlsCrypto.mmtlsSecret(first.private, MmtlsCrypto.publicPoint(second)),
            MmtlsCrypto.mmtlsSecret(second.private, MmtlsCrypto.publicPoint(first)))
    }

    @Test fun packetBytesMatchPythonReference() {
        for (item in vectors.getAsJsonArray("codec")) {
            val row = item.asJsonObject
            fun b(name: String) = row[name].asString.bytes()
            val data = b("payload")
            assertArrayEquals(b("lz4"), MmtlsCodec.lz4Literal(data))
            assertArrayEquals(data, MmtlsCodec.lz4Decompress(b("lz4")))
            assertArrayEquals(b("proto"), MmtlsCodec.pbVar(1, 1029) + MmtlsCodec.pbBytes(2, data))
            assertArrayEquals(data, MmtlsCodec.parseProto(b("proto"))[2] as ByteArray)
            assertEquals(1029L, MmtlsCodec.parseProto(b("proto"))[1])
            assertArrayEquals(b("wpkg"), MmtlsCodec.wpkgHead(mapOf(1 to 1L, 20 to 1504L), mapOf(14 to byteArrayOf(), 24 to data)))
            assertArrayEquals(b("record"), MmtlsCodec.record(23, data))
            assertArrayEquals(b("handshake"), MmtlsCodec.handshake(1, data))
            assertArrayEquals(b("shortlink"), MmtlsCodec.shortlink(1029, 16, data, 0))
            assertArrayEquals(b("hello"), MmtlsCodec.clientHello(b("random"), 1792998000L, b("first"), b("second")))
            assertArrayEquals(b("pskHello"), MmtlsCodec.pskClientHello(b("random"), 1792998000L, data))
            assertArrayEquals(b("jsLogin"), MmtlsCodec.jsLoginPlaintext(12345678, "02-01-02-03-04-05".toByteArray()))
            val frame = b("record")
            for (cut in frame.indices) assertEquals(0, MmtlsCodec.parseRecords(frame.copyOf(cut)).second)
            val parsed = MmtlsCodec.parseRecords(frame + frame)
            assertEquals(frame.size * 2, parsed.second)
            assertEquals(2, parsed.first.size)
        }
    }

    @Test fun malformedPacketsAreRejected() {
        reject { MmtlsCodec.parseProto(byteArrayOf(0)) }
        reject { MmtlsCodec.parseProto(byteArrayOf(10, 127, 1)) }
        reject { MmtlsCodec.Reader(ByteArray(10) { 0xff.toByte() }).varint() }
        reject { MmtlsCodec.lz4Decompress(byteArrayOf(0, 0, 0)) }
        reject { MmtlsCodec.lz4Decompress(MmtlsCodec.lz4Literal(ByteArray(20)), 19) }
        reject { MmtlsCrypto.hkdfExpand(ByteArray(32), byteArrayOf(), 8161) }
        reject { MmtlsCrypto.recordAad(0, 23, 65536) }
        reject { MmtlsCrypto.decryptLayout(ByteArray(16), byteArrayOf(), ByteArray(27)) }
        for (n in listOf(0L, 127L, 128L, 65535L, Long.MAX_VALUE, Long.MIN_VALUE, -1L))
            assertEquals(n, MmtlsCodec.Reader(MmtlsCodec.varint(n)).varint())
        assertArrayEquals("aaaaa".toByteArray(), MmtlsCodec.lz4Decompress(byteArrayOf(0x10, 97, 1, 0)))
    }

    @Test fun manualAuthorizationResponseMatchesPython() {
        val spec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        for (item in vectors.getAsJsonArray("business")) {
            val row = item.asJsonObject
            fun b(name: String) = row[name].asString.bytes()
            val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(row["scalar"].asString), spec))
            val pending = MmtlsBusiness.Pending(private, b("okm"), b("compressed"))
            val meta = MmtlsBusiness.Meta("ticket".toByteArray(), "device".toByteArray(), byteArrayOf())
            val session = MmtlsBusiness.parseLogin(b("response"), pending, meta)
            assertArrayEquals(b("send"), session.send)
            assertArrayEquals(b("receive"), session.receive)
            assertEquals(12345678L, session.uin)
            val damaged = b("response").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            reject { MmtlsBusiness.parseLogin(damaged, pending, meta) }
        }
    }

    @Test fun fullEarlyDataPacketAndResponseMatchPython() {
        val transport = NativeMmtls()
        for (item in vectors.getAsJsonArray("early")) {
            val row = item.asJsonObject
            fun b(name: String) = row[name].asString.bytes()
            val psk = NativeMmtls.AccessPsk(b("psk"), "fixture-ticket".toByteArray(), 1793005200)
            val (request, hello, type8) = transport.earlyRequest(psk, b("envelope"), 1792998000, b("random"))
            assertArrayEquals(b("request"), request)
            assertArrayEquals(b("hello"), hello)
            assertArrayEquals(b("type8"), type8)
            assertEquals("c".repeat(32), transport.parseEarlyResponse(b("response"), b("psk"), hello, type8, b("receive")))
            val damaged = b("response").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            reject { transport.parseEarlyResponse(damaged, b("psk"), hello, type8, b("receive")) }
        }
    }
}
