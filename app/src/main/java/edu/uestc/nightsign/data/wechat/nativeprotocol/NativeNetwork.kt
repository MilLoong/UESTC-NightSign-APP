package edu.uestc.nightsign.data.wechat.nativeprotocol

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeNetwork(val cancelled: AtomicBoolean) {
    data class Target(val host: String, val port: Int)
    companion object {
        private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "wechat-deadline").apply { isDaemon = true } }
        private val resolver = Executors.newFixedThreadPool(2) { Thread(it, "wechat-dns").apply { isDaemon = true } }
        fun now(): Long = System.nanoTime() / 1_000_000
    }
    fun remaining(deadline: Long): Int {
        check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "AUTH_CANCELLED" }
        val left = deadline - now()
        require(left > 0) { "NETWORK_DEADLINE_EXCEEDED" }
        return minOf(left, Int.MAX_VALUE.toLong()).toInt()
    }
    fun <T> socket(target: Target, deadline: Long, block: (Socket) -> T): T {
        require(target.port in 1..65535)
        val lookup = resolver.submit<Array<InetAddress>> { InetAddress.getAllByName(target.host) }
        val addresses = try { lookup.get(remaining(deadline).toLong(), TimeUnit.MILLISECONDS) } finally { lookup.cancel(true) }
        var last: Exception? = null
        for (address in addresses) {
            remaining(deadline)
            val socket = Socket()
            var connected = false
            val timer = watchdog.scheduleAtFixedRate({
                if (cancelled.get() || now() >= deadline) runCatching { socket.close() }
            }, 0, 100, TimeUnit.MILLISECONDS)
            try {
                socket.connect(InetSocketAddress(address, target.port), remaining(deadline))
                connected = true
                socket.soTimeout = remaining(deadline)
                return block(socket)
            } catch (e: Exception) { if (connected) throw e; last = e }
            finally { timer.cancel(false); socket.close() }
        }
        throw last ?: IllegalStateException("DNS_EMPTY_RESPONSE")
    }
    fun read(socket: Socket, count: Int, deadline: Long): ByteArray {
        require(count in 0..16 * 1024 * 1024)
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            socket.soTimeout = remaining(deadline)
            val received = socket.getInputStream().read(bytes, offset, count - offset)
            check(received > 0) { "NETWORK_EOF" }
            offset += received
        }
        return bytes
    }
    fun http(target: Target, host: String, path: String, body: ByteArray?, deadline: Long): ByteArray = socket(target, deadline) { socket ->
        require(!host.contains('\r') && !host.contains('\n') && !path.contains('\r') && !path.contains('\n'))
        val method = if (body == null) "GET" else "POST"
        val headers = "$method $path HTTP/1.0\r\nHost: $host\r\nConnection: close\r\nUser-Agent: MicroMessenger Client\r\n" +
            if (body == null) "\r\n" else "Content-Type: application/octet-stream\r\nUpgrade: mmtls\r\nX-Online-Host: $host\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
        if (body != null) socket.getOutputStream().write(body)
        val input = socket.getInputStream()
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                socket.soTimeout = remaining(deadline)
                val value = input.read()
                check(value >= 0 && out.size() < 65536) { "HTTP_HEADER_INVALID" }
                if (value == 10) return out.toString("US-ASCII").removeSuffix("\r")
                out.write(value)
            }
        }
        require(line().split(' ').getOrNull(1) == "200") { "PROTOCOL_HTTP_FAILED" }
        val responseHeaders = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val item = line()
            headerBytes += item.length
            require(headerBytes <= 65536)
            if (item.isEmpty()) break
            val split = item.indexOf(':')
            if (split > 0) responseHeaders[item.substring(0, split).lowercase()] = item.substring(split + 1).trim()
        }
        val output = ByteArrayOutputStream()
        fun append(bytes: ByteArray) {
            require(bytes.size <= 16 * 1024 * 1024 - output.size()) { "HTTP_BODY_TOO_LARGE" }
            output.write(bytes)
        }
        if (responseHeaders["transfer-encoding"].orEmpty().contains("chunked", true)) {
            while (true) {
                val length = line().substringBefore(';').trim().toLong(16)
                require(length in 0..(16 * 1024 * 1024 - output.size()).toLong())
                if (length == 0L) break
                append(read(socket, length.toInt(), deadline))
                require(line().isEmpty())
            }
        } else if (responseHeaders.containsKey("content-length")) {
            val length = responseHeaders.getValue("content-length").toLong()
            require(length in 0..16L * 1024 * 1024)
            append(read(socket, length.toInt(), deadline))
        } else {
            val buffer = ByteArray(8192)
            while (true) {
                socket.soTimeout = remaining(deadline)
                val count = input.read(buffer)
                if (count < 0) break
                append(buffer.copyOf(count))
            }
        }
        output.toByteArray()
    }
    fun targets(deadline: Long): Pair<List<Target>, List<Target>> {
        val long = mutableListOf<Target>()
        val short = mutableListOf<Target>()
        for (host in listOf("aedns.weixin.qq.com", "180.153.202.85")) {
            try {
                val attempt = minOf(deadline, now() + remaining(deadline) / if (host.startsWith("aedns")) 2 else 1)
                val raw = http(Target(host, 80), "aedns.weixin.qq.com",
                    "/cgi-bin/default/getdns?clientversion=0&devicetype=Windows&uin=0&format=json", null, attempt)
                val dns = JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("dns")
                require(!dns.has("retcode") || dns["retcode"].asInt == 0)
                for (item in dns.getAsJsonArray("domainlist")) {
                    val domain = item.asJsonObject
                    val name = domain["name"].asString
                    if (name !in setOf("longcloud.weixin.com", "shortcloud.weixin.com")) continue
                    val ips = domain.getAsJsonArray("iplist").map { it.asJsonObject["ip"].asString }
                        .filter { it.length <= 64 && Regex("[0-9a-fA-F:.]+").matches(it) }
                    for (proto in domain.getAsJsonArray("protocollist")) {
                        val kind = proto.asJsonObject["name"].asString
                        val ports = proto.asJsonObject.getAsJsonArray("portlist").map { it.asInt }.filter { it in 1..65535 }
                        if ((name == "longcloud.weixin.com" && kind == "mmtlsovertcp") || (name == "shortcloud.weixin.com" && kind == "http"))
                            for (ip in ips) for (port in ports) (if (name.startsWith("long")) long else short).add(Target(ip, port))
                    }
                }
                break
            } catch (_: Exception) { if (cancelled.get()) error("AUTH_CANCELLED") }
        }
        val preference = listOf(443, 8080, 80, 5000)
        val ordered = long.distinct().sortedBy { preference.indexOf(it.port).let { n -> if (n < 0) 99 else n } }.take(5) + Target("longcloud.weixin.com", 443)
        return ordered.distinct() to (short.filter { it.port == 80 }.distinct() + Target("120.241.131.173", 80)).distinct()
    }
}
