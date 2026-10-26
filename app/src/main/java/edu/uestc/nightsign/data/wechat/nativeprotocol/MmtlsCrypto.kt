package edu.uestc.nightsign.data.wechat.nativeprotocol

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** MMTLS labels and record layout differ from standard TLS 1.3. */
object MmtlsCrypto {
    private val random = SecureRandom()
    private val curve: ECParameterSpec get() = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)

    class TrafficKeys(val clientKey: ByteArray, val serverKey: ByteArray,
                      val clientIv: ByteArray, val serverIv: ByteArray) {
        override fun toString() = "TrafficKeys(redacted)"
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

    fun hkdfExtract(salt: ByteArray, input: ByteArray): ByteArray = hmac(salt, input)

    fun hkdfExpand(key: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..8160) { "HKDF_LENGTH_INVALID" }
        val output = ByteArray(length)
        var previous = byteArrayOf()
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(key, previous + info + byteArrayOf(counter++.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
        }
        return output
    }

    fun expandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray =
        hkdfExpand(secret, label.toByteArray(Charsets.US_ASCII) + context, length)

    fun generateEcdh(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    private fun coordinate(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        require(value.signum() >= 0 && value.bitLength() <= 256)
        return ByteArray(32).also { raw.copyInto(it, 32 - minOf(32, raw.size), maxOf(0, raw.size - 32)) }
    }

    fun publicPoint(pair: KeyPair): ByteArray {
        val point = (pair.public as ECPublicKey).w
        return byteArrayOf(4) + coordinate(point.affineX) + coordinate(point.affineY)
    }

    fun rawEcdh(privateKey: PrivateKey, peer: ByteArray): ByteArray {
        require(peer.size == 65 && peer[0] == 4.toByte()) { "ECDH_POINT_INVALID" }
        val spec = curve
        val point = ECPoint(BigInteger(1, peer.copyOfRange(1, 33)), BigInteger(1, peer.copyOfRange(33, 65)))
        val prime = (spec.curve.field as java.security.spec.ECFieldFp).p
        val x = point.affineX
        val y = point.affineY
        require(x < prime && y < prime && y.modPow(BigInteger.valueOf(2), prime) ==
            (x.modPow(BigInteger.valueOf(3), prime) + spec.curve.a * x + spec.curve.b).mod(prime)) {
            "ECDH_POINT_INVALID"
        }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
    }

    fun mmtlsSecret(privateKey: PrivateKey, peer: ByteArray) = sha256(rawEcdh(privateKey, peer))

    fun nonce(iv: ByteArray, sequence: Long): ByteArray {
        require(iv.size == 12) { "IV_LENGTH_INVALID" }
        val result = iv.copyOf()
        for (i in 0..7) result[4 + i] = (result[4 + i].toInt() xor (sequence ushr (56 - i * 8)).toInt()).toByte()
        return result
    }

    fun recordAad(sequence: Long, type: Int, ciphertextLength: Int): ByteArray {
        require(type in 0..255 && ciphertextLength in 0..65535) { "RECORD_LENGTH_INVALID" }
        return ByteBuffer.allocate(13).putLong(sequence).put(type.toByte()).putShort(0xf103.toShort())
            .putShort(ciphertextLength.toShort()).array()
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
        require(key.size in setOf(16, 24, 32) && iv.size == 12) { "GCM_PARAMETERS_INVALID" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }

    fun encryptRecord(key: ByteArray, iv: ByteArray, sequence: Long, type: Int, plaintext: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce(iv, sequence), plaintext, recordAad(sequence, type, plaintext.size + 16))

    fun decryptRecord(key: ByteArray, iv: ByteArray, sequence: Long, type: Int, ciphertext: ByteArray): ByteArray =
        gcm(Cipher.DECRYPT_MODE, key, nonce(iv, sequence), ciphertext, recordAad(sequence, type, ciphertext.size))

    fun encryptLayout(key: ByteArray, aad: ByteArray, plaintext: ByteArray,
                      iv: ByteArray = ByteArray(12).also(random::nextBytes)): ByteArray {
        val encrypted = gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)
        return encrypted.copyOfRange(0, encrypted.size - 16) + iv + encrypted.takeLast(16).toByteArray()
    }

    fun decryptLayout(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 28) { "GCM_BLOB_TRUNCATED" }
        return gcm(Cipher.DECRYPT_MODE, key, blob.copyOfRange(blob.size - 28, blob.size - 16),
            blob.copyOfRange(0, blob.size - 28) + blob.takeLast(16).toByteArray(), aad)
    }

    fun deriveMaster(secret: ByteArray, hash: ByteArray) = expandLabel(secret, "expanded secret", hash, 32)

    private fun split(block: ByteArray) = TrafficKeys(block.copyOfRange(0, 16), block.copyOfRange(16, 32),
        block.copyOfRange(32, 44), block.copyOfRange(44, 56))

    fun handshakeKeys(secret: ByteArray, hash: ByteArray) = split(expandLabel(secret, "handshake key expansion", hash, 56))
    fun applicationKeys(master: ByteArray, hash: ByteArray) = split(expandLabel(master, "application data key expansion", hash, 56))
    fun finished(secret: ByteArray, client: Boolean, hash: ByteArray) =
        hmac(expandLabel(secret, if (client) "client finished" else "server finished", byteArrayOf(), 32), hash)
    fun derivePsk(secret: ByteArray, hash: ByteArray, type: Int) =
        expandLabel(secret, if (type == 1) "PSK_ACCESS" else "PSK_REFRESH", hash, 32)
}
