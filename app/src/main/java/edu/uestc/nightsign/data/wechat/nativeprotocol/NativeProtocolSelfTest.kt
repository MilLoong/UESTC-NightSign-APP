package edu.uestc.nightsign.data.wechat.nativeprotocol

import java.security.MessageDigest

/** Exercises the device's actual JCA provider without network or account data. */
object NativeProtocolSelfTest {
    fun run() {
        val first = MmtlsCrypto.generateEcdh()
        val second = MmtlsCrypto.generateEcdh()
        val secret = MmtlsCrypto.mmtlsSecret(first.private, MmtlsCrypto.publicPoint(second))
        check(MessageDigest.isEqual(secret, MmtlsCrypto.mmtlsSecret(second.private, MmtlsCrypto.publicPoint(first))))
        val keys = MmtlsCrypto.handshakeKeys(secret, MmtlsCrypto.sha256("self-test".toByteArray()))
        val plain = "native-android-test".toByteArray()
        val cipher = MmtlsCrypto.encryptRecord(keys.clientKey, keys.clientIv, 1, 23, plain)
        check(MessageDigest.isEqual(plain, MmtlsCrypto.decryptRecord(keys.clientKey, keys.clientIv, 1, 23, cipher)))
        val corrupted = cipher.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(runCatching { MmtlsCrypto.decryptRecord(keys.clientKey, keys.clientIv, 1, 23, corrupted) }.isFailure)
        check(MessageDigest.isEqual(plain, MmtlsCodec.lz4Decompress(MmtlsCodec.lz4Literal(plain))))
        val proto = MmtlsCodec.parseProto(MmtlsCodec.pbVar(1, 1029) + MmtlsCodec.pbBytes(2, plain))
        check(proto[1] == 1029L && MessageDigest.isEqual(proto[2] as ByteArray, plain))
        val frame = MmtlsCodec.record(23, cipher)
        val records = MmtlsCodec.parseRecords(frame)
        check(records.second == frame.size && records.first.size == 1)
    }
}
