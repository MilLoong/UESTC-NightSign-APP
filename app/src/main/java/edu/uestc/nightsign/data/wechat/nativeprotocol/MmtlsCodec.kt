package edu.uestc.nightsign.data.wechat.nativeprotocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Bounded binary codecs; errors never include packet contents. */
object MmtlsCodec {
    private const val MAX_OUTPUT = 16 * 1024 * 1024
    private fun field(value: Int) { require(value in 1..0x1fffffff) { "PROTO_FIELD_INVALID" } }
    fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream(10)
        while (remaining and -128L != 0L) {
            output.write(((remaining and 127) or 128).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }

    class Reader(val data: ByteArray) {
        var offset = 0
            private set
        val remaining get() = data.size - offset
        fun bytes(length: Int): ByteArray {
            require(length >= 0 && length <= remaining) { "PACKET_TRUNCATED" }
            return data.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun u8(): Int = bytes(1)[0].toInt() and 255
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = ByteBuffer.wrap(bytes(4)).int.toLong() and 0xffffffffL
        fun varint(): Long {
            var result = 0L
            for (i in 0..9) {
                val next = u8()
                require(i != 9 || next <= 1) { "VARINT_OVERFLOW" }
                result = result or ((next and 127).toLong() shl (7 * i))
                if (next < 128) return result
            }
            error("VARINT_OVERFLOW")
        }
    }

    fun pbVar(number: Int, value: Long): ByteArray {
        field(number)
        return varint(number.toLong() shl 3) + varint(value)
    }
    fun pbBytes(number: Int, value: ByteArray): ByteArray {
        field(number)
        return varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    }
    fun parseProto(data: ByteArray): Map<Int, Any> {
        require(data.size <= MAX_OUTPUT) { "PROTO_TOO_LARGE" }
        val reader = Reader(data)
        val result = linkedMapOf<Int, Any>()
        while (reader.remaining > 0) {
            val tag = reader.varint()
            require(tag ushr 3 in 1L..0x1fffffffL) { "PROTO_FIELD_INVALID" }
            val number = (tag ushr 3).toInt()
            result[number] = when ((tag and 7).toInt()) {
                0 -> reader.varint()
                1 -> ByteBuffer.wrap(reader.bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
                2 -> {
                    val length = reader.varint()
                    require(length in 0L..reader.remaining.toLong()) { "PROTO_LENGTH_INVALID" }
                    reader.bytes(length.toInt())
                }
                5 -> ByteBuffer.wrap(reader.bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                else -> error("PROTO_WIRE_TYPE_INVALID")
            }
        }
        return result
    }

    fun lz4Literal(data: ByteArray): ByteArray {
        require(data.size <= MAX_OUTPUT)
        val output = ByteArrayOutputStream()
        output.write(minOf(data.size, 15) shl 4)
        if (data.size >= 15) {
            var extra = data.size - 15
            while (extra >= 255) { output.write(255); extra -= 255 }
            output.write(extra)
        }
        output.write(data)
        return output.toByteArray()
    }

    fun lz4Decompress(data: ByteArray, maxOutput: Int = MAX_OUTPUT): ByteArray {
        require(maxOutput in 0..MAX_OUTPUT)
        val reader = Reader(data)
        var buffer = ByteArray(minOf(4096, maxOutput))
        var size = 0
        fun reserve(count: Int) {
            require(count >= 0 && count <= maxOutput - size) { "LZ4_OUTPUT_TOO_LARGE" }
            if (size + count > buffer.size) buffer = buffer.copyOf(minOf(maxOutput, maxOf(size + count, buffer.size * 2)))
        }
        fun length(base: Int): Int {
            var result = base
            if (base == 15) do {
                val extra = reader.u8()
                require(result <= maxOutput - extra) { "LZ4_OUTPUT_TOO_LARGE" }
                result += extra
            } while (extra == 255)
            return result
        }
        while (reader.remaining > 0) {
            val token = reader.u8()
            val literal = length(token ushr 4)
            reserve(literal)
            reader.bytes(literal).copyInto(buffer, size)
            size += literal
            if (reader.remaining == 0) break
            val distance = reader.u8() or (reader.u8() shl 8)
            require(distance in 1..size) { "LZ4_OFFSET_INVALID" }
            val match = length(token and 15) + 4
            reserve(match)
            repeat(match) { buffer[size] = buffer[size - distance]; size++ }
        }
        return buffer.copyOf(size)
    }

    fun wpkgHead(ints: Map<Int, Long>, bytes: Map<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(varint(1))
        ints.toSortedMap().forEach { (number, value) -> field(number); output.write(varint(number.toLong())); output.write(varint(value)) }
        output.write(0)
        bytes.toSortedMap().forEach { (number, value) ->
            field(number); output.write(varint(number.toLong())); output.write(varint(value.size.toLong())); output.write(value)
        }
        val prefix = output.size()
        output.write(0)
        output.write(varint(prefix.toLong() + 1))
        return output.toByteArray()
    }

    fun u16(value: Int): ByteArray {
        require(value in 0..65535)
        return ByteBuffer.allocate(2).putShort(value.toShort()).array()
    }
    fun u32(value: Long): ByteArray {
        require(value in 0..0xffffffffL)
        return ByteBuffer.allocate(4).putInt(value.toInt()).array()
    }

    class Record(val type: Int, val payload: ByteArray) {
        override fun toString() = "Record(type=$type, bytes=${payload.size})"
    }
    fun record(type: Int, payload: ByteArray): ByteArray {
        require(type in 0..255 && payload.size <= 65535)
        return byteArrayOf(type.toByte()) + u16(0xf103) + u16(payload.size) + payload
    }
    fun parseRecords(data: ByteArray): Pair<List<Record>, Int> {
        val result = mutableListOf<Record>()
        var offset = 0
        while (data.size - offset >= 5) {
            val type = data[offset].toInt() and 255
            require(type in setOf(0x14, 0x15, 0x16, 0x17, 0x19) && data[offset + 1] == 0xf1.toByte() &&
                data[offset + 2] == 3.toByte()) { "RECORD_HEADER_INVALID" }
            val length = ((data[offset + 3].toInt() and 255) shl 8) or (data[offset + 4].toInt() and 255)
            if (length > data.size - offset - 5) break
            result += Record(type, data.copyOfRange(offset + 5, offset + 5 + length))
            offset += 5 + length
        }
        return result to offset
    }

    fun handshake(type: Int, body: ByteArray): ByteArray {
        require(type in 0..255)
        return u32(body.size.toLong() + 1) + byteArrayOf(type.toByte()) + body
    }

    fun clientHello(random: ByteArray, timestamp: Long, first: ByteArray, second: ByteArray): ByteArray {
        require(random.size == 32 && first.size == 65 && second.size == 65)
        val firstShare = u32(1) + u16(first.size) + first
        val secondShare = u32(2) + u16(second.size) + second
        val shares = byteArrayOf(0, 0x10, 2) + u32(firstShare.size.toLong()) + firstShare +
            u32(secondShare.size.toLong()) + secondShare + u32(1)
        val extension = byteArrayOf(1) + u32(shares.size.toLong()) + shares
        return handshake(1, byteArrayOf(3, 0xf1.toByte(), 1) + u16(0xc02b) + random + u32(timestamp) +
            u32(extension.size.toLong()) + extension)
    }

    fun pskClientHello(random: ByteArray, timestamp: Long, ticket: ByteArray): ByteArray {
        require(random.size == 32 && ticket.size <= MAX_OUTPUT)
        val contents = byteArrayOf(0, 15, 1) + u32(ticket.size.toLong()) + ticket
        val extension = byteArrayOf(1) + u32(contents.size.toLong()) + contents
        return handshake(1, byteArrayOf(3, 0xf1.toByte(), 1) + u16(0xa8) + random + u32(timestamp) +
            u32(extension.size.toLong()) + extension)
    }

    fun shortlink(command: Long, sequence: Long, body: ByteArray, version: Int = 0x076d): ByteArray =
        u32(body.size.toLong() + 16) + u16(0x1110) + u16(if (version == 0) 0x076d else version) +
            u32(command) + u32(sequence) + body

    fun jsLoginPlaintext(uin: Long, device: ByteArray = randomSessionDevice(),
                         hostAppId: ByteArray = "wxd44977328b36e647".toByteArray()): ByteArray {
        val appId = "wxaa78bf2905f01cee".toByteArray()
        fun session(platform: String) = pbBytes(1, "sessionkey".toByteArray()) + pbVar(2, uin and 0xffffffffL) +
            pbBytes(3, device) + pbVar(4, 1661404927) + pbBytes(5, platform.toByteArray()) + pbVar(6, 0)
        val body = pbBytes(1, session("UnifiedPCWindows")) + pbBytes(2, appId) + pbVar(4, 1) +
            pbBytes(5, byteArrayOf()) + pbBytes(6, byteArrayOf()) + pbVar(7, 1)
        return pbBytes(1, session("Windows")) + pbBytes(2, "/cgi-bin/mmbiz-bin/js-login".toByteArray()) +
            pbBytes(3, hostAppId) + pbVar(4, 5) + pbBytes(5, body) + pbBytes(6, appId) +
            pbVar(7, 1029) + pbVar(8, 1610627409) + pbBytes(9, "WindowsxWebPlugin".toByteArray()) + pbVar(10, 573651281)
    }

    private fun randomSessionDevice(): ByteArray {
        val raw = ByteArray(6).also(SecureRandom()::nextBytes)
        raw[0] = ((raw[0].toInt() or 2) and 0xfe).toByte()
        return raw.joinToString("-") { "%02X".format(it.toInt() and 255) }.toByteArray(Charsets.US_ASCII)
    }
}
