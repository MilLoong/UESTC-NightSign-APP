package edu.uestc.nightsign.data.wechat

import org.junit.Assert.*
import org.junit.Test

class WechatQrDiagnosticsTest {
    @Test fun identifiesPollDnsFailureWithoutLeakingQrUrl() {
        val body = """{"msg":"Get https://long.open.weixin.qq.com/connect/l/qrconnect?uuid=secret-uuid: lookup long.open.weixin.qq.com: no such host"}"""
        val detail = qrFailureDetail("/wx/qrcodeauth/private-id/poll", body)
        assertEquals("读取扫码结果：DNS 解析失败", detail)
        assertFalse(detail.contains("secret"))
        assertFalse(detail.contains("private-id"))
    }
    @Test fun identifiesTlsAndTimeout() {
        assertEquals("读取扫码结果：证书校验失败", qrFailureDetail("/a/poll", """{"msg":"x509: certificate signed by unknown authority"}"""))
        assertEquals("读取扫码结果：连接超时", qrFailureDetail("/a/poll", """{"msg":"context deadline exceeded"}"""))
    }
    @Test fun distinguishesExchangeStageAndMalformedResponse() {
        assertEquals("保存微信授权：连接中断", qrFailureDetail("/a/confirm", """{"msg":"unexpected EOF"}"""))
        assertEquals("生成二维码：协议核心访问微信失败", qrFailureDetail("/wx/qrcodeauth", "invalid"))
    }
    @Test fun neverEchoesUnknownUpstreamText() {
        val detail = qrFailureDetail("/a/poll", """{"msg":"Bearer secret-token code=secret-code login_buffer=secret-buffer"}""")
        assertEquals("读取扫码结果：协议核心访问微信失败", detail)
    }
}
