package edu.uestc.nightsign.data.wechat.nativeprotocol

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeHandshakeTest {
    private fun receive(socket: Socket): MmtlsCodec.Record {
        val stream = socket.getInputStream()
        val header = stream.readNBytes(5)
        require(header.size == 5)
        val size = ((header[3].toInt() and 255) shl 8) or (header[4].toInt() and 255)
        val body = stream.readNBytes(size)
        require(body.size == size)
        return MmtlsCodec.Record(header[0].toInt() and 255, body)
    }

    private fun exercise(tamper: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val task = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val hello = receive(socket).payload
                    val r = MmtlsCodec.Reader(hello)
                    r.u32(); r.u8(); r.bytes(3); r.u16(); r.bytes(32); r.u32(); r.u32()
                    r.u8(); r.u32(); r.bytes(3); r.u32(); r.u32()
                    val peer = r.bytes(r.u16())
                    val key = MmtlsCrypto.generateEcdh()
                    val point = MmtlsCrypto.publicPoint(key)
                    val share = byteArrayOf(0, 0) + MmtlsCodec.u32(1) + MmtlsCodec.u16(point.size) + point
                    val extension = byteArrayOf(1) + MmtlsCodec.u32(share.size.toLong()) + share
                    val serverHello = MmtlsCodec.handshake(2, byteArrayOf(3, 0xf1.toByte()) + MmtlsCodec.u16(0xc02b) +
                        ByteArray(32) + MmtlsCodec.u32(extension.size.toLong()) + extension)
                    socket.getOutputStream().write(MmtlsCodec.record(22, serverHello))
                    val secret = MmtlsCrypto.mmtlsSecret(key.private, peer)
                    val keys = MmtlsCrypto.handshakeKeys(secret, MmtlsCrypto.sha256(hello + serverHello))
                    val cert = MmtlsCodec.handshake(15, "synthetic-certificate".toByteArray())
                    val ticket = byteArrayOf(1) + MmtlsCodec.u32(7200) + "synthetic-ticket".toByteArray()
                    val tickets = MmtlsCodec.handshake(4, byteArrayOf(1) + MmtlsCodec.u32(ticket.size.toLong()) + ticket)
                    val hash = MmtlsCrypto.sha256(hello + serverHello + cert + tickets)
                    val proof = MmtlsCrypto.finished(secret, false, hash)
                    if (tamper) proof[0] = (proof[0].toInt() xor 1).toByte()
                    val finished = MmtlsCodec.handshake(20, MmtlsCodec.u16(proof.size) + proof)
                    listOf(cert, tickets, finished).forEachIndexed { index, payload ->
                        socket.getOutputStream().write(MmtlsCodec.record(22,
                            MmtlsCrypto.encryptRecord(keys.serverKey, keys.serverIv, index.toLong() + 1, 22, payload)))
                    }
                    if (!tamper) {
                        val response = receive(socket)
                        assertEquals(22, response.type)
                        val clientFinished = MmtlsCrypto.decryptRecord(keys.clientKey, keys.clientIv, 1, 22, response.payload)
                        val expected = MmtlsCrypto.finished(secret, true, hash)
                        assertArrayEquals(MmtlsCodec.handshake(20, MmtlsCodec.u16(expected.size) + expected), clientFinished)
                    }
                }
            }
            try {
                Socket("127.0.0.1", server.localPort).use { socket ->
                    val outcome = runCatching { NativeMmtls().verifyHandshake(socket, NativeNetwork.now() + 5000) }
                    if (tamper) {
                        assertTrue(outcome.isFailure)
                        assertEquals("SERVER_FINISHED_INVALID", outcome.exceptionOrNull()?.message)
                    } else outcome.getOrThrow()
                }
                task.get(6, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun completesHandshakeOverRealSocket() { exercise(false) }
    @Test fun rejectsAuthenticatedButIncorrectServerFinished() { exercise(true) }
}
