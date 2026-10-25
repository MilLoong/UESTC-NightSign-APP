package edu.uestc.nightsign.data.wechat

import com.google.gson.JsonParser

/** Never expose upstream URLs: their query strings can contain QR and authorization credentials. */
internal fun qrFailureDetail(path: String, body: String): String {
    val stage = when {
        path.endsWith("/poll") -> "读取扫码结果"
        path.endsWith("/confirm") -> "保存微信授权"
        else -> "生成二维码"
    }
    val message = runCatching { JsonParser.parseString(body).asJsonObject.get("msg").asString.lowercase() }
        .getOrDefault("")
    val reason = when {
        "no such host" in message || "server misbehaving" in message || "lookup " in message -> "DNS 解析失败"
        "x509:" in message || "certificate" in message -> "证书校验失败"
        "tls handshake timeout" in message -> "TLS 连接超时"
        "timeout" in message || "deadline exceeded" in message -> "连接超时"
        "network is unreachable" in message || "no route to host" in message -> "网络不可达"
        "permission denied" in message || "operation not permitted" in message -> "网络访问被系统拒绝"
        "connection reset" in message || "unexpected eof" in message || "eof" in message -> "连接中断"
        "connection refused" in message -> "连接被拒绝"
        else -> "协议核心访问微信失败"
    }
    return "$stage：$reason"
}
